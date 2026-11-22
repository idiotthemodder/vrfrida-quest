package dev.vrfrida;

import java.io.File;

/** everything the app reads lives in /sdcard/vrfrida (vrfrida.py --sync fills it from the pc, or drop files in by hand) */
final class Paths {
    static final File ROOT = new File("/sdcard/vrfrida");
    static final File MENU = new File(ROOT, "menu.js");                 // compiled UniversalMenu.ts
    static final File PLUGINS = new File(ROOT, "plugins");
    static final File SOUNDS = new File(ROOT, "sounds");
    static final File SOUND_CACHE = new File(ROOT, ".sound_cache");
    static final File PATTERNS = new File(ROOT, "patterns");
    static final File BRIDGES = new File(ROOT, "bridges");
    static final File AGENTS = new File(ROOT, "agents");
    static final File LOGS = new File(ROOT, "logs");
    static final File RUN = new File(ROOT, "run");
    static final File LIBS = new File(ROOT, "libs");                    // libs/<pkg>/*.so: native libs loaded into the game on every start (root)
    static final File STORE = new File(ROOT, "store");                  // plugin store: installed.json, scan_rules.json
    static final File STORE_PLUGINS = new File(STORE, "plugins");       // store installs (vrfrida.py --sync doesn't touch this)

    static String gameFiles(String pkg) { return "/sdcard/Android/data/" + pkg + "/files/imgui_plugins"; }
    static File log(String pkg) { return new File(LOGS, pkg + ".log"); }
    static File pidFile(String pkg) { return new File(RUN, pkg + ".pid"); }
}
