package dev.vrfrida;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A game's imgui_plugins/settings.json (UniversalMenu CFG_VERSION 2). Only "menu" + autoSave/remember are edited;
 * everything else in the file (mods, plugins, hud, notif, modKeys) is kept as it was.
 * The same class holds the app's own look (theme + background) in SharedPreferences.
 */
final class MenuCfg {
    enum Kind { BOOL, INT, FLOAT, CHOICE, THEME, PATFILE, WRIST }

    static final class Field {
        final String section, key, label, desc; final Kind kind; final double min, max; final int decimals; final boolean top;
        String[] options;
        Field opts(String... o) { options = o; return this; }
        Field(String section, String key, String label, Kind kind, double min, double max, int decimals, String desc, boolean top) {
            this.section = section; this.key = key; this.label = label; this.kind = kind; this.min = min; this.max = max;
            this.decimals = decimals; this.desc = desc; this.top = top;
        }
    }

    static List<Field> fields(MenuLook look) {
        List<Field> f = new ArrayList<>();
        String W = "Window", B = "Background", P = "Pointer & HUD", S = "Sounds", V = "Saving";
        f.add(new Field(W, "theme", "Theme", Kind.THEME, 0, 0, 0, null, false));
        f.add(new Field(W, "size", "Menu Size %", Kind.INT, 40, 300, 0, null, false));
        f.add(new Field(W, "width", "Menu Width", Kind.INT, 600, 1400, 0, null, false));
        f.add(new Field(W, "wrist", "Menu Position", Kind.WRIST, 0, 0, 0, null, false).opts("Wrist (hold X)", "Floating"));
        f.add(new Field(W, "tabH", "Tab Height", Kind.CHOICE, 0, 0, 0, null, false).opts("Fixed (scrolls)", "Match Settings", "Fit Content"));
        f.add(new Field(W, "pageH", "Page Height", Kind.INT, 120, 900, 0, null, false));
        f.add(new Field(W, "onTop", "Always On Top", Kind.BOOL, 0, 0, 0, "hands never hide the menu", false));
        f.add(new Field(W, "stab", "Stabilize", Kind.BOOL, 0, 0, 0, "no wrist / pointer shake", false));
        f.add(new Field(W, "opacity", "Menu Opacity", Kind.FLOAT, 0.2, 1, 2, null, false));
        f.add(new Field(W, "rounding", "Rounding", Kind.INT, 0, 14, 0, null, false));
        f.add(new Field(W, "layout", "Layout", Kind.CHOICE, 0, 0, 0, null, false).opts("Rows", "Compact"));
        f.add(new Field(W, "verticalTabs", "Vertical Tabs", Kind.BOOL, 0, 0, 0, "tab buttons in a side rail", false));
        f.add(new Field(B, "pattern", "Background", Kind.BOOL, 0, 0, 0, "animated picture behind the menu", false));
        f.add(new Field(B, "bg", "Background Style", Kind.CHOICE, 0, 0, 0, null, false).opts(look.bgNames));
        f.add(new Field(B, "spin", "Spiral Spin", Kind.INT, -120, 120, 0, null, false));
        f.add(new Field(B, "bgStrength", "Background Strength", Kind.FLOAT, 0.15, 1, 2, null, false));
        f.add(new Field(B, "bgSpeed", "Animation Speed", Kind.FLOAT, 0.2, 3, 2, null, false));
        f.add(new Field(B, "patFile", "Pattern Image", Kind.PATFILE, 0, 0, 0, null, false));
        f.add(new Field(B, "patScale", "Pattern Size", Kind.FLOAT, 0.1, 1.2, 2, null, false));
        f.add(new Field(B, "patSX", "Pattern Scroll X", Kind.FLOAT, -0.1, 0.1, 3, null, false));
        f.add(new Field(B, "patSY", "Pattern Scroll Y", Kind.FLOAT, -0.1, 0.1, 3, null, false));
        f.add(new Field(B, "patColor", "Pattern Color", Kind.CHOICE, 0, 0, 0, null, false).opts(look.patColorNames));
        f.add(new Field(B, "bgFollow", "Follow Cursor", Kind.BOOL, 0, 0, 0, "reacts to the pointer", false));
        f.add(new Field(P, "hud", "HUD", Kind.BOOL, 0, 0, 0, "app, FPS, battery, timer", false));
        f.add(new Field(P, "laser", "Laser", Kind.BOOL, 0, 0, 0, "pointer beam from your right hand", false));
        f.add(new Field(P, "rayPitch", "Laser Angle", Kind.INT, -60, 60, 0, null, false));
        f.add(new Field(P, "laserW", "Laser Width", Kind.INT, 1, 15, 0, null, false));
        f.add(new Field(P, "laserLen", "Laser Length (off menu)", Kind.FLOAT, 0.3, 5, 1, null, false));
        f.add(new Field(P, "cursorShape", "Pointer Shape", Kind.CHOICE, 0, 0, 0, null, false).opts(look.cursorShapes));
        f.add(new Field(P, "cursorSize", "Pointer Size", Kind.INT, 6, 30, 0, null, false));
        f.add(new Field(P, "cursorCol", "Pointer Color", Kind.CHOICE, 0, 0, 0, null, false).opts(look.cursorColors));
        f.add(new Field(P, "hold", "Open With", Kind.CHOICE, 0, 0, 0, null, false).opts("Hold X", "Toggle X"));
        f.add(new Field(P, "pauseMove", "Pause Movement", Kind.BOOL, 0, 0, 0, "while the menu is open", false));
        f.add(new Field(S, "click", "Click Sounds", Kind.BOOL, 0, 0, 0, "click + open / close tones", false));
        f.add(new Field(S, "clickStyle", "Click Style", Kind.CHOICE, 0, 0, 0, null, false).opts(look.clickStyles));
        f.add(new Field(S, "clickVol", "Click Volume", Kind.FLOAT, 0.05, 1, 2, null, false));
        f.add(new Field(V, "autoSave", "Auto-save settings", Kind.BOOL, 0, 0, 0, "the menu writes changes itself", true));
        f.add(new Field(V, "remember", "Remember mod toggles", Kind.BOOL, 0, 0, 0, "mods that were on come back on", true));
        return f;
    }

    /** the background-related subset, for the app's own look */
    static boolean isLookField(Field f) {
        return f.section.equals("Background") || f.key.equals("opacity") || f.key.equals("rounding");
    }

    final JSONObject root;
    final MenuLook look;
    final List<Field> all;

    MenuCfg(MenuLook look, JSONObject root) {
        this.look = look;
        this.all = fields(look);
        this.root = root != null ? root : new JSONObject();
        try {
            if (!this.root.has("v")) this.root.put("v", 2);
            if (!this.root.has("autoSave")) this.root.put("autoSave", true);
            if (!this.root.has("remember")) this.root.put("remember", true);
            if (this.root.optJSONObject("menu") == null) this.root.put("menu", new JSONObject());
        } catch (Exception ignored) {}
    }

    static MenuCfg parse(MenuLook look, String text) {
        try { return new MenuCfg(look, new JSONObject(text)); } catch (Exception e) { return new MenuCfg(look, null); }
    }

    JSONObject menu() { return root.optJSONObject("menu"); }
    JSONObject obj(Field f) { return f.top ? root : menu(); }

    Object def(String key) {
        JSONObject d = look.defaults;
        switch (key) {
            case "theme": return d.optString("THEME", "midnight");
            case "size": return 100;
            case "width": return d.optInt("WINDOW_WIDTH", 960);
            case "wrist": return d.optBoolean("WRIST_MENU", true);
            case "tabH": return d.optInt("PAGE_HEIGHT", 470) > 0 ? 0 : 1;
            case "pageH": return d.optInt("PAGE_HEIGHT", 470) > 0 ? d.optInt("PAGE_HEIGHT", 470) : 470;
            case "onTop": return d.optBoolean("ALWAYS_ON_TOP", true);
            case "stab": return d.optBoolean("STABILIZE", true);
            case "opacity": return 1.0;
            case "rounding": return d.optInt("ROUNDING", 7);
            case "layout": return d.optBoolean("ROW_LAYOUT", true) ? 0 : 1;
            case "verticalTabs": return false;
            case "pattern": return d.optBoolean("WINDOW_PATTERN", true);
            case "bg": return 0;
            case "spin": return d.optInt("SPIRAL_SPIN", 30);
            case "bgStrength": return 0.6;
            case "bgSpeed": return 1.0;
            case "patFile": return "";
            case "patScale": return 0.35;
            case "patSX": return 0.02;
            case "patSY": return 0.012;
            case "patColor": return 0;
            case "bgFollow": return false;
            case "hud": return d.optBoolean("HUD_ENABLED", true);
            case "laser": return d.optBoolean("SHOW_LASER", true);
            case "rayPitch": return d.optInt("RAY_PITCH_DEG", 30);
            case "laserW": return 3;
            case "laserLen": return 1.5;
            case "cursorShape": case "cursorCol": case "clickStyle": return 0;
            case "cursorSize": return 10;
            case "hold": return d.optBoolean("MENU_HOLD_X", false) ? 0 : 1;
            case "pauseMove": case "click": case "autoSave": case "remember": return true;
            case "clickVol": return 0.6;
        }
        return null;
    }

    boolean bool(Field f) { return obj(f).optBoolean(f.key, (Boolean) def(f.key)); }
    double num(Field f) {
        Object d = def(f.key);
        double v = obj(f).optDouble(f.key, d instanceof Number ? ((Number) d).doubleValue() : 0);
        if (f.key.equals("bg") && obj(f).has("bg") && obj(f).optInt("bgv", 0) != 2) {
            // a settings.json from before the weather backgrounds: 0-2 kept, Character Pattern moved, the removed ones show as Sky
            int o = (int) v;
            v = o <= 2 ? o : o == 11 ? look.bgNames.length - 1 : 1;
        }
        return v;
    }
    String str(Field f) { return obj(f).optString(f.key, String.valueOf(def(f.key))); }

    void set(Field f, Object v) {
        try {
            obj(f).put(f.key, v);
            if (f.key.equals("bg")) obj(f).put("bgv", 2);   // index into the new background list (the menu migrates files without it)
        } catch (Exception ignored) {}
    }

    // quick getters for drawing
    Field field(String key) { for (Field f : all) if (f.key.equals(key)) return f; return null; }
    double num(String key) { return num(field(key)); }
    boolean bool(String key) { return bool(field(key)); }
    String str(String key) { return str(field(key)); }
}
