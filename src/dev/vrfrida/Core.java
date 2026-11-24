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
        for (File d : new File[]{Paths.BRIDGES, Paths.AGENTS, Paths.LOGS, Paths.RUN, Paths.SOUND_CACHE, Paths.LIBS}) d.mkdirs();
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
        if (micGames.contains(pkg)) wantsSounds = true;   // the menu's built-in mic soundboard, turned on for this game
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

    /** games with the built-in mic soundboard turned on (Games tab): they get the sounds even with no soundboard plugin */
    static volatile java.util.Set<String> micGames = new java.util.HashSet<>();

    static void loadMicGames(android.content.Context c) { micGames = new java.util.HashSet<>(Settings.prefs(c).getStringSet("micGames", new java.util.HashSet<>())); }

    static void setMicGame(android.content.Context c, String pkg, boolean on) {
        java.util.Set<String> s = new java.util.HashSet<>(micGames);
        if (on) s.add(pkg); else s.remove(pkg);
        micGames = s;
        Settings.prefs(c).edit().putStringSet("micGames", s).apply();
    }

    /** some plugin for this game reads __RASP_SOUNDBOARD (same test syncGameFiles uses), or its mic soundboard is on */
    static boolean wantsSounds(String pkg) {
        if (micGames.contains(pkg)) return true;
        for (File p : pluginsFor(pkg, null, null)) if (Files.head(p, 400).contains("__RASP_SOUNDBOARD")) return true;
        return false;
    }

    static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        f.delete();
    }

    static File obbSounds(String pkg) { return new File("/storage/emulated/0/Android/obb/" + pkg + "/vrfrida_sounds"); }

    /** copy wavs into dir with plain file access, drop .wav files that aren't in the list. throws if dir isn't writable */
    static int copyInto(List<File> wavs, File dir) throws Exception {
        File parent = dir.getParentFile();
        boolean madeParent = parent != null && !parent.exists();
        dir.mkdirs();
        if (!dir.isDirectory()) throw new Exception("can't create " + dir);
        // written by vrfrida's uid, read by the game's: open the folder (and the game folder if we made it) to everyone
        for (File d : madeParent ? new File[]{parent, dir} : new File[]{dir}) { d.setReadable(true, false); d.setExecutable(true, false); }
        java.util.Set<String> keep = new java.util.HashSet<>();
        int changed = 0;
        for (File w : wavs) {
            keep.add(w.getName());
            File to = new File(dir, w.getName());
            if (to.isFile() && to.length() == w.length() && to.lastModified() >= w.lastModified()) continue;
            Files.write(to, Files.read(w));
            to.setReadable(true, false);
            changed++;
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.getName().toLowerCase().endsWith(".wav") && !keep.contains(f.getName())) f.delete();
        return changed;
    }

    /**
     * convert the sound folder and put it where every listed game's menu reads it, one game at a time (Sounds.prepare
     * prunes the shared cache per game).
     *   no root: Android/obb/<game>/vrfrida_sounds. Installer apps (Install unknown apps allowed) may write a game's
     *            obb folder, and the game can always read its own, patched (Frida Gadget) or not
     *   root:    also files/imgui_plugins/sounds, for menus that only look there (older menu builds)
     */
    void pushSoundsToAll(List<String> pkgs, boolean root) {
        int done = 0, failed = 0;
        String why = null;
        int cleaned = 0;
        List<String> without = new ArrayList<>();
        for (String pkg : pkgs) {
            // only games with a soundboard plugin can play them: a full song library copied into every game's folder
            // ate gigabytes per game. Copies left in other games from earlier versions are removed
            if (!wantsSounds(pkg)) {
                File old = obbSounds(pkg);
                if (old.isDirectory()) { deleteTree(old); if (!old.exists()) cleaned++; }
                without.add(pkg);
                continue;
            }
            List<File> wavs = Sounds.prepare(pkg, log);
            boolean ok = false;
            try { copyInto(wavs, obbSounds(pkg)); ok = true; }
            catch (Exception e) { if (why == null) why = e.getMessage(); }
            if (root) {
                String files = "/sdcard/Android/data/" + pkg + "/files";
                // games that never ran have no files folder: a root-made Android/data folder could get the wrong owner
                if (Root.sh("[ -d " + Root.q(files) + " ] && echo yes").out.trim().equals("yes")) {
                    StringBuilder sh = new StringBuilder();
                    copyLines(sh, wavs, Paths.gameFiles(pkg) + "/sounds", new String[]{".wav"});
                    if (Root.sh(sh.toString()).ok()) ok = true;
                }
            }
            if (ok) { done++; log.log("  " + pkg + ": " + wavs.size() + " sound(s)"); } else failed++;
        }
        log.log("sounds pushed to " + done + " game(s) with a soundboard plugin. Press Reload Sounds in the soundboard if a menu is already open");
        if (!without.isEmpty()) log.log(without.size() + " game(s) skipped, no soundboard plugin for them" + (cleaned > 0 ? " (removed old sound copies from " + cleaned + " of them)" : ""));
        if (failed > 0) log.log(failed + " game(s) failed (" + why + "). Without root vrfrida writes into Android/obb: allow \"Install unknown apps\" for vrfrida, then fully close and reopen vrfrida (Android only gives that access on a fresh start) and press Push to all games");
    }

    /** copy a picked audio file into the shared sounds folder (every game gets it). returns the saved file */
    File importSound(android.net.Uri uri, String name) throws Exception {
        if (name == null || name.isEmpty()) name = "sound.mp3";
        name = name.replaceAll("[/\\\\]", "_");
        if (!Sounds.isAudio(name)) throw new Exception(name + " isn't an audio file (" + String.join(" ", Sounds.EXT) + ")");
        Paths.SOUNDS.mkdirs();
        File dst = new File(Paths.SOUNDS, name);
        int dot = name.lastIndexOf('.');
        for (int n = 2; dst.exists(); n++) dst = new File(Paths.SOUNDS, name.substring(0, dot) + "_" + n + name.substring(dot));
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            if (in == null) throw new Exception("couldn't open the file");
            byte[] buf = new byte[1 << 16]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if ((total += n) > 200L << 20) throw new Exception("over 200 MB, too big for a sound");
            }
        } catch (Exception e) { dst.delete(); throw e; }
        return dst;
    }

    /**
     * /sdcard/vrfrida/menu.js (release download / vrfrida.py --sync) only when it is newer than this app's install:
     * an old release's menu.js used to beat the newer menu inside an app that was sideloaded later
     */
    static boolean useSdcardMenu(android.content.Context c) {
        if (!Paths.MENU.isFile()) return false;
        try { return Paths.MENU.lastModified() > c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime; }
        catch (Exception e) { return true; }
    }

    /** bridge + menu in one file for frida-inject (same thing frida's repl does with two -l scripts) */
    File buildAgent(String pkg, File bridge) throws Exception {
        String menu;
        String from;
        if (useSdcardMenu(ctx)) { menu = new String(Files.read(Paths.MENU), StandardCharsets.UTF_8); from = Paths.MENU.getPath(); }
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

    // ---- native libs (.so, e.g. ByNameModding mods), root only ---------------------------------------------
    // /sdcard/vrfrida/libs/<pkg>/*.so get loaded into <pkg> every time it starts, wherever it was started from
    // (this app, the Quest Library, ...). A detached root watcher sees each new game pid, copies the libs to
    // /data/local/tmp (an app can't dlopen from /sdcard) and runs frida-inject -e with a Module.load agent.
    static final String LIB_TMP = "/data/local/tmp/vrfrida_libs";
    static File libsDir(String pkg) { return new File(Paths.LIBS, pkg); }
    static File libsPidFile() { return new File(Paths.RUN, "libs.pid"); }
    static File libsLog() { return new File(Paths.LOGS, "libs.log"); }
    static File libsLoaderJs() { return new File(Paths.RUN, "libs_loader.js"); }

    /**
     * the agent body (the watcher puts "var L=[paths]" in front). Does what System.loadLibrary does: dlopen, then
     * JNI_OnLoad(vm) on a thread attached to the game's JavaVM. ByNameModding libs start in JNI_OnLoad,
     * so without that they load but never run.
     */
    static final String LOADER_JS =
            "function getVm(){\n" +
            "  var f=Module.findGlobalExportByName('JNI_GetCreatedJavaVMs');\n" +
            "  if(!f) return null;\n" +
            "  var buf=Memory.alloc(8), n=Memory.alloc(4);\n" +
            "  var r=new NativeFunction(f,'int',['pointer','int','pointer'])(buf,1,n);\n" +
            "  return (r===0 && n.readS32()>0) ? buf.readPointer() : null;\n" +
            "}\n" +
            "L.forEach(function(p){\n" +
            "  var m;\n" +
            "  try{ m=Module.load(p); console.log('[libs] loaded '+p); }\n" +
            "  catch(e){ console.log('[libs] FAILED '+p+': '+e); return; }\n" +
            "  var il=Process.findModuleByName('libil2cpp.so');\n" +
            "  console.log('[libs] libil2cpp.so '+(il?'already loaded':'not loaded yet'));\n" +
            // ByNameModding: if the game's il2cpp_init already ran, BNM only starts with its late-init hook on
            "  var late=m.findExportByName('_ZN3BNM7Loading17AllowLateInitHookEv');\n" +
            "  if(late){ try{ new NativeFunction(late,'void',[])(); console.log('[libs] BNM late init hook allowed'); }catch(e){ console.log('[libs] AllowLateInitHook failed: '+e); } }\n" +
            "  var onload=m.findExportByName('JNI_OnLoad');\n" +
            "  if(!onload) return;\n" +
            "  var vm=getVm();\n" +
            "  if(!vm){ console.log('[libs] no JavaVM found, JNI_OnLoad not called'); return; }\n" +
            "  var fns=vm.readPointer();\n" +   // JNIInvokeInterface: [4] AttachCurrentThread, [5] DetachCurrentThread
            "  var attach=new NativeFunction(fns.add(4*Process.pointerSize).readPointer(),'int',['pointer','pointer','pointer']);\n" +
            "  var detach=new NativeFunction(fns.add(5*Process.pointerSize).readPointer(),'int',['pointer']);\n" +
            "  var envp=Memory.alloc(Process.pointerSize);\n" +
            "  if(attach(vm,envp,NULL)!==0){ console.log('[libs] AttachCurrentThread failed, JNI_OnLoad not called'); return; }\n" +
            "  try{ var v=new NativeFunction(onload,'int',['pointer','pointer'])(vm,NULL); console.log('[libs] JNI_OnLoad -> 0x'+(v>>>0).toString(16)); }\n" +
            "  catch(e){ console.log('[libs] JNI_OnLoad crashed: '+e); }\n" +
            "  detach(vm);\n" +
            "});\n";

    static List<File> libs(String pkg) {
        List<File> out = new ArrayList<>();
        File[] fs = libsDir(pkg).listFiles((d, n) -> n.endsWith(".so"));
        if (fs != null) { java.util.Arrays.sort(fs); for (File f : fs) if (f.isFile()) out.add(f); }
        return out;
    }

    static boolean anyLibs() {
        File[] ds = Paths.LIBS.listFiles(File::isDirectory);
        if (ds != null) for (File d : ds) if (!libs(d.getName()).isEmpty()) return true;
        return false;
    }

    /** copy a picked .so into libs/<pkg>/, refusing anything that isn't a 64-bit ARM ELF (the game is arm64) */
    File importLib(String pkg, android.net.Uri uri, String name) throws Exception {
        byte[] data;
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("couldn't open the file");
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 512L << 20) throw new Exception("over 512 MB, that's not a mod lib"); }
            data = b.toByteArray();
        }
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') throw new Exception("not an ELF .so file");
        int machine = (data[18] & 0xff) | (data[19] & 0xff) << 8;
        if (data[4] != 2 || machine != 183) throw new Exception("not a 64-bit ARM (arm64-v8a) library, the game can't load it");
        if (name == null || name.isEmpty()) name = "lib.so";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.endsWith(".so")) name += ".so";
        File dir = libsDir(pkg);
        dir.mkdirs();
        File dst = new File(dir, name);
        Files.write(dst, data);
        return dst;
    }

    boolean libWatcherRunning() {
        Root.Result r = Root.sh("p=$(cat " + Root.q(libsPidFile().getPath()) + " 2>/dev/null); [ -n \"$p\" ] && kill -0 $p 2>/dev/null && echo yes");
        return r.out.trim().equals("yes");
    }

    /** (re)start the watcher if any game has libs, stop it if none do. Restarted on every app start: the injector path changes with app updates */
    void syncLibWatcher() {
        stopLibWatcher();
        if (!anyLibs()) return;
        try { Files.write(libsLoaderJs(), LOADER_JS.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { log.log("couldn't write " + libsLoaderJs() + ": " + e); return; }
        String inj = Root.q(injector());
        String script =
                "LOG=" + Root.q(libsLog().getPath()) + "\n" +
                "T=" + LIB_TMP + "\n" +
                "echo $$ > " + Root.q(libsPidFile().getPath()) + "\n" +
                "mkdir -p $T; chmod 755 $T\n" +   // keep <pkg>.last: a game already running with its libs must not get them twice
                "echo \"[libs] $(date '+%H:%M:%S') watcher started\" > \"$LOG\"\n" +
                "while kill -0 $$ 2>/dev/null; do\n" +
                "  for d in " + Paths.LIBS.getPath() + "/*; do\n" +
                "    [ -d \"$d\" ] || continue\n" +
                "    pkg=${d##*/}\n" +
                "    ls \"$d\"/*.so >/dev/null 2>&1 || continue\n" +
                "    pid=$(pidof \"$pkg\" 2>/dev/null | cut -d' ' -f1)\n" +
                "    if [ -z \"$pid\" ]; then rm -f \"$T/$pkg.last\"; continue; fi\n" +
                "    [ \"$(cat \"$T/$pkg.last\" 2>/dev/null)\" = \"$pid\" ] && continue\n" +
                "    echo \"$pid\" > \"$T/$pkg.last\"\n" +
                "    sleep 1\n" +                                    // let the fresh process finish starting up
                "    D=\"$T/$pkg/$pid\"; rm -rf \"$T/$pkg\"; mkdir -p \"$D\"\n" +   // per-pid folder: never a 2nd copy of a lib already loaded
                "    cp \"$d\"/*.so \"$D\"/; chmod 755 \"$T/$pkg\" \"$D\"; chmod 644 \"$D\"/*.so\n" +
                "    chcon -R u:object_r:system_lib_file:s0 \"$T/$pkg\" 2>/dev/null\n" +
                "    JS=\"$D/load.js\"\n" +
                "    echo 'var L=[' > \"$JS\"\n" +
                "    for f in \"$D\"/*.so; do echo \"'$f',\" >> \"$JS\"; done\n" +
                "    echo '];' >> \"$JS\"; cat " + Root.q(libsLoaderJs().getPath()) + " >> \"$JS\"\n" +
                "    echo \"[libs] $(date '+%H:%M:%S') $pkg started (pid $pid), loading $(ls \"$D\"/*.so | wc -l) lib(s)\" >> \"$LOG\"\n" +
                "    " + inj + " -p $pid -s \"$JS\" -R qjs -e < /dev/null >> \"$LOG\" 2>&1\n" +
                "    echo \"[libs] $(date '+%H:%M:%S') frida-inject exited ($?)\" >> \"$LOG\"\n" +
                "  done\n" +
                "  sleep 1\n" +
                "done\n";
        File f = new File(Paths.RUN, "libs.sh");
        try { Files.write(f, script.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { log.log("couldn't write " + f + ": " + e); return; }
        Root.sh("chmod 755 " + inj + " 2>/dev/null; setsid sh " + Root.q(f.getPath()) + " < /dev/null > /dev/null 2>&1 &");
    }

    void stopLibWatcher() {
        String pf = Root.q(libsPidFile().getPath());
        Root.sh("p=$(cat " + pf + " 2>/dev/null)\n" +
                "if [ -n \"$p\" ] && kill -0 $p 2>/dev/null; then pkill -P $p; kill $p; fi; rm -f " + pf);
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
                "    if " + Root.q(injector()) + " -p $pid -s " + Root.q(spoofScript().getPath()) + " -R v8 -e < /dev/null >> \"$LOG\" 2>&1; then\n" +
                "      echo \"[appsafety] $(date '+%H:%M:%S') spoof eternalized\" >> \"$LOG\"\n" +
                "      last=\"$pid\"\n" +                 // only advance once the inject lands - a double Java bridge breaks auth
                "    else\n" +
                "      echo \"[appsafety] $(date '+%H:%M:%S') frida-inject failed (pid $pid), retrying in 2s\" >> \"$LOG\"\n" +
                "    fi\n" +
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

    // ---- hide the Quest's own cursor over this app (root) -----------------------------------------------
    // An eternalized agent in com.oculus.vrshell scales the shell's cursor to 0 on the panel whose window token is in
    // the system property debug.vrfrida.cursor (shellagent/vrshell_cursor.js). A detached root watcher injects it
    // whenever vrshell (re)starts; the app only sets / clears the property.
    static final String VRSHELL = "com.oculus.vrshell", CURSOR_PROP = "debug.vrfrida.cursor";
    static File shellCursorScript() { return new File(Paths.ROOT, "vrshell_cursor.js"); }
    static File shellCursorPidFile() { return new File(Paths.RUN, "vrshell_cursor.pid"); }
    static File shellCursorInjected() { return new File(Paths.RUN, "vrshell_cursor.injected"); }
    static File shellCursorLog() { return new File(Paths.LOGS, "vrshell_cursor.log"); }

    /** this app's panel token (persistableToken of its volumetric window), "" if Horizon hasn't registered it yet */
    static String ownPanelToken(String pkg) {
        Root.Result r = Root.sh("dumpsys volumetric_window 2>/dev/null | awk '/" + pkg.replace(".", "\\.") + "\\/.* bounds=/{f=1} f&&/windowSpace: WindowSpace/{match($0,/uuid=[0-9a-f-]+/); print substr($0,RSTART+5,RLENGTH-5); exit}'");
        String u = r.out.trim();
        return u.matches("[0-9a-f-]{36}") ? u : "";
    }

    /** turn it on (token = this app's panel) or off (token = ""); starts the vrshell watcher when needed */
    void shellCursor(String token) {
        Root.sh("setprop " + CURSOR_PROP + " " + (token.isEmpty() ? "\"\"" : token));
        if (token.isEmpty()) return;
        String inj = shellCursorInjected().getPath();
        String script =
                "LOG=" + Root.q(shellCursorLog().getPath()) + "\n" +
                "echo $$ > " + Root.q(shellCursorPidFile().getPath()) + "\n" +
                "echo \"[vrshell] $(date '+%H:%M:%S') watcher started\" >> \"$LOG\"\n" +
                "while kill -0 $$ 2>/dev/null; do\n" +
                "  pid=$(pidof " + VRSHELL + " 2>/dev/null | tr ' ' '\\n' | head -1)\n" +
                "  done_pid=$(cat " + Root.q(inj) + " 2>/dev/null)\n" +
                "  if [ -n \"$pid\" ] && [ \"$pid\" != \"$done_pid\" ]; then\n" +
                "    sleep 3\n" +                    // a fresh shell needs a moment before its Java side is up
                "    echo \"[vrshell] $(date '+%H:%M:%S') injecting into pid $pid\" >> \"$LOG\"\n" +
                "    if " + Root.q(injector()) + " -p $pid -s " + Root.q(shellCursorScript().getPath()) + " -R v8 -e < /dev/null >> \"$LOG\" 2>&1; then\n" +
                "      echo $pid > " + Root.q(inj) + "\n" +
                "    else echo \"[vrshell] $(date '+%H:%M:%S') frida-inject failed, retrying\" >> \"$LOG\"; fi\n" +
                "  fi\n" +
                "  sleep 3\n" +
                "done\n";
        File f = new File(Paths.RUN, "vrshell_cursor.sh");
        // the script has this install's injector path in it: an app update changes it, so a watcher started by an older
        // install (stale path) is replaced; an up-to-date running one is left alone
        String pf = Root.q(shellCursorPidFile().getPath());
        Root.Result alive = Root.sh("p=$(cat " + pf + " 2>/dev/null); [ -n \"$p\" ] && kill -0 $p 2>/dev/null && echo yes");
        String old = "";
        try { old = new String(Files.read(f), StandardCharsets.UTF_8); } catch (Exception ignored) {}
        if (alive.out.trim().equals("yes") && old.equals(script)) return;
        Root.sh("p=$(cat " + pf + " 2>/dev/null); if [ -n \"$p\" ] && kill -0 $p 2>/dev/null; then pkill -P $p; kill $p; fi");
        try {
            Files.write(shellCursorScript(), Files.readAsset(ctx, "vrshell_cursor.js").getBytes(StandardCharsets.UTF_8));
            Files.write(f, script.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { log.log("couldn't write the vrshell cursor files: " + e); return; }
        Root.sh("chmod 755 " + Root.q(injector()) + " 2>/dev/null; setsid sh " + Root.q(f.getPath()) + " < /dev/null > /dev/null 2>&1 &");
        log.log("Quest pointer: vrshell watcher started (the shell's cursor is hidden over vrfrida)");
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

    /** normal launch intent; if that fails (or the game never shows up) and we have root, monkey as a fallback */
    void launch(String pkg, boolean root) throws Exception {
        Exception err = null;
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            // Quest games often only have an INFO / VR category, not LAUNCHER
            Intent q = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
            java.util.List<android.content.pm.ResolveInfo> r = ctx.getPackageManager().queryIntentActivities(q, 0);
            if (!r.isEmpty()) i = q.setClassName(pkg, r.get(0).activityInfo.name);
        }
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { ctx.startActivity(i); } catch (Exception e) { err = e; }
        } else err = new Exception(pkg + " has no launchable activity");
        if (!root) { if (err != null) throw err; return; }
        // give it a few seconds to appear, else force it with monkey
        for (int t = 0; t < 8 && err == null; t++) {
            if (!pidOf(pkg).isEmpty()) return;
            Thread.sleep(500);
        }
        if (!pidOf(pkg).isEmpty()) return;
        Root.Result m = Root.sh("monkey -p " + Root.q(pkg) + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || monkey -p " + Root.q(pkg) + " 1 >/dev/null 2>&1");
        if (!m.ok() && err != null) throw new Exception("couldn't launch " + pkg + ": " + err.getMessage());
    }
}
