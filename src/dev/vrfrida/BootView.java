package dev.vrfrida;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.function.Supplier;

/**
 * Boot screen over the app while startup runs (root check, storage, game scan). Styles: orbit / glitch / pulse.
 * Stays at least MIN_MS, leaves once the app says it's ready (or after MAX_MS), tap skips. Exit = zoom + fade.
 */
final class BootView extends View {
    static final long MIN_MS = 1900, MAX_MS = 9000;

    final String style;
    final int surface, primary, tertiary, onSurface, dim;
    final Supplier<String> status;
    final Runnable onGone;
    final long t0 = System.currentTimeMillis();
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    final float d;
    volatile boolean ready;
    boolean leaving;
    float progress;                       // eased fake-ish progress, jumps to 1 when ready

    BootView(Context c, String style, java.util.Map<String, Integer> s, Supplier<String> status, Runnable onGone) {
        super(c);
        this.style = style;
        surface = s.get("surface"); primary = s.get("primary"); tertiary = s.get("tertiary");
        onSurface = s.get("onSurface"); dim = s.get("onSurfaceVariant");
        this.status = status; this.onGone = onGone;
        d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
    }

    void ready() { ready = true; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) leave();
        return true;
    }

    void leave() {
        if (leaving) return;
        leaving = true;
        animate().alpha(0).scaleX(1.08f).scaleY(1.08f).setDuration(450).withEndAction(() -> {
            ViewGroup g = (ViewGroup) getParent();
            if (g != null) g.removeView(this);
            if (onGone != null) onGone.run();
        }).start();
    }

    static int a(int c, float al) { return (c & 0x00FFFFFF) | (Math.round(Math.max(0, Math.min(1, al)) * 255) << 24); }
    static float ease(float x) { x = Math.max(0, Math.min(1, x)); return 1 - (1 - x) * (1 - x) * (1 - x); }

    @Override protected void onDraw(Canvas c) {
        long el = System.currentTimeMillis() - t0;
        float t = el / 1000f, w = getWidth(), h = getHeight(), cx = w / 2, cy = h * 0.44f;
        if (!leaving && ((ready && el > MIN_MS) || el > MAX_MS)) post(this::leave);
        float goal = ready ? 1f : Math.min(0.9f, 0.9f * (1 - (float) Math.exp(-el / 2200.0)));
        progress += (goal - progress) * 0.08f;

        c.drawColor(surface);
        float glowR = Math.max(w, h) * 0.55f * (0.9f + 0.1f * (float) Math.sin(t * 1.6));
        p.setShader(new RadialGradient(cx, cy, glowR, a(primary, 0.22f), a(primary, 0), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, glowR, p);
        p.setShader(null);

        float R = Math.min(w, h) * 0.14f;
        switch (style) {
            case "glitch": drawGlitch(c, t, cx, cy, R); break;
            case "pulse": drawPulse(c, t, cx, cy, R); break;
            default: drawOrbit(c, t, cx, cy, R);
        }

        // title: letters drop in one by one
        String title = "vrfrida";
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        p.setTextSize(44 * d);
        p.setStyle(Paint.Style.FILL);
        float tw = p.measureText(title), x = cx - tw / 2, ty = cy + R + 92 * d;
        for (int i = 0; i < title.length(); i++) {
            String ch = String.valueOf(title.charAt(i));
            float k = ease((t - 0.25f - i * 0.07f) / 0.45f);
            float jx = 0, jy = (1 - k) * 18 * d;
            if (style.equals("glitch") && Math.sin(t * 23 + i * 5) > 0.93) jx = (float) Math.sin(t * 91 + i) * 6 * d;
            if (style.equals("glitch")) {
                p.setColor(a(0xFFFF2A6D, 0.55f * k)); c.drawText(ch, x + jx - 2 * d, ty + jy, p);
                p.setColor(a(0xFF05D9E8, 0.55f * k)); c.drawText(ch, x + jx + 2 * d, ty + jy, p);
            }
            p.setColor(a(onSurface, k));
            c.drawText(ch, x + jx, ty + jy, p);
            x += p.measureText(ch);
        }
        p.setTypeface(Typeface.DEFAULT);

        // tagline + status
        float k2 = ease((t - 0.9f) / 0.5f);
        p.setTextSize(15 * d);
        p.setColor(a(dim, k2));
        String tag = "frida toolkit for Quest";
        c.drawText(tag, cx - p.measureText(tag) / 2, ty + 30 * d, p);

        // progress bar
        float bw = Math.min(w * 0.36f, 360 * d), bx = cx - bw / 2, by = ty + 62 * d, bh = 4 * d;
        p.setColor(a(onSurface, 0.12f * k2));
        r.set(bx, by, bx + bw, by + bh); c.drawRoundRect(r, bh / 2, bh / 2, p);
        p.setColor(a(primary, k2));
        r.set(bx, by, bx + Math.max(bh, bw * progress), by + bh); c.drawRoundRect(r, bh / 2, bh / 2, p);
        // sheen moving along the bar
        float sh = bx + ((t * 0.7f) % 1.3f) * bw;
        p.setShader(new RadialGradient(sh, by + bh / 2, 30 * d, a(0xFFFFFFFF, 0.5f * k2), a(0xFFFFFFFF, 0), Shader.TileMode.CLAMP));
        r.set(bx, by, bx + Math.max(bh, bw * progress), by + bh); c.drawRoundRect(r, bh / 2, bh / 2, p);
        p.setShader(null);

        String st = status.get();
        if (st != null) {
            p.setTextSize(13 * d);
            p.setColor(a(dim, 0.85f * k2));
            if (st.length() > 70) st = st.substring(0, 69) + "…";
            c.drawText(st, cx - p.measureText(st) / 2, by + 30 * d, p);
        }
        p.setTextSize(11 * d);
        p.setColor(a(dim, 0.5f * ease((t - 2.5f) / 0.6f)));
        String skip = "tap to skip";
        c.drawText(skip, cx - p.measureText(skip) / 2, h - 28 * d, p);

        if (!leaving || getAlpha() > 0) postInvalidateOnAnimation();
    }

    void drawOrbit(Canvas c, float t, float cx, float cy, float R) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        float in = ease(t / 0.8f);
        for (int i = 0; i < 3; i++) {
            float rr = R * (0.55f + i * 0.32f) * in;
            float sweep = 70 + 40 * (float) Math.sin(t * 1.3 + i);
            float rot = (t * (110 - i * 30) * (i % 2 == 0 ? 1 : -1)) % 360;
            p.setStrokeWidth((5 - i) * d);
            p.setColor(a(i == 1 ? tertiary : primary, 0.9f - i * 0.2f));
            r.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(r, rot, sweep, false, p);
            c.drawArc(r, rot + 180, sweep * 0.6f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) {
            double ang = t * (0.9 + i * 0.15) + i * Math.PI / 3;
            float rr = R * (1.25f + 0.08f * (float) Math.sin(t * 2 + i)) * in;
            float px = cx + (float) Math.cos(ang) * rr, py = cy + (float) Math.sin(ang) * rr * 0.55f;
            p.setColor(a(i % 2 == 0 ? primary : tertiary, 0.85f));
            c.drawCircle(px, py, (2.5f + (i % 3)) * d, p);
        }
        // core: a "v" mark
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5 * d);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(a(onSurface, in));
        float s = R * 0.28f;
        android.graphics.Path v = new android.graphics.Path();
        v.moveTo(cx - s, cy - s * 0.7f); v.lineTo(cx, cy + s * 0.8f); v.lineTo(cx + s, cy - s * 0.7f);
        c.drawPath(v, p);
        p.setStyle(Paint.Style.FILL);
    }

    void drawPulse(Canvas c, float t, float cx, float cy, float R) {
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 4; i++) {
            float ph = ((t * 0.6f) + i * 0.25f) % 1f;
            p.setStrokeWidth(3 * d * (1 - ph) + d);
            p.setColor(a(i % 2 == 0 ? primary : tertiary, 0.8f * (1 - ph)));
            c.drawCircle(cx, cy, R * (0.3f + 1.3f * ph), p);
        }
        p.setStyle(Paint.Style.FILL);
        float beat = 1 + 0.08f * (float) Math.pow(Math.max(0, Math.sin(t * 5)), 8);
        p.setShader(new RadialGradient(cx, cy, R * 0.55f * beat, a(tertiary, 0.95f), a(primary, 0.9f), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R * 0.45f * beat, p);
        p.setShader(null);
    }

    void drawGlitch(Canvas c, float t, float cx, float cy, float R) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * d);
        float in = ease(t / 0.6f);
        for (int i = 0; i < 3; i++) {
            float off = (float) Math.sin(t * 7 + i * 2) * 3 * d;
            int col = i == 0 ? 0xFFFF2A6D : i == 1 ? 0xFF05D9E8 : onSurface;
            p.setColor(a(col, i == 2 ? in : 0.6f * in));
            float s = R * 0.9f * in;
            r.set(cx - s + off, cy - s, cx + s + off, cy + s);
            c.drawRoundRect(r, 18 * d, 18 * d, p);
        }
        // scanlines
        p.setStyle(Paint.Style.FILL);
        p.setColor(a(0xFF000000, 0.12f));
        for (float y = 0; y < getHeight(); y += 4 * d) c.drawRect(0, y, getWidth(), y + d, p);
        // random glitch bars
        if (Math.sin(t * 13) > 0.7) {
            p.setColor(a(primary, 0.25f));
            float y = (float) ((Math.sin(t * 97) * 0.5 + 0.5) * getHeight());
            c.drawRect(0, y, getWidth(), y + 6 * d, p);
        }
    }
}
