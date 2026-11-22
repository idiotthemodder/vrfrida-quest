package dev.vrfrida;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** the menu window's background, drawn the way UniversalMenu's syncSpiral / syncBgLayers do it */
final class MenuBgView extends View {
    static final class Layer { String id, kind; float alpha, scale, sx, sy, sway, twinkle, flash; boolean tint; }

    interface Source { MenuCfg cfg(); Map<String, float[]> colors(); default boolean animate() { return true; } }

    final MenuLook look;
    final Source src;
    final List<List<Layer>> sets = new ArrayList<>();
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Map<String, BitmapShader> shaders = new HashMap<>();
    final Matrix m = new Matrix();
    final Path clip = new Path();
    final RectF rect = new RectF();
    final long t0 = System.currentTimeMillis();
    double clock = 0;
    long last = 0;
    final float spiralCenter;

    MenuBgView(Context c, MenuLook look, Source src) {
        super(c);
        this.look = look;
        this.src = src;
        spiralCenter = (float) look.defaults.optDouble("SPIRAL_CENTER", 0.12);
        try {
            JSONArray all = look.meta.getJSONArray("BG_SETS");
            for (int i = 0; i < all.length(); i++) {
                List<Layer> set = new ArrayList<>();
                JSONArray a = all.getJSONArray(i);
                for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.getJSONObject(k);
                    Layer l = new Layer();
                    l.id = o.getString("id"); l.kind = o.getString("kind");
                    l.alpha = (float) o.optDouble("alpha", 1); l.scale = (float) o.optDouble("scale", 1);
                    l.sx = (float) o.optDouble("sx", 0); l.sy = (float) o.optDouble("sy", 0);
                    l.sway = (float) o.optDouble("sway", 0); l.twinkle = (float) o.optDouble("twinkle", 0); l.flash = (float) o.optDouble("flash", 0);
                    l.tint = o.optBoolean("tint", false);
                    set.add(l);
                }
                sets.add(set);
            }
        } catch (Exception ignored) {}
    }

    float radius() {
        MenuCfg cfg = src.cfg();
        return (float) (cfg.num("rounding") * 2 * getResources().getDisplayMetrics().density * 0.9);
    }

    @Override protected void onDraw(Canvas cv) {
        MenuCfg cfg = src.cfg();
        Map<String, float[]> C = src.colors();
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        float opacity = (float) cfg.num("opacity");
        long now = System.currentTimeMillis();
        if (last != 0 && now - last < 500) clock += (now - last) / 1000.0 * cfg.num("bgSpeed");
        last = now;

        rect.set(0, 0, W, H);
        float r = radius();
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setColor(MenuLook.argb(C.get("WindowBg"), opacity));
        cv.drawRect(rect, paint);

        if (cfg.bool("pattern")) {
            int bg = (int) cfg.num("bg");
            if (bg == 0) drawSpiral(cv, W, H, cfg, C, opacity, now);
            else {
                List<Layer> set = bg < sets.size() ? sets.get(bg) : new ArrayList<>();
                if (bg == look.bgNames.length - 1) set = patternSet(cfg);
                for (int i = 0; i < set.size(); i++) drawLayer(cv, W, H, set.get(i), i, cfg, C, opacity);
            }
        }
        cv.restore();
        paint.setShader(null);
        paint.setColorFilter(null);
        // ~30 fps is plenty for a slow background, and leaves the CPU / battery to the game list (was every vsync)
        if (src.animate()) postInvalidateDelayed(33);
    }

    List<Layer> patternSet(MenuCfg cfg) {
        String[] files = MenuLook.patternFiles();
        String f = cfg.str("patFile");
        if ((f.isEmpty() || java.util.Arrays.asList(files).indexOf(f) < 0) && files.length > 0) f = files[0];
        Layer l = new Layer();
        l.id = "pat:" + f; l.kind = "tile"; l.alpha = 1; l.tint = true;
        l.scale = (float) cfg.num("patScale"); l.sx = (float) cfg.num("patSX"); l.sy = (float) cfg.num("patSY");
        List<Layer> out = new ArrayList<>();
        out.add(l);
        return out;
    }

    void drawSpiral(Canvas cv, float W, float H, MenuCfg cfg, Map<String, float[]> C, float opacity, long now) {
        Bitmap b = look.bitmap("spiral");
        if (b == null) return;
        double spin = cfg.num("spin");
        float angle = spin != 0 ? (float) (((now - t0) / 1000.0 * spin) % 360) : 0;
        float cx = W * spiralCenter, R = (float) Math.max(Math.hypot(W / 2 + Math.abs(cx), H / 2), 1);
        m.reset();
        m.postScale(2 * R / b.getWidth(), 2 * R / b.getHeight());
        m.postTranslate(-R, -R);
        m.postRotate(-angle);
        m.postTranslate(W / 2 + cx, H / 2);
        paint.setAlpha(255);
        paint.setColorFilter(new PorterDuffColorFilter(MenuLook.argb(C.get("Pattern"), opacity), PorterDuff.Mode.MULTIPLY));
        cv.drawBitmap(b, m, paint);
        paint.setColorFilter(null);
    }

    float[] patColor(MenuCfg cfg, Map<String, float[]> C) {
        int i = (int) cfg.num("patColor");
        if (i == 5) {      // Rainbow
            double h = (System.currentTimeMillis() / 1000.0 * 0.08) % 1;
            float[] o = new float[4];
            int[] n = {5, 3, 1};
            for (int k = 0; k < 3; k++) { double kk = (n[k] + h * 6) % 6; o[k] = (float) (1 - Math.max(0, Math.min(Math.min(kk, 4 - kk), 1))); }
            o[3] = 1;
            return o;
        }
        try {
            JSONArray pc = look.meta.getJSONArray("patColors");
            if (i > 0 && i < pc.length() && !pc.isNull(i)) {
                JSONArray a = pc.getJSONArray(i);
                return new float[]{(float) a.getDouble(0), (float) a.getDouble(1), (float) a.getDouble(2), (float) a.getDouble(3)};
            }
        } catch (Exception ignored) {}
        return C.get("Pattern");
    }

    void drawLayer(Canvas cv, float W, float H, Layer d, int idx, MenuCfg cfg, Map<String, float[]> C, float opacity) {
        Bitmap b = look.bitmap(d.id);
        if (b == null) return;
        double t = clock;
        float offU = (float) (t * d.sx + (d.sway != 0 ? d.sway * Math.sin(t * 0.7 + idx * 1.3) : 0));
        float offV = (float) (t * d.sy);
        float a = (float) (d.alpha * cfg.num("bgStrength"));
        if (d.twinkle != 0) a *= 0.6f + 0.4f * (float) Math.sin(clock * 1.3 * d.twinkle + idx * 1.7);
        if (d.flash != 0) { a *= flash(clock * d.flash); if (a <= 0.001f) return; }
        if (d.tint) {
            float[] c = d.id.startsWith("pat:") ? patColor(cfg, C) : C.get("Pattern");
            paint.setColorFilter(new PorterDuffColorFilter(MenuLook.argb(c, opacity * (a / Math.max(0.01f, d.alpha))), PorterDuff.Mode.MULTIPLY));
            paint.setAlpha(255);
        } else {
            paint.setColorFilter(null);
            paint.setAlpha(Math.round(Math.max(0, Math.min(1, a * opacity)) * 255));
        }
        if ("cover".equals(d.kind)) {
            float z = d.scale, w = W * z, h = H * z;
            m.reset();
            m.postScale(w / b.getWidth(), h / b.getHeight());
            m.postTranslate((W - w) / 2 - offU * w, (H - h) / 2 + offV * h);
            cv.drawBitmap(b, m, paint);
        } else {
            float T = Math.max(40, H * d.scale);
            BitmapShader sh = shaders.get(d.id);
            if (sh == null) { sh = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); shaders.put(d.id, sh); }
            m.reset();
            m.postScale(T / b.getWidth(), T / b.getHeight());
            m.postTranslate(W / 2 - offU * T, H / 2 + offV * T);
            sh.setLocalMatrix(m);
            paint.setShader(sh);
            cv.drawRect(0, 0, W, H, paint);
            paint.setShader(null);
        }
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    /** UniversalMenu's rngSeed (mulberry32), first value */
    static double rng1(int seed) {
        int a = seed + 0x6D2B79F5, t = a;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
    }

    /** same lightning timing as the menu's bgFlash */
    static float flash(double t) {
        double P = 5.5; long k = (long) Math.floor(t / P);
        double ph = t - k * P - rng1((int) (k * 7919 + 13)) * 3;
        if (ph < 0 || ph > 0.7) return 0;
        return (float) (ph < 0.07 ? 1 : ph < 0.15 ? 0.2 : ph < 0.25 ? 0.95 : Math.max(0, 1 - (ph - 0.25) / 0.45) * 0.7);
    }
}
