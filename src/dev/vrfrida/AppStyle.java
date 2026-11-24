package dev.vrfrida;

import android.content.Context;
import android.graphics.Typeface;

import org.json.JSONObject;

import java.util.Map;

/**
 * The app's own look + feel (Customize tab), kept in the "applook" pref next to appTheme.
 * Everything has a default, so an old / empty applook just gets today's look plus the new extras.
 */
final class AppStyle {
    // ---------------------------------------------------------------- choices (value ids + labels shown in the UI)
    static final String[] THEMES = {"auto", "meta", "uxpatcher", "violet", "rose", "emerald", "sunset", "ocean", "crimson", "gold", "mono", "custom"};
    static final String[] THEME_LABELS = {"Auto", "Meta blue", "UXpatcher", "Violet", "Rose", "Emerald", "Sunset", "Ocean", "Crimson", "Gold", "Mono", "Custom hue"};
    static final int[] THEME_SEEDS = {0, M3.META_BLUE, 0, 0xFF8B5CF6, 0xFFF43F86, 0xFF10B981, 0xFFFF7A1A, 0xFF06B6D4, 0xFFE11D48, 0xFFEAB308, 0xFF808080, 0};

    static final String[] MODES = {"dark", "amoled", "light"};
    static final String[] MODE_LABELS = {"Dark", "AMOLED black", "Light"};

    static final String[] NAV = {"left", "right", "top", "bottom"};
    static final String[] NAV_LABELS = {"Rail on the left", "Rail on the right", "Tabs on top", "Tabs at the bottom"};

    static final String[] FONTS = {"sans-serif", "sans-serif-condensed", "serif", "monospace", "sans-serif-light"};
    static final String[] FONT_LABELS = {"Sans", "Condensed", "Serif", "Mono", "Light"};

    static final String[] BGS = {"off", "aurora", "particles", "waves", "grid", "menu"};
    static final String[] BG_LABELS = {"Off (flat)", "Aurora", "Particles", "Waves", "Synthwave grid", "Synced menu background"};

    static final String[] PACKS = {"soft", "glass", "wood", "minimal", "scifi", "bubble", "retro"};
    static final String[] PACK_LABELS = {"Soft (marimba)", "Glass (bells)", "Wood (plucked)", "Minimal (quiet taps)", "Sci-fi", "Bubble", "Retro (8-bit)"};

    static final String[] CURSORS = {"off", "dot", "ring", "crosshair", "arrow", "reticle", "diamond"};
    static final String[] CURSOR_LABELS = {"Off (Quest pointer only)", "Dot", "Ring", "Crosshair", "Arrow", "Reticle", "Diamond"};

    static final String[] CURSOR_COLORS = {"accent", "white", "tertiary", "rainbow"};
    static final String[] CURSOR_COLOR_LABELS = {"Accent", "White", "Tertiary", "Rainbow"};

    static final String[] BOOTS = {"orbit", "glitch", "pulse", "off"};
    static final String[] BOOT_LABELS = {"Orbit", "Glitch", "Pulse", "Off"};

    final JSONObject o;

    AppStyle(JSONObject o) { this.o = o; }

    // ---------------------------------------------------------------- reads (with defaults)
    String theme() { return pick(THEMES, "appTheme", "auto"); }
    String mode() { return pick(MODES, "mode", "dark"); }
    int hue() { return o.optInt("hue", 280); }
    String nav() { return pick(NAV, "nav", "left"); }
    boolean navLabels() { return o.optBoolean("navLabels", true); }
    float uiScale() { return (float) clamp(o.optDouble("uiScale", 1), 0.7, 1.5); }
    float corners() { return (float) clamp(o.optDouble("corners", 1), 0, 2.5); }
    String font() { return pick(FONTS, "font", "sans-serif"); }
    String bg() { return pick(BGS, "appBg", "aurora"); }
    float bgStrength() { return (float) clamp(o.optDouble("bgStrength", 0.55), 0, 1); }
    float bgSpeed() { return (float) clamp(o.optDouble("bgSpeed", 1), 0.1, 3); }
    boolean bgAnimate() { return o.optBoolean("animate", true); }
    boolean motion() { return o.optBoolean("motion", true); }
    float motionSpeed() { return (float) clamp(o.optDouble("motionSpeed", 1), 0.3, 3); }
    boolean sfx() { return o.optBoolean("sfx", true); }
    float sfxVol() { return (float) clamp(o.optDouble("sfxVol", 0.35), 0, 1); }
    String sfxPack() { return pick(PACKS, "sfxPack", "soft"); }
    boolean sfxHover() { return o.optBoolean("sfxHover", false); }
    String cursor() { return pick(CURSORS, "cursor", "ring"); }
    int cursorSize() { return (int) clamp(o.optInt("cursorSize", 20), 8, 48); }
    String cursorColor() { return pick(CURSOR_COLORS, "cursorColor", "accent"); }
    boolean cursorTrail() { return o.optBoolean("cursorTrail", false); }
    boolean hidePointer() { return o.optBoolean("hidePointer", true); }
    String boot() { return pick(BOOTS, "boot", "orbit"); }

    Typeface typeface(boolean medium) {
        String f = font();
        if (medium && f.equals("sans-serif")) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (medium && f.equals("sans-serif-light")) return Typeface.create("sans-serif", Typeface.NORMAL);
        return Typeface.create(f, medium ? Typeface.BOLD : Typeface.NORMAL);
    }

    void put(String k, Object v) { try { o.put(k, v); } catch (Exception ignored) {} }

    String pick(String[] allowed, String key, String def) {
        String v = o.optString(key, def);
        for (String a : allowed) if (a.equals(v)) return v;
        return def;
    }

    static int index(String[] a, String v) { for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i; return 0; }
    static double clamp(double v, double lo, double hi) { return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    // ---------------------------------------------------------------- the color scheme
    /** M3 roles for the chosen theme + mode. auto / meta / uxpatcher behave exactly like before (mode applies to meta) */
    Map<String, Integer> scheme(Context c) {
        String t = theme(), m = mode();
        boolean dark = !m.equals("light");
        if (t.equals("auto") || t.equals("uxpatcher")) {
            boolean ux = MenuLook.hasUxpatcher(c);
            if (ux) return M3.scheme(c, t);          // UXpatcher's colors win, they are the system theme
            t = "meta";
        }
        Map<String, Integer> s;
        if (t.equals("custom")) s = M3.fromSeed(M3.tone(hue(), 70, 55), dark, 5);
        else if (t.equals("mono")) s = mono(dark);
        else s = M3.fromSeed(THEME_SEEDS[index(THEMES, t)], dark, 5);
        if (m.equals("amoled")) amoled(s);
        return s;
    }

    static Map<String, Integer> mono(boolean dark) {
        Map<String, Integer> s = M3.fromSeed(0xFF808080, dark, 0);
        double h = 0;
        if (dark) {
            s.put("primary", M3.tone(h, 0, 92)); s.put("onPrimary", M3.tone(h, 0, 10));
            s.put("primaryContainer", M3.tone(h, 0, 30)); s.put("onPrimaryContainer", M3.tone(h, 0, 92));
            s.put("secondary", M3.tone(h, 0, 78)); s.put("secondaryContainer", M3.tone(h, 0, 26)); s.put("onSecondaryContainer", M3.tone(h, 0, 92));
            s.put("tertiary", M3.tone(h, 0, 70)); s.put("tertiaryContainer", M3.tone(h, 0, 34)); s.put("onTertiaryContainer", M3.tone(h, 0, 92));
        } else {
            s.put("primary", M3.tone(h, 0, 15)); s.put("onPrimary", 0xFFFFFFFF);
            s.put("primaryContainer", M3.tone(h, 0, 85)); s.put("onPrimaryContainer", M3.tone(h, 0, 10));
            s.put("secondary", M3.tone(h, 0, 35)); s.put("secondaryContainer", M3.tone(h, 0, 88)); s.put("onSecondaryContainer", M3.tone(h, 0, 10));
            s.put("tertiary", M3.tone(h, 0, 45)); s.put("tertiaryContainer", M3.tone(h, 0, 82)); s.put("onTertiaryContainer", M3.tone(h, 0, 10));
        }
        return s;
    }

    /** true black surfaces, containers a few tones up so cards still read */
    static void amoled(Map<String, Integer> s) {
        double h = M3.lch(s.get("primary"))[2];
        s.put("surface", 0xFF000000); s.put("surfaceContainerLowest", 0xFF000000);
        s.put("surfaceContainerLow", M3.tone(h, 4, 4)); s.put("surfaceContainer", M3.tone(h, 4, 7));
        s.put("surfaceContainerHigh", M3.tone(h, 4, 11)); s.put("surfaceContainerHighest", M3.tone(h, 4, 16));
    }

    /** swatch color for a theme choice in the picker (0 = no fixed color) */
    static int swatch(Context c, String theme, int hue) {
        switch (theme) {
            case "custom": return M3.tone(hue, 70, 65);
            case "auto": case "uxpatcher":
                return MenuLook.hasUxpatcher(c) ? 0xFF000000 | MenuLook.uxpInt(c, "accent_color", 0xB07CFF) : M3.META_BLUE;
            default: return THEME_SEEDS[index(THEMES, theme)];
        }
    }
}
