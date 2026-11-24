package dev.vrfrida;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * The app window's own animated background (behind the translucent surfaces): aurora blobs, drifting particles,
 * layered waves or a synthwave grid, in the current theme's colors. ~30 fps, stops drawing while not visible.
 */
final class AppBgView extends View {
    String kind = "aurora";
    float strength = 0.55f, speed = 1f;
    boolean animate = true;
    int surface, primary, secondary, tertiary;
    float px = -1, py = -1;               // pointer, the particles lean towards it a little

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final long t0 = System.currentTimeMillis();
    final float[][] dots = new float[70][5];

    AppBgView(Context c) {
        super(c);
        Random r = new Random(7);
        for (float[] d : dots) { d[0] = r.nextFloat(); d[1] = r.nextFloat(); d[2] = 0.3f + r.nextFloat(); d[3] = r.nextFloat() * 6.28f; d[4] = r.nextFloat(); }
    }

    void set(String kind, float strength, float speed, boolean animate, int surface, int primary, int secondary, int tertiary) {
        this.kind = kind; this.strength = strength; this.speed = speed; this.animate = animate;
        this.surface = surface; this.primary = primary; this.secondary = secondary; this.tertiary = tertiary;
        invalidate();
    }

    static int alpha(int c, float a) { return (c & 0x00FFFFFF) | (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(surface);
        if (kind.equals("off") || kind.equals("menu") || w <= 0) return;
        float t = animate ? (System.currentTimeMillis() - t0) / 1000f * speed : 12f;
        float s = strength;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        switch (kind) {
            case "aurora": {
                int[] cols = {primary, tertiary, secondary, primary};
                for (int i = 0; i < 4; i++) {
                    float cx = w * (0.5f + 0.42f * (float) Math.sin(t * 0.11 * (1 + i * 0.37) + i * 1.7));
                    float cy = h * (0.5f + 0.40f * (float) Math.cos(t * 0.09 * (1 + i * 0.29) + i * 2.3));
                    float r = Math.max(w, h) * (0.38f + 0.08f * (float) Math.sin(t * 0.2 + i));
                    p.setShader(new RadialGradient(cx, cy, r, alpha(cols[i], 0.34f * s), alpha(cols[i], 0), Shader.TileMode.CLAMP));
                    c.drawCircle(cx, cy, r, p);
                }
                break;
            }
            case "particles": {
                float d = getResources().getDisplayMetrics().density;
                for (float[] o : dots) {
                    float x = (o[0] + t * 0.012f * o[2]) % 1f, y = (o[1] - t * 0.02f * o[2] + 10) % 1f;
                    x = x * w + (float) Math.sin(t * 0.6 + o[3]) * 12 * d;
                    y = y * h;
                    if (px >= 0) { float dx = px - x, dy = py - y, dist = (float) Math.hypot(dx, dy); if (dist < 220 * d) { float k = (1 - dist / (220 * d)) * 18 * d; x += dx / Math.max(1, dist) * k; y += dy / Math.max(1, dist) * k; } }
                    float tw = 0.5f + 0.5f * (float) Math.sin(t * 1.5 + o[3] * 3);
                    int col = o[4] < 0.5f ? primary : o[4] < 0.8f ? tertiary : secondary;
                    float r = (1.2f + o[2] * 2.2f) * d;
                    p.setShader(new RadialGradient(x, y, r * 4, alpha(col, 0.5f * s * tw), alpha(col, 0), Shader.TileMode.CLAMP));
                    c.drawCircle(x, y, r * 4, p);
                    p.setShader(null);
                    p.setColor(alpha(col, (0.45f + 0.5f * tw) * s));
                    c.drawCircle(x, y, r, p);
                }
                break;
            }
            case "waves": {
                int[] cols = {tertiary, secondary, primary};
                for (int i = 0; i < 3; i++) {
                    path.reset();
                    float base = h * (0.58f + i * 0.13f), amp = h * (0.05f + i * 0.015f);
                    path.moveTo(0, h);
                    for (int x = 0; x <= 48; x++) {
                        float fx = x / 48f * w;
                        float y = base + amp * (float) Math.sin(fx / w * 6.28 * (1.2 + i * 0.4) + t * (0.5 + i * 0.25) + i * 2)
                                + amp * 0.4f * (float) Math.sin(fx / w * 6.28 * 3.1 - t * 0.7);
                        path.lineTo(fx, y);
                    }
                    path.lineTo(w, h);
                    path.close();
                    p.setShader(new LinearGradient(0, base - amp, 0, h, alpha(cols[i], (0.22f + i * 0.08f) * s), alpha(cols[i], 0.03f * s), Shader.TileMode.CLAMP));
                    c.drawPath(path, p);
                }
                break;
            }
            case "grid": {
                float horizon = h * 0.42f;
                p.setShader(new LinearGradient(0, 0, 0, horizon, alpha(tertiary, 0), alpha(primary, 0.28f * s), Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, horizon, p);
                p.setShader(null);
                // sun
                float sr = h * 0.16f;
                p.setShader(new LinearGradient(0, horizon - sr, 0, horizon, alpha(tertiary, 0.55f * s), alpha(primary, 0.35f * s), Shader.TileMode.CLAMP));
                c.drawCircle(w / 2, horizon, sr, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1, getResources().getDisplayMetrics().density * 1.2f));
                p.setColor(alpha(primary, 0.4f * s));
                c.drawLine(0, horizon, w, horizon, p);
                float scroll = (t * 0.35f) % 1f;
                for (int i = 0; i < 14; i++) {
                    float z = (i + scroll) / 14f, y = horizon + (h - horizon) * z * z;
                    p.setColor(alpha(primary, 0.38f * s * z));
                    c.drawLine(0, y, w, y, p);
                }
                for (int i = -12; i <= 12; i++) {
                    p.setColor(alpha(primary, 0.3f * s));
                    c.drawLine(w / 2 + i * w * 0.03f, horizon, w / 2 + i * w * 0.22f, h, p);
                }
                p.setStyle(Paint.Style.FILL);
                break;
            }
        }
        p.setShader(null);
        if (animate && isShown()) postInvalidateDelayed(33);
    }

    @Override protected void onVisibilityChanged(View v, int vis) { super.onVisibilityChanged(v, vis); if (vis == VISIBLE) invalidate(); }
}
