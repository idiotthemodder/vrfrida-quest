package dev.vrfrida;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Rewrites an APK without recompressing it: every entry is copied raw (so a 1 GB game takes seconds, not minutes),
 * the old signature files are dropped, chosen entries are replaced / added, then apksig (Google's apksigner library,
 * dexed into this app) signs it with the patch key bundled in the app (assets/patchkey).
 */
final class ApkPatch {
    interface Edit { byte[] apply(byte[] old) throws Exception; }

    static final class Entry {
        String name; int flags, method, time, date; long crc, csize, usize, lho; int iattr; long eattr; int madeBy, verNeed;
    }

    static boolean isOldSignature(String n) {
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        String u = n.toUpperCase();
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    static List<Entry> centralDirectory(RandomAccessFile f) throws Exception {
        long len = f.length();
        int tail = (int) Math.min(len, 65536 + 22);
        byte[] t = new byte[tail];
        f.seek(len - tail);
        f.readFully(t);
        ByteBuffer b = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) if (b.getInt(i) == 0x06054b50) { eocd = i; break; }
        if (eocd < 0) throw new Exception("not a zip (no end of central directory)");
        int count = b.getShort(eocd + 10) & 0xffff;
        long cdSize = b.getInt(eocd + 12) & 0xffffffffL, cdOff = b.getInt(eocd + 16) & 0xffffffffL;
        if (count == 0xffff || cdOff == 0xffffffffL) throw new Exception("zip64 APKs (over 4 GB) aren't supported");
        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOff);
        f.readFully(cd);
        ByteBuffer c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        List<Entry> out = new ArrayList<>();
        int p = 0;
        for (int i = 0; i < count; i++) {
            if (c.getInt(p) != 0x02014b50) throw new Exception("broken central directory");
            Entry e = new Entry();
            e.madeBy = c.getShort(p + 4) & 0xffff;
            e.verNeed = c.getShort(p + 6) & 0xffff;
            e.flags = c.getShort(p + 8) & 0xffff;
            e.method = c.getShort(p + 10) & 0xffff;
            e.time = c.getShort(p + 12) & 0xffff;
            e.date = c.getShort(p + 14) & 0xffff;
            e.crc = c.getInt(p + 16) & 0xffffffffL;
            e.csize = c.getInt(p + 20) & 0xffffffffL;
            e.usize = c.getInt(p + 24) & 0xffffffffL;
            int nlen = c.getShort(p + 28) & 0xffff, elen = c.getShort(p + 30) & 0xffff, clen = c.getShort(p + 32) & 0xffff;
            e.iattr = c.getShort(p + 36) & 0xffff;
            e.eattr = c.getInt(p + 38) & 0xffffffffL;
            e.lho = c.getInt(p + 42) & 0xffffffffL;
            e.name = new String(cd, p + 46, nlen, StandardCharsets.UTF_8);
            out.add(e);
            p += 46 + nlen + elen + clen;
        }
        return out;
    }

    /** names of the entries in an apk */
    static List<String> names(File apk) throws Exception {
        List<String> n = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) { for (Entry e : centralDirectory(f)) n.add(e.name); }
        return n;
    }

    static byte[] readEntry(RandomAccessFile f, Entry e) throws Exception {
        byte[] raw = rawData(f, e);
        if (e.method == 0) return raw;
        if (e.method != 8) throw new Exception(e.name + ": unsupported compression " + e.method);
        java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
        inf.setInput(raw);
        byte[] out = new byte[(int) e.usize];
        int n = 0;
        while (n < out.length && !inf.finished()) { int r = inf.inflate(out, n, out.length - n); if (r == 0 && inf.needsInput()) break; n += r; }
        inf.end();
        if (n != out.length) throw new Exception(e.name + ": inflated to the wrong size");
        return out;
    }

    static long dataStart(RandomAccessFile f, Entry e) throws IOException {
        byte[] h = new byte[30];
        f.seek(e.lho);
        f.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x04034b50) throw new IOException(e.name + ": bad local header");
        return e.lho + 30 + (b.getShort(26) & 0xffff) + (b.getShort(28) & 0xffff);
    }

    static byte[] rawData(RandomAccessFile f, Entry e) throws IOException {
        byte[] d = new byte[(int) e.csize];
        f.seek(dataStart(f, e));
        f.readFully(d);
        return d;
    }

    /** counts bytes so local header offsets are known */
    static final class Out extends OutputStream {
        final OutputStream o; long pos;
        Out(OutputStream o) { this.o = o; }
        @Override public void write(int x) throws IOException { o.write(x); pos++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); pos += len; }
        @Override public void close() throws IOException { o.close(); }
        void u16(int v) throws IOException { write(v & 0xff); write((v >>> 8) & 0xff); }
        void u32(long v) throws IOException { u16((int) (v & 0xffff)); u16((int) ((v >>> 16) & 0xffff)); }
    }

    /**
     * copy `src` to `dst` (unsigned): old signature dropped, `edits` change existing entries (by name), `add` entries
     * are added (or replace ones with the same name). New entries are stored if `storeNew`, else deflated.
     */
    static void rewrite(File src, File dst, Map<String, Edit> edits, Map<String, byte[]> add, boolean storeNew) throws Exception {
        List<Entry> written = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(src, "r");
             Out o = new Out(new BufferedOutputStream(new FileOutputStream(dst), 1 << 20))) {
            byte[] buf = new byte[1 << 20];
            for (Entry e : centralDirectory(f)) {
                if (isOldSignature(e.name) || add.containsKey(e.name)) continue;
                Edit ed = edits.get(e.name);
                if (ed != null) {
                    byte[] data = ed.apply(readEntry(f, e));
                    written.add(writeNew(o, e.name, data, e.method == 0, e));
                    continue;
                }
                Entry n = copyMeta(e);
                n.flags &= ~0x08;                      // sizes go in the local header, no data descriptor
                localHeader(o, n, align(e));
                f.seek(dataStart(f, e));
                long left = e.csize;
                while (left > 0) {
                    int r = f.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new IOException("short read in " + e.name);
                    o.write(buf, 0, r);
                    left -= r;
                }
                written.add(n);
            }
            for (Map.Entry<String, byte[]> a : add.entrySet()) written.add(writeNew(o, a.getKey(), a.getValue(), storeNew, null));
            long cdStart = o.pos;
            for (Entry e : written) {
                byte[] name = e.name.getBytes(StandardCharsets.UTF_8);
                o.u32(0x02014b50); o.u16(e.madeBy); o.u16(e.verNeed); o.u16(e.flags); o.u16(e.method); o.u16(e.time); o.u16(e.date);
                o.u32(e.crc); o.u32(e.csize); o.u32(e.usize); o.u16(name.length); o.u16(0); o.u16(0); o.u16(0);
                o.u16(e.iattr); o.u32(e.eattr); o.u32(e.lho);
                o.write(name);
            }
            long cdSize = o.pos - cdStart;
            if (o.pos > 0xffffffffL || written.size() >= 0xffff) throw new Exception("the patched APK would need zip64 (over 4 GB)");
            o.u32(0x06054b50); o.u16(0); o.u16(0); o.u16(written.size()); o.u16(written.size()); o.u32(cdSize); o.u32(cdStart); o.u16(0);
        }
    }

    static int align(Entry e) { return e.method != 0 ? 0 : e.name.endsWith(".so") ? 16384 : 4; }

    static Entry copyMeta(Entry e) {
        Entry n = new Entry();
        n.name = e.name; n.flags = e.flags; n.method = e.method; n.time = e.time; n.date = e.date; n.crc = e.crc;
        n.csize = e.csize; n.usize = e.usize; n.iattr = e.iattr; n.eattr = e.eattr; n.madeBy = e.madeBy; n.verNeed = e.verNeed;
        return n;
    }

    static void localHeader(Out o, Entry n, int align) throws IOException {
        byte[] name = n.name.getBytes(StandardCharsets.UTF_8);
        int pad = 0;
        if (align > 0) pad = (int) ((align - (o.pos + 30 + name.length) % align) % align);
        n.lho = o.pos;
        o.u32(0x04034b50); o.u16(n.verNeed); o.u16(n.flags); o.u16(n.method); o.u16(n.time); o.u16(n.date);
        o.u32(n.crc); o.u32(n.csize); o.u32(n.usize); o.u16(name.length); o.u16(pad);
        o.write(name);
        for (int i = 0; i < pad; i++) o.write(0);
    }

    static Entry writeNew(Out o, String name, byte[] data, boolean store, Entry like) throws IOException {
        Entry n = new Entry();
        n.name = name;
        n.verNeed = 20; n.madeBy = like != null ? like.madeBy : 20;
        n.time = like != null ? like.time : 0x0821; n.date = like != null ? like.date : 0x0221;   // 1981-01-01 like aapt
        n.eattr = like != null ? like.eattr : 0;
        CRC32 crc = new CRC32();
        crc.update(data);
        n.crc = crc.getValue();
        n.usize = data.length;
        byte[] body = data;
        if (store) n.method = 0;
        else {
            n.method = 8;
            ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 2 + 64);
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(bo, d, 1 << 16)) { dos.write(data); }
            d.end();
            body = bo.toByteArray();
        }
        n.csize = body.length;
        localHeader(o, n, store ? (name.endsWith(".so") ? 16384 : 4) : 0);
        o.write(body);
        return n;
    }

    // ---------------------------------------------------------------- signing
    static PrivateKey key(Context ctx) throws Exception {
        byte[] k = Files.readAll(ctx.getAssets().open("patchkey/key.pk8"));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(k));
    }

    static X509Certificate cert(Context ctx) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(ctx.getAssets().open("patchkey/cert.der"));
    }

    /** v2 + v3 signature with the bundled patch key. Every apk of one game must be signed with the same key */
    static void sign(Context ctx, File unsigned, File out) throws Exception {
        com.android.apksig.ApkSigner.SignerConfig sc = new com.android.apksig.ApkSigner.SignerConfig.Builder(
                "vrfrida", key(ctx), Collections.singletonList(cert(ctx))).build();
        new com.android.apksig.ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(unsigned).setOutputApk(out)
                .setMinSdkVersion(29)          // Quest OS is Android 10+: no v1 (jar) signature needed, so no re-hashing every entry twice
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false)
                .setCreatedBy("vrfrida")
                .build().sign();
    }

    static Map<String, Edit> edits() { return new LinkedHashMap<>(); }
}
