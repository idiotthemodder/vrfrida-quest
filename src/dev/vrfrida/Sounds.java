package dev.vrfrida;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** same job as vrfrida.py prepare_sounds, but ffmpeg is replaced by MediaCodec: any audio -> mono 24 kHz 16-bit wav */
final class Sounds {
    static final String[] EXT = {".wav", ".mp3", ".ogg", ".m4a", ".flac", ".aac", ".opus", ".wma"};
    static final int RATE = 24000;

    static boolean isAudio(String n) {
        String l = n.toLowerCase();
        for (String e : EXT) if (l.endsWith(e)) return true;
        return false;
    }

    /** sounds/*  plus  sounds/<package>/* */
    static List<File> sources(String pkg) {
        List<File> out = new ArrayList<>();
        for (File dir : new File[]{Paths.SOUNDS, new File(Paths.SOUNDS, pkg)}) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) if (f.isFile() && isAudio(f.getName())) out.add(f);
        }
        return out;
    }

    /** -> converted wavs in .sound_cache (converted only when missing or older than the source) */
    static List<File> prepare(String pkg, Bridge.Log log) {
        List<File> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Paths.SOUND_CACHE.mkdirs();
        for (File src : sources(pkg)) {
            String base = src.getName();
            int dot = base.lastIndexOf('.');
            String stem = (dot > 0 ? base.substring(0, dot) : base).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
            if (stem.isEmpty()) stem = "sound";
            String name = stem + ".wav";
            for (int n = 2; used.contains(name); n++) name = stem + "_" + n + ".wav";
            used.add(name);
            File dst = new File(Paths.SOUND_CACHE, name);
            if (dst.isFile() && dst.lastModified() >= src.lastModified() && isTargetWav(dst)) { out.add(dst); continue; }
            try {
                if (isTargetWav(src)) copy(src, dst);
                else convert(src, dst);
                out.add(dst);
                log.log("  converted " + base);
            } catch (Exception e) {
                dst.delete();
                log.log("  couldn't convert " + base + ": " + e.getMessage());
            }
        }
        File[] cached = Paths.SOUND_CACHE.listFiles();
        if (cached != null) for (File f : cached)
            if (f.getName().toLowerCase().endsWith(".wav") && !used.contains(f.getName())) f.delete();
        return out;
    }

    static boolean isTargetWav(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[36];
            r.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            return b.getInt(0) == 0x46464952 && b.getInt(8) == 0x45564157 && b.getShort(20) == 1
                    && b.getShort(22) == 1 && b.getInt(24) == RATE && b.getShort(34) == 16;
        } catch (Exception e) {
            return false;
        }
    }

    static void copy(File a, File b) throws Exception { Files.write(b, Files.read(a)); }

    static void convert(File src, File dst) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src.getPath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) { ex.release(); throw new Exception("no audio track"); }
        ex.selectTrack(track);
        MediaCodec dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        dec.configure(fmt, null, null, 0);
        dec.start();

        int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        float[] mono = new float[1 << 16];
        int count = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        try {
            while (!outDone) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    ob.order(ByteOrder.LITTLE_ENDIAN);
                    int bps = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                    int frames = info.size / (bps * channels);
                    if (count + frames > mono.length) mono = Arrays.copyOf(mono, Math.max(mono.length * 2, count + frames));
                    for (int f = 0; f < frames; f++) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++)
                            sum += bps == 4 ? ob.getFloat() : ob.getShort() / 32768f;
                        mono[count++] = sum / channels;
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            dec.stop();
            dec.release();
            ex.release();
        }

        // linear resample to 24 kHz
        int outN = (int) ((long) count * RATE / Math.max(1, rate));
        ByteBuffer pcm = ByteBuffer.allocate(44 + outN * 2).order(ByteOrder.LITTLE_ENDIAN);
        pcm.putInt(0x46464952).putInt(36 + outN * 2).putInt(0x45564157)
           .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
           .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
           .putInt(0x61746164).putInt(outN * 2);
        double step = (double) rate / RATE;
        for (int i = 0; i < outN; i++) {
            double p = i * step;
            int a = (int) p;
            float fr = (float) (p - a);
            float v = a + 1 < count ? mono[a] * (1 - fr) + mono[a + 1] * fr : mono[Math.min(a, count - 1)];
            pcm.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f))));
        }
        File tmp = new File(dst.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(pcm.array()); }
        if (!tmp.renameTo(dst)) throw new Exception("couldn't write " + dst);
    }
}
