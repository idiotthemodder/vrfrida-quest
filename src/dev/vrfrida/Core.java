package dev.vrfrida;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** the vrfrida.py pipeline, run on the headset: bridge -> plugins/sounds/patterns -> agent -> launch -> wait -> frida-inject */
final class Core {
    // only for plugins that have no "// game:" line yet (same table as vrfrida.py)
    static final Map<String, List<String>> LEGACY_MAP = new HashMap<>();
    static {
        LEGACY_MAP.put("com.dogelabs.deadeye", Arrays.asList("animal_rivals.js", "pizza_mods.js", "deadeye_names.js", "deadeye_cosmetics.js", "deadeye_join.js"));
        LEGACY_MAP.put("com.AnotherAxiom.GorillaTag", Collections.singletonList("gorilla_tag.js"));
    }
    static final String[] DATA_FILES = {"pfp_links.txt", "rift_menu_logo.png"};
    static final Pattern SYSTEM = Pattern.compile("^(android$|com\\.android\\.|com\\.oculus\\.|com\\.meta\\.|com\\.facebook\\.|com\\.google\\.|horizonos|systemux|com\\.qualcomm)");
    static final Pattern GAME_LINE = Pattern.compile("^[ \\t/*#@]*games?\\s*[:=]\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    final Context ctx;
    final Bridge.Log log;

    Core(Context ctx, Bridge.Log log) { this.ctx = ctx; this.log = log; }

    String injector() { return ctx.getApplicationInfo().nativeLibraryDir + "/libfrida-inject.so"; }

    // ---- first run: unpack the plugins / patterns / sounds that were bundled into the apk ----
    void seedIfEmpty() {
        for (String[] pair : new String[][]{{"seed/plugins", "plugins"}, {"seed/patterns", "patterns"}, {"seed/sounds", "sounds"}}) {
            File dst = new File(Paths.ROOT, pair[1]);
            String[] have = dst.list();
            if (have != null && have.length > 0) continue;
            try {
                String[] names = ctx.getAssets().list(pair[0]);
                if (names == null || names.length == 0) continue;
                for (String n : names) Files.copyAsset(ctx, pair[0] + "/" + n, new File(dst, n));
                log.log("unpacked " + names.length + " bundled files into " + dst);
            } catch (Exception e) {
                log.log("couldn't unpack " + pair[0] + ": " + e);
            }
        }
        for (File d : new File[]{Paths.BRIDGES, Paths.AGENTS, Paths.LOGS, Paths.RUN, Paths.SOUND_CACHE}) d.mkdirs();
    }

    // ---- plugins (vrfrida.py plugins_for) ----
    static List<String> pluginGames(File f) {
        Matcher m = GAME_LINE.matcher(Files.head(f, 40));
        List<String> out = new ArrayList<>();
        if (!m.find()) return out;
        String raw = m.group(1).replaceAll("\\*/\\s*$", "");
        for (String g : raw.split("[,\\s;]+")) if (!g.isEmpty()) out.add(g);
        return out;
    }

    static List<File> pluginsFor(String pkg, Bridge.Log log) { return pluginsFor(pkg, log, null); }

    /** plugins/ (PC sync) + store/plugins/ (Store tab), sorted by name. A local file wins over a store file with the same name */
    static List<File> allPlugins() {
        java.util.TreeMap<String, File> m = new java.util.TreeMap<>();
        File[] st = Paths.STORE_PLUGINS.listFiles();
        if (st != null) for (File f : st) if (f.isFile() && f.getName().toLowerCase().endsWith(".js")) m.put(f.getName(), f);
        File[] fs = Paths.PLUGINS.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().toLowerCase().endsWith(".js")) m.put(f.getName(), f);
        return new ArrayList<>(m.values());
    }

    /** plugins whose game line matches pkg (or "*"), minus the ones turned off for this game in the Plugins tab */
    static List<File> pluginsFor(String pkg, Bridge.Log log, java.util.Set<String> disabled) {
        List<File> match = new ArrayList<>();
        for (File f : allPlugins()) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".js")) continue;
            List<String> games = pluginGames(f);
            if (!games.isEmpty()) {
                boolean ok = false;
                for (String g : games) if (g.equals("*") || g.equalsIgnoreCase(pkg)) ok = true;
                if (ok && disabled != null && disabled.contains(f.getName())) { if (log != null) log.log("  off    " + f.getName() + "  (turned off in the Plugins tab)"); }
                else if (ok) match.add(f);
                else if (log != null) log.log("  skip   " + f.getName() + "  (for " + String.join(", ", games) + ")");
            } else {
                List<String> legacy = LEGACY_MAP.get(pkg);
                boolean ok = (legacy != null && legacy.contains(f.getName())) || Files.head(f, 40).toLowerCase().contains(pkg.toLowerCase());
                if (ok && disabled != null && disabled.contains(f.getName())) { if (log != null) log.log("  off    " + f.getName() + "  (turned off in the Plugins tab)"); }
                else if (ok) match.add(f);
                else if (log != null) log.log("  skip   " + f.getName() + "  (no '// game:' line)");
            }
        }
        return match;
    }

    /**
     * shell lines that copy `files` into `dst` (only the ones that changed),
     * and with prune: delete files with those extensions in dst that aren't in the list
     */
    static void copyLines(StringBuilder sh, List<File> files, String dst, String[] prune) {
        sh.append("mkdir -p ").append(Root.q(dst)).append('\n');
        for (File f : files) {
            String to = dst + "/" + f.getName();
            sh.append("cmp -s ").append(Root.q(f.getPath())).append(' ').append(Root.q(to))
              .append(" || { cp -f ").append(Root.q(f.getPath())).append(' ').append(Root.q(to))
              .append(" && echo '  pushed ").append(f.getName().replace("'", "")).append("'; }\n");
        }
        if (prune != null) {
            StringBuilder keep = new StringBuilder();
            for (File f : files) keep.append('|').append(f.getName().replace("|", ""));
            sh.append("keep=").append(Root.q(keep.append('|').toString())).append('\n');
            sh.append("for f in ").append(Root.q(dst)).append("/*; do n=${f##*/}; case \"$n\" in ");
            for (int i = 0; i < prune.length; i++) sh.append(i == 0 ? "" : "|").append("*").append(prune[i]);
            sh.append(") case \"$keep\" in *\"|$n|\"*) ;; *) rm -f \"$f\" && echo \"  removed old $n\";; esac;; esac; done\n");
        }
    }

    /** push plugins, sounds, patterns into the game's files/imgui_plugins (one root shell for all of it) */
    void syncGameFiles(String pkg, boolean sounds, boolean patterns, java.util.Set<String> disabled) {
        List<File> plugins = pluginsFor(pkg, log, disabled);
        if (plugins.isEmpty()) log.log("no plugins for " + pkg + " in " + Paths.PLUGINS + " (the Mods tab still works)");
        List<File> files = new ArrayList<>(plugins);
        for (String d : DATA_FILES) { File f = new File(Paths.PLUGINS, d); if (f.isFile()) files.add(f); }
        String dst = Paths.gameFiles(pkg);
        StringBuilder sh = new StringBuilder();
        copyLines(sh, files, dst, null);
        // plugins turned off for this game: take out copies pushed earlier, or the menu would still load them
        if (disabled != null) for (String n : disabled) sh.append("rm -f ").append(Root.q(dst + "/" + n)).append('\n');

        boolean wantsSounds = false;
        for (File p : plugins) if (Files.head(p, 400).contains("__RASP_SOUNDBOARD")) wantsSounds = true;
        int nSounds = -1;
        if (sounds && wantsSounds) {
            List<File> wavs = Sounds.prepare(pkg, log);
            nSounds = wavs.size();
            copyLines(sh, wavs, dst + "/sounds", new String[]{".wav"});
        }
        int nPatterns = -1;
        if (patterns) {
            List<File> imgs = new ArrayList<>();
            File[] ps = Paths.PATTERNS.listFiles();
            if (ps != null) for (File f : ps) {
                String l = f.getName().toLowerCase();
                if (f.isFile() && (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg"))) imgs.add(f);
            }
            if (!imgs.isEmpty()) { nPatterns = imgs.size(); copyLines(sh, imgs, dst + "/patterns", new String[]{".png", ".jpg", ".jpeg"}); }
        }
        Root.Result r = Root.sh(sh.toString());
        if (!r.out.isEmpty()) log.log(r.out);
        log.log("plugins for " + pkg + ": " + plugins.size() + " matched -> " + dst);
        if (nSounds >= 0) log.log("sounds: " + nSounds + " on the headset (press Reload Sounds in the soundboard if the menu is already open)");
        if (nPatterns >= 0) log.log("patterns: " + nPatterns);
    }

    /** bridge + menu in one file for frida-inject (same thing frida's repl does with two -l scripts) */
    File buildAgent(String pkg, File bridge) throws Exception {
        String menu;
        String from;
        if (Paths.MENU.isFile()) { menu = new String(Files.read(Paths.MENU), StandardCharsets.UTF_8); from = Paths.MENU.getPath(); }
        else { menu = Files.readAsset(ctx, "menu.js"); from = "the copy bundled in the app"; }
        log.log("menu: " + from);
        String b = new String(Files.read(bridge), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length() + menu.length() + 1024);
        sb.append("// built by the vrfrida quest app for ").append(pkg).append('\n');
        sb.append("Script.evaluate(\"/frida-il2cpp-bridge.js\", "); Files.jsonQuote(b, sb); sb.append(");\n");
        sb.append("Script.evaluate(\"/UniversalMenu.js\", "); Files.jsonQuote(menu, sb); sb.append(");\n");
        File out = new File(Paths.AGENTS, pkg + ".js");
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** everything that has to happen before the game starts. returns the agent file */
    File prepare(ApplicationInfo ai, Settings s, boolean forceBridge) throws Exception {
        String pkg = ai.packageName;
        if (SYSTEM.matcher(pkg).find()) throw new Exception(pkg + " looks like a system / Meta package, refusing to inject it");
        File bridge = Bridge.ensure(ctx, ai, forceBridge, log);
        syncGameFiles(pkg, s.sounds, s.patterns, s.disabledPlugins);
        if (s.followGlobal && applyLook(pkg, Settings.globalLook(ctx))) log.log("synced menu look applied");
        return buildAgent(pkg, bridge);
    }

    // ---- the synced look: merge the global "menu" keys into a game's settings.json (everything else is kept) ----
    /** true if the file was written; false when there is nothing to apply or no root */
    static boolean applyLook(String pkg, String globalJson) {
        org.json.JSONObject gm;
        try { gm = new org.json.JSONObject(globalJson).optJSONObject("menu"); } catch (Exception e) { return false; }
        if (gm == null || gm.length() == 0) return false;
        org.json.JSONObject root;
        try { String t = readSettings(pkg); root = t.trim().isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(t); }
        catch (Exception e) { root = new org.json.JSONObject(); }
        try {
            if (!root.has("v")) root.put("v", 2);
            org.json.JSONObject m = root.optJSONObject("menu");
            if (m == null) { m = new org.json.JSONObject(); root.put("menu", m); }
            boolean changed = false;
            for (java.util.Iterator<String> it = gm.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = gm.get(k);
                if (!v.equals(m.opt(k))) { m.put(k, v); changed = true; }
            }
            if (!changed) return true;
        } catch (Exception e) { return false; }
        return writeSettings(pkg, root.toString());
    }

    // ---- what's running: one root shell for every game (was one pidof per installed package) ----
    static final class Status { final java.util.Set<String> running = new java.util.HashSet<>(), injected = new java.util.HashSet<>(); }

    static Status status() {
        Root.Result r = Root.sh("ps -A -o NAME= 2>/dev/null | sed 's/^/R /'; " +
                "for f in " + Root.q(Paths.RUN.getPath()) + "/*.pid; do [ -f \"$f\" ] || continue; p=$(cat \"$f\"); kill -0 $p 2>/dev/null && echo \"I ${f##*/}\"; done");
        Status s = new Status();
        for (String l : r.out.split("\n")) {
            l = l.trim();
            if (l.startsWith("R ")) s.running.add(l.substring(2).trim().replaceAll(":.*$", ""));
            else if (l.startsWith("I ") && l.endsWith(".pid")) s.injected.add(l.substring(2, l.length() - 4));
        }
        return s;
    }

    String pidOf(String pkg) {
        Root.Result r = Root.sh("pidof " + Root.q(pkg));
        String[] p = r.out.trim().split("\\s+");
        return r.ok() && p.length > 0 ? p[0] : "";
    }

    /** pid of the frida-inject runner for this game, "" if none */
    String injectorPid(String pkg) {
        Root.Result r = Root.sh("p=$(cat " + Root.q(Paths.pidFile(pkg).getPath()) + " 2>/dev/null); [ -n \"$p\" ] && kill -0 $p 2>/dev/null && echo $p");
        return r.out.trim();
    }

    /**
     * starts a detached root shell that waits for the game, sleeps `delay` seconds, then runs frida-inject.
     * it outlives this app (Quest pauses 2D apps when a VR game takes over), and logs to /sdcard/vrfrida/logs/<pkg>.log
     */
    void startInjector(String pkg, File agent, int delay, boolean v8) {
        stopInjector(pkg, false);
        String logPath = Paths.log(pkg).getPath();
        String script =
                "LOG=" + Root.q(logPath) + "\n" +
                "echo $$ > " + Root.q(Paths.pidFile(pkg).getPath()) + "\n" +
                "echo \"[vrfrida] $(date '+%H:%M:%S') waiting for " + pkg + "\" > \"$LOG\"\n" +
                "i=0; while [ -z \"$(pidof " + pkg + ")\" ] && [ $i -lt 120 ]; do sleep 1; i=$((i+1)); done\n" +
                "pid=$(pidof " + pkg + " | cut -d' ' -f1)\n" +
                "if [ -z \"$pid\" ]; then echo '[vrfrida] the game never started' >> \"$LOG\"; exit 1; fi\n" +
                "echo \"[vrfrida] game pid $pid, injecting in " + delay + "s\" >> \"$LOG\"\n" +
                "sleep " + delay + "\n" +
                "if ! kill -0 $pid 2>/dev/null; then echo '[vrfrida] the game closed before the delay ran out' >> \"$LOG\"; exit 1; fi\n" +
                "echo \"[vrfrida] $(date '+%H:%M:%S') frida-inject -p $pid -R " + (v8 ? "v8" : "qjs") + "\" >> \"$LOG\"\n" +
                Root.q(injector()) + " -p $pid -s " + Root.q(agent.getPath()) + " -R " + (v8 ? "v8" : "qjs") + " < /dev/null >> \"$LOG\" 2>&1\n" +
                "echo \"[vrfrida] $(date '+%H:%M:%S') frida-inject exited ($?), the menu is unloaded\" >> \"$LOG\"\n" +
                "rm -f " + Root.q(Paths.pidFile(pkg).getPath()) + "\n";
        File f = new File(Paths.RUN, pkg + ".sh");
        try { Files.write(f, script.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { log.log("couldn't write " + f + ": " + e); return; }
        Root.Result r = Root.sh("chmod 755 " + Root.q(injector()) + " 2>/dev/null; setsid sh " + Root.q(f.getPath()) + " < /dev/null > /dev/null 2>&1 &");
        if (!r.out.isEmpty()) log.log(r.out);
        log.log("injector started (" + delay + "s after the game opens)");
    }

    /** killing frida-inject unloads the script, so this also takes the menu out of the game */
    void stopInjector(String pkg, boolean say) {
        String pf = Root.q(Paths.pidFile(pkg).getPath());
        Root.Result r = Root.sh(
                "p=$(cat " + pf + " 2>/dev/null)\n" +
                "if [ -n \"$p\" ] && kill -0 $p 2>/dev/null; then pkill -P $p; kill $p; rm -f " + pf + "; echo stopped; fi");
        if (say) log.log(r.out.contains("stopped") ? "stopped the injector, the menu is unloaded" : "no injector was running for " + pkg);
    }

    // ---- appsafety root-detection spoof (same hooks as ~/thing.sh) --------------------------------------
    // Injected into com.oculus.appsafety (NOT the game), eternalized so it stays after frida-inject detaches,
    // so games that check device integrity at startup still authenticate on a rooted headset.
    static final String APPSAFETY = "com.oculus.appsafety";
    static File spoofScript() { return new File(Paths.ROOT, "appsafety_spoof.js"); }
    static File spoofPidFile() { return new File(Paths.RUN, "appsafety.pid"); }
    static File spoofLog() { return new File(Paths.LOGS, "appsafety.log"); }

    /** (re)write the spoof script from the copy bundled in the app */
    void writeSpoofScript() {
        try { Files.write(spoofScript(), Files.readAsset(ctx, "appsafety_spoof.js").getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { log.log("couldn't write the appsafety spoof script: " + e); }
    }

    boolean spoofRunning() {
        Root.Result r = Root.sh("p=$(cat " + Root.q(spoofPidFile().getPath()) + " 2>/dev/null); [ -n \"$p\" ] && kill -0 $p 2>/dev/null && echo yes");
        return r.out.trim().equals("yes");
    }

    /**
     * Start a detached root watcher that injects the eternalized spoof into appsafety whenever it (re)spawns.
     * It outlives the app and survives appsafety restarts, so auth keeps working across game launches.
     */
    void startSpoof(boolean say) {
        writeSpoofScript();
        stopSpoof(false);
        String logPath = spoofLog().getPath();
        String script =
                "LOG=" + Root.q(logPath) + "\n" +
                "echo $$ > " + Root.q(spoofPidFile().getPath()) + "\n" +
                "echo \"[appsafety] $(date '+%H:%M:%S') watcher started\" > \"$LOG\"\n" +
                "last=\"\"\n" +
                "while kill -0 $$ 2>/dev/null; do\n" +
                "  pid=$(pidof " + APPSAFETY + " 2>/dev/null | tr ' ' '\\n' | head -1)\n" +
                "  if [ -n \"$pid\" ] && [ \"$pid\" != \"$last\" ]; then\n" +
                "    echo \"[appsafety] $(date '+%H:%M:%S') injecting into pid $pid\" >> \"$LOG\"\n" +
                "    " + Root.q(injector()) + " -p $pid -s " + Root.q(spoofScript().getPath()) + " -R v8 -e < /dev/null >> \"$LOG\" 2>&1\n" +
                "    echo \"[appsafety] $(date '+%H:%M:%S') frida-inject exited ($?)\" >> \"$LOG\"\n" +
                "    last=\"$pid\"\n" +
                "  fi\n" +
                "  [ -z \"$pid\" ] && last=\"\"\n" +   // appsafety died: re-inject when it comes back
                "  sleep 2\n" +
                "done\n";
        File f = new File(Paths.RUN, "appsafety.sh");
        try { Files.write(f, script.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { log.log("couldn't write " + f + ": " + e); return; }
        Root.sh("chmod 755 " + Root.q(injector()) + " 2>/dev/null; setsid sh " + Root.q(f.getPath()) + " < /dev/null > /dev/null 2>&1 &");
        if (say) log.log("appsafety spoof watcher started (injects when appsafety runs; open a game or the Store to trigger it)");
    }

    void stopSpoof(boolean say) {
        String pf = Root.q(spoofPidFile().getPath());
        Root.Result r = Root.sh(
                "p=$(cat " + pf + " 2>/dev/null)\n" +
                "if [ -n \"$p\" ] && kill -0 $p 2>/dev/null; then kill $p; rm -f " + pf + "; echo stopped; fi");
        if (say) log.log(r.out.contains("stopped") ? "appsafety spoof watcher stopped (already-spoofed appsafety stays spoofed until it restarts)" : "no appsafety spoof watcher was running");
    }

    // ---- the menu's settings.json in the game's files folder (root: Android/data is off limits otherwise) ----
    static String settingsPath(String pkg) { return Paths.gameFiles(pkg) + "/settings.json"; }

    /** file text, or "" if the game has no settings yet */
    static String readSettings(String pkg) {
        Root.Result r = Root.sh("cat " + Root.q(settingsPath(pkg)) + " 2>/dev/null");
        return r.ok() ? r.out : "";
    }

    static boolean writeSettings(String pkg, String text) {
        File tmp = new File(Paths.RUN, pkg + ".settings.json");
        try { Files.write(tmp, text.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { return false; }
        String dst = settingsPath(pkg);
        Root.Result r = Root.sh("mkdir -p " + Root.q(Paths.gameFiles(pkg)) + " && cp -f " + Root.q(tmp.getPath()) + " " + Root.q(dst) + " && echo ok");
        tmp.delete();
        return r.out.contains("ok");
    }

    void launch(String pkg) throws Exception {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) throw new Exception(pkg + " has no launcher activity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
