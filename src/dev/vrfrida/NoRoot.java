package dev.vrfrida;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * No-root mode: puts Frida Gadget into the game's own APK (on the headset, no PC), the same patch as
 * nonroot/patch_apk.py, then installs it with the normal Android installer.
 *
 *   lib/arm64-v8a/libfrida-gadget.so           official Frida Gadget (shipped inside this app)
 *   lib/arm64-v8a/libfrida-gadget.config.so    runs libvrfrida-loader.js.so
 *   lib/arm64-v8a/libvrfrida-loader.js.so      waits for libil2cpp.so + the delay, then loads the newest agent it can read
 *   lib/arm64-v8a/libvrfrida-agent.js.so       bridge + plugins + menu, built at patch time (the fallback that always works)
 *   libmain.so                                 gets libfrida-gadget.so as a DT_NEEDED (ElfPatch), so Gadget starts with the game
 *
 * Every apk of the game (base + splits) is re-signed with the patch key bundled in this app. The first install has to
 * uninstall the store copy (different signature: Android deletes its local data). Patching again later installs as a
 * normal update (same key) and keeps the data.
 */
final class NoRoot {
    static final String ABI_DIR = "lib/arm64-v8a/";
    static final String GADGET = "libfrida-gadget.so";
    static final String LIBMAIN = ABI_DIR + "libmain.so";
    static final String ACTION_RESULT = "dev.vrfrida.INSTALL_RESULT";
    static final int REQ_UNINSTALL = 4711;

    final Activity act;
    final Bridge.Log log;
    final Runnable done;                  // called on the ui thread when an install flow ends (either way)
    String pending;                       // package waiting for uninstall -> install
    File obbKept;
    BroadcastReceiver receiver;

    NoRoot(Activity act, Bridge.Log log, Runnable done) { this.act = act; this.log = log; this.done = done; }

    // ---------------------------------------------------------------- detection
    /** true if any apk of the game already carries Frida Gadget */
    static boolean isPatched(ApplicationInfo ai) {
        for (String apk : Bridge.apks(ai)) {
            try (ZipFile z = new ZipFile(apk)) { if (z.getEntry(ABI_DIR + GADGET) != null) return true; }
            catch (Exception ignored) {}
        }
        return false;
    }

    /** true if the installed copy is signed with our patch key (so a repatch installs as an update) */
    boolean signedByUs(String pkg) {
        try {
            byte[] ours = ApkPatch.cert(act).getEncoded();
            PackageInfo pi = act.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null) return false;
            for (Signature s : pi.signingInfo.getApkContentsSigners()) if (Arrays.equals(s.toByteArray(), ours)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    File outDir(String pkg) { return new File(act.getExternalFilesDir("patched"), pkg); }

    // ---------------------------------------------------------------- agent + loader
    static File gadgetAgentFile(String pkg) { return new File(Paths.AGENTS, pkg + ".gadget.js"); }

    /** bridge + this game's plugins (embedded, the game can't read /sdcard/vrfrida/plugins) + menu, as one script */
    String buildAgent(Core core, ApplicationInfo ai, Settings cfg) throws Exception {
        String pkg = ai.packageName;
        File bridge = Bridge.ensure(act, ai, false, log);
        String menu = Paths.MENU.isFile() ? new String(Files.read(Paths.MENU), StandardCharsets.UTF_8) : Files.readAsset(act, "menu.js");
        log.log("menu: " + (Paths.MENU.isFile() ? Paths.MENU.getPath() : "the copy bundled in the app"));
        List<File> plugins = Core.pluginsFor(pkg, log, cfg.disabledPlugins);
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("// vrfrida gadget agent for ").append(pkg).append(" (no root)\n");
        sb.append("globalThis.__imguiEmbeddedPlugins = {");
        boolean first = true;
        for (File p : plugins) {
            if (!first) sb.append(',');
            first = false;
            Files.jsonQuote(p.getName(), sb);
            sb.append(':');
            Files.jsonQuote(new String(Files.read(p), StandardCharsets.UTF_8), sb);
        }
        sb.append("};\n");
        sb.append("Script.evaluate(\"/frida-il2cpp-bridge.js\", "); Files.jsonQuote(new String(Files.read(bridge), StandardCharsets.UTF_8), sb); sb.append(");\n");
        sb.append("Script.evaluate(\"/UniversalMenu.js\", "); Files.jsonQuote(menu, sb); sb.append(");\n");
        log.log(plugins.size() + " plugin(s) embedded");
        String agent = sb.toString();
        try { Files.write(gadgetAgentFile(pkg), agent.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { log.log("couldn't write " + gadgetAgentFile(pkg) + ": " + e); }
        return agent;
    }

    /**
     * Gadget runs this as soon as libmain.so loads, long before Unity sets up il2cpp.
     * Agent order: the PC kit's files/frida/menu.js (patch_apk.py --push-only), /sdcard/vrfrida/agents/<pkg>.gadget.js
     * (only readable if the game has storage access), then the copy inside the apk.
     */
    static String loader(String pkg, int delay) {
        StringBuilder q = new StringBuilder();
        Files.jsonQuote(pkg, q);
        return "// vrfrida gadget loader\n" +
                "(function () {\n" +
                "  var PKG = " + q + ", DELAY = " + Math.max(0, delay) + ";\n" +
                // Frida cloaks its own module (findModuleByName(gadget) is always null), so take the folder from the game's
                // own libs: they sit in the same lib dir as libfrida-gadget.so and libvrfrida-agent.js.so
                // Gadget's console goes nowhere on Quest: the loader keeps its own log in the game's files folder
                "  var LOG = '/storage/emulated/0/Android/data/' + PKG + '/files/vrfrida-loader.log', lines = [];\n" +
                "  function log(m) { lines.push(new Date().toISOString() + '  ' + m); if (lines.length > 200) lines.shift(); try { File.writeAllText(LOG, lines.join('\\n') + '\\n'); } catch (e) {} try { console.log('[vrfrida] ' + m); } catch (e) {} }\n" +
                "  log('loader started (frida ' + Frida.version + ', ' + Script.runtime + ')');\n" +
                "  function libDir() {\n" +
                "    var m = Process.findModuleByName('libmain.so') || Process.findModuleByName('libil2cpp.so') || Process.findModuleByName('" + GADGET + "');\n" +
                "    return m ? m.path.replace(/\\/[^\\/]*$/, '') : '';\n" +
                "  }\n" +
                "  var paths = ['/storage/emulated/0/Android/data/' + PKG + '/files/frida/menu.js',\n" +
                "               '/storage/emulated/0/vrfrida/agents/' + PKG + '.gadget.js',\n" +
                "               null];\n" +
                "  function load() {\n" +
                "    paths[2] = libDir() + '/libvrfrida-agent.js.so';\n" +
                "    for (var i = 0; i < paths.length; i++) {\n" +
                "      var src = null;\n" +
                "      try { src = File.readAllText(paths[i]); } catch (e) { continue; }\n" +
                "      if (!src || src.length < 100) continue;\n" +
                "      log('loading ' + paths[i] + ' (' + src.length + ' chars)');\n" +
                "      try { Script.evaluate(paths[i], src); log('menu script started'); } catch (e) { log('ERROR in ' + paths[i] + ': ' + (e.stack || e)); }\n" +
                "      return;\n" +
                "    }\n" +
                "    log('no agent found in ' + paths.join(', '));\n" +
                "  }\n" +
                "  var waited = 0;\n" +
                "  (function poll() {\n" +
                "    if (!Process.findModuleByName('libil2cpp.so')) { waited += 250; if (waited % 10000 === 0) log('waiting for libil2cpp.so'); setTimeout(poll, 250); return; }\n" +
                "    log('libil2cpp.so loaded, menu in ' + DELAY + ' s');\n" +
                "    setTimeout(load, DELAY * 1000);\n" +
                "  })();\n" +
                "})();\n";
    }

    static byte[] gadgetConfig(boolean v8) {
        return ("{\n  \"interaction\": {\"type\": \"script\", \"path\": \"libvrfrida-loader.js.so\", \"on_change\": \"ignore\"},\n" +
                "  \"runtime\": \"" + (v8 ? "v8" : "qjs") + "\"\n}\n").getBytes(StandardCharsets.UTF_8);
    }

    /** rewrite /sdcard/vrfrida/agents/<pkg>.gadget.js without repatching (used only if the game can read /sdcard) */
    void updateAgent(Core core, ApplicationInfo ai, Settings cfg) throws Exception {
        if (!isPatched(ai))
            throw new Exception("this game isn't patched yet (Patch + install never finished, check the log above for why). Update mods only works on a patched game: press Patch + install first");
        buildAgent(core, ai, cfg);
        log.log("wrote " + gadgetAgentFile(ai.packageName));
        log.log("the patched game loads it next start IF it can read /sdcard. If the menu doesn't change, use Patch + install again (installs as an update, keeps your game data)");
    }

    // ---------------------------------------------------------------- patch
    /** patches + signs every apk of the game into outDir(pkg). returns the signed apks (base first) */
    List<File> patch(Core core, ApplicationInfo ai, Settings cfg) throws Exception {
        String pkg = ai.packageName;
        if (Core.SYSTEM.matcher(pkg).find()) throw new Exception(pkg + " looks like a system / Meta package, refusing to patch it");
        File gadget = new File(act.getApplicationInfo().nativeLibraryDir, GADGET);
        if (!gadget.isFile()) throw new Exception("Frida Gadget is missing from this app (" + gadget + "), rebuild the apk");

        List<String> srcs = Bridge.apks(ai);
        String mainApk = null;
        boolean stored = false;
        long total = 0;
        for (String s : srcs) {
            total += new File(s).length();
            try (ZipFile z = new ZipFile(s)) {
                ZipEntry e = z.getEntry(LIBMAIN);
                if (e != null && mainApk == null) { mainApk = s; stored = e.getMethod() == ZipEntry.STORED; }
            }
        }
        if (mainApk == null) throw new Exception("no " + LIBMAIN + " in any apk of " + pkg + " (not a 64-bit Unity game?)");
        File out = outDir(pkg);
        deleteTree(out);
        out.mkdirs();
        long free = out.getUsableSpace();
        if (free < total * 2 + (200L << 20))
            throw new Exception(String.format("not enough free space: need about %.1f GB, have %.1f GB", (total * 2) / 1e9, free / 1e9));
        if (stored) log.log("warning: this game keeps its native libs uncompressed (extractNativeLibs=false). Gadget may not find its config; if the menu never shows, tell the vrfrida dev");

        String agent = buildAgent(core, ai, cfg);
        Map<String, byte[]> add = new LinkedHashMap<>();
        add.put(ABI_DIR + GADGET, Files.read(gadget));
        add.put(ABI_DIR + "libfrida-gadget.config.so", gadgetConfig(cfg.v8));
        add.put(ABI_DIR + "libvrfrida-loader.js.so", loader(pkg, cfg.delay).getBytes(StandardCharsets.UTF_8));
        add.put(ABI_DIR + "libvrfrida-agent.js.so", agent.getBytes(StandardCharsets.UTF_8));

        List<File> result = new ArrayList<>();
        int i = 0;
        for (String s : srcs) {
            File src = new File(s);
            String name = i++ == 0 ? "base.apk" : src.getName();
            File unsigned = new File(out, name + ".unsigned"), signed = new File(out, name);
            long t0 = System.currentTimeMillis();
            Map<String, ApkPatch.Edit> edits = ApkPatch.edits();
            boolean isMain = s.equals(mainApk);
            if (isMain) {
                edits.put(LIBMAIN, old -> {
                    byte[] p = ElfPatch.addNeeded(old, GADGET);
                    log.log(p == null ? "libmain.so already loads Frida Gadget" : "libmain.so now loads Frida Gadget");
                    return p == null ? old : p;
                });
            }
            log.log(String.format("rewriting %s (%.0f MB)%s", src.getName(), src.length() / 1e6, isMain ? " + Frida Gadget" : ""));
            ApkPatch.rewrite(src, unsigned, edits, isMain ? add : new LinkedHashMap<>(), stored);
            log.log("signing " + name);
            ApkPatch.sign(act, unsigned, signed);
            unsigned.delete();
            log.log(String.format("  %s done in %.1fs", name, (System.currentTimeMillis() - t0) / 1000.0));
            result.add(signed);
        }
        return result;
    }

    static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteTree(c);
        f.delete();
    }

    // ---------------------------------------------------------------- install (ui thread entry points)
    boolean canInstall() { return act.getPackageManager().canRequestPackageInstalls(); }

    void openInstallPermission() {
        act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
    }

    /** step 2 after patching. ui thread */
    void install(String pkg, List<File> apks, boolean needsUninstall) {
        if (!needsUninstall) { new Thread(() -> commit(pkg, apks)).start(); return; }
        File obb = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + pkg);
        obbKept = null;
        android.content.SharedPreferences sp = act.getSharedPreferences("noroot", 0);
        if (obb.isDirectory()) {
            String[] inside = obb.list();
            if (inside != null && inside.length == 0) {
                // most Quest games have an empty OBB folder: nothing to lose, let the uninstall take it
                log.log("the game's OBB folder is empty, nothing to keep");
            } else {
                // same folder only: Android/obb is a different mount than /sdcard, a rename across them always fails
                File keep = new File(obb.getParentFile(), pkg + ".vrfrida-keep");
                if (obb.renameTo(keep)) obbKept = keep;
                if (obbKept == null) {
                    obbBlocked(pkg, apks, obb, inside != null, sp);
                    return;
                }
                log.log("moved the game's OBB aside to " + obbKept + " (it comes back after the install)");
            }
        }
        sp.edit().remove("obbRestart").apply();
        uninstallThenInstall(pkg, apks);
    }

    /**
     * couldn't move the OBB. Android hands Android/obb to an installer app only when its process starts, so right after
     * "Install unknown apps" is switched on the running app still can't touch it: restart once. If it still fails after
     * that, let the user decide (their OBB folder may well be empty, we just can't see it).
     */
    void obbBlocked(String pkg, List<File> apks, File obb, boolean canSee, android.content.SharedPreferences sp) {
        boolean restarted = sp.getBoolean("obbRestart", false);
        if (!restarted && !canSee) {
            log.log("can't reach " + obb + " yet: Android only gives vrfrida OBB access after it restarts (needed once after allowing \"Install unknown apps\")");
            new android.app.AlertDialog.Builder(act)
                    .setTitle("Restart vrfrida")
                    .setMessage("vrfrida has to restart once so Android lets it reach the game's OBB folder.\n\nAfter it reopens, select the game and press Patch + install again.\n\nIf it doesn't reopen by itself, open vrfrida again from your library.")
                    .setPositiveButton("Restart", (d, w) -> {
                        sp.edit().putBoolean("obbRestart", true).commit();
                        restartApp();
                    })
                    .setNegativeButton("Cancel", (d, w) -> done.run())
                    .setOnCancelListener(d -> done.run())
                    .show();
            return;
        }
        log.log("couldn't move the OBB folder (" + obb + ") aside" + (canSee ? "" : ", and vrfrida can't see inside it"));
        new android.app.AlertDialog.Builder(act)
                .setTitle("OBB folder")
                .setMessage("vrfrida can't move this game's OBB folder out of the way, so uninstalling the store copy deletes it.\n\n" +
                        "For most Quest games that folder is empty and nothing is lost. If the game does keep files there, the patched game may not start; then move " + obb + " somewhere safe with a file manager / SideQuest first.\n\nContinue anyway?")
                .setPositiveButton("Continue", (d, w) -> { sp.edit().remove("obbRestart").apply(); uninstallThenInstall(pkg, apks); })
                .setNegativeButton("Stop", (d, w) -> { log.log("stopped, nothing changed"); done.run(); })
                .setOnCancelListener(d -> done.run())
                .show();
    }

    void restartApp() {
        Intent launch = act.getPackageManager().getLaunchIntentForPackage(act.getPackageName());
        if (launch != null) act.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        act.finishAffinity();
        Runtime.getRuntime().exit(0);
    }

    void uninstallThenInstall(String pkg, List<File> apks) {
        pending = pkg;
        pendingApks = apks;
        log.log("uninstalling the store copy of " + pkg + " (confirm in the system dialog)");
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        act.startActivityForResult(i, REQ_UNINSTALL);
    }
    List<File> pendingApks;

    /** MainActivity forwards onActivityResult here */
    boolean onActivityResult(int req) {
        if (req != REQ_UNINSTALL || pending == null) return false;
        String pkg = pending;
        pending = null;
        if (installed(act, pkg)) {
            log.log("the game is still installed (uninstall cancelled), nothing changed");
            restoreObb(pkg);
            done.run();
            return true;
        }
        List<File> apks = pendingApks;
        new Thread(() -> commit(pkg, apks)).start();
        return true;
    }

    void restoreObb(String pkg) {
        if (obbKept == null) return;
        File obb = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + pkg);
        if (obb.exists()) log.log("an OBB folder already exists at " + obb + ", left the old one at " + obbKept);
        else if (obbKept.renameTo(obb)) log.log("put the game's OBB back");
        else log.log("couldn't move the OBB back: it is at " + obbKept + ", move it to " + obb + " by hand (SideQuest / a file manager)");
        obbKept = null;
    }

    /** background thread: one PackageInstaller session with every apk */
    void commit(String pkg, List<File> apks) {
        try {
            registerReceiver();
            PackageInstaller pi = act.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(pkg);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                for (File f : apks) {
                    log.log(String.format("handing %s to the installer (%.0f MB)", f.getName(), f.length() / 1e6));
                    try (InputStream in = new FileInputStream(f); OutputStream o = s.openWrite(f.getName(), 0, f.length())) {
                        byte[] b = new byte[1 << 20];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        s.fsync(o);
                    }
                }
                Intent i = new Intent(ACTION_RESULT).setPackage(act.getPackageName()).putExtra("pkg", pkg);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(act, id, i, fl).getIntentSender());
            }
            log.log("installing...");
        } catch (Exception e) {
            log.log("install failed: " + e);
            act.runOnUiThread(() -> { restoreObb(pkg); done.run(); });
        }
    }

    void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String pkg = i.getStringExtra("pkg");
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(confirm); }
                    log.log("confirm the install in the system dialog");
                    return;
                }
                if (st == PackageInstaller.STATUS_SUCCESS) {
                    log.log("installed the patched " + pkg + ". Start it from your library: the menu loads after the game boots + the delay (hold Y)");
                    for (File f : new File[]{new File(act.getExternalFilesDir("patched"), pkg)}) deleteTree(f);
                } else {
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    log.log("install failed (" + st + "): " + msg + (installed(act, pkg) ? "" : ". The patched apks are kept in " + new File(act.getExternalFilesDir("patched"), pkg)));
                }
                restoreObb(pkg);
                done.run();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else act.registerReceiver(receiver, f);
    }

    void close() {
        if (receiver != null) { try { act.unregisterReceiver(receiver); } catch (Exception ignored) {} receiver = null; }
    }
}
