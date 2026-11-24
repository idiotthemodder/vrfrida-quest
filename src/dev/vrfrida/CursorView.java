package dev.vrfrida;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Custom cursor drawn over the whole window. MainActivity feeds it the hover / touch position from its dispatch
 * hooks (the Quest controller ray sends hover events to 2D apps), it never takes input itself.
 * Grows over anything clickable, makes a ripple ring on press, optional fading trail.
 */
final class CursorView extends View {
    String shape = "ring", colorMode = "accent";
    float sizeDp = 20;
    boolean trail;
    int accent = 0xFF8AB4FF, tertiary = 0xFFFFB0D0;

    float x = -1, y = -1, sx = -1, sy = -1;   // target + smoothed position
    float hot = 0, hotTarget = 0, vis = 0, visTarget = 0, down = 0;
    long pressAt = 0, lastMove = 0;
    float pressX, pressY;
    final float[] tx = new float[14], ty = new float[14];
    int tn = 0;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final float d;

    CursorView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void style(String shape, float sizeDp, String colorMode, boolean trail, int accent, int tertiary) {
        this.shape = shape; this.sizeDp = sizeDp; this.colorMode = colorMode; this.trail = trail; this.accent = accent; this.tertiary = tertiary;
        setVisibility(shape.equals("off") ? GONE : VISIBLE);
        invalidate();
    }

    void move(float nx, float ny, boolean overClickable) {
        if (sx < 0) { sx = nx; sy = ny; }
        x = nx; y = ny;
        hotTarget = overClickable ? 1 : 0;
        visTarget = 1;
        lastMove = System.currentTimeMillis();
        invalidate();
    }

    void press(float nx, float ny) { pressAt = System.currentTimeMillis(); pressX = nx; pressY = ny; down = 1; move(nx, ny, hotTarget > 0.5f); }
    void release() { down = 0; invalidate(); }
    void exit() { visTarget = 0; invalidate(); }

    int color(long now) {
        switch (colorMode) {
            case "white": return Color.WHITE;
            case "tertiary": return tertiary;
            case "rainbow": return Color.HSVToColor(new float[]{(now / 12f) % 360, 0.6f, 1f});
            default: return accent;
        }
    }

    static int a(int c, float al) { return (c & 0x00FFFFFF) | (Math.round(Math.max(0, Math.min(1, al)) * 255) << 24); }

    @Override protected void onDraw(Canvas c) {
        if (shape.equals("off") || sx < 0) return;
        long now = System.currentTimeMillis();
        // smooth: position, hover growth, fade in/out (hidden 4 s after the last move)
        if (now - lastMove > 4000) visTarget = 0;
        sx += (x - sx) * 0.55f; sy += (y - sy) * 0.55f;
        hot += (hotTarget - hot) * 0.25f;
        vis += (visTarget - vis) * 0.2f;
        boolean moving = Math.abs(x - sx) > 0.5f || Math.abs(y - sy) > 0.5f || Math.abs(hotTarget - hot) > 0.01f || Math.abs(visTarget - vis) > 0.01f;
        int col = color(now);
        float r = sizeDp * d / 2 * (1 + 0.35f * hot) * (down > 0 ? 0.85f : 1);

        if (trail) {
            tx[tn % tx.length] = sx; ty[tn % ty.length] = sy; tn++;
            int n = Math.min(tn, tx.length);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                int k = (tn - 1 - i) % tx.length;
                float f = 1 - (float) i / n;
                p.setColor(a(col, 0.35f * f * vis));
                c.drawCircle(tx[k], ty[k], r * 0.35f * f + d, p);
            }
            moving = true;
        }

        // press ripple
        float pr = (now - pressAt) / 380f;
        if (pr < 1) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d * (1 - pr));
            p.setColor(a(col, 0.7f * (1 - pr)));
            c.drawCircle(pressX, pressY, r + 26 * d * pr, p);
            moving = true;
        }

        if (vis > 0.01f) {
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float cx = sx, cy = sy;
            switch (shape) {
                case "dot":
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(a(col, 0.25f * vis * hot)); c.drawCircle(cx, cy, r * 1.4f, p);
                    p.setColor(a(Color.BLACK, 0.35f * vis)); c.drawCircle(cx, cy, r * 0.5f + d, p);
                    p.setColor(a(col, vis)); c.drawCircle(cx, cy, r * 0.5f, p);
                    break;
                case "crosshair":
                    stroke(c, 3.2f, a(Color.BLACK, 0.4f * vis), cx, cy, r);
                    stroke(c, 2f, a(col, vis), cx, cy, r);
                    break;
                case "arrow": {
                    float s = r * 1.5f;
                    path.reset();
                    path.moveTo(cx, cy); path.lineTo(cx, cy + s * 1.25f); path.lineTo(cx + s * 0.33f, cy + s * 0.95f);
                    path.lineTo(cx + s * 0.58f, cy + s * 1.45f); path.lineTo(cx + s * 0.75f, cy + s * 1.36f);
                    path.lineTo(cx + s * 0.52f, cy + s * 0.88f); path.lineTo(cx + s * 0.92f, cy + s * 0.88f); path.close();
                    p.setStyle(Paint.Style.FILL); p.setColor(a(col, vis)); c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f * d); p.setColor(a(Color.BLACK, 0.6f * vis)); c.drawPath(path, p);
                    break;
                }
                case "reticle": {
                    float spin = (now % 4000) / 4000f * 360 * (0.4f + hot);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); p.setColor(a(col, vis));
                    android.graphics.RectF o = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
                    for (int i = 0; i < 3; i++) c.drawArc(o, spin + i * 120, 70, false, p);
                    p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, 1.8f * d, p);
                    moving = true;
                    break;
                }
                case "diamond": {
                    float s = r * 0.9f;
                    path.reset();
                    path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close();
                    p.setStyle(Paint.Style.FILL); p.setColor(a(col, (0.18f + 0.2f * hot) * vis)); c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); p.setColor(a(col, vis)); c.drawPath(path, p);
                    break;
                }
                default: // ring
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(a(col, (0.08f + 0.2f * hot) * vis)); c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(3.2f * d); p.setColor(a(Color.BLACK, 0.3f * vis)); c.drawCircle(cx, cy, r, p);
                    p.setStrokeWidth(2 * d); p.setColor(a(col, vis)); c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, 1.6f * d, p);
            }
        }
        if (moving || (colorMode.equals("rainbow") && vis > 0.01f)) postInvalidateOnAnimation();
    }

    void stroke(Canvas c, float wDp, int col, float cx, float cy, float r) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(wDp * d); p.setColor(col);
        float g = r * 0.3f;
        lines[0] = cx - r; lines[1] = cy; lines[2] = cx - g; lines[3] = cy;
        lines[4] = cx + g; lines[5] = cy; lines[6] = cx + r; lines[7] = cy;
        lines[8] = cx; lines[9] = cy - r; lines[10] = cx; lines[11] = cy - g;
        lines[12] = cx; lines[13] = cy + g; lines[14] = cx; lines[15] = cy + r;
        c.drawLines(lines, p);
        c.drawPoint(cx, cy, p);
    }

    final float[] lines = new float[16];
}
