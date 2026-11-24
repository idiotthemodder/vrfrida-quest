package dev.vrfrida;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * UI sounds, synthesized at runtime (no audio files in the apk): each pack renders a small set of 16-bit mono wavs
 * into the cache dir once, then plays them through a SoundPool (low latency, mixes with itself).
 */
final class UiSfx {
    static final String[] NAMES = {"click", "hover", "tab", "on", "off", "success", "error", "boot", "open", "whoosh"};
    static final int RATE = 44100;

    final Context ctx;
    SoundPool pool;
    final Map<String, Integer> ids = new HashMap<>();
    String pack = "";
    float vol = 0.5f;
    boolean enabled = true, hover = true;
    long lastPlay, lastHover;
    String lastName = "";

    UiSfx(Context c) { ctx = c.getApplicationContext(); }

    /** (re)load a pack. Cheap when nothing changed; renders on the calling thread, so call it off the ui thread */
    synchronized void configure(boolean on, String pack, float vol, boolean hover) {
        this.enabled = on; this.vol = vol; this.hover = hover;
        if (!on || pack.equals(this.pack)) return;
        this.pack = pack;
        if (pool == null) {
            pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((sp, id, status) -> {
                synchronized (UiSfx.this) {
                    if (status != 0) return;
                    loaded.add(id);
                    if (whenReady != null && id == ids.getOrDefault(whenReady, -1)) { String n = whenReady; whenReady = null; play(n); }
                }
            });
        }
        for (int id : ids.values()) pool.unload(id);
        ids.clear();
        loaded.clear();
        File dir = new File(ctx.getCacheDir(), "sfx");
        dir.mkdirs();
        for (String n : NAMES) {
            File f = new File(dir, pack + "_" + n + "_v2.wav");
            try {
                if (!f.isFile()) writeWav(f, render(pack, n));
                ids.put(n, pool.load(f.getPath(), 1));
            } catch (Exception ignored) {}
        }
    }

    String whenReady;

    /** play once the sample has finished loading (SoundPool loads async; used for the boot sound) */
    synchronized void playWhenReady(String name) {
        Integer id = ids.get(name);
        if (id != null && loaded.contains(id)) play(name);
        else whenReady = name;
    }

    final java.util.Set<Integer> loaded = new java.util.HashSet<>();

    void play(String name) { play(name, 1f, 1f); }

    synchronized void play(String name, float gain, float rate) {
        if (!enabled || pool == null || vol <= 0) return;
        Integer id = ids.get(name);
        if (id == null) return;
        long now = System.currentTimeMillis();
        if (name.equals("hover")) { if (!hover || now - lastHover < 45) return; lastHover = now; }
        else if (name.equals(lastName) && now - lastPlay < 40) return;   // two hooks for one tap -> one sound
        lastPlay = now; lastName = name;
        float v = Math.min(1f, vol * gain);
        pool.play(id, v, v, 1, 0, rate);
    }

    /** true when a tap sound played this recently (the window hook and a widget's own hook don't stack) */
    synchronized boolean playedWithin(long ms) { return System.currentTimeMillis() - lastPlay < ms; }

    void release() { synchronized (this) { if (pool != null) pool.release(); pool = null; ids.clear(); pack = ""; } }

    // ---------------------------------------------------------------- synthesis
    /** one note: start (s), length (s), freq (Hz), end freq (glide), amp, attack (s) */
    static final class N {
        final double t, len, f0, f1, amp, att;
        N(double t, double len, double f0, double f1, double amp, double att) { this.t = t; this.len = len; this.f0 = f0; this.f1 = f1; this.amp = amp; this.att = att; }
    }

    static N n(double t, double len, double f, double amp) { return new N(t, len, f, f, amp, 0.002); }
    static N g(double t, double len, double f0, double f1, double amp) { return new N(t, len, f0, f1, amp, 0.002); }

    static N[] score(String name) {
        switch (name) {
            case "click": return new N[]{g(0, 0.045, 1900, 1200, 0.55)};
            case "hover": return new N[]{n(0, 0.025, 2600, 0.16)};
            case "tab": return new N[]{n(0, 0.06, 880, 0.4), n(0.045, 0.09, 1320, 0.35)};
            case "on": return new N[]{n(0, 0.05, 660, 0.4), n(0.04, 0.08, 990, 0.4)};
            case "off": return new N[]{n(0, 0.05, 880, 0.35), n(0.04, 0.08, 587, 0.35)};
            case "success": return new N[]{n(0, 0.12, 784, 0.35), n(0.08, 0.12, 988, 0.35), n(0.16, 0.32, 1319, 0.4)};
            case "error": return new N[]{g(0, 0.14, 220, 196, 0.45), g(0.13, 0.22, 196, 165, 0.45)};
            case "open": return new N[]{g(0, 0.09, 520, 880, 0.35)};
            case "whoosh": return new N[]{new N(0, 0.28, 300, 1400, 0.2, 0.1)};
            case "boot": return new N[]{
                    new N(0, 1.3, 130.8, 130.8, 0.22, 0.25), new N(0.12, 1.2, 196, 196, 0.2, 0.25), new N(0.24, 1.1, 261.6, 261.6, 0.2, 0.2),
                    new N(0.36, 1.0, 329.6, 329.6, 0.2, 0.15), new N(0.5, 0.9, 392, 392, 0.22, 0.1), new N(0.62, 0.9, 523.3, 523.3, 0.25, 0.05),
                    new N(0.74, 0.7, 784, 784, 0.18, 0.02)};
            default: return new N[]{n(0, 0.04, 1000, 0.3)};
        }
    }

    static short[] render(String pack, String name) {
        N[] notes = score(name);
        double end = 0;
        for (N x : notes) end = Math.max(end, x.t + x.len);
        end += pack.equals("glass") ? 0.35 : 0.05;
        int len = (int) (end * RATE);
        double[] buf = new double[len];
        java.util.Random rnd = new java.util.Random(name.hashCode());
        for (N x : notes) {
            int s0 = (int) (x.t * RATE);
            double tail = pack.equals("glass") ? 0.3 : 0;
            int count = (int) ((x.len + tail) * RATE);
            double ph = 0, ph2 = 0;
            for (int i = 0; i < count && s0 + i < len; i++) {
                double t = (double) i / RATE, k = Math.min(1, t / x.len);
                double f = x.f0 + (x.f1 - x.f0) * k;
                if (pack.equals("bubble")) f *= 1 + 0.6 * Math.exp(-t * 40);   // little upward "bloop"
                ph += 2 * Math.PI * f / RATE;
                ph2 += 2 * Math.PI * f * 2.76 / RATE;
                double env = Math.min(1, t / Math.max(0.0005, x.att));
                double v;
                switch (pack) {
                    case "retro":
                        env *= t < x.len ? 1 - 0.4 * k : 0;
                        v = (Math.sin(ph) >= 0 ? 1 : -1) * 0.45;
                        break;
                    case "glass":
                        env *= Math.exp(-t * (name.equals("boot") ? 2.2 : 9));
                        v = Math.sin(ph) * 0.75 + Math.sin(ph2) * 0.25 * Math.exp(-t * 18);
                        break;
                    case "bubble":
                        env *= t < x.len ? Math.sin(Math.PI * Math.min(1, t / x.len)) : 0;
                        v = Math.sin(ph);
                        break;
                    default:   // soft: sine with a fast exponential decay, a touch of 2nd harmonic
                        env *= t < x.len ? Math.exp(-t * (name.equals("boot") ? 1.6 : 4.5 / x.len * 0.12 + 8)) : 0;
                        v = Math.sin(ph) * 0.85 + Math.sin(2 * ph) * 0.15;
                }
                if (name.equals("whoosh")) v = v * 0.25 + (rnd.nextDouble() * 2 - 1) * 0.75 * Math.sin(Math.PI * k);
                buf[s0 + i] += v * env * x.amp;
            }
        }
        // 3 ms fade at both ends (no clicks), soft clip
        int fade = RATE * 3 / 1000;
        short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            double v = buf[i];
            if (i < fade) v *= (double) i / fade;
            if (i > len - fade) v *= (double) (len - i) / fade;
            v = Math.tanh(v * 1.2);
            out[i] = (short) Math.round(v * 30000);
        }
        return out;
    }

    static void writeWav(File f, short[] pcm) throws Exception {
        int dataLen = pcm.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + dataLen).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(dataLen);
        for (short s : pcm) b.putShort(s);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b.array()); }
        tmp.renameTo(f);
    }
}
