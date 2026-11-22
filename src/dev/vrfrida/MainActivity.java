package dev.vrfrida;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** the app, drawn to look like UniversalMenu (same themes, backgrounds and widgets) */
public class MainActivity extends Activity {
    static final String[] TABS = {"Games", "Plugins", "Store", "Menu Look", "Log"};

    static final class Game {
        ApplicationInfo ai; String label; Bitmap icon; String fp = ""; String bridge = "?"; boolean running, injected, fav, patched;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newSingleThreadExecutor();   // launch / inject / bridge jobs (one at a time)
    final ExecutorService io = Executors.newSingleThreadExecutor();   // quick reads: status, settings.json (never stuck behind a job)
    final List<Game> games = new ArrayList<>();
    final StringBuilder appLog = new StringBuilder();
    final List<View> actions = new ArrayList<>();
    Core core;
    NoRoot noRoot;
    Updater updater;
    Updater.Release update;               // newer release found by the last check, null = none
    Store store;
    volatile int storeWaiting;            // community plugin updates waiting for review
    volatile boolean storeLoading;
    int storeFilter = 0;                  // 0 = selected game, 1 = all games
    MenuLook look;
    MenuCfg appLook, gameCfg, globalCfg;  // appLook: app-only prefs (colors source, animation). globalCfg: the synced look
    String gameCfgFor = "", gameCfgState = "";
    dev.vrfrida.Settings cfg;
    Game sel;
    boolean busy, rootOk;
    int tab = 0, lookScope = 0;           // lookScope 0 = all games (synced), 1 = the selected game only
    String status = "starting...", uxpKey = "", search = "";
    final int[] scrollY = new int[TABS.length];
    ScrollView tabScroll;
    LinearLayout gameList;
    TextView statusView, logView, miniLog;
    Map<String, float[]> C;               // colors the widgets were built with

    final Runnable poll = new Runnable() {
        @Override public void run() { refreshLogs(); snapIfAsked(); ui.postDelayed(this, 1000); }
    };
    final Runnable saveGame = () -> flushSave();

    /** dev: `adb shell touch /sdcard/vrfrida/.snap` -> the app draws ONLY its own window into /sdcard/vrfrida/snap.png
     *  (a headset screenshot would include the passthrough camera; this can't) */
    void snapIfAsked() {
        File trig = new File(Paths.ROOT, ".snap");
        if (!trig.exists()) return;
        String want = "";
        try { want = new String(Files.read(trig), "UTF-8").trim(); } catch (Exception ignored) {}
        trig.delete();
        if (want.matches("[0-4]") && Integer.parseInt(want) != tab) { goTab(Integer.parseInt(want)); ui.postDelayed(() -> { try { Files.write(trig, new byte[0]); } catch (Exception ignored) {} }, 1500); return; }
        try {
            View v = getWindow().getDecorView();
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(Paths.ROOT, "snap.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        } catch (Throwable e) {
            log("snap failed: " + e);
            try { Files.write(new File(Paths.ROOT, "snap.err"), android.util.Log.getStackTraceString(e).getBytes("UTF-8")); } catch (Exception ignored) {}
        }
    }

    // ================================================================ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        core = new Core(this, this::log);
        updater = new Updater(this, this::log);
        store = new Store(this, this::log);
        noRoot = new NoRoot(this, this::log, () -> { setBusy(false); bg.execute(this::scanGames); });
        look = new MenuLook(this);
        appLook = MenuCfg.parse(look, dev.vrfrida.Settings.prefs(this).getString("applook", "{}"));
        globalCfg = MenuCfg.parse(look, dev.vrfrida.Settings.globalLook(this));
        String th = appLook.root.optString("appTheme", "auto");
        if (!th.equals("meta") && !th.equals("uxpatcher")) { try { appLook.root.put("appTheme", "auto"); } catch (Exception ignored) {} }
        uxpKey = uxpSignature();
        rebuild();
        bg.execute(this::startup);
    }

    @Override protected void onResume() {
        super.onResume();
        String k = uxpSignature();
        if (!k.equals(uxpKey)) { uxpKey = k; rebuild(); }       // UXpatcher colors changed while we were away
        ui.post(poll);
        // throttled: a Magisk toast / notification pauses + resumes us, and refreshing calls su again (which toasts again)
        long now = System.currentTimeMillis();
        if (rootOk && now - lastResumeRefresh > 15000) { lastResumeRefresh = now; io.execute(this::refreshStatus); }
    }
    long lastResumeRefresh = System.currentTimeMillis();
    String shownSig = "";

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
        flushSave();
    }

    String uxpSignature() { return MenuLook.uxp(this, "bg_color") + "|" + MenuLook.uxp(this, "accent_color") + "|" + MenuLook.uxp(this, "text_color"); }

    void startup() {
        autoCheckUpdate();
        log("checking root (accept the Magisk prompt if one shows up)");
        rootOk = Root.available();
        if (!rootOk) {
            log("no root: no-root mode. Pick a game and use Patch + install (puts Frida Gadget into the game, no root needed)");
            ui.post(this::rebuild);
        }
        if (!Environment.isExternalStorageManager()) {
            if (rootOk) Root.sh("appops set --uid " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
            if (!Environment.isExternalStorageManager()) {
                log(rootOk ? "couldn't grant all-files access with root, opening the settings page for it" : "allow all-files access for vrfrida (for /sdcard/vrfrida), then open the app again");
                ui.post(() -> startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))));
                return;
            }
        }
        core.seedIfEmpty();
        autoStoreUpdate();
        core.writeSpoofScript();        // appsafety spoof script on disk, ready for a launch
        look.bitmaps.clear();          // pattern pngs exist now
        if (rootOk && !new File(core.injector()).isFile()) log("frida-inject is missing from the app (" + core.injector() + "), rebuild the apk");
        scanGames();
    }

    /**
     * finds Unity il2cpp games. Whether an apk has libil2cpp.so (and its fingerprint) is cached per apk path + update time,
     * so only new / updated apps get their zip opened (it used to open every installed app's apk on every refresh)
     */
    void scanGames() {
        setStatus("looking for Unity il2cpp games...");
        PackageManager pm = getPackageManager();
        SharedPreferences sp = dev.vrfrida.Settings.prefs(this);
        SharedPreferences.Editor ed = sp.edit();
        Set<String> favs = dev.vrfrida.Settings.favorites(this);
        List<Game> found = new ArrayList<>();
        int px = dp(44);
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(getPackageName())) continue;
            if (Core.SYSTEM.matcher(ai.packageName).find()) continue;
            String want = ai.sourceDir + "|" + pi.lastUpdateTime + "|";
            String c = sp.getString("scan2:" + ai.packageName, null), fp;
            boolean patched;
            if (c != null && c.startsWith(want)) { fp = c.substring(want.length()); patched = fp.endsWith("#gadget"); fp = fp.replace("#gadget", ""); }
            else {
                Bridge.Lib lib = Bridge.findLib(ai);
                fp = lib == null ? "-" : lib.fingerprint();
                patched = lib != null && NoRoot.isPatched(ai);
                ed.putString("scan2:" + ai.packageName, want + fp + (patched ? "#gadget" : ""));
            }
            if (fp.equals("-") || pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            Game g = new Game();
            g.ai = ai;
            g.fp = fp;
            g.patched = patched;
            g.label = String.valueOf(pm.getApplicationLabel(ai));
            g.icon = iconBitmap(pm.getApplicationIcon(ai), px);
            g.fav = favs.contains(ai.packageName);
            found.add(g);
        }
        ed.apply();
        sortGames(found);
        ui.post(() -> {
            String keep = sel != null ? sel.ai.packageName : null;
            synchronized (games) { games.clear(); games.addAll(found); }
            Game again = null;
            for (Game g : found) if (g.ai.packageName.equals(keep)) again = g;
            if (again != null) { sel = again; rebuild(); }
            else if (!games.isEmpty()) select(games.get(0));
            else rebuild();
        });
        refreshStatus();
    }

    static void sortGames(List<Game> l) {
        Collections.sort(l, (a, c) -> a.fav != c.fav ? (a.fav ? -1 : 1) : a.label.compareToIgnoreCase(c.label));
    }

    /** small bitmap once, instead of drawing the full-size adaptive icon drawable on every rebuild */
    static Bitmap iconBitmap(Drawable d, int px) {
        try {
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(c);
            return b;
        } catch (Exception e) { return null; }
    }

    void refreshStatus() {
        Core.Status st = rootOk ? Core.status() : new Core.Status();
        List<Game> snapshot;
        synchronized (games) { snapshot = new ArrayList<>(games); }
        for (Game g : snapshot) {
            g.running = st.running.contains(g.ai.packageName);
            g.injected = st.injected.contains(g.ai.packageName);
            g.bridge = Bridge.stateCached(g.ai, g.fp);
        }
        status = snapshot.size() + " games  ·  " + syncInfo();
        StringBuilder sig = new StringBuilder(status);
        for (Game g : snapshot) sig.append('|').append(g.ai.packageName).append(g.bridge).append(g.running).append(g.injected);
        String newSig = sig.toString();
        ui.post(() -> {
            if (newSig.equals(shownSig)) { updateStatusLine(); return; }     // nothing changed: don't rebuild (it eats taps)
            shownSig = newSig;
            if (tab == 0 && gameList != null) { fillGameList(); refreshDetailHeader(); updateStatusLine(); }
            else rebuild();
        });
    }

    /** when the PC last ran `vrfrida.py --sync` (it writes /sdcard/vrfrida/.sync.json), else the menu file's date */
    static String syncInfo() {
        SimpleDateFormat f = new SimpleDateFormat("MMM d HH:mm", Locale.US);
        File s = new File(Paths.ROOT, ".sync.json");
        if (s.isFile()) {
            try {
                JSONObject o = new JSONObject(new String(Files.read(s), java.nio.charset.StandardCharsets.UTF_8));
                long t = o.optLong("time", s.lastModified() / 1000) * 1000;
                String v = o.optString("menuVersion", "");
                return "PC sync " + f.format(new Date(t)) + (v.isEmpty() ? "" : " (menu " + v + ")") + "  ·  " + o.optInt("plugins", 0) + " plugins";
            } catch (Exception ignored) {}
        }
        return Paths.MENU.isFile() ? "menu from " + f.format(new Date(Paths.MENU.lastModified())) : "bundled menu (run vrfrida.py --sync on the PC)";
    }

    // ================================================================ selection + menu settings file
    void select(Game g) {
        flushSave();
        sel = g;
        cfg = dev.vrfrida.Settings.load(this, g.ai.packageName);
        gameCfg = null;
        gameCfgFor = g.ai.packageName;
        gameCfgState = "loading...";
        rebuild();
        if (rootOk) loadGameCfg(g);
    }

    void loadGameCfg(Game g) {
        io.execute(() -> {
            String text = Core.readSettings(g.ai.packageName);
            MenuCfg c = MenuCfg.parse(look, text);
            ui.post(() -> {
                if (sel != g) return;
                gameCfg = c;
                gameCfgState = text.trim().isEmpty() ? "no settings.json yet: showing the menu defaults (saved on first change)" : "loaded from the game";
                if (tab == 3) rebuild();
            });
        });
    }

    void gameChanged(boolean restyle) {
        gameCfgState = "unsaved changes...";
        ui.removeCallbacks(saveGame);
        ui.postDelayed(saveGame, 800);
        if (restyle) rebuild();
    }

    /** write the selected game's settings.json now if there are pending changes */
    void flushSave() {
        ui.removeCallbacks(saveGame);
        if (gameCfg == null || sel == null || !gameCfgState.startsWith("unsaved")) return;
        String pkg = sel.ai.packageName, text = gameCfg.root.toString();
        Game g = sel;
        gameCfgState = "saving...";
        io.execute(() -> {
            boolean ok = Core.writeSettings(pkg, text);
            ui.post(() -> {
                if (sel == g) gameCfgState = ok ? "saved to the game" + (g.injected ? " (the running menu may overwrite it, unload first)" : "") : "couldn't save (no root?)";
                if (!ok) log("couldn't write " + Core.settingsPath(pkg));
                updateStatusLine();
            });
        });
    }

    void globalChanged(boolean restyle) {
        dev.vrfrida.Settings.saveGlobalLook(this, globalCfg.root.toString());
        if (restyle) rebuild();
    }

    void appLookChanged(boolean restyle) {
        dev.vrfrida.Settings.prefs(this).edit().putString("applook", appLook.root.toString()).apply();
        if (restyle) rebuild();
    }

    /** copy the synced look into every game that follows it, right now (otherwise it happens on each launch) */
    void applyLookToAll() {
        if (!rootOk || busy) return;
        List<Game> snapshot;
        synchronized (games) { snapshot = new ArrayList<>(games); }
        String gj = globalCfg.root.toString();
        setBusy(true);
        log("── apply the synced look to every game");
        io.execute(() -> {
            int n = 0, skipped = 0, running = 0;
            for (Game g : snapshot) {
                if (!dev.vrfrida.Settings.load(this, g.ai.packageName).followGlobal) { skipped++; continue; }
                if (Core.applyLook(g.ai.packageName, gj)) { n++; if (g.injected) running++; }
            }
            log("synced look written to " + n + " game(s)" + (skipped > 0 ? ", " + skipped + " keep their own look" : "")
                    + (running > 0 ? ". " + running + " have the menu running and may overwrite it, unload or relaunch them" : ""));
            int fn = n;
            ui.post(() -> {
                setBusy(false);
                if (sel != null && tab == 3 && lookScope == 1) loadGameCfg(sel);
                if (fn == 0 && globalCfg.menu().length() == 0) log("nothing to apply yet: change something under All games first");
            });
        });
    }

    // ================================================================ which look is showing
    String appTheme() { return appLook.root.optString("appTheme", "auto"); }   // auto | meta | uxpatcher
    boolean animateBg() { return appLook.root.optBoolean("animate", true); }

    /** the settings the window background follows: the synced look, or the selected game's while editing that */
    MenuCfg shownCfg() {
        if (tab == 3 && lookScope == 1 && gameCfg != null) return gameCfg;
        return globalCfg;
    }

    String shownThemeId() { return shownCfg().str("theme"); }

    /** the menu's own colors (for the menu preview in the Menu Look tab only) */
    Map<String, float[]> menuColorsNow() {
        double speed = shownCfg().root.optDouble("rainbowSpeed", 0.05);
        return look.colors(shownThemeId(), this, (float) speed);
    }

    Map<String, Integer> S;               // Material 3 roles (M3.scheme): UXpatcher's colors, or Meta's

    /** app colors: Material 3, from UXpatcher when it's installed (or chosen), else Meta blue */
    Map<String, float[]> colorsNow() {
        S = M3.scheme(this, appTheme());
        return M3.legacy(S);
    }

    int m3(String role) { Integer c = S.get(role); return c == null ? 0xFFFF00FF : c; }

    int col(String key) { return MenuLook.argb(C.get(key)); }
    int col(String key, float alphaMul) { return MenuLook.argb(C.get(key), alphaMul); }

    // ================================================================ jobs
    interface Job { void run(Game g) throws Exception; }

    void job(String what, Job j) { if (rootOk) job(what, j, false); }

    /** keepBusy: the job hands off to the install flow, which clears busy itself when it ends */
    void job(String what, Job j, boolean keepBusy) {
        if (sel == null || busy) return;
        cfg.save(this, sel.ai.packageName);
        flushSave();
        Game g = sel;
        setBusy(true);
        log("── " + what + ": " + g.label);
        // the settings.json save runs on io; wait for it so the menu reads the new settings
        io.execute(() -> bg.execute(() -> {
            boolean ok = false;
            try { j.run(g); ok = true; }
            catch (Exception e) { log("error: " + e.getMessage()); }
            finally { if (!(ok && keepBusy)) ui.post(() -> setBusy(false)); refreshStatus(); }
        }));
    }

    void launchAndInject() {
        job("launch + inject", g -> {
            File agent = core.prepare(g.ai, cfg, false);
            if (cfg.authSpoof) { core.startSpoof(true); log("auth spoof armed (appsafety)"); }
            core.startInjector(g.ai.packageName, agent, cfg.delay, cfg.v8);
            log("launching " + g.label + ", the menu loads " + cfg.delay + "s after it opens");
            core.launch(g.ai.packageName);
        });
    }

    void injectNow() {
        job("inject now", g -> {
            if (core.pidOf(g.ai.packageName).isEmpty()) throw new Exception(g.label + " isn't running, use Launch + inject");
            File agent = core.prepare(g.ai, cfg, false);
            if (cfg.authSpoof) { core.startSpoof(true); log("auth spoof armed (appsafety)"); }
            core.startInjector(g.ai.packageName, agent, 0, cfg.v8);
            log("injecting now, switch back to the game");
        });
    }

    /** no root: patch every apk of the game with Frida Gadget, then uninstall (first time) + install */
    void patchAndInstall() {
        if (sel == null || busy) return;
        if (!noRoot.canInstall()) {
            log("allow \"Install unknown apps\" for vrfrida, then press Patch + install again");
            noRoot.openInstallPermission();
            return;
        }
        Game target = sel;
        Runnable go = () -> job("patch + install (no root)", g -> {
            String pkg = g.ai.packageName;
            boolean update = noRoot.signedByUs(pkg);
            List<File> apks = noRoot.patch(core, g.ai, cfg);
            log(update ? "installed copy is already ours: installing as an update (game data kept)" : "patched, now the store copy has to go");
            ui.post(() -> noRoot.install(pkg, apks, !update));
        }, true);
        if (noRoot.signedByUs(target.ai.packageName)) { go.run(); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Patch " + target.label + "?")
                .setMessage("The patched game has a different signature, so after patching the store copy is uninstalled first.\n\n" +
                        "Android deletes the game's local data with it (offline progress, settings). Anything saved to your account online stays. " +
                        "The OBB folder is moved aside and put back.\n\nA store update later replaces the patched game: just patch again.")
                .setPositiveButton("Patch", (dl, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (noRoot != null) noRoot.onActivityResult(req);
    }

    @Override protected void onDestroy() {
        if (noRoot != null) noRoot.close();
        if (updater != null) updater.close();
        super.onDestroy();
    }

    /** at most every 6 hours, on its own thread so a slow network never holds up the games scan */
    void autoCheckUpdate() {
        SharedPreferences sp = dev.vrfrida.Settings.prefs(this);
        long now = System.currentTimeMillis();
        if (Updater.repo(this).isEmpty() || now - sp.getLong("updateCheckedAt", 0) < 6 * 3600_000L) return;
        sp.edit().putLong("updateCheckedAt", now).apply();
        new Thread(() -> { Updater.Release r = updater.check(true); if (r != null) ui.post(() -> { update = r; if (tab == 4) rebuild(); }); }).start();
    }

    void checkUpdate() {
        dev.vrfrida.Settings.prefs(this).edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply();
        log("checking github.com/" + Updater.repo(this) + " for updates");
        new Thread(() -> { Updater.Release r = updater.check(false); ui.post(() -> { update = r; if (tab == 4) rebuild(); }); }).start();
    }

    void installUpdate() {
        Updater.Release r = update;
        if (r == null || busy) return;
        setBusy(true);
        bg.execute(() -> {
            try { updater.update(r); }
            catch (Exception e) { log("update failed: " + e.getMessage()); }
            finally { ui.post(() -> setBusy(false)); }
        });
    }

    View updatesBox() {
        LinearLayout d = vbox();
        d.addView(section("Updates  ·  version " + updater.installedVersion()));
        EditText repo = new EditText(this);
        repo.setSingleLine(true);
        repo.setHint("owner/repo on GitHub");
        repo.setText(Updater.repo(this));
        repo.setTextSize(15);
        repo.setTextColor(col("Text"));
        repo.setHintTextColor(col("TextDisabled"));
        repo.setBackground(rr(m3("surfaceContainerHighest"), 12));
        repo.setPadding(dp(16), dp(12), dp(16), dp(12));
        repo.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { Updater.setRepo(MainActivity.this, s.toString()); }
        });
        d.addView(row("Update repo", repo));
        LinearLayout r = hbox();
        r.addView(button("Check for updates", update == null, v -> checkUpdate(), false), lpm(0, -2, 1, 3));
        if (update != null) r.addView(button("Update to " + update.tag, true, v -> installUpdate(), true), lpm(0, -2, 1, 3));
        d.addView(r);
        return d;
    }

    void setBusy(boolean b) {
        busy = b;
        if (!b) progressValue = Float.NaN;
        for (View x : actions) { x.setEnabled(!b); x.setAlpha(b ? 0.38f : 1f); }
        showProgress();
    }

    // ================================================================ log
    void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        synchronized (appLog) {
            appLog.append(line).append('\n');
            if (appLog.length() > 40000) appLog.delete(0, appLog.length() - 30000);
        }
        ui.post(this::refreshLogs);
    }

    String logText(int maxGameLog) {
        StringBuilder sb = new StringBuilder();
        synchronized (appLog) { sb.append(appLog); }
        if (sel != null) {
            File f = Paths.log(sel.ai.packageName);
            if (f.isFile()) sb.append("\n── injector / menu log ──\n").append(Files.tail(f, maxGameLog));
        }
        return sb.toString();
    }

    String logSig = "";

    /** only reads the log file when a log is on screen and the app log or the file changed */
    void refreshLogs() {
        if (logView == null && miniLog == null) return;
        File f = sel != null ? Paths.log(sel.ai.packageName) : null;
        int len;
        synchronized (appLog) { len = appLog.length(); }
        String sig = len + "|" + (f != null ? f.lastModified() + ":" + f.length() : "") + "|" + (logView != null) + (sel != null ? sel.ai.packageName : "");
        if (sig.equals(logSig)) return;
        logSig = sig;
        if (logView != null) {
            String t = logText(16000);
            boolean atEnd = tabScroll != null && tabScroll.getScrollY() + tabScroll.getHeight() >= logView.getHeight() - 60;
            logView.setText(t);
            if (atEnd) tabScroll.post(() -> tabScroll.fullScroll(View.FOCUS_DOWN));
        }
        if (miniLog != null) {
            String[] lines = logText(4000).split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 9); i < lines.length; i++) sb.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
            miniLog.setText(sb);
        }
    }

    void setStatus(String s) { status = s; ui.post(this::updateStatusLine); }

    void updateStatusLine() {
        if (statusView == null) return;
        String extra = tab == 3 && lookScope == 1 && sel != null ? "  ·  " + gameCfgState : "";
        statusView.setText(status + extra);
    }

    // ================================================================ widgets in the menu's style
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    float rounding() { return 12f; }      // M3 medium shape

    GradientDrawable rr(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    GradientDrawable rrKey(String key) { return rr(col(key), 12); }

    /** pressed / hover feedback like M3 state layers */
    Drawable ripple(Drawable content, int onColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        if (content instanceof GradientDrawable) mask.setCornerRadii(null);
        if (content instanceof GradientDrawable) { try { mask.setCornerRadius(((GradientDrawable) content).getCornerRadius()); } catch (Throwable ignored) {} }
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((onColor & 0x00FFFFFF) | 0x24000000), content, mask);
    }

    Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    /** a Material icon from res/drawable/ic_<name>.xml, tinted */
    Drawable icon(String name, int color, int sizeDp) {
        int id = getResources().getIdentifier("ic_" + name, "drawable", getPackageName());
        if (id == 0) return null;
        Drawable d = getResources().getDrawable(id, getTheme()).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    ImageView iconView(String name, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(icon(name, color, sizeDp));
        v.setScaleType(ImageView.ScaleType.CENTER);
        return v;
    }

    /** M3 button with a leading 18dp icon in the button's text color */
    <T extends TextView> T withIcon(T b, String name) {
        Drawable d = icon(name, b.getCurrentTextColor(), 18);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(8));
        b.setPadding(dp(16), b.getPaddingTop(), dp(20), b.getPaddingBottom());
        return b;
    }

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }

    LinearLayout.LayoutParams lpm(int w, int h, float weight, int mdp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(mdp), dp(mdp), dp(mdp), dp(mdp));
        return p;
    }

    /** a horizontal row of buttons that stacks them (full width) when they don't fit side by side */
    LinearLayout hbox() { return new AdaptiveRow(0); }

    /**
     * Horizontal LinearLayout that turns vertical when it gets narrow: minWidthPx > 0 = below that width,
     * 0 = when its children's natural widths don't fit (only if they are all buttons; other rows never switch).
     * Vertical mode gives every child the full width; switching back restores their layout params.
     */
    final class AdaptiveRow extends LinearLayout {
        final int minWidthPx;
        final Map<View, LinearLayout.LayoutParams> orig = new java.util.HashMap<>();
        boolean vertical;
        AdaptiveRow(int minWidthPx) { super(MainActivity.this); this.minWidthPx = minWidthPx; setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            boolean want = false;
            if (MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED && getChildCount() > 1) {
                if (minWidthPx > 0) want = w < minWidthPx;
                else {
                    int sum = 0;
                    boolean allButtons = true;
                    for (int i = 0; i < getChildCount(); i++) {
                        View c = getChildAt(i);
                        if (c.getVisibility() == GONE) continue;
                        if (!"btn".equals(c.getTag())) { allButtons = false; break; }
                        c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        LinearLayout.LayoutParams lp = orig.containsKey(c) ? orig.get(c) : (LinearLayout.LayoutParams) c.getLayoutParams();
                        sum += c.getMeasuredWidth() + (lp == null ? 0 : lp.leftMargin + lp.rightMargin);
                    }
                    want = allButtons && sum > w;
                }
            }
            if (want != vertical) apply(want);
            super.onMeasure(ws, hs);
        }

        void apply(boolean v) {
            vertical = v;
            setOrientation(v ? VERTICAL : HORIZONTAL);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (v) {
                    LinearLayout.LayoutParams o = (LinearLayout.LayoutParams) c.getLayoutParams();
                    if (o == null) o = new LinearLayout.LayoutParams(-2, -2);
                    orig.put(c, o);
                    LinearLayout.LayoutParams n = new LinearLayout.LayoutParams(-1, -2, 0);
                    n.setMargins(o.leftMargin, Math.max(o.topMargin, dp(2)), o.rightMargin, Math.max(o.bottomMargin, dp(2)));
                    c.setLayoutParams(n);
                } else if (orig.containsKey(c)) c.setLayoutParams(orig.get(c));
            }
            if (!v) orig.clear();
        }
    }
    LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    /** M3 button: primary = filled (primary / onPrimary), otherwise tonal (secondaryContainer / onSecondaryContainer). pill shaped */
    TextView button(String label, boolean primary, View.OnClickListener l, boolean action) {
        int bg = primary ? m3("primary") : m3("secondaryContainer"), fg = primary ? m3("onPrimary") : m3("onSecondaryContainer");
        TextView b = text(label, 14, fg);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setTypeface(medium());
        b.setLetterSpacing(0.01f);
        b.setBackground(ripple(rr(bg, 20), fg));
        b.setOnClickListener(l);
        b.setClickable(true);
        b.setFocusable(true);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setTag("btn");
        if (action) { actions.add(b); if (busy) { b.setEnabled(false); b.setAlpha(0.38f); } }
        return b;
    }

    /** destructive action: filled error */
    TextView dangerButton(String label, View.OnClickListener l) {
        TextView b = button(label, false, l, true);
        b.setBackground(ripple(rr(m3("errorContainer"), 20), m3("onErrorContainer")));
        b.setTextColor(m3("onErrorContainer"));
        return b;
    }

    /** M3 outlined button */
    TextView outlinedButton(String label, View.OnClickListener l, boolean action) {
        TextView b = button(label, false, l, action);
        GradientDrawable g = rr(0, 20);
        g.setStroke(dp(1), m3("outline"));
        b.setBackground(ripple(g, m3("primary")));
        b.setTextColor(m3("primary"));
        return b;
    }

    /** M3 section header: title-small in primary */
    View section(String title) {
        TextView t = text(title, 14, m3("primary"));
        t.setTypeface(medium());
        t.setLetterSpacing(0.01f);
        t.setPadding(dp(4), dp(20), 0, dp(6));
        return t;
    }

    /** M3 list item: label on the left, control on the right, surfaceContainer card */
    LinearLayout row(String label, View control) {
        LinearLayout r = new AdaptiveRow(dp(520));
        r.setBackground(rr(m3("surfaceContainer"), 12));
        r.setPadding(dp(16), dp(6), dp(12), dp(6));
        r.setMinimumHeight(dp(56));
        TextView l = text(label, 15, m3("onSurface"));
        r.addView(l, lp(0, -2, 0.40f));
        r.addView(control, lp(0, -2, 0.60f));
        LinearLayout.LayoutParams p = lp(-1, -2, 0);
        p.setMargins(0, dp(3), 0, 0);
        r.setLayoutParams(p);
        return r;
    }

    /** M3 switch (52 x 32 track, thumb grows when on), description after it */
    View checkbox(boolean on, String desc, java.util.function.Consumer<Boolean> set) {
        LinearLayout box = hbox();
        final boolean[] v = {on};
        final float[] anim = {on ? 1f : 0f};
        View sw = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), k = anim[0];
                float target = v[0] ? 1f : 0f;
                if (Math.abs(target - k) > 0.01f) { anim[0] += (target - k) * 0.35f; postInvalidateOnAnimation(); } else anim[0] = target;
                k = anim[0];
                android.graphics.RectF track = new android.graphics.RectF(dp(1), dp(1), w - dp(1), h - dp(1));
                p.setStyle(Paint.Style.FILL);
                p.setColor(M3.mix(m3("surfaceContainerHighest"), m3("primary"), k));
                c.drawRoundRect(track, h / 2, h / 2, p);
                if (k < 0.99f) {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2));
                    p.setColor((m3("outline") & 0x00FFFFFF) | ((int) (255 * (1 - k)) << 24));
                    c.drawRoundRect(track, h / 2, h / 2, p);
                }
                float r = dp(8) + dp(4) * k, cx = h / 2 + (w - h) * k;
                p.setStyle(Paint.Style.FILL);
                p.setColor(M3.mix(m3("outline"), m3("onPrimary"), k));
                c.drawCircle(cx, h / 2, r, p);
                if (k > 0.5f) {   // check mark on the thumb
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(m3("primary"));
                    Path t = new Path();
                    t.moveTo(cx - dp(4.5f), h / 2); t.lineTo(cx - dp(1.2f), h / 2 + dp(3.2f)); t.lineTo(cx + dp(4.8f), h / 2 - dp(3.5f));
                    c.drawPath(t, p);
                }
            }
        };
        box.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(32)));
        if (desc != null) {
            TextView d = text(desc, 13, m3("onSurfaceVariant"));
            d.setPadding(dp(14), 0, 0, 0);
            box.addView(d, lp(0, -2, 1));
        }
        box.setBackground(ripple(rr(0, 16), m3("onSurface")));
        box.setOnClickListener(x -> { v[0] = !v[0]; sw.invalidate(); set.accept(v[0]); });
        return box;
    }

    /** slider: FrameBg track, SliderFill, SliderGrab thumb, value on the right */
    View slider(double value, double min, double max, int decimals, java.util.function.Consumer<Double> set) {
        LinearLayout box = hbox();
        SeekBar sb = new SeekBar(this);
        int steps = decimals == 0 ? (int) Math.round(max - min) : (int) Math.round((max - min) * Math.pow(10, decimals));
        sb.setMax(Math.max(1, steps));
        sb.setProgress((int) Math.round((value - min) / (max - min) * sb.getMax()));
        sb.setProgressTintList(ColorStateList.valueOf(m3("primary")));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(m3("secondaryContainer")));
        sb.setThumbTintList(ColorStateList.valueOf(m3("primary")));
        TextView val = text(fmt(value, decimals), 14, col("Text"));
        val.setGravity(Gravity.END);
        box.addView(sb, lp(0, -2, 1));
        box.addView(val, lp(dp(60), -2, 0));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                double v = min + (max - min) * p / s.getMax();
                v = decimals == 0 ? Math.round(v) : Math.round(v * Math.pow(10, decimals)) / Math.pow(10, decimals);
                val.setText(fmt(v, decimals));
                set.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return box;
    }

    static String fmt(double v, int decimals) { return decimals == 0 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%." + decimals + "f", v); }

    /** combo: FrameBg box with the value, opens a PopupBg list */
    View combo(String[] labels, int index, java.util.function.Consumer<Integer> set) {
        TextView t = text((index >= 0 && index < labels.length ? labels[index] : "?") + "   ▾", 15, m3("onSurface"));
        t.setBackground(ripple(rr(m3("surfaceContainerHighest"), 12), m3("onSurface")));
        t.setPadding(dp(16), dp(10), dp(14), dp(10));
        t.setOnClickListener(anchor -> {
            LinearLayout list = vbox();
            list.setPadding(dp(4), dp(4), dp(4), dp(4));
            ScrollView sv = new ScrollView(this);
            sv.addView(list);
            sv.setBackground(rr(m3("surfaceContainer"), 12));
            PopupWindow pw = new PopupWindow(sv, Math.max(anchor.getWidth(), dp(220)), Math.min(dp(44) * labels.length + dp(12), dp(420)), true);
            pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            pw.setElevation(dp(8));
            for (int i = 0; i < labels.length; i++) {
                final int k = i;
                TextView o = text(labels[i], 15, i == index ? m3("onSecondaryContainer") : m3("onSurface"));
                o.setPadding(dp(16), dp(12), dp(16), dp(12));
                o.setBackground(ripple(rr(i == index ? m3("secondaryContainer") : 0, 8), m3("onSurface")));
                o.setOnClickListener(x -> { pw.dismiss(); set.accept(k); });
                list.addView(o, lp(-1, -2, 0));
            }
            pw.showAsDropDown(anchor);
        });
        return t;
    }

    // ================================================================ build
    void rebuild() {
        if (tabScroll != null) scrollY[tab] = tabScroll.getScrollY();
        C = colorsNow();
        actions.clear();
        logView = null; miniLog = null; gameList = null; detailHeader = null;
        logSig = "";

        LinearLayout root = hbox();
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(m3("surface"));
        root.addView(navRail(), lp(dp(96), -1, 0));

        LinearLayout content = vbox();
        content.addView(titleBar());
        topProgress = new LinearProgress();
        content.addView(topProgress, lp(-1, dp(4), 0));
        showProgress();
        tabScroll = new ScrollView(this);
        tabScroll.setFillViewport(true);
        tabScroll.setVerticalScrollBarEnabled(true);
        View body = tab == 0 ? gamesTab() : tab == 1 ? pluginsTab() : tab == 2 ? storeTab() : tab == 3 ? lookTab() : logTab();
        tabScroll.addView(body);
        tabScroll.setPadding(dp(16), dp(8), dp(20), dp(16));
        tabScroll.setClipToPadding(false);
        content.addView(tabScroll, lp(-1, 0, 1));
        root.addView(content, lp(0, -1, 1));
        setContentView(root);
        final int y = scrollY[tab];
        tabScroll.post(() -> tabScroll.scrollTo(0, y));
        refreshLogs();
        updateStatusLine();
    }

    Bitmap logo;

    /** M3 top app bar: title, status as supporting text, Refresh */
    View titleBar() {
        LinearLayout t = hbox();
        t.setPadding(dp(16), dp(12), dp(16), dp(8));
        if (logo == null) {
            File f = new File(Paths.PLUGINS, "rift_menu_logo.png");
            if (f.isFile()) logo = BitmapFactory.decodeFile(f.getPath());
        }
        if (logo != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setPadding(0, 0, dp(12), 0);
            t.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(32)));
        }
        LinearLayout tt = vbox();
        TextView name = text(TABS[tab], 22, m3("onSurface"));
        tt.addView(name);
        statusView = text(status, 13, m3("onSurfaceVariant"));
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.addView(statusView);
        t.addView(tt, lp(0, -2, 1));
        // Refresh also picks up a new PC sync (logo, patterns, plugins) without restarting the app
        t.addView(withIcon(outlinedButton("Refresh", v -> {
            logo = null; look.bitmaps.clear();
            io.execute(this::scanGames);
            if (tab == 2) loadStore();
        }, false), "refresh"));
        return t;
    }

    static final String[] TAB_ICONS = {"games", "extension", "store", "palette", "article"};

    /** M3 navigation rail: icon in a pill indicator, label under it */
    View navRail() {
        LinearLayout rail = vbox();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setBackgroundColor(m3("surfaceContainer"));
        rail.setPadding(0, dp(14), 0, dp(14));
        TextView brand = text("vrfrida", 13, m3("primary"));
        brand.setTypeface(medium());
        brand.setGravity(Gravity.CENTER);
        rail.addView(brand, lp(-1, -2, 0));
        View sp = new View(this);
        rail.addView(sp, lp(1, dp(18), 0));
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            boolean on = i == tab;
            LinearLayout item = vbox();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, dp(6), 0, dp(10));
            FrameLayout pill = new FrameLayout(this);
            pill.setBackground(ripple(rr(on ? m3("secondaryContainer") : 0, 16), m3("onSurface")));
            ImageView ic = iconView(TAB_ICONS[i], on ? m3("onSecondaryContainer") : m3("onSurfaceVariant"), 24);
            pill.addView(ic, new FrameLayout.LayoutParams(-1, -1));
            if (i == 2 && storeWaiting > 0) {
                TextView b = text(String.valueOf(storeWaiting), 10, m3("onError"));
                b.setGravity(Gravity.CENTER);
                b.setTypeface(medium());
                b.setBackground(rr(m3("error"), 8));
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.END);
                bp.setMargins(0, dp(1), dp(8), 0);
                pill.addView(b, bp);
            }
            item.addView(pill, new LinearLayout.LayoutParams(dp(56), dp(32)));
            TextView lb = text(TABS[i], 12, on ? m3("onSurface") : m3("onSurfaceVariant"));
            lb.setGravity(Gravity.CENTER);
            if (on) lb.setTypeface(medium());
            lb.setPadding(0, dp(4), 0, 0);
            item.addView(lb, lp(-1, -2, 0));
            item.setOnClickListener(x -> goTab(k));
            pill.setOnClickListener(x -> goTab(k));
            rail.addView(item, lp(-1, -2, 0));
        }
        return rail;
    }

    // ---------------------------------------------------------------- progress (M3 linear progress indicator)
    LinearProgress topProgress;
    volatile float progressValue = Float.NaN;   // NaN = none, < 0 = indeterminate, 0..1 = determinate

    /** show the top bar: <0 indeterminate, 0..1 done fraction, NaN = only while busy / loading */
    void progress(float p) { progressValue = p; ui.post(this::showProgress); }

    void showProgress() {
        if (topProgress == null) return;
        float p = progressValue;
        if (Float.isNaN(p) && (busy || storeLoading)) p = -1;
        topProgress.set(p);
    }

    final class LinearProgress extends View {
        float value = Float.NaN;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final long t0 = System.currentTimeMillis();
        LinearProgress() { super(MainActivity.this); }
        void set(float v) { value = v; setVisibility(Float.isNaN(v) ? View.INVISIBLE : View.VISIBLE); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            if (Float.isNaN(value)) return;
            float w = getWidth(), h = getHeight(), r = h / 2, gap = dp(4);
            paint.setColor(m3("secondaryContainer"));
            if (value >= 0) {
                float x = Math.max(h, w * Math.min(1, value));
                paint.setColor(m3("primary"));
                c.drawRoundRect(0, 0, x, h, r, r, paint);
                paint.setColor(m3("secondaryContainer"));
                if (x + gap < w) c.drawRoundRect(x + gap, 0, w, h, r, r, paint);
            } else {
                // indeterminate: a bar that grows / slides across, track in the gaps
                float t = ((System.currentTimeMillis() - t0) % 1800) / 1800f;
                float a = w * (float) Math.max(0, Math.min(1, (t - 0.0) * 1.6 - 0.3)), b = w * (float) Math.min(1, t * 1.6);
                c.drawRoundRect(0, 0, w, h, r, r, paint);
                paint.setColor(m3("primary"));
                if (b - a > h) c.drawRoundRect(a, 0, b, h, r, r, paint);
                postInvalidateOnAnimation();
            }
        }
    }

    void goTab(int k) {
        if (tab == k) return;
        flushSave();
        if (tabScroll != null) scrollY[tab] = tabScroll.getScrollY();
        tab = k;
        rebuild();
    }

    // ---------------------------------------------------------------- Games tab
    View gamesTab() {
        LinearLayout split = new AdaptiveRow(dp(760));
        split.setGravity(Gravity.TOP);
        LinearLayout left = vbox();
        EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint("search games");
        q.setText(search);
        q.setTextSize(15);
        q.setTextColor(col("Text"));
        q.setHintTextColor(col("TextDisabled"));
        q.setBackground(rr(m3("surfaceContainerHighest"), 12));
        q.setPadding(dp(16), dp(12), dp(16), dp(12));
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { search = s.toString(); fillGameList(); }   // no rebuild: keeps the keyboard up
        });
        left.addView(q, lpm(-1, -2, 0, 2));
        gameList = vbox();
        left.addView(gameList);
        fillGameList();
        split.addView(left, lpm(0, -2, 0.9f, 0));
        View gap = new View(this);
        split.addView(gap, lp(dp(12), dp(12), 0));
        split.addView(gameDetail(), lp(0, -2, 1.6f));
        return split;
    }

    void fillGameList() {
        if (gameList == null) return;
        gameList.removeAllViews();
        List<Game> snapshot;
        synchronized (games) { snapshot = new ArrayList<>(games); }
        if (snapshot.isEmpty()) { gameList.addView(text("no Unity il2cpp games found", 14, col("TextDisabled"))); return; }
        String s = search.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (Game g : snapshot) {
            if (!s.isEmpty() && !g.label.toLowerCase(Locale.US).contains(s) && !g.ai.packageName.toLowerCase(Locale.US).contains(s)) continue;
            gameList.addView(gameRow(g));
            shown++;
        }
        if (shown == 0) gameList.addView(text("no game matches \"" + search + "\"", 14, col("TextDisabled")));
    }

    View gameRow(Game g) {
        boolean s = g == sel;
        LinearLayout r = hbox();
        int onBg = s ? m3("onSecondaryContainer") : m3("onSurface");
        r.setBackground(ripple(rr(s ? m3("secondaryContainer") : m3("surfaceContainerLow"), 16), onBg));
        r.setPadding(dp(12), dp(8), dp(4), dp(8));
        ImageView icon = new ImageView(this);
        if (g.icon != null) icon.setImageBitmap(g.icon);
        icon.setClipToOutline(true);
        icon.setBackground(rr(0xFF000000, 10));
        r.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout col = vbox();
        col.setPadding(dp(12), 0, 0, 0);
        TextView name = text(g.label, 15, onBg);
        name.setTypeface(medium());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        String st = g.injected ? "● menu loaded" : g.running ? "running" : g.patched ? "patched (no root)" + (rootOk ? "" : "  ·  bridge " + g.bridge) : "bridge " + g.bridge;
        int sc = g.injected ? m3("success") : s ? M3.mix(onBg, m3("secondaryContainer"), 0.25) : g.running || g.bridge.equals("fresh") || g.patched ? m3("onSurfaceVariant") : m3("warning");
        col.addView(text(st, 12, sc));
        r.addView(col, lp(0, -2, 1));
        ImageView star = iconView(g.fav ? "star" : "star_border", g.fav ? m3("warning") : m3("onSurfaceVariant"), 22);
        star.setPadding(dp(10), dp(8), dp(10), dp(8));
        star.setBackground(ripple(rr(0, 20), m3("onSurface")));
        star.setOnClickListener(v -> {
            g.fav = !g.fav;
            dev.vrfrida.Settings.setFavorite(this, g.ai.packageName, g.fav);
            synchronized (games) { sortGames(games); }
            fillGameList();
        });
        r.addView(star);
        r.setOnClickListener(v -> select(g));
        LinearLayout.LayoutParams p = lp(-1, -2, 0);
        p.setMargins(0, dp(3), 0, dp(3));
        r.setLayoutParams(p);
        return r;
    }

    TextView detailHeader;

    void refreshDetailHeader() {
        if (detailHeader != null && sel != null) detailHeader.setText(detailLine(sel));
    }

    String detailLine(Game g) {
        return g.ai.packageName + "   ·   bridge " + g.bridge + (g.patched ? "   ·   Frida Gadget patched" : "") + (g.injected ? "   ·   menu loaded" : g.running ? "   ·   running" : "");
    }

    View gameDetail() {
        LinearLayout d = vbox();
        if (sel == null) { d.addView(text("pick a game", 15, col("TextDisabled"))); return d; }
        TextView t = text(sel.label, 24, m3("onSurface"));
        d.addView(t);
        detailHeader = text(detailLine(sel), 13, m3("onSurfaceVariant"));
        d.addView(detailHeader);

        LinearLayout r1 = hbox();
        r1.setPadding(0, dp(8), 0, 0);
        r1.addView(withIcon(button("Launch + inject", true, v -> launchAndInject(), true), "play"), lpm(0, -2, 1.4f, 3));
        r1.addView(withIcon(button("Inject now", false, v -> injectNow(), true), "bolt"), lpm(0, -2, 1, 3));
        r1.addView(withIcon(dangerButton("Unload menu", v -> job("unload", g -> core.stopInjector(g.ai.packageName, true))), "stop"), lpm(0, -2, 1, 3));
        d.addView(r1);
        LinearLayout r2 = hbox();
        r2.addView(button("Plugins ›", false, v -> goTab(1), false), lpm(0, -2, 1, 3));
        r2.addView(button("Menu look ›", false, v -> { lookScope = cfg.followGlobal ? 0 : 1; goTab(3); }, false), lpm(0, -2, 1, 3));
        r2.addView(withIcon(button("Rebuild bridge", false, v -> job("rebuild bridge", g -> Bridge.ensure(this, g.ai, true, this::log)), true), "build"), lpm(0, -2, 1, 3));
        d.addView(r2);

        if (!rootOk) {
            TextView w = text("No root: Launch + inject needs Magisk. Use No root (Frida Gadget) below instead.", 13, col("NotifWarn"));
            w.setPadding(0, dp(6), 0, 0);
            d.addView(w);
        }
        d.addView(section("No root (Frida Gadget)"));
        LinearLayout r3 = hbox();
        r3.addView(withIcon(button(sel.patched ? "Repatch + update" : "Patch + install", !rootOk, v -> patchAndInstall(), true), "build"), lpm(0, -2, 1.4f, 3));
        r3.addView(withIcon(button("Update mods", false, v -> job("update mods (no root)", g -> noRoot.updateAgent(core, g.ai, cfg), false), true), "sync"), lpm(0, -2, 1, 3));
        d.addView(r3);
        TextView nr = text(sel.patched
                ? "Patched: the menu starts by itself in this game. Repatch after changing plugins / menu or after a store update (installs as an update, keeps game data)."
                : "Copies the game, adds Frida Gadget + the menu, re-signs it and installs it. The first time the store copy is uninstalled (Android deletes its local data; online saves stay). Uses Menu Delay and V8 from below.", 12, col("TextDisabled"));
        nr.setPadding(0, dp(2), 0, 0);
        d.addView(nr);

        d.addView(section("Launch options"));
        String pkg = sel.ai.packageName;
        d.addView(row("Menu Delay (s)", slider(cfg.delay, 0, 120, 0, v -> { cfg.delay = (int) Math.round(v); cfg.save(this, pkg); })));
        d.addView(row("Synced look", checkbox(cfg.followGlobal, "use the All games look from the Menu Look tab", v -> { cfg.followGlobal = v; cfg.save(this, pkg); })));
        d.addView(row("Auth spoof", checkbox(cfg.authSpoof, "spoof root in appsafety so games authenticate (needed by most)", v -> { cfg.authSpoof = v; cfg.save(this, pkg); if (!v && rootOk) io.execute(() -> core.stopSpoof(true)); })));
        d.addView(row("V8 Runtime", checkbox(cfg.v8, "faster than quickjs for heavy hooks", v -> { cfg.v8 = v; cfg.save(this, pkg); })));
        d.addView(row("Push Sounds", checkbox(cfg.sounds, "soundboard clips, converted on the headset", v -> { cfg.sounds = v; cfg.save(this, pkg); })));
        d.addView(row("Push Patterns", checkbox(cfg.patterns, "menu background pictures", v -> { cfg.patterns = v; cfg.save(this, pkg); })));

        d.addView(section("Log"));
        miniLog = text("", 12, col("Text"));
        miniLog.setTypeface(Typeface.MONOSPACE);
        miniLog.setBackground(rr(m3("surfaceContainerLowest"), 12));
        miniLog.setPadding(dp(10), dp(8), dp(10), dp(8));
        miniLog.setOnClickListener(v -> goTab(4));
        LinearLayout.LayoutParams lp = lp(-1, -2, 0);
        lp.setMargins(0, dp(4), 0, 0);
        d.addView(miniLog, lp);
        return d;
    }

    // ---------------------------------------------------------------- Plugins tab
    /** the comment line under the game: / soundboard: lines, as a one-line description */
    static String pluginDesc(File f) {
        for (String l : Files.head(f, 12).split("\n")) {
            String s = l.trim();
            if (!s.startsWith("//") && !s.startsWith("*") && !s.startsWith("/*")) continue;
            s = s.replaceAll("^(/\\*+|\\*+|//+)\\s*", "").trim();
            if (s.isEmpty() || s.matches("(?i)^(games?|soundboard)\\s*[:=].*") || s.matches("^[╔╗╚╝═║\\s]*$")) continue;
            s = s.replaceAll("[║╔╗╚╝]", "").trim();
            if (s.length() > 110) s = s.substring(0, 107) + "...";
            return s;
        }
        return "";
    }

    View pluginsTab() {
        LinearLayout d = vbox();
        if (sel == null) { d.addView(text("pick a game in the Games tab first", 15, col("TextDisabled"))); return d; }
        String pkg = sel.ai.packageName;
        List<File> mine = Core.pluginsFor(pkg, null, null);
        Set<String> mineNames = new java.util.HashSet<>();
        for (File f : mine) mineNames.add(f.getName());
        int on = 0;
        for (File f : mine) if (!cfg.disabledPlugins.contains(f.getName())) on++;

        TextView t = text(sel.label, 20, m3("onSurface"));
        d.addView(t);
        LinearLayout head = hbox();
        head.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(button("All on", false, v -> { cfg.disabledPlugins.clear(); cfg.save(this, pkg); rebuild(); }, false), lpm(-2, -2, 0, 3));
        head.addView(button("All off", false, v -> { for (File f : mine) cfg.disabledPlugins.add(f.getName()); cfg.save(this, pkg); rebuild(); }, false), lpm(-2, -2, 0, 3));
        d.addView(head);
        TextView h = text(on + " of " + mine.size() + " on for this game. Changes apply on the next Launch / Inject (plugins you turn off are removed from the game then)."
                + (sel.injected ? " The menu is running now: unload + inject again to apply." : ""), 13, col("TextDisabled"));
        h.setPadding(dp(4), dp(4), 0, 0);
        d.addView(h);

        d.addView(section("For " + sel.label));
        if (mine.isEmpty()) d.addView(text("no plugin has a \"// game: " + pkg + "\" line. The menu's built-in tabs still work.", 14, col("TextDisabled")));
        for (File f : mine) {
            String n = f.getName();
            boolean enabled = !cfg.disabledPlugins.contains(n);
            String desc = pluginDesc(f);
            d.addView(row(n.replaceAll("\\.js$", ""), checkbox(enabled, (desc.isEmpty() ? "" : desc + "   ") + "(" + Math.max(1, f.length() / 1024) + " KB)", v -> {
                if (v) cfg.disabledPlugins.remove(n); else cfg.disabledPlugins.add(n);
                cfg.save(this, pkg);
            })));
        }

        List<String> others = new ArrayList<>();
        for (File f : Core.allPlugins()) if (!mineNames.contains(f.getName())) {
            List<String> gs = Core.pluginGames(f);
            others.add(f.getName() + "   ·   " + (gs.isEmpty() ? "no game line" : "for " + String.join(", ", gs)));
        }
        Collections.sort(others);
        if (!others.isEmpty()) {
            d.addView(section("Other games (" + others.size() + ")"));
            TextView o = text(String.join("\n", others), 13, col("TextDisabled"));
            o.setPadding(dp(6), dp(6), 0, 0);
            d.addView(o);
        }
        TextView w = text("Plugin files live in " + Paths.PLUGINS + " (vrfrida.py --sync on the PC updates them) and " + Paths.STORE_PLUGINS + " (Store tab).", 12, col("TextDisabled"));
        w.setPadding(dp(4), dp(10), 0, 0);
        d.addView(w);
        return d;
    }

    // ---------------------------------------------------------------- Store tab
    /** official plugins update by themselves at most every 6 h; community updates only get counted (they need review) */
    void autoStoreUpdate() {
        SharedPreferences sp = dev.vrfrida.Settings.prefs(this);
        long now = System.currentTimeMillis();
        if (Store.repo(this).isEmpty() || now - sp.getLong("storeCheckedAt", 0) < 6 * 3600_000L) return;
        sp.edit().putLong("storeCheckedAt", now).apply();
        new Thread(() -> { int w = store.autoUpdate(); ui.post(() -> { storeWaiting = w; rebuild(); }); }).start();
    }

    void loadStore() {
        if (storeLoading) return;
        storeLoading = true;
        showProgress();
        new Thread(() -> {
            store.refresh();
            int w = store.updates(false).size();
            ui.post(() -> { storeLoading = false; storeWaiting = w; showProgress(); if (tab == 2) rebuild(); });
        }).start();
    }

    int sevColor(String sev) { return sev.equals("high") ? col("NotifError") : sev.equals("medium") ? col("NotifWarn") : col("TextDisabled"); }

    /** M3 label chip: container color + on-container text */
    TextView badge(String s, String container, String on) { return badge(s, container, on, null); }

    TextView badge(String s, String container, String on, String iconName) {
        TextView b = text(s, 11, m3(on));
        if (iconName != null) { b.setCompoundDrawablesRelative(icon(iconName, m3(on), 14), null, null, null); b.setCompoundDrawablePadding(dp(4)); b.setGravity(Gravity.CENTER_VERTICAL); }
        b.setTypeface(medium());
        b.setLetterSpacing(0.04f);
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        b.setBackground(rr(m3(container), 8));
        return b;
    }

    View storeTab() {
        LinearLayout d = vbox();
        LinearLayout head = hbox();
        head.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(withIcon(button(storeLoading ? "Loading..." : "Refresh list", false, v -> loadStore(), false), "sync"), lpm(-2, -2, 0, 3));
        head.addView(withIcon(button("Upload a plugin", false, v -> uploadDialog(), false), "upload"), lpm(-2, -2, 0, 3));
        d.addView(head);

        List<Store.Plugin> idx = store.index;
        if (idx == null || System.currentTimeMillis() - store.indexAt > 10 * 60_000L) loadStore();
        if (idx == null) {
            TextView l = text(store.error.isEmpty() ? "Loading plugins from github.com/" + Store.repo(this) + "..." : "Couldn't load the store: " + store.error, 14, store.error.isEmpty() ? m3("onSurfaceVariant") : m3("error"));
            l.setPadding(dp(4), dp(16), 0, dp(10));
            d.addView(l);
            if (store.error.isEmpty()) { LinearProgress lpb = new LinearProgress(); lpb.set(-1); d.addView(lpb, lp(-1, dp(4), 0)); }
            return d;
        }
        String[] filters = {sel != null ? "For " + sel.label : "For the selected game", "All games"};
        if (sel == null) storeFilter = 1;
        d.addView(row("Show", combo(filters, storeFilter, k -> { storeFilter = k; rebuild(); })));
        TextView h = text(idx.size() + " plugins. OFFICIAL = made by idiotthemodder, updates by itself. COMMUNITY = uploaded by anyone, NOT checked: read the code before you install. Installed plugins load on the next Launch / Inject.", 13, col("TextDisabled"));
        h.setPadding(dp(4), dp(6), 0, 0);
        d.addView(h);

        List<Store.Plugin> ups = store.updates(null);
        if (!ups.isEmpty()) {
            d.addView(section("Updates (" + ups.size() + ")"));
            for (Store.Plugin p : ups) d.addView(pluginCard(p));
        }
        List<Store.Plugin> shown = new ArrayList<>();
        for (Store.Plugin p : idx) if (!ups.contains(p) && (storeFilter == 1 || sel == null || p.forGame(sel.ai.packageName))) shown.add(p);
        shown.sort((a, b) -> a.trusted != b.trusted ? (a.trusted ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        d.addView(section(storeFilter == 0 && sel != null ? "For " + sel.label + " (" + shown.size() + ")" : "All plugins (" + shown.size() + ")"));
        if (shown.isEmpty()) d.addView(text(storeFilter == 0 ? "nothing for this game yet. Try \"All games\", or upload one." : "the store is empty", 14, col("TextDisabled")));
        for (Store.Plugin p : shown) d.addView(pluginCard(p));
        return d;
    }

    View pluginCard(Store.Plugin p) {
        LinearLayout c = vbox();
        c.setBackground(rr(m3("surfaceContainerLow"), 16));
        c.setPadding(dp(16), dp(14), dp(14), dp(12));
        LinearLayout.LayoutParams cp = lp(-1, -2, 0);
        cp.setMargins(0, dp(6), 0, 0);
        c.setLayoutParams(cp);
        Store.Installed inst = store.installedOf(p.id);
        boolean hasUpdate = inst != null && !inst.version.equals(p.version);

        LinearLayout top = hbox();
        TextView n = text(p.name, 17, m3("onSurface"));
        n.setTypeface(medium());
        top.addView(n);
        LinearLayout.LayoutParams bp = lp(-2, -2, 0);
        bp.setMargins(dp(8), 0, 0, 0);
        top.addView(p.trusted ? badge("OFFICIAL", "successContainer", "onSuccessContainer", "check") : badge("COMMUNITY · UNTRUSTED", "errorContainer", "onErrorContainer", "warning"), bp);
        if (inst != null) { LinearLayout.LayoutParams ip = lp(-2, -2, 0); ip.setMargins(dp(6), 0, 0, 0); top.addView(badge(hasUpdate ? "UPDATE" : "INSTALLED", "primaryContainer", "onPrimaryContainer"), ip); }
        c.addView(top);
        if (!p.desc.isEmpty()) { TextView ds = text(p.desc, 13, col("Text")); ds.setPadding(0, dp(3), 0, 0); c.addView(ds); }
        String meta = (p.games.isEmpty() ? "no game line" : "for " + String.join(", ", p.games)) + "  ·  by " + (p.author.isEmpty() ? "?" : p.author)
                + "  ·  " + p.files.size() + (p.files.size() == 1 ? " file" : " files") + ", " + Math.max(1, p.size() / 1024) + " KB  ·  " + p.updated;
        TextView m = text(meta, 12, col("TextDisabled"));
        m.setPadding(0, dp(2), 0, 0);
        c.addView(m);
        TextView sc = text("scan: " + p.high + " high · " + p.medium + " medium · " + p.low + " low", 12, p.high > 0 ? col("NotifError") : p.medium > 0 ? col("NotifWarn") : col("TextDisabled"));
        c.addView(sc);

        LinearLayout btns = hbox();
        btns.setPadding(0, dp(6), 0, 0);
        if (inst == null) btns.addView(withIcon(button("Install", true, v -> startInstall(p), true), "download"), lpm(0, -2, 1, 3));
        else if (hasUpdate) btns.addView(withIcon(button("Update", true, v -> startInstall(p), true), "download"), lpm(0, -2, 1, 3));
        btns.addView(withIcon(button("View code", false, v -> fetchThen(p, files -> showCode(p.name, files)), false), "code"), lpm(0, -2, 1, 3));
        if (inst != null) btns.addView(withIcon(dangerButton("Remove", v -> bg.execute(() -> {
            try { store.remove(p.id); } catch (Exception e) { log("store: remove failed: " + e.getMessage()); }
            ui.post(() -> { storeWaiting = store.updates(false).size(); rebuild(); });
        })), "delete"), lpm(0, -2, 1, 3));
        c.addView(btns);
        return c;
    }

    /** download (sha-checked) on the bg thread, then run `then` on the ui thread */
    void fetchThen(Store.Plugin p, java.util.function.Consumer<Map<String, byte[]>> then) {
        if (busy) return;
        setBusy(true);
        setStatus("downloading " + p.name + "...");
        progress(0f);
        store.onProgress = this::progress;
        bg.execute(() -> {
            try {
                Map<String, byte[]> files = store.download(p);
                store.onProgress = null;
                ui.post(() -> { setBusy(false); setStatus("ready"); then.accept(files); });
            } catch (Exception e) {
                store.onProgress = null;
                log("store: download of " + p.name + " failed: " + e.getMessage());
                ui.post(() -> { setBusy(false); setStatus("download failed"); });
            }
        });
    }

    void startInstall(Store.Plugin p) {
        fetchThen(p, files -> {
            if (p.trusted) { doInstall(p, files); return; }
            String code, old;
            try { code = Store.jsText(files); } catch (Exception e) { log("store: " + e.getMessage()); return; }
            old = store.installedText(p.id);
            untrustedDialog(p, files, code, old);
        });
    }

    void doInstall(Store.Plugin p, Map<String, byte[]> files) {
        setBusy(true);
        bg.execute(() -> {
            try { store.install(p, files); } catch (Exception e) { log("store: install of " + p.name + " failed: " + e.getMessage()); }
            ui.post(() -> { setBusy(false); storeWaiting = store.updates(false).size(); rebuild(); });
        });
    }

    /** a themed dialog: content on top, buttons at the bottom */
    android.app.Dialog themedDialog(View content, View buttons) {
        android.app.Dialog dl = new android.app.Dialog(this);
        dl.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = vbox();
        box.setBackground(rr(m3("surfaceContainerHigh"), 28));
        box.setPadding(dp(24), dp(22), dp(24), dp(18));
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        box.addView(sv, lp(-1, 0, 1));
        if (buttons != null) { buttons.setPadding(0, dp(10), 0, 0); box.addView(buttons, lp(-1, -2, 0)); }
        dl.setContentView(box);
        android.view.Window w = dl.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.8f), (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
        }
        return dl;
    }

    /** lines in `now` that aren't in `before` (multiset), and how many went away */
    static String[] lineDiff(String before, String now) {
        Map<String, Integer> have = new java.util.HashMap<>();
        for (String l : before.split("\n")) have.merge(l, 1, Integer::sum);
        StringBuilder added = new StringBuilder();
        int nAdd = 0;
        for (String l : now.split("\n")) {
            Integer k = have.get(l);
            if (k != null && k > 0) have.put(l, k - 1);
            else { nAdd++; if (nAdd <= 80) added.append("+ ").append(l.length() > 160 ? l.substring(0, 160) + "..." : l).append('\n'); }
        }
        int nDel = 0;
        for (int v : have.values()) nDel += v;
        if (nAdd > 80) added.append("... and ").append(nAdd - 80).append(" more added lines (View code shows everything)\n");
        return new String[]{String.valueOf(nAdd), String.valueOf(nDel), added.toString()};
    }

    void untrustedDialog(Store.Plugin p, Map<String, byte[]> files, String code, String old) {
        boolean isUpdate = !old.isEmpty();
        LinearLayout v = vbox();
        ImageView wi = iconView("warning", m3("error"), 40);
        v.addView(wi, lp(-1, dp(48), 0));
        TextView warn = text("Not trusted", 24, m3("error"));
        warn.setGravity(Gravity.CENTER);
        v.addView(warn, lp(-1, -2, 0));
        TextView msg = text((isUpdate ? "Update for \"" : "\"") + p.name + "\" was uploaded by " + (p.author.isEmpty() ? "someone" : "\"" + p.author + "\"")
                + ", not by idiotthemodder. Nobody has checked that it is safe.\n\n"
                + "A plugin runs inside the game with the game's full access: your account in that game, its saves, and the internet. "
                + "A bad plugin can steal your account or ruin your saves.\n\n"
                + "DO NOT INSTALL ANYTHING BEFORE YOU HAVE LOOKED AT THE CODE YOURSELF (View code), and read the scan below."
                + (isUpdate ? " The code changed since you installed it: read what's new, an update can add malware to a plugin that was fine." : ""), 14, col("Text"));
        msg.setPadding(0, dp(8), 0, dp(4));
        v.addView(msg);

        List<Scanner.Finding> now = Scanner.scan(this, code);
        if (isUpdate) {
            Set<String> before = new java.util.HashSet<>();
            for (Scanner.Finding f : Scanner.scan(this, old)) before.add(f.id);
            String[] df = lineDiff(old, code);
            v.addView(section("What changed: +" + df[0] + " / -" + df[1] + " lines"));
            List<String> fresh = new ArrayList<>();
            for (Scanner.Finding f : now) if (!before.contains(f.id)) fresh.add("[" + f.sev.toUpperCase(Locale.US) + "] " + f.label);
            if (!fresh.isEmpty()) {
                TextView nf = text("NEW in this version:\n" + String.join("\n", fresh), 14, col("NotifError"));
                nf.setTypeface(Typeface.DEFAULT_BOLD);
                nf.setPadding(0, dp(4), 0, dp(4));
                v.addView(nf);
            }
            TextView add = text(df[2].isEmpty() ? "(only removed lines)" : df[2], 11, col("Text"));
            add.setTypeface(Typeface.MONOSPACE);
            add.setBackground(rrKey("FrameBg"));
            add.setPadding(dp(8), dp(6), dp(8), dp(6));
            v.addView(add);
        }

        int[] cnt = Scanner.counts(now);
        v.addView(section("Safety scan: " + cnt[0] + " high · " + cnt[1] + " medium · " + cnt[2] + " low"));
        if (now.isEmpty()) v.addView(text("The scan found none of the patterns it knows. That does NOT mean it is safe: it only knows patterns, and clever or hidden code gets past it. Still read the code.", 13, col("NotifWarn")));
        for (Scanner.Finding f : now) {
            TextView l = text("[" + f.sev.toUpperCase(Locale.US) + "]  " + f.label + "  (" + f.count + "x)", 14, sevColor(f.sev));
            l.setTypeface(Typeface.DEFAULT_BOLD);
            l.setPadding(0, dp(6), 0, 0);
            v.addView(l);
            v.addView(text(f.why, 12, col("TextDisabled")));
            if (!f.hits.isEmpty()) {
                TextView hits = text(String.join("\n", f.hits), 11, col("Text"));
                hits.setTypeface(Typeface.MONOSPACE);
                hits.setPadding(dp(8), dp(2), 0, 0);
                v.addView(hits);
            }
        }
        TextView tip = text("Red flags: sending data to the internet (Discord webhooks, Telegram, paste sites, raw IPs), running shell commands, eval / new Function, reading tokens or other apps' folders, obfuscated code (_0x names, long \\x.. strings, big base64 blobs). If you don't understand the code, don't install it.", 12, col("TextDisabled"));
        tip.setPadding(0, dp(10), 0, dp(6));
        v.addView(tip);
        final boolean[] ok = {false};
        LinearLayout btns = vbox();
        TextView[] go = new TextView[1];
        btns.addView(checkbox(false, "I read the code myself and I accept the risk", b -> { ok[0] = b; go[0].setEnabled(b); go[0].setAlpha(b ? 1f : 0.45f); }));
        LinearLayout r = hbox();
        r.setPadding(0, dp(8), 0, 0);
        android.app.Dialog[] dl = new android.app.Dialog[1];
        r.addView(button("View code", false, x -> showCode(p.name, files), false), lpm(0, -2, 1, 3));
        r.addView(button("Cancel", false, x -> dl[0].dismiss(), false), lpm(0, -2, 1, 3));
        go[0] = dangerButton(isUpdate ? "Install update" : "Install anyway", x -> { if (!ok[0]) return; dl[0].dismiss(); doInstall(p, files); });
        actions.remove(go[0]);
        go[0].setEnabled(false); go[0].setAlpha(0.45f);
        r.addView(go[0], lpm(0, -2, 1, 3));
        btns.addView(r);
        dl[0] = themedDialog(v, btns);
        dl[0].show();
    }

    void showCode(String title, Map<String, byte[]> files) {
        String code;
        try { code = Store.jsText(files); } catch (Exception e) { code = e.toString(); }
        LinearLayout v = vbox();
        TextView t = text(title + "  ·  " + code.split("\n").length + " lines", 16, col("Text"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        v.addView(t);
        int cap = 250_000;
        TextView c = text(code.length() > cap ? code.substring(0, cap) + "\n\n... cut at 250 KB here. The full file is " + code.length() / 1024 + " KB: read it on a PC before trusting it." : code, 11, col("Text"));
        c.setTypeface(Typeface.MONOSPACE);
        c.setTextIsSelectable(true);
        c.setBackground(rrKey("FrameBg"));
        c.setPadding(dp(8), dp(6), dp(8), dp(6));
        v.addView(c);
        android.app.Dialog[] dl = new android.app.Dialog[1];
        LinearLayout r = hbox();
        r.addView(button("Close", true, x -> dl[0].dismiss(), false), lpm(0, -2, 1, 3));
        dl[0] = themedDialog(v, r);
        dl[0].show();
    }

    EditText field(String hint, String value, int maxLen) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(15);
        e.setTextColor(col("Text"));
        e.setHintTextColor(col("TextDisabled"));
        e.setBackground(rr(m3("surfaceContainerHighest"), 12));
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLen)});
        return e;
    }

    void uploadDialog() {
        List<File> cands = new ArrayList<>();
        for (File dir : new File[]{Paths.PLUGINS, new File(Paths.ROOT, "upload"), new File("/sdcard/Download")}) {
            File[] fs = dir.listFiles((x, n) -> n.toLowerCase(Locale.US).endsWith(".js"));
            if (fs != null) { java.util.Arrays.sort(fs); for (File f : fs) if (f.length() <= 600 * 1024) cands.add(f); }
        }
        LinearLayout v = vbox();
        TextView t = text("Upload a plugin", 22, m3("onSurface"));
        v.addView(t);
        v.addView(text("It goes into a review queue. If it's approved it shows up in the store for everyone as COMMUNITY (untrusted), with your code public. "
                + "Only upload your own work (or with the author's permission). Max 600 KB, and it needs a \"// game: <package>\" line near the top.", 13, col("TextDisabled")));
        if (cands.isEmpty()) {
            v.addView(text("\nNo .js files found. Put your plugin in " + Paths.PLUGINS + ", " + new File(Paths.ROOT, "upload") + " or /sdcard/Download.", 14, col("NotifWarn")));
            android.app.Dialog[] dl = new android.app.Dialog[1];
            LinearLayout r = hbox();
            r.addView(button("Close", true, x -> dl[0].dismiss(), false), lpm(0, -2, 1, 3));
            dl[0] = themedDialog(v, r);
            dl[0].show();
            return;
        }
        String[] labels = new String[cands.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = cands.get(i).getParentFile().getName() + "/" + cands.get(i).getName();
        int[] pick = {0};
        TextView gameInfo = text("", 13, col("TextDisabled"));
        EditText name = field("name shown in the store", "", 40);
        EditText desc = field("what it does (one line)", "", 300);
        EditText author = field("your name", dev.vrfrida.Settings.prefs(this).getString("storeAuthor", ""), 32);
        List<String> mineCommunity = new ArrayList<>();
        mineCommunity.add("(a new plugin)");
        if (store.index != null) for (Store.Plugin p : store.index) if (!p.trusted) mineCommunity.add(p.id);
        int[] upd = {0};
        Runnable onPick = () -> {
            File f = cands.get(pick[0]);
            List<String> gs = Core.pluginGames(f);
            gameInfo.setText(gs.isEmpty() ? "this file has no // game: line, the server will refuse it" : "for " + String.join(", ", gs) + "  ·  " + Math.max(1, f.length() / 1024) + " KB");
            if (name.getText().length() == 0) name.setText(f.getName().replaceAll("\\.js$", "").replace('_', ' '));
            if (desc.getText().length() == 0) desc.setText(pluginDesc(f));
        };
        View[] fileCombo = new View[1];
        LinearLayout fileRow = hbox();
        java.util.function.Consumer<Integer>[] setPick = new java.util.function.Consumer[1];
        setPick[0] = k -> { pick[0] = k; name.setText(""); desc.setText(""); onPick.run(); fileRow.removeAllViews(); fileRow.addView(combo(labels, k, setPick[0]), lp(-1, -2, 1)); };
        fileRow.addView(combo(labels, 0, setPick[0]), lp(-1, -2, 1));
        onPick.run();
        v.addView(row("File", fileRow));
        gameInfo.setPadding(dp(4), dp(2), 0, 0);
        v.addView(gameInfo);
        v.addView(row("Name", name));
        v.addView(row("Description", desc));
        v.addView(row("Author", author));
        LinearLayout updRow = hbox();
        java.util.function.Consumer<Integer>[] setUpd = new java.util.function.Consumer[1];
        setUpd[0] = k -> { upd[0] = k; updRow.removeAllViews(); updRow.addView(combo(mineCommunity.toArray(new String[0]), k, setUpd[0]), lp(-1, -2, 1)); };
        updRow.addView(combo(mineCommunity.toArray(new String[0]), 0, setUpd[0]), lp(-1, -2, 1));
        v.addView(row("Update of", updRow));
        android.app.Dialog[] dl = new android.app.Dialog[1];
        LinearLayout r = hbox();
        r.addView(button("Cancel", false, x -> dl[0].dismiss(), false), lpm(0, -2, 1, 3));
        r.addView(button("Submit for review", true, x -> {
            File f = cands.get(pick[0]);
            List<String> gs = Core.pluginGames(f);
            String nm = name.getText().toString().trim(), ds = desc.getText().toString().trim().replaceAll("[\\r\\n]+", " "), au = author.getText().toString().trim();
            if (gs.isEmpty()) { gameInfo.setText("add a \"// game: <package>\" line to the top of the file first"); return; }
            if (nm.length() < 2 || au.isEmpty()) { gameInfo.setText("name and author are needed"); return; }
            dev.vrfrida.Settings.prefs(this).edit().putString("storeAuthor", au).apply();
            String updates = upd[0] == 0 ? "" : mineCommunity.get(upd[0]);
            dl[0].dismiss();
            setStatus("uploading " + f.getName() + "...");
            new Thread(() -> {
                try {
                    String id = Store.submit(nm, gs.get(0), ds, au, updates, new String(Files.read(f), "UTF-8"));
                    log("store: uploaded " + f.getName() + " for review, upload id " + id + ". It shows up in the store once it's approved");
                    ui.post(() -> setStatus("uploaded (id " + id + "), waiting for review"));
                } catch (Exception e) {
                    log("store: upload failed: " + e.getMessage());
                    ui.post(() -> setStatus("upload failed: " + e.getMessage()));
                }
            }).start();
        }, false), lpm(0, -2, 1, 3));
        dl[0] = themedDialog(v, r);
        dl[0].show();
    }

    // ---------------------------------------------------------------- Menu Look tab
    View lookTab() {
        LinearLayout d = vbox();
        LinearLayout head = hbox();
        String[] scopes = sel != null ? new String[]{"All games (synced)", sel.label + " only"} : new String[]{"All games (synced)"};
        View sc = combo(scopes, Math.min(lookScope, scopes.length - 1), i -> { flushSave(); lookScope = i; rebuild(); });
        d.addView(row("Editing", sc));
        d.addView(menuPreview());

        if (lookScope == 0 || sel == null) {
            TextView h = text("One look for every game: games with \"Synced look\" on (the default) get these settings copied into their menu on every Launch / Inject. Only what you change here is copied; mods and plugin settings in each game are kept.", 13, col("TextDisabled"));
            h.setPadding(dp(4), dp(4), 0, dp(4));
            d.addView(h);
            LinearLayout r = hbox();
            r.addView(button("Apply to all games now", true, v -> applyLookToAll(), true), lpm(0, -2, 1.3f, 3));
            if (sel != null) r.addView(button("Copy from " + sel.label, false, v -> copyLookFromGame(), true), lpm(0, -2, 1, 3));
            r.addView(button("Reset", false, v -> {
                try { globalCfg.root.put("menu", new JSONObject()); } catch (Exception ignored) {}
                globalChanged(true);
                log("synced look reset (games keep what they have until you change something and apply)");
            }, false), lpm(0, -2, 0.6f, 3));
            d.addView(r);
            String last = "";
            for (MenuCfg.Field f : globalCfg.all) {
                if (f.top) continue;                          // autoSave / remember stay per game
                if (!f.section.equals(last)) { d.addView(section(f.section)); last = f.section; }
                d.addView(row(f.label, editor(globalCfg, f, this::globalChanged)));
            }
        } else {
            LinearLayout r = hbox();
            r.addView(button("Reload", false, v -> { gameCfg = null; gameCfgState = "loading..."; rebuild(); loadGameCfg(sel); }, false), lpm(0, -2, 1, 3));
            r.addView(button("Reset to defaults", false, v -> resetGameCfg(), false), lpm(0, -2, 1, 3));
            d.addView(r);
            d.addView(text(Core.settingsPath(sel.ai.packageName), 12, col("TextDisabled")));
            if (cfg.followGlobal) {
                TextView w = text("This game uses the synced look, so the All games settings replace these on the next launch. Turn off \"Synced look\" on the Games tab to keep a separate look.", 13, col("NotifWarn"));
                w.setPadding(0, dp(6), 0, 0);
                d.addView(w);
            }
            if (sel.injected) {
                TextView w = text("The menu is running in this game and saves its own settings, so it can overwrite these. Unload it first, or change them in the game.", 13, col("NotifWarn"));
                w.setPadding(0, dp(6), 0, 0);
                d.addView(w);
            }
            if (gameCfg == null) { d.addView(text(gameCfgState, 14, col("TextDisabled"))); }
            else {
                String last = "";
                for (MenuCfg.Field f : gameCfg.all) {
                    if (!f.section.equals(last)) { d.addView(section(f.section)); last = f.section; }
                    d.addView(row(f.label, editor(gameCfg, f, restyle -> gameChanged(restyle))));
                }
            }
        }

        d.addView(section("This app"));
        String[] srcs = {"Auto (" + (MenuLook.hasUxpatcher(this) ? "UXpatcher" : "Meta") + ")", "Meta", MenuLook.hasUxpatcher(this) ? "UXpatcher" : "UXpatcher (not installed)"};
        String at = appTheme();
        d.addView(row("App theme", combo(srcs, at.equals("meta") ? 1 : at.equals("uxpatcher") ? 2 : 0, i -> {
            try { appLook.root.put("appTheme", i == 1 ? "meta" : i == 2 ? "uxpatcher" : "auto"); } catch (Exception ignored) {}
            appLookChanged(true);
        })));
        return d;
    }

    /** live preview of the menu look being edited (the menu's own background + colors) */
    View menuPreview() {
        FrameLayout f = new FrameLayout(this);
        MenuBgView bgv = new MenuBgView(this, look, new MenuBgView.Source() {
            @Override public MenuCfg cfg() { return shownCfg(); }
            @Override public Map<String, float[]> colors() { return menuColorsNow(); }
            @Override public boolean animate() { return animateBg(); }
        });
        f.addView(bgv, new FrameLayout.LayoutParams(-1, -1));
        Map<String, float[]> mc = menuColorsNow();
        LinearLayout over = vbox();
        over.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView a = text("Menu preview", 18, MenuLook.argb(mc.get("Text")));
        a.setTypeface(medium());
        over.addView(a);
        over.addView(text("theme: " + shownThemeId() + "  ·  this is how the in-game menu looks", 13, MenuLook.argb(mc.get("TextDisabled"))));
        LinearLayout chips = hbox();
        chips.setPadding(0, dp(10), 0, 0);
        for (String k : new String[]{"TabActive", "Button", "FrameBg", "Check"}) {
            View sw = new View(this);
            sw.setBackground(rr(MenuLook.argb(MenuLook.a(mc.get(k), 1)), 8));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(34), dp(20));
            sp.setMargins(0, 0, dp(6), 0);
            chips.addView(sw, sp);
        }
        over.addView(chips);
        f.addView(over, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        f.setClipToOutline(true);
        f.setBackground(rr(m3("surfaceContainerLowest"), 16));
        LinearLayout.LayoutParams p = lp(-1, dp(150), 0);
        p.setMargins(0, dp(10), 0, dp(4));
        f.setLayoutParams(p);
        return f;
    }

    /** take the selected game's current menu look as the synced look */
    void copyLookFromGame() {
        if (sel == null || !rootOk) return;
        Game g = sel;
        io.execute(() -> {
            MenuCfg c = MenuCfg.parse(look, Core.readSettings(g.ai.packageName));
            ui.post(() -> {
                try { globalCfg.root.put("menu", new JSONObject(c.menu().toString())); } catch (Exception ignored) {}
                globalChanged(true);
                log("synced look copied from " + g.label + " (" + c.menu().length() + " settings). Press Apply to all games now to push it");
            });
        });
    }

    void resetGameCfg() {
        if (gameCfg == null) return;
        try {
            gameCfg.root.put("menu", new JSONObject());
            gameCfg.root.put("autoSave", true);
            gameCfg.root.put("remember", true);
        } catch (Exception ignored) {}
        gameChanged(true);
        log("menu settings for " + sel.label + " reset to defaults (mods and plugin settings kept)");
    }

    /** the right control for a settings field; onChange(restyle) */
    View editor(MenuCfg c, MenuCfg.Field f, java.util.function.Consumer<Boolean> onChange) {
        switch (f.kind) {
            case BOOL:
                return checkbox(c.bool(f), f.desc, v -> { c.set(f, v); onChange.accept(f.key.equals("pattern")); });
            case INT: case FLOAT:
                return slider(c.num(f), f.min, f.max, f.kind == MenuCfg.Kind.INT ? 0 : f.decimals,
                        v -> { c.set(f, f.kind == MenuCfg.Kind.INT ? (Object) (int) Math.round(v) : (Object) v); onChange.accept(false); });
            case CHOICE:
                return combo(f.options, (int) c.num(f), i -> { c.set(f, i); onChange.accept(true); });
            case WRIST:
                return combo(f.options, c.bool(f) ? 0 : 1, i -> { c.set(f, i == 0); onChange.accept(true); });
            case THEME: {
                String[] names = new String[look.themes.size()];
                int idx = 0;
                String cur = look.theme(c.str(f)).id;
                for (int i = 0; i < names.length; i++) { names[i] = look.themes.get(i).name; if (look.themes.get(i).id.equals(cur)) idx = i; }
                return combo(names, idx, i -> { c.set(f, look.themes.get(i).id); onChange.accept(true); });
            }
            case PATFILE: {
                String[] files = MenuLook.patternFiles();
                if (files.length == 0) return text("no images in /sdcard/vrfrida/patterns", 13, col("TextDisabled"));
                String[] names = new String[files.length];
                for (int i = 0; i < files.length; i++) names[i] = files[i].replaceAll("(?i)\\.(png|jpe?g)$", "");
                int idx = Math.max(0, java.util.Arrays.asList(files).indexOf(c.str(f)));
                return combo(names, idx, i -> { c.set(f, files[i]); onChange.accept(true); });
            }
        }
        return new View(this);
    }

    // ---------------------------------------------------------------- Log tab
    View logTab() {
        LinearLayout d = vbox();
        LinearLayout head = hbox();
        TextView t = text(sel != null ? sel.label : "", 20, m3("onSurface"));
        head.addView(t, lp(0, -2, 1));
        head.addView(button("Clear", false, v -> { synchronized (appLog) { appLog.setLength(0); } logSig = ""; refreshLogs(); }, false));
        d.addView(head);
        d.addView(updatesBox());
        d.addView(section("Log"));
        logView = text("", 12, col("Text"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setBackground(rrKey("FrameBg"));
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = lp(-1, -2, 0);
        lp.setMargins(0, dp(6), 0, 0);
        d.addView(logView, lp);
        return d;
    }
}
