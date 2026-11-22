package dev.vrfrida;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * UniversalMenu's themes and backgrounds, as exported at build time by gen_menu_assets.py (assets/menu/).
 * Colors are kept as float rgba like the menu, plus a port of colorsFromRGB for the UXpatcher theme.
 */
final class MenuLook {
    static final String UXPATCHER = "uxpatcher", GAME = "game";

    static final class Theme { String id, name; Map<String, float[]> colors; List<Map<String, float[]>> frames; }

    final List<Theme> themes = new ArrayList<>();
    final Map<String, String> aliases = new HashMap<>();
    JSONObject meta, defaults;
    String[] bgNames, patColorNames, cursorShapes, cursorColors, clickStyles;
    final Map<String, Bitmap> bitmaps = new HashMap<>();
    final Context ctx;

    MenuLook(Context ctx) {
        this.ctx = ctx;
        try {
            JSONObject t = new JSONObject(Files.readAsset(ctx, "menu/themes.json"));
            JSONArray arr = t.getJSONArray("themes");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Theme th = new Theme();
                th.id = o.getString("id");
                th.name = o.getString("name");
                th.colors = colorMap(o.getJSONObject("colors"));
                JSONArray fr = o.optJSONArray("frames");
                if (fr != null) { th.frames = new ArrayList<>(); for (int k = 0; k < fr.length(); k++) th.frames.add(colorMap(fr.getJSONObject(k))); }
                themes.add(th);
            }
            JSONObject al = t.optJSONObject("aliases");
            if (al != null) for (Iterator<String> it = al.keys(); it.hasNext(); ) { String k = it.next(); aliases.put(k, al.getString(k)); }
            meta = new JSONObject(Files.readAsset(ctx, "menu/meta.json"));
            defaults = meta.optJSONObject("defaults");
            if (defaults == null) defaults = new JSONObject();
            bgNames = strings(meta.getJSONArray("BG_NAMES"));
            patColorNames = strings(meta.getJSONArray("PAT_COLORS"));
            cursorShapes = strings(meta.getJSONArray("CURSOR_SHAPES"));
            cursorColors = strings(meta.getJSONArray("CURSOR_COLORS"));
            clickStyles = strings(meta.getJSONArray("CLICK_STYLES"));
        } catch (Exception e) {
            throw new RuntimeException("menu assets missing from the apk (gen_menu_assets.py): " + e);
        }
    }

    static String[] strings(JSONArray a) throws Exception {
        String[] s = new String[a.length()];
        for (int i = 0; i < s.length; i++) s[i] = a.getString(i);
        return s;
    }

    static Map<String, float[]> colorMap(JSONObject o) throws Exception {
        Map<String, float[]> m = new HashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray a = o.getJSONArray(k);
            m.put(k, new float[]{(float) a.getDouble(0), (float) a.getDouble(1), (float) a.getDouble(2), a.length() > 3 ? (float) a.getDouble(3) : 1f});
        }
        return m;
    }

    String defaultTheme() { return defaults.optString("THEME", "midnight"); }

    Theme theme(String id) {
        if (id != null && aliases.containsKey(id)) id = aliases.get(id);
        for (Theme t : themes) if (t.id.equals(id)) return t;
        for (Theme t : themes) if (t.id.equals(defaultTheme())) return t;
        return themes.get(0);
    }

    /** colors for a theme id right now (the Rainbow theme drifts like it does in the menu) */
    Map<String, float[]> colors(String id, Context c, float rainbowSpeed) {
        if (UXPATCHER.equals(id)) return uxpatcher(c);
        Theme t = theme(id);
        if (t.frames == null || t.frames.isEmpty()) return t.colors;
        double h = (System.currentTimeMillis() / 1000.0 * rainbowSpeed) % 1.0;
        double p = h * t.frames.size();
        int a = (int) p % t.frames.size(), b = (a + 1) % t.frames.size();
        float f = (float) (p - Math.floor(p));
        Map<String, float[]> out = new HashMap<>();
        for (Map.Entry<String, float[]> e : t.frames.get(a).entrySet()) {
            float[] x = e.getValue(), y = t.frames.get(b).get(e.getKey());
            if (y == null) { out.put(e.getKey(), x); continue; }
            out.put(e.getKey(), new float[]{x[0] + (y[0] - x[0]) * f, x[1] + (y[1] - x[1]) * f, x[2] + (y[2] - x[2]) * f, x[3] + (y[3] - x[3]) * f});
        }
        return out;
    }

    boolean animated(String id) { return !UXPATCHER.equals(id) && theme(id).frames != null; }

    // ---- UXpatcher: its 3 colors -> a menu palette -> the menu's own colorsFromRGB ----
    static float[] rgb(int c) { return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f}; }
    static float[] mix(float[] a, float[] b, float t) { return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t}; }
    static float[] lighter(float[] c, float t) { return mix(c, new float[]{1, 1, 1}, t); }
    static float[] darker(float[] c, float t) { return mix(c, new float[]{0, 0, 0}, t); }
    static float[] a(float[] c, float alpha) { return new float[]{c[0], c[1], c[2], alpha}; }

    static boolean hasUxpatcher(Context c) { return uxp(c, "accent_color") != null; }

    static String uxp(Context c, String key) {
        try { return android.provider.Settings.Global.getString(c.getContentResolver(), "uxpatcher_" + key); } catch (Exception e) { return null; }
    }

    static int uxpInt(Context c, String key, int def) {
        try { return Integer.parseInt(uxp(c, key).trim()); } catch (Exception e) { return def; }
    }

    static Map<String, float[]> uxpatcher(Context c) {
        float[] bg = rgb(uxpInt(c, "bg_color", 0x101018)), acc = rgb(uxpInt(c, "accent_color", 0xB07CFF)), text = rgb(uxpInt(c, "text_color", 0xFFFFFF));
        float lum = 0.299f * acc[0] + 0.587f * acc[1] + 0.114f * acc[2];
        return colorsFromRGB(bg, mix(bg, text, 0.06f), mix(bg, text, 0.11f), acc, lighter(acc, 0.35f), text, mix(text, bg, 0.45f),
                lum > 0.6f ? darker(bg, 0.2f) : new float[]{1, 1, 1});
    }

    /** UniversalMenu.ts colorsFromRGB (the keys the app draws with) */
    static Map<String, float[]> colorsFromRGB(float[] bg, float[] panel, float[] surface, float[] ac, float[] a2, float[] text, float[] muted, float[] onA) {
        Map<String, float[]> o = new HashMap<>();
        o.put("Text", a(text, 1)); o.put("TextDisabled", a(muted, 0.92f)); o.put("TextOnActive", a(onA, 1));
        o.put("WindowBg", a(bg, 0.94f)); o.put("Pattern", a(ac, 0.14f)); o.put("PopupBg", a(panel, 0.98f)); o.put("Border", a(mix(bg, ac, 0.5f), 0.6f));
        o.put("TitleBg", a(darker(bg, 0.25f), 0.97f)); o.put("TitleBgActive", a(mix(panel, ac, 0.22f), 0.98f));
        o.put("RowBg", a(surface, 0.42f)); o.put("RowHover", a(mix(surface, ac, 0.28f), 0.7f));
        o.put("FrameBg", a(panel, 0.8f)); o.put("FrameBgHovered", a(mix(panel, ac, 0.22f), 0.88f)); o.put("FrameBgActive", a(mix(panel, ac, 0.4f), 0.94f));
        o.put("Check", a(a2, 1)); o.put("CheckMark", a(lighter(a2, 0.25f), 1));
        o.put("SliderFill", a(ac, 0.78f)); o.put("SliderFillActive", a(lighter(ac, 0.15f), 0.95f)); o.put("SliderGrab", a(lighter(ac, 0.3f), 1)); o.put("SliderGrabActive", a(lighter(ac, 0.55f), 1));
        o.put("Button", a(mix(panel, ac, 0.42f), 0.9f)); o.put("ButtonHovered", a(mix(panel, ac, 0.58f), 0.96f)); o.put("ButtonActive", a(ac, 1));
        o.put("Separator", a(ac, 0.28f)); o.put("Tab", a(panel, 0.88f)); o.put("TabHovered", a(mix(panel, ac, 0.5f), 0.92f)); o.put("TabActive", a(ac, 0.98f));
        o.put("GradTop", a(ac, 1)); o.put("GradBottom", a(a2, 1)); o.put("Accent", a(ac, 1));
        o.put("NotifWarn", new float[]{1, 0.82f, 0.36f, 1}); o.put("NotifError", new float[]{1, 0.38f, 0.45f, 1}); o.put("NotifSuccess", new float[]{0.37f, 0.88f, 0.54f, 1});
        return o;
    }

    static int argb(float[] c) { return argb(c, 1f); }

    static int argb(float[] c, float alphaMul) {
        if (c == null) return 0xFFFF00FF;
        int a = Math.round(Math.max(0, Math.min(1, c[3] * alphaMul)) * 255);
        return (a << 24) | (Math.round(c[0] * 255) << 16) | (Math.round(c[1] * 255) << 8) | Math.round(c[2] * 255);
    }

    /** generated background layer, the spiral, or a pattern png from /sdcard/vrfrida/patterns */
    Bitmap bitmap(String id) {
        Bitmap b = bitmaps.get(id);
        if (b != null || bitmaps.containsKey(id)) return b;
        try {
            if (id.startsWith("pat:")) {
                File f = new File(Paths.PATTERNS, id.substring(4));
                b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
            } else {
                try (InputStream in = ctx.getAssets().open(id.equals("spiral") ? "menu/spiral.png" : "menu/bg/" + id + ".png")) { b = BitmapFactory.decodeStream(in); }
            }
        } catch (Exception ignored) {}
        bitmaps.put(id, b);
        return b;
    }

    /** pattern files the menu would list (it sees the same folder after a sync) */
    static String[] patCache; static long patAt, patMtime;

    /** the pattern pictures (cached: the background asks every frame; re-listed when the folder changes or after 5 s) */
    static synchronized String[] patternFiles() {
        long now = System.currentTimeMillis(), mt = Paths.PATTERNS.lastModified();
        if (patCache != null && mt == patMtime && now - patAt < 5000) return patCache;
        String[] fs = Paths.PATTERNS.list((d, n) -> n.toLowerCase().matches(".*\\.(png|jpe?g)$"));
        if (fs == null) fs = new String[0];
        java.util.Arrays.sort(fs);
        patCache = fs; patAt = now; patMtime = mt;
        return fs;
    }
}
