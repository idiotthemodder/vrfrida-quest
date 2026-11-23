package dev.vrfrida;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * System-wide mic soundboard (root): assets/sysmic.js runs inside audioserver and mixes a sound into the mic blocks
 * every recording app gets (Meta party chat, any game's voice chat). A detached root runner keeps frida-inject attached
 * with -i, reading commands from a fifo; it re-injects if audioserver restarts. Sounds are sent as base64 PCM lines
 * (audioserver can't read /sdcard itself).
 */
final class SysMic {
    static final String FIFO = "/data/local/tmp/vrfrida_sysmic.fifo";
    static final int CHUNK = 256 * 1024;   // raw bytes per command line
    final Context ctx;
    final Core core;

    SysMic(Context ctx, Core core) { this.ctx = ctx; this.core = core; }

    static File script() { return new File(Paths.ROOT, "sysmic.js"); }
    static File log() { return new File(Paths.LOGS, "sysmic.log"); }
    static File pidFile() { return new File(Paths.RUN, "sysmic.pid"); }
    static File cmdFile() { return new File(Paths.RUN, "sysmic.cmd"); }
    static File cache() { return new File(Paths.ROOT, ".sysmic_cache"); }

    boolean running() {
        Root.Result r = Root.sh("p=$(cat " + Root.q(pidFile().getPath()) + " 2>/dev/null); [ -n \"$p\" ] && kill -0 $p 2>/dev/null && [ -p " + FIFO + " ] && echo yes");
        return r.out.trim().equals("yes");
    }

    /** start the runner (injects into audioserver); returns an error or null */
    String start() throws Exception {
        if (running()) return null;
        Paths.RUN.mkdirs(); Paths.LOGS.mkdirs();
        Files.write(script(), Files.readAsset(ctx, "sysmic.js").getBytes(StandardCharsets.UTF_8));
        String inj = Root.q(core.injector());
        String sh =
                "LOG=" + Root.q(log().getPath()) + "\n" +
                "echo $$ > " + Root.q(pidFile().getPath()) + "\n" +
                "echo \"[runner] $(date '+%H:%M:%S') started\" > \"$LOG\"\n" +
                "rm -f " + FIFO + "; mkfifo -m 600 " + FIFO + "\n" +
                "exec 3<>" + FIFO + "\n" +       // read+write: the fifo never hits EOF between commands
                "while [ -p " + FIFO + " ]; do\n" +
                "  pid=$(pidof audioserver | cut -d' ' -f1)\n" +
                "  if [ -n \"$pid\" ]; then\n" +
                "    echo \"[runner] $(date '+%H:%M:%S') injecting into audioserver ($pid)\" >> \"$LOG\"\n" +
                "    " + inj + " -p $pid -s " + Root.q(script().getPath()) + " -R v8 -i <&3 >> \"$LOG\" 2>&1\n" +
                "    echo \"[runner] $(date '+%H:%M:%S') frida-inject exited ($?)\" >> \"$LOG\"\n" +
                "  fi\n" +
                "  sleep 2\n" +
                "done\n";
        File f = new File(Paths.RUN, "sysmic.sh");
        Files.write(f, sh.getBytes(StandardCharsets.UTF_8));
        Root.Result r = Root.sh("chmod 755 " + inj + " 2>/dev/null; setsid sh " + Root.q(f.getPath()) + " < /dev/null > /dev/null 2>&1 &");
        if (!r.out.isEmpty()) return r.out;
        for (int i = 0; i < 40; i++) {           // wait for "[sysmic] ready"
            Thread.sleep(250);
            String l = tail(4000);
            if (l.contains("[sysmic] ready")) return null;
            if (l.contains("frida-inject exited")) return "frida-inject couldn't attach to audioserver:\n" + l;
        }
        return "audioserver hook didn't start in 10 s:\n" + tail(2000);
    }

    /** unload: removing the fifo ends the loop, killing frida-inject takes the hook out of audioserver */
    void stop() {
        String pf = Root.q(pidFile().getPath());
        Root.sh("p=$(cat " + pf + " 2>/dev/null)\nrm -f " + FIFO + "\n" +
                "if [ -n \"$p\" ]; then pkill -P $p; kill $p; fi 2>/dev/null\nrm -f " + pf);
    }

    void send(String lines) throws Exception {
        if (!running()) { String e = start(); if (e != null) throw new Exception(e); }
        Files.write(cmdFile(), lines.getBytes(StandardCharsets.UTF_8));
        Root.Result r = Root.sh("cat " + Root.q(cmdFile().getPath()) + " > " + FIFO);
        cmdFile().delete();
        if (!r.ok()) throw new Exception("couldn't send to the audioserver hook: " + r.out);
    }

    void settings(float vol, boolean mute, boolean loop) throws Exception {
        send("{\"cmd\":\"set\",\"vol\":" + vol + ",\"mute\":" + mute + ",\"loop\":" + loop + "}\n");
    }

    /** convert (24 kHz mono 16-bit, honours the clip length) and send the sound, then play it */
    void play(File src, float vol, boolean mute, boolean loop) throws Exception {
        File wav = prepared(src);
        byte[] pcm = pcmOf(wav);
        StringBuilder sb = new StringBuilder(pcm.length * 4 / 3 + 256);
        sb.append("{\"cmd\":\"fmt\",\"sizes\":").append(formats()).append("}\n");
        sb.append("{\"cmd\":\"load\",\"rate\":").append(Sounds.RATE).append(",\"samples\":").append(pcm.length / 2).append("}\n");
        for (int o = 0; o < pcm.length; o += CHUNK) {
            int n = Math.min(CHUNK, pcm.length - o);
            sb.append("{\"cmd\":\"chunk\",\"b64\":\"").append(Base64.encodeToString(pcm, o, n, Base64.NO_WRAP)).append("\"}\n");
        }
        sb.append("{\"cmd\":\"set\",\"vol\":").append(vol).append(",\"mute\":").append(mute).append(",\"loop\":").append(loop).append("}\n");
        sb.append("{\"cmd\":\"play\"}\n");
        send(sb.toString());
    }

    /**
     * capture formats of audioflinger's input threads, as {"<HAL buffer bytes>":[frameSize, format, rate], "*": ...}.
     * "*" (any size) only when every input thread uses the same frame size / format / rate.
     */
    static String formats() {
        Root.Result r = Root.sh("dumpsys media.audio_flinger");
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        java.util.Set<String> kinds = new java.util.HashSet<>();
        String[] parts = r.out.split("\nInput thread ");
        for (int i = 1; i < parts.length; i++) {
            String t = parts[i];
            int end = t.indexOf("\nOutput thread ");
            if (end > 0) t = t.substring(0, end);
            long fc = num(t, "HAL frame count: (\\d+)"), bs = num(t, "HAL buffer size: (\\d+)"), rate = num(t, "Sample rate: (\\d+)");
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile("HAL format: 0x([0-9a-fA-F]+)").matcher(t);
            if (fc <= 0 || bs <= 0 || bs % fc != 0 || !fm.find()) continue;
            String v = "[" + (bs / fc) + "," + Long.parseLong(fm.group(1), 16) + "," + (rate > 0 ? rate : 48000) + "]";
            m.put(String.valueOf(bs), v);
            kinds.add(v);
        }
        StringBuilder sb = new StringBuilder("{");
        for (java.util.Map.Entry<String, String> e : m.entrySet()) sb.append(sb.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":").append(e.getValue());
        if (kinds.size() == 1) sb.append(sb.length() > 1 ? "," : "").append("\"*\":").append(kinds.iterator().next());
        return sb.append('}').toString();
    }

    static long num(String t, String re) {
        java.util.regex.Matcher x = java.util.regex.Pattern.compile(re).matcher(t);
        return x.find() ? Long.parseLong(x.group(1)) : -1;
    }

    void stopSound() throws Exception { if (running()) send("{\"cmd\":\"stop\"}\n"); }
    void status() throws Exception { if (running()) send("{\"cmd\":\"status\"}\n"); }

    static File prepared(File src) throws Exception {
        File dir = cache();
        dir.mkdirs();
        String name = src.getName().replaceAll("[^A-Za-z0-9._ -]", "_") + "." + Sounds.maxSec + ".wav";
        File dst = new File(dir, name);
        if (dst.isFile() && dst.lastModified() >= src.lastModified() && Sounds.isTargetWav(dst)) return dst;
        File[] old = dir.listFiles();
        if (old != null && old.length > 40) for (File f : old) f.delete();
        if (Sounds.isTargetWav(src) && (Sounds.maxSec == 0 || src.length() <= 44 + (long) Sounds.maxSec * Sounds.RATE * 2)) Sounds.copy(src, dst);
        else Sounds.convert(src, dst);
        return dst;
    }

    /** the data chunk of a wav */
    static byte[] pcmOf(File wav) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            long off = 12, len = r.length();
            byte[] h = new byte[8];
            while (off + 8 <= len) {
                r.seek(off); r.readFully(h);
                ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                int id = b.getInt(0); long sz = b.getInt(4) & 0xffffffffL;
                if (id == 0x61746164) {   // "data"
                    int n = (int) Math.min(sz, len - off - 8) & ~1;
                    byte[] out = new byte[n];
                    r.readFully(out);
                    return out;
                }
                off += 8 + sz + (sz & 1);
            }
        }
        throw new Exception("no data in " + wav.getName());
    }

    /** last chars of the log, only the hook's own lines */
    static String tail(int max) {
        try {
            String t = new String(Files.read(log()), StandardCharsets.UTF_8);
            return t.length() > max ? t.substring(t.length() - max) : t;
        } catch (Exception e) { return ""; }
    }
}
