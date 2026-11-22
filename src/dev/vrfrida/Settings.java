package dev.vrfrida;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** per-game launch settings (delay, synced look, disabled plugins); v8 / sounds / patterns / auth spoof are global */
final class Settings {
    static final int DEFAULT_DELAY = 15;

    int delay = DEFAULT_DELAY;
    boolean v8 = true, sounds = true, patterns = true, authSpoof = true;
    boolean followGlobal = true;                 // this game gets the synced (all games) menu look on every launch
    Set<String> disabledPlugins = new HashSet<>();

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("vrfrida", Context.MODE_PRIVATE); }

    static Settings load(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
        s.delay = p.getInt("delay:" + pkg, p.getInt("delay", DEFAULT_DELAY));
        s.v8 = p.getBoolean("v8", true);
        s.sounds = p.getBoolean("sounds", true);
        s.patterns = p.getBoolean("patterns", true);
        s.authSpoof = p.getBoolean("authSpoof", true);
        s.followGlobal = p.getBoolean("followGlobal:" + pkg, true);
        s.disabledPlugins = new HashSet<>(p.getStringSet("plugoff:" + pkg, new HashSet<>()));
        return s;
    }

    void save(Context c, String pkg) {
        prefs(c).edit().putInt("delay:" + pkg, delay).putInt("delay", delay)
                .putBoolean("v8", v8).putBoolean("sounds", sounds).putBoolean("patterns", patterns).putBoolean("authSpoof", authSpoof)
                .putBoolean("followGlobal:" + pkg, followGlobal)
                .putStringSet("plugoff:" + pkg, new HashSet<>(disabledPlugins)).apply();
    }

    // ---- favorites (pinned to the top of the games list) ----
    static Set<String> favorites(Context c) { return new HashSet<>(prefs(c).getStringSet("favs", new HashSet<>())); }

    static void setFavorite(Context c, String pkg, boolean on) {
        Set<String> f = favorites(c);
        if (on) f.add(pkg); else f.remove(pkg);
        prefs(c).edit().putStringSet("favs", f).apply();
    }

    // ---- the synced menu look: a settings.json "menu" block applied to every game that follows it ----
    static String globalLook(Context c) { return prefs(c).getString("globalLook", "{}"); }
    static void saveGlobalLook(Context c, String json) { prefs(c).edit().putString("globalLook", json).apply(); }
}
