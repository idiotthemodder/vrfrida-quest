package dev.vrfrida;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * UI sounds, synthesized at runtime (no audio files in the apk). Each pack renders its set of 16-bit mono wavs into
 * the cache dir once, then they play through a SoundPool.
 *  - everything is tuned to one pentatonic key, low-passed and short, so it stays in the background
 *  - repeats get a small random pitch / level wobble so a run of clicks doesn't sound like a machine gun
 *  - tab switches climb the scale with the tab number, sliders tick in pitch with their position
 */
final class UiSfx {
    static final String[] NAMES = {"click", "hover", "tab", "on", "off", "select", "open", "close", "tick", "key",
            "success", "error", "warn", "start", "copy", "refresh", "delete", "notify", "boot", "whoosh"};
    static final int RATE = 44100;
    /** C major pentatonic ratios: tab k plays at PENTA[k % 5] (one octave up after 5) */
    static final float[] PENTA = {1f, 1.125f, 1.25f, 1.5f, 1.6667f};

    final Context ctx;
    SoundPool pool;
    final Map<String, Integer> ids = new HashMap<>();
    final java.util.Set<Integer> loaded = new java.util.HashSet<>();
    final Random rnd = new Random();
    String pack = "", whenReady;
    float vol = 0.35f;
    boolean enabled = true, hover = false;
    long lastPlay, lastHover, lastTick;
    String lastName = "";

    UiSfx(Context c) { ctx = c.getApplicationContext(); }

    /** (re)load a pack. Cheap when nothing changed; renders on the calling thread, so call it off the ui thread */
    synchronized void configure(boolean on, String pack, float vol, boolean hover) {
        this.enabled = on; this.vol = vol; this.hover = hover;
        if (!on || pack.equals(this.pack)) return;
        this.pack = pack;
        if (pool == null) {
            pool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(new AudioAttributes.Builder()
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
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.getName().contains("_v3")) f.delete();
        for (String n : NAMES) {
            File f = new File(dir, pack + "_" + n + "_v3.wav");
            try {
                if (!f.isFile()) writeWav(f, render(pack, n));
                ids.put(n, pool.load(f.getPath(), 1));
            } catch (Exception ignored) {}
        }
    }

    /** play once the sample has finished loading (SoundPool loads async; used for the boot sound) */
    synchronized void playWhenReady(String name) {
        Integer id = ids.get(name);
        if (id != null && loaded.contains(id)) play(name);
        else whenReady = name;
    }

    void play(String name) { play(name, 1f, 1f); }

    /** tab switch: pitch climbs with the tab number */
    void tab(int k) { play("tab", 1f, PENTA[k % 5] * (k >= 5 ? 1.25f : 1f) * 0.9f); }

    /** slider moved: a tiny tick, pitched by position (0..1), at most ~25 per second */
    void tick(double pos) {
        long now = System.currentTimeMillis();
        if (now - lastTick < 40) return;
        lastTick = now;
        play("tick", 0.8f, (float) (0.8 + 0.5 * Math.max(0, Math.min(1, pos))));
    }

    synchronized void play(String name, float gain, float rate) {
        if (!enabled || pool == null || vol <= 0) return;
        Integer id = ids.get(name);
        if (id == null) return;
        long now = System.currentTimeMillis();
        if (name.equals("hover")) { if (!hover || now - lastHover < 70) return; lastHover = now; }
        else if (!name.equals("tick") && !name.equals("key")) {
            if (name.equals(lastName) && now - lastPlay < 45) return;   // two hooks for one tap -> one sound
            lastPlay = now; lastName = name;
        }
        boolean wobble = name.equals("click") || name.equals("hover") || name.equals("key") || name.equals("select");
        if (wobble) { rate *= 0.96f + rnd.nextFloat() * 0.08f; gain *= 0.85f + rnd.nextFloat() * 0.15f; }
        float v = Math.min(1f, vol * gain);
        pool.play(id, v, v, name.equals("boot") ? 2 : 1, 0, Math.max(0.5f, Math.min(2f, rate)));
    }

    /** true when a tap sound played this recently (the window hook and a widget's own hook don't stack) */
    synchronized boolean playedWithin(long ms) { return System.currentTimeMillis() - lastPlay < ms; }

    void release() { synchronized (this) { if (pool != null) pool.release(); pool = null; ids.clear(); loaded.clear(); pack = ""; } }

    // ================================================================ synthesis
    // notes: C5 = 523.25. Everything sits on C pentatonic (C D E G A) so sounds that overlap stay in tune
    static final double C4 = 261.63, D4 = 293.66, E4 = 329.63, G4 = 392.00, A4 = 440.00,
            C5 = 523.25, D5 = 587.33, E5 = 659.25, G5 = 783.99, A5 = 880.00, C6 = 1046.5, E6 = 1318.5, G6 = 1568.0;

    /** one note: start (s), length (s), freq, end freq (glide), amp */
    static final class N {
        final double t, len, f0, f1, amp;
        N(double t, double len, double f0, double f1, double amp) { this.t = t; this.len = len; this.f0 = f0; this.f1 = f1; this.amp = amp; }
    }

    static N n(double t, double len, double f, double amp) { return new N(t, len, f, f, amp); }
    static N g(double t, double len, double f0, double f1, double amp) { return new N(t, len, f0, f1, amp); }

    static N[] score(String name) {
        switch (name) {
            case "click": return new N[]{n(0, 0.05, G5, 0.5)};
            case "hover": return new N[]{n(0, 0.03, C6, 0.12)};
            case "tick": return new N[]{n(0, 0.02, E6, 0.18)};
            case "key": return new N[]{n(0, 0.025, A5, 0.14)};
            case "tab": return new N[]{n(0, 0.07, C5, 0.32), n(0.05, 0.12, G5, 0.3)};
            case "on": return new N[]{n(0, 0.06, E5, 0.36), n(0.05, 0.1, A5, 0.36)};
            case "off": return new N[]{n(0, 0.06, A5, 0.3), n(0.05, 0.1, E5, 0.3)};
            case "select": return new N[]{n(0, 0.06, D5, 0.3), n(0.035, 0.09, G5, 0.32)};
            case "open": return new N[]{g(0, 0.09, E5, A5, 0.3)};
            case "close": return new N[]{g(0, 0.08, A5, E5, 0.26)};
            case "copy": return new N[]{n(0, 0.05, A5, 0.3), n(0.06, 0.08, A5, 0.26)};
            case "refresh": return new N[]{n(0, 0.05, C5, 0.26), n(0.05, 0.05, E5, 0.26), n(0.1, 0.08, G5, 0.28)};
            case "start": return new N[]{n(0, 0.08, G4, 0.3), n(0.07, 0.08, C5, 0.3), n(0.14, 0.14, E5, 0.32)};
            case "success": return new N[]{n(0, 0.1, C5, 0.3), n(0.08, 0.1, E5, 0.3), n(0.16, 0.1, G5, 0.32), n(0.24, 0.35, C6, 0.34)};
            case "notify": return new N[]{n(0, 0.12, E5, 0.3), n(0.12, 0.25, C5, 0.3)};
            case "warn": return new N[]{n(0, 0.1, A4, 0.34), n(0.12, 0.14, A4, 0.3)};
            case "delete": return new N[]{g(0, 0.14, A4, D4, 0.34)};
            case "error": return new N[]{n(0, 0.12, E4, 0.38), n(0.11, 0.24, C4, 0.38)};
            case "whoosh": return new N[]{g(0, 0.35, 220, 1200, 0.18)};
            case "boot": return new N[]{
                    n(0, 1.6, C4, 0.18), n(0.1, 1.5, G4, 0.16), n(0.22, 1.4, C5, 0.16), n(0.34, 1.3, E5, 0.16),
                    n(0.48, 1.2, G5, 0.17), n(0.62, 1.1, C6, 0.18), n(0.78, 0.9, E6, 0.12)};
            default: return new N[]{n(0, 0.04, C5, 0.3)};
        }
    }

    /** packs: soft (marimba), glass (FM bell), wood (plucked string), retro (filtered square), bubble, scifi, minimal */
    static short[] render(String pack, String name) {
        N[] notes = score(name);
        boolean longTail = pack.equals("glass") || pack.equals("scifi");
        double end = 0;
        for (N x : notes) end = Math.max(end, x.t + x.len);
        end += longTail ? 0.45 : pack.equals("wood") ? 0.25 : 0.08;
        int len = (int) (end * RATE);
        double[] buf = new double[len];
        Random rnd = new Random(name.hashCode() * 31L + pack.hashCode());
        boolean boot = name.equals("boot"), noise = name.equals("whoosh");
        for (N x : notes) {
            int s0 = (int) (x.t * RATE);
            int count = Math.min(len - s0, (int) ((x.len + (longTail ? 0.4 : pack.equals("wood") ? 0.22 : 0.03)) * RATE));
            if (pack.equals("wood") && !noise) { pluck(buf, s0, count, x, rnd, boot); continue; }
            double ph = 0, mph = 0, lp = 0;
            for (int i = 0; i < count; i++) {
                double t = (double) i / RATE, k = Math.min(1, t / x.len);
                double f = x.f0 + (x.f1 - x.f0) * (k * k * (3 - 2 * k));   // smoothstep glide
                ph += 2 * Math.PI * f / RATE;
                double att = Math.min(1, t / (boot ? 0.04 : 0.004));
                double v, env;
                switch (pack) {
                    case "glass": {      // FM bell: modulator 3.5x, index fades
                        mph += 2 * Math.PI * f * 3.5 / RATE;
                        double idx = 2.2 * Math.exp(-t * 12);
                        v = Math.sin(ph + idx * Math.sin(mph));
                        env = Math.exp(-t * (boot ? 1.6 : 7));
                        break;
                    }
                    case "retro": {      // square through a one-pole lowpass (no harsh fizz), stepped decay
                        double sq = Math.sin(ph) >= 0 ? 1 : -1;
                        lp += (sq - lp) * 0.18;
                        v = lp * 0.55;
                        env = t < x.len ? 1 - 0.5 * k : Math.max(0, 1 - (t - x.len) / 0.02) * 0.5;
                        break;
                    }
                    case "bubble": {     // sine with a quick upward pitch bloop
                        ph += 2 * Math.PI * f * 0.5 * Math.exp(-t * 45) / RATE;
                        v = Math.sin(ph);
                        env = t < x.len ? Math.sin(Math.PI * Math.min(1, t / x.len)) : 0;
                        break;
                    }
                    case "scifi": {      // detuned pair + slow vibrato, soft
                        mph += 2 * Math.PI * f * 1.007 / RATE;
                        v = 0.6 * Math.sin(ph + 0.3 * Math.sin(t * 2 * Math.PI * 7)) + 0.4 * Math.sin(mph);
                        env = Math.exp(-t * (boot ? 1.4 : 5));
                        break;
                    }
                    case "minimal": {    // very short, low and dry: just a soft tap
                        v = Math.sin(ph * 0.5);
                        env = Math.exp(-t * (boot ? 3 : 55));
                        break;
                    }
                    default: {           // soft: marimba (fundamental + quickly fading 4th harmonic)
                        v = Math.sin(ph) + 0.22 * Math.sin(4 * ph) * Math.exp(-t * 40) + 0.08 * Math.sin(2 * ph);
                        env = Math.exp(-t * (boot ? 1.8 : 3.2 / Math.max(0.03, x.len)));
                    }
                }
                if (noise) v = v * 0.2 + (rnd.nextDouble() * 2 - 1) * 0.8;
                buf[s0 + i] += v * env * att * x.amp;
            }
        }
        if (noise) { double lp = 0; for (int i = 0; i < len; i++) { double k = (double) i / len, c = 0.03 + 0.25 * k; lp += (buf[i] - lp) * c; buf[i] = lp * Math.sin(Math.PI * k) * 1.6; } }
        // gentle lowpass on everything (takes the edge off), a little room on the long-tail packs
        double cut = pack.equals("minimal") ? 0.25 : pack.equals("retro") ? 0.5 : 0.42, z = 0;
        for (int i = 0; i < len; i++) { z += (buf[i] - z) * cut; buf[i] = z; }
        if (longTail || boot) room(buf, 0.22);
        // level match: the plucked pack runs quiet, the whoosh is normalized (its lowpass eats most of the noise)
        double peak = 1e-9;
        for (double v : buf) peak = Math.max(peak, Math.abs(v));
        double gain = noise ? 0.3 / peak : pack.equals("wood") ? 1.8 : 1;
        if (gain != 1) for (int i = 0; i < len; i++) buf[i] *= gain;
        int fade = RATE * 3 / 1000;
        short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            double v = buf[i];
            if (i < fade) v *= (double) i / fade;
            if (i > len - fade) v *= (double) (len - i) / fade;
            out[i] = (short) Math.round(Math.tanh(v * 1.1) * 28000);
        }
        return out;
    }

    /** Karplus-Strong plucked string (the "wood" pack) */
    static void pluck(double[] buf, int s0, int count, N x, Random rnd, boolean boot) {
        int period = Math.max(2, (int) Math.round(RATE / x.f0));
        double[] ring = new double[period];
        for (int i = 0; i < period; i++) ring[i] = rnd.nextDouble() * 2 - 1;
        double damp = boot ? 0.998 : 0.985, prev = 0;
        for (int i = 0, p = 0; i < count && s0 + i < buf.length; i++, p = (p + 1) % period) {
            double v = ring[p];
            double nv = damp * 0.5 * (v + prev);
            prev = v;
            ring[p] = nv;
            buf[s0 + i] += v * x.amp * 0.9;
        }
    }

    /** three short feedback combs: a small room, not a cathedral */
    static void room(double[] b, double mix) {
        int[] d = {RATE * 29 / 1000, RATE * 37 / 1000, RATE * 43 / 1000};
        double[] wet = new double[b.length];
        for (int dl : d) {
            double[] c = new double[b.length];
            for (int i = 0; i < b.length; i++) c[i] = b[i] + (i >= dl ? c[i - dl] * 0.45 : 0);
            for (int i = 0; i < b.length; i++) wet[i] += c[i] / d.length;
        }
        for (int i = 0; i < b.length; i++) b[i] = b[i] * (1 - mix) + wet[i] * mix;
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
