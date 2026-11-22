package dev.vrfrida;

import android.content.pm.ApplicationInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * On-headset port of makebridge.py + il2cpp-toolkit (il2cpp-scan / il2cpp-patch).
 * Same fingerprint and meta.json format as the PC script, so bridges built on either side are reused by the other.
 */
final class Bridge {
    static final String[] LIB_NAMES = {"lib/arm64-v8a/libil2cpp.so", "lib/armeabi-v7a/libil2cpp.so"};
    static final String BRIDGE_NAME = "frida-il2cpp-bridge-patched.js";

    interface Log { void log(String s); }

    static final class Lib {
        final String apk; final String member; final long crc; final long size;
        Lib(String apk, String member, long crc, long size) { this.apk = apk; this.member = member; this.crc = crc; this.size = size; }
        String fingerprint() { return String.format("%s:%08x:%d", member, crc, size); }
    }

    static List<String> apks(ApplicationInfo ai) {
        List<String> out = new ArrayList<>();
        out.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) out.add(s);
        return out;
    }

    /** libil2cpp.so in base.apk or a split, straight from the zip index. null = not an il2cpp game */
    static Lib findLib(ApplicationInfo ai) {
        for (String apk : apks(ai)) {
            try (ZipFile z = new ZipFile(apk)) {
                for (String n : LIB_NAMES) {
                    ZipEntry e = z.getEntry(n);
                    if (e != null) return new Lib(apk, n, e.getCrc(), e.getSize());
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    static File dir(String pkg) { return new File(Paths.BRIDGES, pkg); }
    static File file(String pkg) { return new File(dir(pkg), BRIDGE_NAME); }

    static boolean bridgeOk(File f) {
        try {
            if (!f.isFile() || f.length() < 20000) return false;
            return new String(Files.read(f), StandardCharsets.UTF_8).toLowerCase().contains("il2cpp");
        } catch (Exception e) {
            return false;
        }
    }

    static String savedFingerprint(String pkg) {
        try {
            return new JSONObject(new String(Files.read(new File(dir(pkg), "meta.json")), StandardCharsets.UTF_8)).optString("fingerprint", "");
        } catch (Exception e) {
            return "";
        }
    }

    static final Map<String, String[]> stateCache = new HashMap<>();

    /**
     * same as state(ai), but with the lib fingerprint from the games scan cache and the answer kept until the bridge or
     * meta.json changes on disk (state() opens the apk and reads the whole bridge file every time)
     */
    static synchronized String stateCached(ApplicationInfo ai, String fp) {
        if (fp == null || fp.isEmpty()) return state(ai);
        File f = file(ai.packageName), meta = new File(dir(ai.packageName), "meta.json");
        String key = fp + "|" + f.lastModified() + "|" + f.length() + "|" + meta.lastModified();
        String[] c = stateCache.get(ai.packageName);
        if (c != null && c[0].equals(key)) return c[1];
        String st;
        if (!f.isFile()) st = "missing";
        else {
            String saved = savedFingerprint(ai.packageName);
            st = saved.isEmpty() ? "unknown" : saved.equals(fp) && bridgeOk(f) ? "fresh" : "stale";
        }
        stateCache.put(ai.packageName, new String[]{key, st});
        return st;
    }

    /** fresh | stale | unknown | missing | not il2cpp */
    static String state(ApplicationInfo ai) {
        Lib lib = findLib(ai);
        if (lib == null) return "not il2cpp";
        File f = file(ai.packageName);
        if (!f.isFile()) return "missing";
        String saved = savedFingerprint(ai.packageName);
        if (saved.isEmpty()) return "unknown";
        return saved.equals(lib.fingerprint()) && bridgeOk(f) ? "fresh" : "stale";
    }

    /** returns the bridge file, building it when the game's libil2cpp.so changed */
    static File ensure(android.content.Context ctx, ApplicationInfo ai, boolean force, Log log) throws Exception {
        String pkg = ai.packageName;
        Lib lib = findLib(ai);
        if (lib == null) throw new Exception("no libil2cpp.so in any apk of " + pkg + " (not an il2cpp game)");
        File out = file(pkg);
        String fp = lib.fingerprint();
        String saved = savedFingerprint(pkg);
        if (!force && saved.equals(fp) && bridgeOk(out)) {
            log.log("bridge is up to date (" + fp + ")");
            return out;
        }
        if (force) log.log("forced bridge rebuild");
        else if (!saved.isEmpty()) log.log("libil2cpp.so changed since the last build, rebuilding the bridge");
        else log.log("no bridge for " + pkg + " yet, building it on the headset");

        long t0 = System.currentTimeMillis();
        File so = new File(ctx.getCacheDir(), "libil2cpp.so");
        try {
            log.log(String.format("extracting %s from %s (%.1f MB)", lib.member, new File(lib.apk).getName(), lib.size / 1e6));
            extract(lib, so);
            List<String[]> exports = exports(so);          // {name, address}
            log.log(exports.size() + " exports");
            String template = Files.readAsset(ctx, "frida-il2cpp-bridge.js");
            String patched;
            boolean plain = false;
            for (String[] x : exports) if (x[0].equals("il2cpp_init")) { plain = true; break; }
            if (plain) {
                log.log("this game exports the real il2cpp_* names, using the plain bridge");
                patched = template;
            } else {
                Map<String, String> map = symbolMap(exports, log);
                if (map.isEmpty()) throw new Exception("couldn't map any il2cpp exports (no exports between the marker symbols)");
                patched = patch(template, map, log);
            }

            dir(pkg).mkdirs();
            File tmp = new File(out.getPath() + ".tmp");
            Files.write(tmp, patched.getBytes(StandardCharsets.UTF_8));
            if (!bridgeOk(tmp)) { tmp.delete(); throw new Exception("the new bridge looks wrong, keeping the old one"); }
            if (!tmp.renameTo(out)) throw new Exception("couldn't replace " + out);
            JSONObject meta = new JSONObject();
            meta.put("fingerprint", fp);
            meta.put("apk", new File(lib.apk).getName());
            meta.put("member", lib.member);
            meta.put("crc32", String.format("%08x", lib.crc));
            meta.put("size", lib.size);
            meta.put("built", System.currentTimeMillis() / 1000);
            meta.put("built_on", "headset");
            Files.write(new File(dir(pkg), "meta.json"), meta.toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {
            so.delete();
        }
        log.log(String.format("bridge built in %.1fs", (System.currentTimeMillis() - t0) / 1000.0));
        return out;
    }

    static void extract(Lib lib, File dst) throws Exception {
        try (ZipFile z = new ZipFile(lib.apk); InputStream in = z.getInputStream(z.getEntry(lib.member));
             FileOutputStream out = new FileOutputStream(dst)) {
            CRC32 crc = new CRC32();
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) { crc.update(b, 0, n); out.write(b, 0, n); }
            if (crc.getValue() != lib.crc) throw new Exception("crc32 of the extracted libil2cpp.so doesn't match the apk");
        }
    }

    /** il2cpp-toolkit _parse_elf: GLOBAL/WEAK symbols with a value and a name, from .dynsym and .symtab in section order */
    static List<String[]> exports(File so) throws Exception {
        List<String[]> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(so, "r"); FileChannel ch = raf.getChannel()) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            m.order(ByteOrder.LITTLE_ENDIAN);
            if (m.getInt(0) != 0x464c457f) throw new Exception("libil2cpp.so isn't an ELF file");
            if (m.get(4) != 2) throw new Exception("32-bit libil2cpp.so isn't supported (Quest games are arm64)");
            long shoff = m.getLong(0x28);
            int shentsize = m.getShort(0x3A) & 0xffff, shnum = m.getShort(0x3C) & 0xffff, shstrndx = m.getShort(0x3E) & 0xffff;
            long shstrOff = m.getLong((int) (shoff + (long) shstrndx * shentsize + 0x18));
            for (int i = 0; i < shnum; i++) {
                int h = (int) (shoff + (long) i * shentsize);
                String sname = cstr(m, shstrOff + (m.getInt(h) & 0xffffffffL));
                if (!sname.equals(".dynsym") && !sname.equals(".symtab")) continue;
                long off = m.getLong(h + 0x18), size = m.getLong(h + 0x20), ent = m.getLong(h + 0x38);
                int link = m.getInt(h + 0x28);
                long strOff = m.getLong((int) (shoff + (long) link * shentsize + 0x18));
                if (ent == 0) ent = 24;
                for (long s = off; s + ent <= off + size; s += ent) {
                    int bind = (m.get((int) s + 4) & 0xff) >> 4;
                    long value = m.getLong((int) s + 8);
                    long nameIdx = m.getInt((int) s) & 0xffffffffL;
                    if ((bind == 1 || bind == 2) && value > 0 && nameIdx != 0) {
                        String name = cstr(m, strOff + nameIdx);
                        if (!name.isEmpty()) out.add(new String[]{name, Long.toString(value)});
                    }
                }
            }
        }
        return out;
    }

    private static String cstr(ByteBuffer m, long at) {
        int p = (int) at, e = p;
        while (m.get(e) != 0) e++;
        byte[] b = new byte[e - p];
        for (int i = 0; i < b.length; i++) b[i] = m.get(p + i);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** filter_between + build_symbol_map */
    static Map<String, String> symbolMap(List<String[]> exports, Log log) throws Exception {
        Map<String, Long> byName = new HashMap<>();
        for (String[] e : exports) byName.put(e[0], Long.parseLong(e[1]));
        Long s = byName.get(ExportNames.START), e = byName.get(ExportNames.END);
        if (s == null) throw new Exception("start symbol not found: " + ExportNames.START);
        if (e == null) throw new Exception("end symbol not found: " + ExportNames.END);
        List<String[]> filtered = new ArrayList<>();
        for (String[] x : exports) {
            long a = Long.parseLong(x[1]);
            if (s < a && a < e) filtered.add(x);
        }
        filtered.sort((a, b) -> Long.compare(Long.parseLong(a[1]), Long.parseLong(b[1])));   // List.sort is stable, like python sorted
        log.log(filtered.size() + " exports between " + ExportNames.START + " and " + ExportNames.END);
        Map<String, String> map = new LinkedHashMap<>();
        int missing = 0;
        for (int i = 0; i < ExportNames.NAMES.length; i++) {
            if (i < filtered.size()) map.put(ExportNames.NAMES[i], filtered.get(i)[0]);
            else missing++;
        }
        if (missing > 0) log.log("warning: no export for " + missing + " il2cpp api names");
        return map;
    }

    /** il2cpp-patch: r("il2cpp_x"  ->  r("<obfuscated>" */
    static String patch(String bridge, Map<String, String> map, Log log) {
        Matcher m = Pattern.compile("r\\(\"([^\"]+)\"").matcher(bridge);
        StringBuffer sb = new StringBuffer(bridge.length() + 4096);
        int replaced = 0;
        java.util.Set<String> missing = new java.util.HashSet<>();
        while (m.find()) {
            String to = map.get(m.group(1));
            if (to != null) { replaced++; m.appendReplacement(sb, Matcher.quoteReplacement("r(\"" + to + "\"")); }
            else { missing.add(m.group(1)); m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0))); }
        }
        m.appendTail(sb);
        log.log("patched " + replaced + " symbols, " + missing.size() + " left as is");
        return sb.toString();
    }
}
