package dev.vrfrida;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Adds a DT_NEEDED entry to a 64-bit little-endian ELF shared library (what LIEF's add_library does for patch_apk.py).
 *
 * The dynamic table and .dynstr have no spare room, so both are copied, grown, and put in a new segment appended to the
 * end of the file. The program header table can't grow in place, so the PT_NOTE entry becomes that new PT_LOAD
 * (bionic doesn't need the notes of a library to load it). PT_DYNAMIC, DT_STRTAB / DT_STRSZ and the .dynamic / .dynstr
 * section headers are pointed at the copies (bionic checks that the .dynamic section matches PT_DYNAMIC).
 */
final class ElfPatch {
    static final int PT_LOAD = 1, PT_DYNAMIC = 2, PT_NOTE = 4;
    static final long DT_NULL = 0, DT_NEEDED = 1, DT_STRTAB = 5, DT_STRSZ = 10;
    static final int SHT_DYNAMIC = 6;

    /** the patched file, or null if `lib` is already a DT_NEEDED of it */
    static byte[] addNeeded(byte[] so, String lib) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(so).order(ByteOrder.LITTLE_ENDIAN);
        if (so.length < 64 || b.getInt(0) != 0x464c457f) throw new Exception("not an ELF file");
        if (so[4] != 2 || so[5] != 1) throw new Exception("only 64-bit little-endian ELF is supported");
        long phoff = b.getLong(0x20), shoff = b.getLong(0x28);
        int phentsize = b.getShort(0x36) & 0xffff, phnum = b.getShort(0x38) & 0xffff;
        int shentsize = b.getShort(0x3a) & 0xffff, shnum = b.getShort(0x3c) & 0xffff;
        if (phentsize != 56) throw new Exception("unexpected program header size " + phentsize);

        int dynIdx = -1, noteIdx = -1, lastLoad = -1;
        long align = 0x1000, maxV = 0;
        for (int i = 0; i < phnum; i++) {
            int p = (int) (phoff + (long) i * phentsize);
            int type = b.getInt(p);
            if (type == PT_DYNAMIC) dynIdx = i;
            else if (type == PT_NOTE && noteIdx < 0) noteIdx = i;
            else if (type == PT_LOAD) {
                lastLoad = i;
                align = Math.max(align, b.getLong(p + 48));
                maxV = Math.max(maxV, b.getLong(p + 16) + b.getLong(p + 40));
            }
        }
        if (dynIdx < 0) throw new Exception("no PT_DYNAMIC (static library?)");

        // ---- the old dynamic table
        int dp = (int) (phoff + (long) dynIdx * phentsize);
        long dynOff = b.getLong(dp + 8), dynSize = b.getLong(dp + 32);
        List<long[]> dyn = new ArrayList<>();
        for (long o = dynOff; o + 16 <= dynOff + dynSize; o += 16) {
            long tag = b.getLong((int) o), val = b.getLong((int) o + 8);
            if (tag == DT_NULL) break;
            dyn.add(new long[]{tag, val});
        }
        long strVaddr = -1, strSz = -1;
        for (long[] d : dyn) { if (d[0] == DT_STRTAB) strVaddr = d[1]; else if (d[0] == DT_STRSZ) strSz = d[1]; }
        if (strVaddr < 0 || strSz < 0) throw new Exception("no DT_STRTAB / DT_STRSZ");
        long strOff = vaddrToOffset(b, phoff, phnum, strVaddr);
        if (strOff < 0 || strOff + strSz > so.length) throw new Exception("DT_STRTAB isn't inside a loaded segment");
        for (long[] d : dyn) if (d[0] == DT_NEEDED && lib.equals(cstr(so, (int) (strOff + d[1])))) return null;
        if (noteIdx < 0) throw new Exception("no PT_NOTE program header to turn into the new segment");

        // ---- the new segment: [old .dynstr + lib\0][pad to 8][new dynamic table]
        byte[] name = (lib + "\0").getBytes(StandardCharsets.UTF_8);
        long newStrSz = strSz + name.length;
        long dynRel = (newStrSz + 7) & ~7L;
        long newOff = alignUp(so.length, align), newV = alignUp(maxV, align);

        List<long[]> nd = new ArrayList<>();
        int lastNeeded = -1;
        for (int i = 0; i < dyn.size(); i++) if (dyn.get(i)[0] == DT_NEEDED) lastNeeded = i;
        for (int i = 0; i < dyn.size(); i++) {
            long[] d = dyn.get(i);
            if (d[0] == DT_STRTAB) nd.add(new long[]{DT_STRTAB, newV});
            else if (d[0] == DT_STRSZ) nd.add(new long[]{DT_STRSZ, newStrSz});
            else nd.add(d);
            if (i == lastNeeded) nd.add(new long[]{DT_NEEDED, strSz});
        }
        if (lastNeeded < 0) nd.add(0, new long[]{DT_NEEDED, strSz});
        nd.add(new long[]{DT_NULL, 0});
        long newDynSize = nd.size() * 16L;
        long segSize = dynRel + newDynSize;

        ByteBuffer o = ByteBuffer.allocate((int) (newOff + segSize)).order(ByteOrder.LITTLE_ENDIAN);
        o.put(so);
        o.position((int) newOff);
        o.put(so, (int) strOff, (int) strSz);
        o.put(name);
        o.position((int) (newOff + dynRel));
        for (long[] d : nd) { o.putLong(d[0]); o.putLong(d[1]); }

        // ---- program headers: PT_DYNAMIC -> the copy, PT_NOTE -> the new PT_LOAD, moved right after the last PT_LOAD
        int np = (int) (phoff + (long) dynIdx * phentsize);
        o.putLong(np + 8, newOff + dynRel);
        o.putLong(np + 16, newV + dynRel);
        o.putLong(np + 24, newV + dynRel);
        o.putLong(np + 32, newDynSize);
        o.putLong(np + 40, newDynSize);
        int pn = (int) (phoff + (long) noteIdx * phentsize);
        o.putInt(pn, PT_LOAD);
        o.putInt(pn + 4, 4 | 2);                 // R W
        o.putLong(pn + 8, newOff);
        o.putLong(pn + 16, newV);
        o.putLong(pn + 24, newV);
        o.putLong(pn + 32, segSize);
        o.putLong(pn + 40, segSize);
        o.putLong(pn + 48, align);
        byte[] out = o.array();
        int to = lastLoad < noteIdx ? lastLoad + 1 : lastLoad;
        if (to != noteIdx) {
            List<byte[]> ph = new ArrayList<>();
            for (int i = 0; i < phnum; i++) { int p = (int) (phoff + (long) i * phentsize); ph.add(Arrays.copyOfRange(out, p, p + phentsize)); }
            byte[] moved = ph.remove(noteIdx);
            ph.add(to, moved);
            for (int i = 0; i < phnum; i++) System.arraycopy(ph.get(i), 0, out, (int) (phoff + (long) i * phentsize), phentsize);
        }

        // ---- section headers: .dynamic and the .dynstr it links to
        if (shoff != 0 && shentsize == 64 && shoff + (long) shnum * 64 <= so.length) {
            ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < shnum; i++) {
                int s = (int) (shoff + (long) i * 64);
                if (ob.getInt(s + 4) != SHT_DYNAMIC) continue;
                setSection(ob, s, newV + dynRel, newOff + dynRel, newDynSize);
                int link = ob.getInt(s + 0x28);
                if (link > 0 && link < shnum) setSection(ob, (int) (shoff + (long) link * 64), newV, newOff, newStrSz);
            }
        }
        return out;
    }

    static void setSection(ByteBuffer b, int s, long addr, long off, long size) {
        b.putLong(s + 0x10, addr);
        b.putLong(s + 0x18, off);
        b.putLong(s + 0x20, size);
    }

    static long vaddrToOffset(ByteBuffer b, long phoff, int phnum, long v) {
        for (int i = 0; i < phnum; i++) {
            int p = (int) (phoff + (long) i * 56);
            if (b.getInt(p) != PT_LOAD) continue;
            long off = b.getLong(p + 8), va = b.getLong(p + 16), fsz = b.getLong(p + 32);
            if (v >= va && v < va + fsz) return off + (v - va);
        }
        return -1;
    }

    static long alignUp(long v, long a) { return (v + a - 1) / a * a; }

    static String cstr(byte[] d, int at) {
        int e = at;
        while (e < d.length && d[e] != 0) e++;
        return new String(d, at, e - at, StandardCharsets.UTF_8);
    }
}
