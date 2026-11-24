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
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
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
    static final String[] TABS = {"Games", "Plugins", "Store", "Menu Look", "Log", "Info", "Logcat", "Mic", "Tools", "Customize"};
    static final int TAB_TOOLS = 8, TAB_CUSTOMIZE = 9;

    static final class Game {
        ApplicationInfo ai; String label; Bitmap icon; String fp = ""; String bridge = "?"; boolean running, injected, fav, patched, outdated; long patchVer;
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
        if (want.matches("[0-9]") && Integer.parseInt(want) != tab) { goTab(Integer.parseInt(want)); ui.postDelayed(() -> { try { Files.write(trig, new byte[0]); } catch (Exception ignored) {} }, 1500); return; }
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
        sysMic = new SysMic(this, core);
        Core.loadMicGames(this);
        Sounds.maxSec = dev.vrfrida.Settings.prefs(this).getInt("soundMaxSec", 0);
        updater = new Updater(this, this::log);
        store = new Store(this, this::log);
        noRoot = new NoRoot(this, this::log, () -> { setBusy(false); bg.execute(this::scanGames); });
        look = new MenuLook(this);
        appLook = MenuCfg.parse(look, dev.vrfrida.Settings.prefs(this).getString("applook", "{}"));
        globalCfg = MenuCfg.parse(look, dev.vrfrida.Settings.globalLook(this));
        st = new AppStyle(appLook.root);
        sfx = new UiSfx(this);
        uxpKey = uxpSignature();
        buildHost();
        rebuild();
        if (b == null && !st.boot().equals("off")) showBoot(true);
        else configureSfx(false);
        bg.execute(() -> { try { startup(); } finally { ui.post(this::bootReady); } });
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
        if (!rootOk) { try { Thread.sleep(1500); } catch (InterruptedException ignored) {} rootOk = Root.available(); }
        log(rootOk ? "root ok" : "root check failed");
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
        if (rootOk) core.syncLibWatcher();
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
        long appVer = NoRoot.appVersion(this);
        int px = dp(44);
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(getPackageName())) continue;
            if (Core.SYSTEM.matcher(ai.packageName).find()) continue;
            String want = ai.sourceDir + "|" + pi.lastUpdateTime + "|";
            String c = sp.getString("scan2:" + ai.packageName, null), fp;
            long patchVer;
            // cache: <apk>|<updated>|<bridge fingerprint>#pv<vrfrida version that patched it, 0 = not patched, -1 = older than v10>
            int at = c != null && c.startsWith(want) ? c.indexOf("#pv", want.length()) : -1;
            if (at >= 0) { fp = c.substring(want.length(), at); patchVer = Long.parseLong(c.substring(at + 3)); }
            else {
                Bridge.Lib lib = Bridge.findLib(ai);
                fp = lib == null ? "-" : lib.fingerprint();
                patchVer = lib == null ? 0 : NoRoot.patchVersion(ai);
                ed.putString("scan2:" + ai.packageName, want + fp + "#pv" + patchVer);
            }
            boolean patched = patchVer != 0;
            if (fp.equals("-") || pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            Game g = new Game();
            g.ai = ai;
            g.fp = fp;
            g.patched = patched;
            g.outdated = patched && patchVer < appVer;
            g.patchVer = patchVer;
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
        S = st.scheme(this);
        return M3.legacy(S);
    }

    int m3(String role) { Integer c = S.get(role); return c == null ? 0xFFFF00FF : c; }

    int col(String key) { return MenuLook.argb(C.get(key)); }
    int col(String key, float alphaMul) { return MenuLook.argb(C.get(key), alphaMul); }

    // ================================================================ jobs
    interface Job { void run(Game g) throws Exception; }

    void job(String what, Job j) {
        if (!rootOk) { log(what + " needs root (Magisk). Without root: Launch works, the menu loads in games done with Patch + install"); return; }
        job(what, j, false);
    }

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
            catch (Throwable e) { log("error: " + e); }
            finally { boolean fok = ok; if (!(ok && keepBusy)) ui.post(() -> setBusy(false)); ui.post(() -> sfx.play(fok ? "success" : "error")); refreshStatus(); }
        }));
    }

    // ---------------------------------------------------------------- native libs (root)
    static final int REQ_PICK_LIB = 4712;
    String libPickPkg;

    void pickLib() {
        if (sel == null || !rootOk) return;
        libPickPkg = sel.ai.packageName;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, REQ_PICK_LIB); }
        catch (Exception e) { log("no file picker on this headset: put the .so in " + Core.libsDir(libPickPkg) + " by hand"); }
    }

    void onLibPicked(int res, Intent data) {
        String pkg = libPickPkg;
        if (res != RESULT_OK || data == null || data.getData() == null || pkg == null) return;
        Uri uri = data.getData();
        io.execute(() -> {
            String name = null;
            try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {}
            try {
                File f = core.importLib(pkg, uri, name);
                log("added " + f.getName() + " for " + pkg + ": it loads every time the game starts");
                core.syncLibWatcher();
                if (!core.pidOf(pkg).isEmpty()) log("the game is running now: restart it to load the new lib");
            } catch (Exception e) {
                log("couldn't add that lib: " + e.getMessage());
            }
            ui.post(this::rebuild);
        });
    }

    // ---------------------------------------------------------------- sounds (every game)
    static final int REQ_PICK_SOUNDS = 4713;

    /** vrfrida has all-files access, so it lists the headset's audio itself (Quest's system picker often shows nothing) */
    void pickSounds() {
        io.execute(() -> {
            List<File> found = new ArrayList<>();
            File sd = Environment.getExternalStorageDirectory();
            if (Environment.isExternalStorageManager()) {
                File[] top = sd.listFiles();
                if (top != null) for (File d : top) {
                    String n = d.getName();
                    // skip Android/ (other apps' data), our own folders and hidden ones
                    if (n.startsWith(".") || n.equals("Android") || n.equals("vrfrida")) continue;
                    if (d.isFile()) { if (Sounds.isAudio(n)) found.add(d); }
                    else findAudio(d, 4, found);
                }
            }
            found.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            ui.post(() -> showSoundList(found, sd));
        });
    }

    static void findAudio(File dir, int depth, List<File> out) {
        if (depth < 0 || out.size() >= 500) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) findAudio(f, depth - 1, out);
            else if (Sounds.isAudio(f.getName())) out.add(f);
        }
    }

    void showSoundList(List<File> found, File sd) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this).setTitle("Add sounds");
        if (found.isEmpty()) {
            b.setMessage("No audio files found on the headset (mp3 / ogg / wav / m4a / flac...).\n\nPut some in the Download or Music folder (browser download, SideQuest, USB), or look with the system file picker.")
             .setPositiveButton("System picker", (d, w) -> systemSoundPicker())
             .setNegativeButton("Close", null).show();
            return;
        }
        String[] labels = new String[found.size()];
        String root = sd.getPath() + "/";
        for (int k = 0; k < labels.length; k++) {
            File f = found.get(k);
            labels[k] = f.getName() + "   (" + f.getParent().replace(root, "").replace(sd.getPath(), "") + ", " + Math.max(1, f.length() / 1024) + " KB)";
        }
        boolean[] on = new boolean[labels.length];
        b.setMultiChoiceItems(labels, on, (d, k, v) -> on[k] = v)
         .setPositiveButton("Add", (d, w) -> {
             List<File> pick = new ArrayList<>();
             for (int k = 0; k < on.length; k++) if (on[k]) pick.add(found.get(k));
             if (pick.isEmpty()) return;
             io.execute(() -> {
                 int added = 0;
                 for (File f : pick) {
                     try { File o = core.importSound(Uri.fromFile(f), f.getName()); log("added sound " + o.getName()); added++; }
                     catch (Exception e) { log("couldn't add " + f.getName() + ": " + e.getMessage()); }
                 }
                 if (added > 0) pushSoundsNow();
                 ui.post(this::rebuild);
             });
         })
         .setNeutralButton("System picker", (d, w) -> systemSoundPicker())
         .setNegativeButton("Cancel", null).show();
    }

    /** fallback: the system picker, showing every file type (importSound checks the extension) */
    void systemSoundPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK_SOUNDS); }
        catch (Exception e) { log("no file picker on this headset: put the sounds in " + Paths.SOUNDS + " by hand"); }
    }

    void onSoundsPicked(int res, Intent data) {
        if (res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        io.execute(() -> {
            int added = 0;
            for (Uri uri : uris) {
                String name = null;
                try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                } catch (Exception ignored) {}
                try { File f = core.importSound(uri, name); log("added sound " + f.getName()); added++; }
                catch (Exception e) { log("couldn't add " + (name == null ? "that file" : name) + ": " + e.getMessage()); }
            }
            if (added > 0) pushSoundsNow();
            ui.post(this::rebuild);
        });
    }

    /** io thread */
    void pushSoundsNow() {
        if (!rootOk && !noRoot.canInstall()) {
            log("sounds are saved in " + Paths.SOUNDS + ". To put them in games without root, allow \"Install unknown apps\" for vrfrida (that lets it write Android/obb), reopen vrfrida, then press Push to all games");
            ui.post(noRoot::openInstallPermission);
            return;
        }
        List<String> pkgs = new ArrayList<>();
        synchronized (games) { for (Game g : games) pkgs.add(g.ai.packageName); }
        log("── pushing sounds to " + pkgs.size() + " game(s)");
        core.pushSoundsToAll(pkgs, rootOk);
    }

    // ---------------------------------------------------------------- sound manager (play / rename / delete)
    android.media.MediaPlayer preview;

    void stopPreview() {
        if (preview != null) { try { preview.stop(); } catch (Exception ignored) {} preview.release(); preview = null; }
    }

    void playPreview(File f) {
        stopPreview();
        try {
            preview = new android.media.MediaPlayer();
            preview.setDataSource(f.getPath());
            preview.setOnCompletionListener(mp -> stopPreview());
            preview.prepare();
            int max = Sounds.maxSec * 1000;
            preview.start();
            if (max > 0) { final android.media.MediaPlayer mine = preview; ui.postDelayed(() -> { if (preview == mine) stopPreview(); }, max); }
        } catch (Exception e) { log("can't play " + f.getName() + ": " + e.getMessage()); stopPreview(); }
    }

    static List<File> soundFiles() {
        List<File> out = new ArrayList<>();
        File[] fs = Paths.SOUNDS.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && Sounds.isAudio(f.getName())) out.add(f);
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    void soundManager() {
        final boolean[] changed = {false};
        LinearLayout list = vbox();
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Sounds (" + soundCount() + ")")
                .setView(sv)
                .setPositiveButton("Done", null)
                .create();
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            List<File> fs = soundFiles();
            if (fs.isEmpty()) list.addView(text("No sounds yet: use Add sounds.", 14, col("TextDisabled")));
            long total = 0;
            for (File f : fs) {
                total += f.length();
                LinearLayout r = new LinearLayout(this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(0, dp(3), 0, dp(3));
                TextView n = text(f.getName() + "   " + Math.max(1, f.length() / 1024) + " KB", 14, col("Text"));
                n.setSingleLine(true);
                n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
                r.addView(button("Play", false, v -> playPreview(f), false));
                r.addView(button("Rename", false, v -> renameSound(f, () -> { changed[0] = true; fill[0].run(); }), false));
                r.addView(dangerButton("Delete", v -> new android.app.AlertDialog.Builder(this)
                        .setMessage("Delete " + f.getName() + "?\n\nIt's removed from every game on the next push. The original file you picked stays where it was.")
                        .setPositiveButton("Delete", (x, w) -> { stopPreview(); if (f.delete()) { changed[0] = true; log("deleted sound " + f.getName()); } fill[0].run(); })
                        .setNegativeButton("Cancel", null).show()));
                list.addView(r);
            }
            dlg.setTitle("Sounds (" + fs.size() + ", " + DeviceInfo.mb(total) + ")");
        };
        fill[0].run();
        dlg.setOnDismissListener(x -> {
            stopPreview();
            if (changed[0]) { io.execute(this::pushSoundsNow); ui.post(this::rebuild); }
        });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(dp(900), dp(700));
    }

    void renameSound(File f, Runnable after) {
        String name = f.getName(), ext = name.substring(name.lastIndexOf('.'));
        EditText e = new EditText(this);
        e.setText(name.substring(0, name.lastIndexOf('.')));
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this).setTitle("Rename").setView(e)
                .setPositiveButton("Rename", (d, w) -> {
                    String n = e.getText().toString().replaceAll("[/\\\\:*?\"<>|]", "_").trim();
                    if (n.isEmpty()) return;
                    File to = new File(f.getParentFile(), n + ext);
                    if (to.exists()) { log(to.getName() + " already exists"); return; }
                    stopPreview();
                    if (f.renameTo(to)) log("renamed " + name + " -> " + to.getName()); else log("couldn't rename " + name);
                    after.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    static int soundCount() {
        File[] fs = Paths.SOUNDS.listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.isFile() && Sounds.isAudio(f.getName())) n++;
        return n;
    }

    void removeLib(File f) {
        io.execute(() -> {
            log(f.delete() ? "removed " + f.getName() + " (a running game keeps it until it restarts)" : "couldn't remove " + f);
            core.syncLibWatcher();
            ui.post(this::rebuild);
        });
    }

    View libsBox(String pkg) {
        LinearLayout d = vbox();
        d.addView(section("Native libs (.so)"));
        List<File> ls = Core.libs(pkg);
        for (File f : ls) {
            LinearLayout r = hbox();
            TextView t = text(f.getName() + "   " + (f.length() / 1024) + " KB", 14, col("Text"));
            r.addView(t, lpm(0, -2, 1, 3));
            r.addView(dangerButton("Remove", v -> removeLib(f)), lpm(-2, -2, 0, 3));
            d.addView(r);
        }
        LinearLayout r = hbox();
        r.addView(withIcon(button("Add .so", ls.isEmpty(), v -> pickLib(), true), "upload"), lpm(0, -2, 1, 3));
        d.addView(r);
        TextView h = text("arm64 libs (ByNameModding etc.) loaded into the game every time it starts, also from the Quest Library. "
                + "Log: " + Core.libsLog().getPath(), 12, col("TextDisabled"));
        h.setPadding(0, dp(2), 0, 0);
        d.addView(h);
        return d;
    }

    void launchAndInject() {
        if (sel != null && cfg.logcatOnLaunch && canReadLogs()) startLogcat(sel);
        if (!rootOk) {
            job("launch (no root)", g -> {
                core.launch(g.ai.packageName, false);
                log(g.patched ? "launching " + g.label + ", it's patched so the menu loads by itself after " + cfg.delay + "s"
                        : "launched " + g.label + " without the menu: no root, so use Patch + install below to get the menu in it");
            }, false);
            return;
        }
        job("launch + inject", g -> {
            File agent = core.prepare(g.ai, cfg, false);
            if (cfg.authSpoof) { core.startSpoof(true); log("auth spoof armed (appsafety)"); }
            core.startInjector(g.ai.packageName, agent, cfg.delay, cfg.v8);
            log("launching " + g.label + ", the menu loads " + cfg.delay + "s after it opens");
            core.launch(g.ai.packageName, true);
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
        if (req == REQ_PICK_LIB) onLibPicked(res, data);
        if (req == REQ_PICK_SOUNDS) onSoundsPicked(res, data);
    }

    @Override protected void onDestroy() {
        // the audioserver hook stays loaded on purpose (party chat keeps working with the app closed); Unload hook removes it
        if (noRoot != null) noRoot.close();
        logcat.stop();
        if (updater != null) updater.close();
        if (sfx != null) sfx.release();
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
        repo.setTextSize(15 * st.uiScale());
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
        android.util.Log.i("vrfrida", s);
        lastLine = s;
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
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density * (st == null ? 1f : st.uiScale())); }

    float rounding() { return 12f; }      // M3 medium shape

    GradientDrawable rr(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp * (st == null ? 1f : st.corners())));
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

    Typeface medium() { return st.typeface(true); }

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
        // keep icon + label together in the middle of wide (weighted / stacked) buttons, like M3
        b.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int content = dp(18) + dp(8) + (int) Math.ceil(b.getPaint().measureText(b.getText().toString()));
            int pad = Math.max(dp(16), (r - l - content) / 2);
            if (b.getPaddingLeft() != pad) b.post(() -> b.setPadding(pad, b.getPaddingTop(), Math.max(dp(8), r - l - content - pad), b.getPaddingBottom()));
        });
        return b;
    }

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp * st.uiScale());
        t.setTextColor(color);
        t.setTypeface(st.typeface(false));
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
        b.setOnClickListener(v -> { if (!sfx.playedWithin(80)) sfx.play("click"); if (l != null) l.onClick(v); });
        pressMotion(b);
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
        box.setOnClickListener(x -> { v[0] = !v[0]; sfx.play(v[0] ? "on" : "off"); sw.invalidate(); set.accept(v[0]); });
        return box;
    }

    /** slider: FrameBg track, SliderFill, SliderGrab thumb, value on the right */
    View slider(double value, double min, double max, int decimals, java.util.function.Consumer<Double> set) { return slider(value, min, max, decimals, set, null); }

    View slider(double value, double min, double max, int decimals, java.util.function.Consumer<Double> set, Runnable onRelease) {
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
            @Override public void onStopTrackingTouch(SeekBar s) { sfx.play("click"); if (onRelease != null) ui.post(onRelease); }
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
            sfx.play("open");
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
                o.setOnClickListener(x -> { sfx.play("click"); pw.dismiss(); set.accept(k); });
                list.addView(o, lp(-1, -2, 0));
            }
            if (st.motion()) pw.setAnimationStyle(android.R.style.Animation_Dialog);
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

        applyHostStyle();
        String nav = st.nav();
        boolean side = nav.equals("left") || nav.equals("right");
        LinearLayout root = side ? hbox() : vbox();
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(st.bg().equals("off") ? m3("surface") : 0);
        if (nav.equals("left")) root.addView(navRail(), lp(dp(st.navLabels() ? 96 : 80), -1, 0));
        if (nav.equals("top")) root.addView(navBar(), lp(-1, -2, 0));

        LinearLayout content = vbox();
        content.addView(titleBar());
        topProgress = new LinearProgress();
        content.addView(topProgress, lp(-1, dp(4), 0));
        showProgress();
        tabScroll = new ScrollView(this);
        tabScroll.setFillViewport(true);
        tabScroll.setVerticalScrollBarEnabled(true);
        View body = tab == 0 ? gamesTab() : tab == 1 ? pluginsTab() : tab == 2 ? storeTab() : tab == 3 ? lookTab() : tab == 4 ? logTab() : tab == 5 ? infoTab() : tab == 6 ? logcatTab() : tab == 7 ? micTab() : tab == TAB_TOOLS ? toolsTab() : customizeTab();
        tabScroll.addView(body);
        tabScroll.setPadding(dp(16), dp(8), dp(20), dp(16));
        tabScroll.setClipToPadding(false);
        content.addView(tabScroll, lp(-1, 0, 1));
        root.addView(content, side ? lp(0, -1, 1) : lp(-1, 0, 1));
        if (nav.equals("right")) root.addView(navRail(), lp(dp(st.navLabels() ? 96 : 80), -1, 0));
        if (nav.equals("bottom")) root.addView(navBar(), lp(-1, -2, 0));
        slot.removeAllViews();
        slot.addView(root, new FrameLayout.LayoutParams(-1, -1));
        final int y = scrollY[tab];
        tabScroll.post(() -> tabScroll.scrollTo(0, y));
        if (animateNext) { animateNext = false; enterMotion(body); }
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

    static final String[] TAB_ICONS = {"games", "extension", "store", "palette", "article", "bolt", "code", "mic", "terminal", "tune"};

    /** M3 navigation rail: icon in a pill indicator, label under it */
    View navRail() {
        LinearLayout rail = vbox();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean labels = st.navLabels();
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
            item.setPadding(0, dp(6), 0, dp(labels ? 10 : 6));
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
            if (on && navAnim && st.motion()) { pill.setScaleX(0.35f); pill.animate().scaleX(1f).setDuration(mt(320)).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start(); }
            if (labels) {
                TextView lb = text(TABS[i], 12, on ? m3("onSurface") : m3("onSurfaceVariant"));
                lb.setGravity(Gravity.CENTER);
                if (on) lb.setTypeface(medium());
                lb.setPadding(0, dp(4), 0, 0);
                item.addView(lb, lp(-1, -2, 0));
            }
            item.setOnClickListener(x -> goTab(k));
            pill.setOnClickListener(x -> goTab(k));
            pill.setTooltipText(TABS[i]);
            rail.addView(item, lp(-1, -2, 0));
        }
        navAnim = false;
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(rail);
        sv.setBackgroundColor(m3("surfaceContainer", st.bg().equals("off") ? 1f : 0.72f));
        return sv;
    }

    /** horizontal tab bar (Navigation: top / bottom): icon + label pills in a scrolling row */
    View navBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView brand = text("vrfrida", 15, m3("primary"));
        brand.setTypeface(medium());
        brand.setPadding(dp(8), 0, dp(14), 0);
        bar.addView(brand);
        boolean labels = st.navLabels();
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            boolean on = i == tab;
            TextView b = text(labels ? TABS[i] : "", 14, on ? m3("onSecondaryContainer") : m3("onSurfaceVariant"));
            if (on) b.setTypeface(medium());
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setCompoundDrawablesRelative(icon(TAB_ICONS[i], on ? m3("onSecondaryContainer") : m3("onSurfaceVariant"), 20), null, null, null);
            b.setCompoundDrawablePadding(labels ? dp(8) : 0);
            b.setPadding(dp(labels ? 14 : 12), dp(8), dp(labels ? 16 : 12), dp(8));
            b.setBackground(ripple(rr(on ? m3("secondaryContainer") : 0, 18), m3("onSurface")));
            b.setOnClickListener(x -> goTab(k));
            b.setTooltipText(TABS[i]);
            pressMotion(b);
            if (on && navAnim && st.motion()) { b.setScaleX(0.6f); b.animate().scaleX(1f).setDuration(mt(300)).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start(); }
            LinearLayout.LayoutParams p = lp(-2, -2, 0);
            p.setMargins(dp(2), 0, dp(2), 0);
            bar.addView(b, p);
            if (on) { final View fv = b; bar.post(() -> { View sv = (View) bar.getParent(); if (sv instanceof android.widget.HorizontalScrollView) ((android.widget.HorizontalScrollView) sv).smoothScrollTo(Math.max(0, fv.getLeft() - dp(80)), 0); }); }
        }
        navAnim = false;
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.addView(bar);
        sv.setBackgroundColor(m3("surfaceContainer", st.bg().equals("off") ? 1f : 0.72f));
        return sv;
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
        motionDir = k > tab ? 1 : -1;
        tab = k;
        sfx.play("tab");
        animateNext = true; navAnim = true;
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
        q.setTextSize(15 * st.uiScale());
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
        String st = g.injected ? "● menu loaded" : g.running ? "running" : g.outdated ? "repatch needed (patched by an older vrfrida)" : g.patched ? "patched (no root)" + (rootOk ? "" : "  ·  bridge " + g.bridge) : "bridge " + g.bridge;
        int sc = g.injected ? m3("success") : s ? M3.mix(onBg, m3("secondaryContainer"), 0.25) : g.outdated ? m3("warning") : g.running || g.bridge.equals("fresh") || g.patched ? m3("onSurfaceVariant") : m3("warning");
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
        r1.addView(withIcon(button(rootOk ? "Launch + inject" : "Launch", true, v -> launchAndInject(), true), "play"), lpm(0, -2, 1.4f, 3));
        if (rootOk) r1.addView(withIcon(button("Inject now", false, v -> injectNow(), true), "bolt"), lpm(0, -2, 1, 3));
        if (rootOk) r1.addView(withIcon(dangerButton("Unload menu", v -> job("unload", g -> core.stopInjector(g.ai.packageName, true))), "stop"), lpm(0, -2, 1, 3));
        d.addView(r1);
        LinearLayout r2 = hbox();
        r2.addView(button("Plugins ›", false, v -> goTab(1), false), lpm(0, -2, 1, 3));
        r2.addView(button("Menu look ›", false, v -> { lookScope = cfg.followGlobal ? 0 : 1; goTab(3); }, false), lpm(0, -2, 1, 3));
        if (rootOk) r2.addView(withIcon(button("Rebuild bridge", false, v -> job("rebuild bridge", g -> Bridge.ensure(this, g.ai, true, this::log)), true), "build"), lpm(0, -2, 1, 3));
        d.addView(r2);

        if (!rootOk) {
            TextView w = text("No root: Launch just opens the game. For the menu use Patch + install below (then Launch loads it by itself).", 13, col("NotifWarn"));
            w.setPadding(0, dp(6), 0, 0);
            d.addView(w);
        }
        d.addView(section("No root (Frida Gadget)"));
        LinearLayout r3 = hbox();
        r3.addView(withIcon(button(sel.patched ? "Repatch + update" : "Patch + install", !rootOk, v -> patchAndInstall(), true), "build"), lpm(0, -2, 1.4f, 3));
        r3.addView(withIcon(button("Update mods", false, v -> job("update mods (no root)", g -> noRoot.updateAgent(core, g.ai, cfg), false), true), "sync"), lpm(0, -2, 1, 3));
        d.addView(r3);
        if (sel.outdated) {
            TextView w = text("Repatch needed: this game was patched by " + (sel.patchVer < 0 ? "an older vrfrida (from before patches were marked)" : "vrfrida v" + sel.patchVer)
                    + ", the app is v" + NoRoot.appVersion(this) + ". Its menu misses the newer fixes until you press Repatch + update (keeps game data).", 13, col("NotifWarn"));
            w.setPadding(0, dp(4), 0, dp(2));
            d.addView(w);
        }
        TextView nr = text(sel.patched
                ? "Patched: the menu starts by itself in this game. Repatch after changing plugins / menu or after a store update (installs as an update, keeps game data)."
                : "Copies the game, adds Frida Gadget + the menu, re-signs it and installs it. The first time the store copy is uninstalled (Android deletes its local data; online saves stay). Uses Menu Delay and V8 from below.", 12, col("TextDisabled"));
        nr.setPadding(0, dp(2), 0, 0);
        d.addView(nr);

        d.addView(section("Launch options"));
        String pkg = sel.ai.packageName;
        d.addView(row("Menu Delay (s)", slider(cfg.delay, 0, 120, 0, v -> { cfg.delay = (int) Math.round(v); cfg.save(this, pkg); })));
        d.addView(row("Synced look", checkbox(cfg.followGlobal, "use the All games look from the Menu Look tab", v -> { cfg.followGlobal = v; cfg.save(this, pkg); })));
        if (rootOk) d.addView(row("Auth spoof", checkbox(cfg.authSpoof, "spoof root in appsafety so games authenticate (needed by most)", v -> { cfg.authSpoof = v; cfg.save(this, pkg); if (!v && rootOk) io.execute(() -> core.stopSpoof(true)); })));
        d.addView(row("V8 Runtime", checkbox(cfg.v8, "faster than quickjs for heavy hooks", v -> { cfg.v8 = v; cfg.save(this, pkg); })));
        d.addView(row("Push Sounds", checkbox(cfg.sounds, "soundboard clips, converted on the headset", v -> { cfg.sounds = v; cfg.save(this, pkg); })));
        d.addView(row("Push Patterns", checkbox(cfg.patterns, "menu background pictures", v -> { cfg.patterns = v; cfg.save(this, pkg); })));

        d.addView(section("Sounds (every game)"));
        LinearLayout rs = hbox();
        rs.addView(withIcon(button("Add sounds", true, v -> pickSounds(), true), "upload"), lpm(0, -2, 1, 3));
        rs.addView(withIcon(button("Manage", false, v -> soundManager(), false), "article"), lpm(0, -2, 1, 3));
        rs.addView(withIcon(button("Push to all games", false, v -> io.execute(this::pushSoundsNow), true), "sync"), lpm(0, -2, 1, 3));
        d.addView(rs);
        TextView sn = text(soundCount() + " sound(s) in " + Paths.SOUNDS + ". Pick mp3 / ogg / wav / m4a files from the headset: they're converted and copied into every game's soundboard (root or no root: without root it uses the game's Android/obb folder, so allow Install unknown apps).", 12, col("TextDisabled"));
        sn.setPadding(0, dp(2), 0, 0);
        d.addView(sn);
        d.addView(row("Clip length (s)", slider(Sounds.maxSec, 0, 60, 0, v -> {
            int n = (int) Math.round(v);
            if (n == Sounds.maxSec) return;
            Sounds.maxSec = n;
            dev.vrfrida.Settings.prefs(this).edit().putInt("soundMaxSec", n).apply();
        })));
        TextView cl = text("Keep only the first N seconds of every sound, 0 = whole file. Full songs take ~3 MB a minute per game; a soundboard rarely needs more than 15 s. Press Push to all games after changing it.", 12, col("TextDisabled"));
        cl.setPadding(0, dp(2), 0, 0);
        d.addView(cl);
        d.addView(row("Mic soundboard", checkbox(Core.micGames.contains(pkg), "play your sounds through your mic in this game (menu \u203a Plugins \u203a mic soundboard). Copies your sounds into it", v -> {
            Core.setMicGame(this, pkg, v);
            io.execute(this::pushSoundsNow);   // on: copies the sounds in, off: removes them
        })));
        if (rootOk) d.addView(libsBox(pkg));

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
        e.setTextSize(15 * st.uiScale());
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
        if (!rootOk) lookScope = 0;
        String[] scopes = sel != null && rootOk ? new String[]{"All games (synced)", sel.label + " only"} : new String[]{"All games (synced)"};
        View sc = combo(scopes, Math.min(lookScope, scopes.length - 1), i -> { flushSave(); lookScope = i; rebuild(); });
        d.addView(row("Editing", sc));
        d.addView(menuPreview());

        if (lookScope == 0 || sel == null) {
            TextView h = text("One look for every game: games with \"Synced look\" on (the default) get these settings copied into their menu on every Launch / Inject. Only what you change here is copied; mods and plugin settings in each game are kept.", 13, col("TextDisabled"));
            h.setPadding(dp(4), dp(4), 0, dp(4));
            d.addView(h);
            LinearLayout r = hbox();
            if (rootOk) r.addView(button("Apply to all games now", true, v -> applyLookToAll(), true), lpm(0, -2, 1.3f, 3));
            if (sel != null && rootOk) r.addView(button("Copy from " + sel.label, false, v -> copyLookFromGame(), true), lpm(0, -2, 1, 3));
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
        d.addView(row("App theme, layout, sounds, cursor", withIcon(button("Open Customize", false, v -> goTab(TAB_CUSTOMIZE), false), "tune")));
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
    // ---------------------------------------------------------------- Info tab (questfetch + debug report)
    // drawn like a terminal running fastfetch with the user's own config: planet logo, coloured keys, colour blocks
    View infoTab() {
        LinearLayout d = vbox();
        TextView qf = terminal(d, "questfetch", 13);
        // logo on a real character grid (Android's monospace font draws block characters wider than letters)
        LinearLayout fetchRow = new LinearLayout(this);
        fetchRow.setOrientation(LinearLayout.HORIZONTAL);
        LogoView logoView = new LogoView(DeviceInfo.LOGO, 13);
        logoView.setPadding(dp(8), dp(18), dp(28), 0);
        fetchRow.addView(logoView, new LinearLayout.LayoutParams(-2, -2));
        ViewGroup card = (ViewGroup) qf.getParent();
        card.removeView(qf);
        fetchRow.addView(qf, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(fetchRow);
        LinearLayout b = hbox();
        b.addView(withIcon(button("Copy info", true, v -> copyText("vrfrida info", infoText), false), "upload"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button("Send debug report", false, v -> sendReport(), true), "upload"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button("Refresh", false, v -> rebuild(), false), "refresh"), lpm(0, -2, 1, 3));
        LinearLayout.LayoutParams blp = lp(-1, -2, 0);
        blp.setMargins(0, dp(8), 0, 0);
        d.addView(b, blp);
        TextView rep = terminal(d, "questfetch --debug" + (sel != null ? " " + sel.ai.packageName : ""), 12);
        rep.setText(span("collecting...", DeviceInfo.DIM, false));
        TextView gl = terminal(d, "tail " + (sel != null ? "Android/obb/" + sel.ai.packageName + "/vrfrida-game.log" : "vrfrida-game.log"), 12);
        final Game g = sel;
        io.execute(() -> {
            int games, patched = 0;
            synchronized (this.games) { games = this.games.size(); for (Game x : this.games) if (x.patched) patched++; }
            long ver = updater.installedVersion();
            List<String[]> rows = DeviceInfo.fetch(this, rootOk, games, patched, Core.allPlugins().size(), soundCount(), ver);
            String report = DeviceInfo.report(this, rootOk, g != null ? g.ai : null, g != null ? g.fp : null, ver);
            String glog = g == null ? "select a game on the Games tab" : readTail(NoRoot.obbGameLog(g.ai.packageName), 12000,
                    "no game log yet. Patched games write one here once they were patched with this vrfrida version and started (it needs \"Install unknown apps\" for vrfrida to read it)");
            ui.post(() -> {
                qf.setText(fetchText(rows));
                rep.setText(colorReport(report));
                gl.setText(glog);
                StringBuilder plain = new StringBuilder("vrfrida@" + Build.MODEL + "\n");
                for (String[] r : rows) plain.append(r[0]).append(": ").append(r[1]).append('\n');
                infoText = plain + "\n" + report;
            });
        });
        return d;
    }
    String infoText = "";

    /** a terminal panel: dark card, "❯ command" prompt line, monospace output. returns the output view */
    TextView terminal(LinearLayout parent, String command, float sp) {
        LinearLayout card = vbox();
        card.setBackground(rr(DeviceInfo.BG, 14));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        TextView prompt = text("", sp, DeviceInfo.TEXT);
        prompt.setTypeface(Typeface.MONOSPACE);
        android.text.SpannableStringBuilder p = new android.text.SpannableStringBuilder();
        p.append(span("vrfrida", DeviceInfo.C3, true)).append(span(" ~ ", DeviceInfo.DIM, false)).append(span("❯ ", DeviceInfo.C1, true)).append(command);
        prompt.setText(p);
        card.addView(prompt);
        TextView out = text("", sp, DeviceInfo.TEXT);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setLineSpacing(0, 1.05f);
        out.setPadding(0, dp(6), 0, 0);
        card.addView(out);
        LinearLayout.LayoutParams lp = lp(-1, -2, 0);
        lp.setMargins(0, dp(12), 0, 0);
        parent.addView(card, lp);
        return out;
    }

    /** fastfetch layout: logo (padding top 1, left 1, right 3), then title, ----, "Key  value" rows, two rows of colour blocks */
    CharSequence fetchText(List<String[]> rows) {
        String title = "vrfrida@" + Build.MODEL.replace(' ', '_');
        List<CharSequence> right = new ArrayList<>();
        right.add("");
        android.text.SpannableStringBuilder t = new android.text.SpannableStringBuilder();
        t.append(span("vrfrida", DeviceInfo.TITLE, true)).append(span("@", DeviceInfo.TEXT, false)).append(span(Build.MODEL.replace(' ', '_'), DeviceInfo.TITLE, true));
        right.add(t);
        right.add(span(new String(new char[title.length()]).replace('\0', '-'), DeviceInfo.TEXT, false));
        for (String[] r : rows) {
            android.text.SpannableStringBuilder l = new android.text.SpannableStringBuilder();
            l.append(span(r[0], DeviceInfo.KEY, true)).append("  ").append(span(r[1], DeviceInfo.TEXT, false));
            right.add(l);
        }
        right.add("");
        for (int row = 0; row < 2; row++) {
            android.text.SpannableStringBuilder bl = new android.text.SpannableStringBuilder();
            for (int c : DeviceInfo.BLOCKS) bl.append(span("███", row == 0 ? c : MenuLook.argb(new float[]{((c >> 16) & 255) / 255f * 0.6f, ((c >> 8) & 255) / 255f * 0.6f, (c & 255) / 255f * 0.6f, 1f}), false));
            right.add(bl);
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < right.size(); i++) { sb.append(right.get(i)); if (i < right.size() - 1) sb.append('\n'); }
        return sb;
    }

    /** fastfetch logo drawn cell by cell: block characters as exact rectangles, everything else centred in its cell */
    final class LogoView extends View {
        final String[] lines; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float cw, ch;
        int cols;
        LogoView(String[] src, float sp) {
            super(MainActivity.this);
            lines = src;
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
            cw = paint.measureText("M");
            ch = paint.getFontSpacing() * 1.05f;
            for (String l : src) cols = Math.max(cols, l.replaceAll("\\$[1-3]", "").length());
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) Math.ceil(cols * cw) + getPaddingLeft() + getPaddingRight(), (int) Math.ceil(lines.length * ch) + getPaddingTop() + getPaddingBottom());
        }
        @Override protected void onDraw(Canvas c) {
            float x0 = getPaddingLeft(), y0 = getPaddingTop();
            Paint.FontMetrics fm = paint.getFontMetrics();
            for (int r = 0; r < lines.length; r++) {
                String l = lines[r];
                int color = DeviceInfo.C1, col = 0;
                for (int i = 0; i < l.length(); i++) {
                    char k = l.charAt(i);
                    if (k == '$' && i + 1 < l.length() && l.charAt(i + 1) >= '1' && l.charAt(i + 1) <= '3') {
                        int n = l.charAt(++i) - '0';
                        color = n == 1 ? DeviceInfo.C1 : n == 2 ? DeviceInfo.C2 : DeviceInfo.C3;
                        continue;
                    }
                    float x = x0 + col * cw, y = y0 + r * ch;
                    col++;
                    if (k == ' ') continue;
                    paint.setColor(color);
                    switch (k) {
                        case '█': c.drawRect(x, y, x + cw + 0.5f, y + ch + 0.5f, paint); break;
                        case '▀': c.drawRect(x, y, x + cw + 0.5f, y + ch / 2, paint); break;
                        case '▄': c.drawRect(x, y + ch / 2, x + cw + 0.5f, y + ch + 0.5f, paint); break;
                        case '░': case '▒': case '▓':
                            paint.setAlpha(k == '░' ? 70 : k == '▒' ? 130 : 200);
                            c.drawRect(x, y, x + cw + 0.5f, y + ch + 0.5f, paint);
                            break;
                        default:
                            String g = String.valueOf(k);
                            c.drawText(g, x + (cw - paint.measureText(g)) / 2, y + (ch - fm.bottom - fm.top) / 2, paint);
                    }
                }
            }
        }
    }

    CharSequence logoLine(String src, int width) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int color = DeviceInfo.C1, vis = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$([1-3])").matcher(src);
        int at = 0;
        while (true) {
            boolean f = m.find();
            String chunk = src.substring(at, f ? m.start() : src.length());
            if (!chunk.isEmpty()) { sb.append(span(chunk, color, false)); vis += chunk.length(); }
            if (!f) break;
            int k = m.group(1).charAt(0) - '0';
            color = k == 1 ? DeviceInfo.C1 : k == 2 ? DeviceInfo.C2 : DeviceInfo.C3;
            at = m.end();
        }
        for (; vis < width; vis++) sb.append(' ');
        return sb;
    }

    /** debug report: [section] headers in the title colour, keys in the key colour, NO / NOT in red */
    CharSequence colorReport(String rep) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (String line : rep.split("\n", -1)) {
            if (line.startsWith("[")) sb.append(span(line, DeviceInfo.TITLE, true));
            else if (line.startsWith("  ") && line.length() > 17) {
                sb.append(span(line.substring(0, 17), DeviceInfo.KEY, false));
                String v = line.substring(17);
                sb.append(v.startsWith("NO") || v.startsWith("NOT") || v.contains("NOT granted") ? span(v, 0xFFFFB4AB, true) : span(v, DeviceInfo.TEXT, false));
            } else sb.append(span(line, DeviceInfo.TEXT, false));
            sb.append('\n');
        }
        return sb;
    }

    CharSequence span(String s, int color, boolean bold) {
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, s.length(), 0);
        if (bold) sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, s.length(), 0);
        return sp;
    }

    void copyText(String label, String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, s));
        log("copied " + label + " to the clipboard (" + s.length() + " chars)");
    }

    static String readTail(File f, int max, String missing) {
        if (!f.isFile()) return missing;
        try {
            byte[] b = Files.read(f);
            String t = new String(b, "UTF-8");
            return t.length() > max ? "…\n" + t.substring(t.length() - max) : t;
        } catch (Exception e) { return "can't read " + f + ": " + e.getMessage(); }
    }

    // ---------------------------------------------------------------- debug report upload
    void sendReport() {
        Game g = sel;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Send debug report?")
                .setMessage("Uploads one text file to the vrfrida server and gives you a 6-letter code to post (Discord etc).\n\n"
                        + "It has: the Info tab (headset model, Android build, storage, permissions, your installed games count)"
                        + (g != null ? ", and for " + g.label + ": its version, patch state, crash report, game log and the recorded logcat" : "")
                        + ".\nGame logs can contain your in-game name or ids.\n\nOnly the vrfrida dev can open it with the code. It's deleted after 7 days.")
                .setPositiveButton("Send", (d, w) -> job("send debug report", x -> {
                    String code = uploadReport(g);
                    log("debug report sent: code " + code + " (valid 7 days)");
                    ui.post(() -> showReportCode(code));
                }, false))
                .setNegativeButton("Cancel", null).show();
    }

    /** background thread */
    String uploadReport(Game g) throws Exception {
        if (Store.uploadUrl().isEmpty()) throw new Exception("no server set in this build");
        long ver = updater.installedVersion();
        int games, patched = 0;
        synchronized (this.games) { games = this.games.size(); for (Game x : this.games) if (x.patched) patched++; }
        StringBuilder t = new StringBuilder();
        t.append("vrfrida@").append(Build.MODEL).append('\n');
        for (String[] r : DeviceInfo.fetch(this, rootOk, games, patched, Core.allPlugins().size(), soundCount(), ver)) t.append(r[0]).append(": ").append(r[1]).append('\n');
        t.append('\n').append(DeviceInfo.report(this, rootOk, g != null ? g.ai : null, g != null ? g.fp : null, ver));
        if (g != null) {
            String pkg = g.ai.packageName;
            t.append("\n==== crashes ====\n").append(CrashReader.read(pkg, g.ai.uid, rootOk, canReadLogs()));
            t.append("\n==== game log ====\n").append(readTail(NoRoot.obbGameLog(pkg), 120000, "none"));
            t.append("\n\n==== logcat (last recording) ====\n").append(readTail(GameLogcat.fileFor(pkg), 300000, "none recorded (Logcat tab)"));
        }
        synchronized (appLog) { t.append("\n\n==== vrfrida log ====\n").append(appLog.length() > 60000 ? appLog.substring(appLog.length() - 60000) : appLog); }
        String text = t.length() > 590000 ? t.substring(0, 590000) + "\n[cut]" : t.toString();
        JSONObject b = new JSONObject();
        b.put("text", text);
        byte[] body = b.toString().getBytes("UTF-8");
        java.net.HttpURLConnection c = Updater.open(Store.uploadUrl() + "/v1/report/submit");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(body.length);
        try (java.io.OutputStream o = c.getOutputStream()) { o.write(body); }
        int st = c.getResponseCode();
        java.io.InputStream in = st < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = in == null ? "" : new String(Files.readAll(in), "UTF-8");
        JSONObject r;
        try { r = new JSONObject(resp); } catch (Exception e) { throw new Exception("server answered " + st + (st == 404 ? " (the server doesn't have debug reports yet)" : "")); }
        if (!r.optBoolean("ok")) throw new Exception(r.optString("err", "server answered " + st));
        return r.getString("code");
    }

    void showReportCode(String code) {
        TextView big = text(code, 44, m3("primary"));
        big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        big.setGravity(Gravity.CENTER);
        big.setLetterSpacing(0.2f);
        big.setTextIsSelectable(true);
        big.setPadding(dp(16), dp(20), dp(16), dp(8));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Debug report sent")
                .setView(big)
                .setMessage("Post this code to the vrfrida dev. It works for 7 days.")
                .setPositiveButton("Copy code", (d, w) -> copyText("report code", code))
                .setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- Logcat tab
    final GameLogcat logcat = new GameLogcat();
    TextView logcatView, logcatHead;

    boolean canReadLogs() { return rootOk || checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED; }

    void startLogcat(Game g) {
        logcat.start(g.ai.packageName, g.ai.uid, rootOk, cfg.logcatWarn, () -> ui.post(this::refreshLogcat));
        log("logcat: recording " + g.label + " -> " + GameLogcat.fileFor(g.ai.packageName));
        ui.post(this::refreshLogcat);
    }

    void refreshLogcat() {
        if (logcatView == null || tab != 6) return;
        boolean atEnd = tabScroll != null && tabScroll.getScrollY() + tabScroll.getHeight() >= logcatView.getHeight() - 60;
        logcatView.setText(logcat.pkg == null ? "" : logcat.text(30000));
        if (logcatHead != null) logcatHead.setText(logcat.pkg == null ? "not recording"
                : (logcat.running ? "recording " : "stopped: ") + logcat.pkg + "  ·  " + logcat.total + " lines  ·  saved to " + GameLogcat.fileFor(logcat.pkg));
        if (atEnd && tabScroll != null) tabScroll.post(() -> tabScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------- Mic tab (system-wide mic soundboard, root)
    SysMic sysMic;
    volatile boolean sysMicOn;
    TextView sysMicStatus, sysMicLog;

    View micTab() {
        LinearLayout d = vbox();
        d.addView(text("Mic soundboard", 20, m3("onSurface")));
        TextView h = text("Two ways to play your sounds through your microphone:\n"
                + "  \u2022 In a game: open the menu \u203a Plugins \u203a mic soundboard (every game, root or no root). Turn on \"Mic soundboard\" for the game on the Games tab so your sounds get copied into it.\n"
                + "  \u2022 Everywhere (root): below. vrfrida hooks the headset's audio service, so every app that records the mic gets the sound as your voice: Meta party chat, every game's voice chat, recorders.", 13, m3("onSurfaceVariant"));
        h.setPadding(0, dp(4), 0, dp(4));
        d.addView(h);
        if (!rootOk) {
            TextView w = text("System-wide needs root. Use the in-game one from the menu.", 14, col("NotifWarn"));
            w.setPadding(0, dp(8), 0, 0);
            d.addView(w);
            return d;
        }
        android.content.SharedPreferences pr = dev.vrfrida.Settings.prefs(this);
        d.addView(section("Everywhere (root)"));
        sysMicStatus = text(sysMicOn ? "hook loaded in audioserver" : "not loaded (Play loads it)", 13, sysMicOn ? col("NotifSuccess") : m3("onSurfaceVariant"));
        d.addView(sysMicStatus);
        LinearLayout b = hbox();
        b.addView(withIcon(button("Stop sound", true, v -> io.execute(() -> { try { sysMic.stopSound(); } catch (Exception e) { log("mic: " + e.getMessage()); } stopPreview(); }), false), "stop"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button(sysMicOn ? "Unload hook" : "Load hook", false, v -> io.execute(() -> {
            if (sysMicOn) { sysMic.stop(); sysMicOn = false; log("mic: audioserver hook unloaded"); }
            else micStart();
            ui.post(this::rebuild);
        }), true), sysMicOn ? "clear" : "bolt"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button("Status", false, v -> io.execute(() -> { try { sysMic.status(); Thread.sleep(400); } catch (Exception e) { log("mic: " + e.getMessage()); } ui.post(this::refreshMicLog); }), false), "refresh"), lpm(0, -2, 1, 3));
        d.addView(b);
        d.addView(row("Volume", slider(pr.getFloat("micVol", 1f), 0, 3, 2, v -> { pr.edit().putFloat("micVol", v.floatValue()).apply(); micSettings(); })));
        d.addView(row("Mute real voice", checkbox(pr.getBoolean("micMute", false), "only the sound goes out while it plays", v -> { pr.edit().putBoolean("micMute", v).apply(); micSettings(); })));
        d.addView(row("Loop", checkbox(pr.getBoolean("micLoop", false), "repeat until Stop sound", v -> { pr.edit().putBoolean("micLoop", v).apply(); micSettings(); })));
        d.addView(row("Hear it myself", checkbox(pr.getBoolean("micHear", true), "also play it on the headset (apps never play your mic back to you)", v -> pr.edit().putBoolean("micHear", v).apply())));

        List<File> sounds = soundFiles();
        d.addView(section("Sounds (" + sounds.size() + ")"));
        if (sounds.isEmpty()) d.addView(text("No sounds yet: add some on the Games tab (Sounds \u203a Add sounds).", 13, col("TextDisabled")));
        for (File f : sounds) {
            LinearLayout r = hbox();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(2), 0, dp(2));
            TextView n = text(f.getName().replaceAll("\\.[^.]+$", ""), 14, m3("onSurface"));
            r.addView(n, lpm(0, -2, 1, 3));
            r.addView(withIcon(button("Play", false, v -> io.execute(() -> micPlay(f)), false), "play"), lpm(-2, -2, 0, 3));
            d.addView(r);
        }
        d.addView(section("Hook log"));
        sysMicLog = terminal(d, "cat " + SysMic.log().getPath(), 11);
        refreshMicLog();
        return d;
    }

    void refreshMicLog() {
        if (sysMicLog == null) return;
        StringBuilder sb = new StringBuilder();
        for (String l : SysMic.tail(6000).split("\n")) if (!l.trim().isEmpty()) sb.append(l).append('\n');
        sysMicLog.setText(sb.length() == 0 ? "(nothing yet)" : sb.toString().trim());
    }

    boolean micStart() {
        try {
            String err = sysMic.start();
            if (err != null) { log("mic: " + err); return false; }
            sysMicOn = true;
            log("mic: audioserver hook loaded");
            ui.post(this::refreshMicLog);
            return true;
        } catch (Exception e) { log("mic: couldn't start: " + e); return false; }
    }

    void micSettings() {
        if (!sysMicOn) return;
        android.content.SharedPreferences pr = dev.vrfrida.Settings.prefs(this);
        io.execute(() -> { try { sysMic.settings(pr.getFloat("micVol", 1f), pr.getBoolean("micMute", false), pr.getBoolean("micLoop", false)); } catch (Exception e) { log("mic: " + e.getMessage()); } });
    }

    void micPlay(File f) {
        android.content.SharedPreferences pr = dev.vrfrida.Settings.prefs(this);
        if (!sysMicOn && !micStart()) return;
        ui.post(this::rebuild);
        try {
            sysMic.play(f, pr.getFloat("micVol", 1f), pr.getBoolean("micMute", false), pr.getBoolean("micLoop", false));
            if (pr.getBoolean("micHear", true)) ui.post(() -> playPreview(f));
            Thread.sleep(300);
        } catch (Exception e) { log("mic: " + e.getMessage()); }
        ui.post(this::refreshMicLog);
    }

    View logcatTab() {
        LinearLayout d = vbox();
        String pkg = sel != null ? sel.ai.packageName : null;
        TextView t = text(sel != null ? sel.label : "select a game on the Games tab", 20, m3("onSurface"));
        d.addView(t);
        if (!canReadLogs()) {
            TextView w = text("No root: Android only shows vrfrida its own log lines. To see the game's, run this once from a PC (SideQuest / adb):\n"
                    + "  adb shell pm grant dev.vrfrida android.permission.READ_LOGS\nthen fully close and reopen vrfrida. The Game log below works without it.", 13, col("NotifWarn"));
            w.setTypeface(Typeface.MONOSPACE);
            w.setTextIsSelectable(true);
            w.setPadding(0, dp(6), 0, dp(6));
            d.addView(w);
        }
        LinearLayout b = hbox();
        b.addView(withIcon(button(logcat.running ? "Restart" : "Start", true, v -> { if (sel != null) startLogcat(sel); rebuild(); }, false), "play"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button("Stop", false, v -> { logcat.stop(); rebuild(); }, false), "stop"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button("Copy", false, v -> copyText("logcat", logcat.text(200000)), false), "upload"), lpm(0, -2, 1, 3));
        b.addView(withIcon(button("Launch game", false, v -> launchAndInject(), true), "play"), lpm(0, -2, 1, 3));
        d.addView(b);
        if (pkg != null) {
            d.addView(row("Start with Launch", checkbox(cfg.logcatOnLaunch, "record this game's logcat every time you press Launch", v -> { cfg.logcatOnLaunch = v; cfg.save(this, pkg); })));
            d.addView(row("Warnings + errors only", checkbox(cfg.logcatWarn, "hide info / debug lines (applies on the next Start)", v -> { cfg.logcatWarn = v; cfg.save(this, pkg); })));
        }
        d.addView(section("Logcat"));
        logcatHead = text("", 12, m3("onSurfaceVariant"));
        d.addView(logcatHead);
        logcatView = text("", 11, col("Text"));
        logcatView.setTypeface(Typeface.MONOSPACE);
        logcatView.setTextIsSelectable(true);
        logcatView.setBackground(rrKey("FrameBg"));
        logcatView.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = lp(-1, -2, 0);
        lp.setMargins(0, dp(6), 0, 0);
        d.addView(logcatView, lp);
        if (pkg != null) {
            d.addView(section("Game log (patched game: loader + menu messages, no root needed)"));
            TextView gl = text(readTail(NoRoot.obbGameLog(pkg), 12000, "none yet (patch with this vrfrida version, then start the game)"), 11, col("Text"));
            gl.setTypeface(Typeface.MONOSPACE);
            gl.setTextIsSelectable(true);
            gl.setBackground(rrKey("FrameBg"));
            gl.setPadding(dp(10), dp(8), dp(10), dp(8));
            d.addView(gl, lp(-1, -2, 0));
        }
        if (pkg != null) {
            LinearLayout cb = hbox();
            cb.addView(withIcon(button("Read crashes", true, v -> readCrashes(), false), "warning"), lpm(0, -2, 1, 3));
            cb.addView(withIcon(button("Copy", false, v -> copyText("crash report", crashText), false), "upload"), lpm(0, -2, 1, 3));
            LinearLayout.LayoutParams clp = lp(-1, -2, 0);
            clp.setMargins(0, dp(14), 0, 0);
            d.addView(cb, clp);
            crashView = terminal(d, "crashes " + pkg, 11);
            crashView.setText(crashText.isEmpty() || !pkg.equals(crashPkg) ? "press Read crashes" : crashText);
        }
        ui.post(this::refreshLogcat);
        return d;
    }

    TextView crashView;
    String crashText = "", crashPkg = "";

    void readCrashes() {
        if (sel == null) return;
        Game g = sel;
        if (crashView != null) crashView.setText("reading...");
        io.execute(() -> {
            String t = CrashReader.read(g.ai.packageName, g.ai.uid, rootOk, canReadLogs());
            ui.post(() -> { crashText = t; crashPkg = g.ai.packageName; if (crashView != null) crashView.setText(colorReport(t)); });
        });
    }

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

    // ================================================================ app style: host window, background, cursor, boot
    AppStyle st;
    UiSfx sfx;
    FrameLayout host, slot;
    AppBgView appBg;
    MenuBgView appMenuBg;
    CursorView cursor;
    BootView boot;
    volatile String lastLine = "";
    boolean animateNext, navAnim;
    int motionDir = 1;
    final ExecutorService toolsExec = Executors.newSingleThreadExecutor();   // Tools tab shell commands

    int m3(String role, float alpha) { return (m3(role) & 0x00FFFFFF) | (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24); }

    /** motion duration scaled by the Motion speed setting */
    long mt(long ms) { return Math.max(1, Math.round(ms / st.motionSpeed())); }

    /** the window: animated background, the tab UI (rebuilt), cursor on top. Built once, rebuild() only swaps the slot */
    void buildHost() {
        host = new FrameLayout(this) {
            @Override public boolean onInterceptHoverEvent(MotionEvent e) { return false; }
        };
        appBg = new AppBgView(this);
        host.addView(appBg, new FrameLayout.LayoutParams(-1, -1));
        appMenuBg = new MenuBgView(this, look, new MenuBgView.Source() {
            @Override public MenuCfg cfg() { return globalCfg; }
            @Override public Map<String, float[]> colors() { return look.colors(globalCfg.str("theme"), MainActivity.this, (float) globalCfg.root.optDouble("rainbowSpeed", 0.05)); }
            @Override public boolean animate() { return st.bgAnimate(); }
        });
        host.addView(appMenuBg, new FrameLayout.LayoutParams(-1, -1));
        slot = new FrameLayout(this);
        host.addView(slot, new FrameLayout.LayoutParams(-1, -1));
        cursor = new CursorView(this);
        host.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);
    }

    /** push the current style into the long-lived views (no rebuild needed for these) */
    void applyHostStyle() {
        if (host == null || S == null) return;
        String bgk = st.bg();
        appBg.set(bgk, st.bgStrength(), st.bgSpeed(), st.bgAnimate(), m3("surface"), m3("primary"), m3("secondary"), m3("tertiary"));
        appMenuBg.setVisibility(bgk.equals("menu") ? View.VISIBLE : View.GONE);
        appMenuBg.setAlpha(st.bgStrength());
        cursor.style(st.cursor(), st.cursorSize(), st.cursorColor(), st.cursorTrail(), m3("primary"), m3("tertiary"));
        android.view.PointerIcon pi = st.hidePointer() && !st.cursor().equals("off")
                ? android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL) : null;
        host.setPointerIcon(pi);
        slot.setPointerIcon(pi);
    }

    void configureSfx(boolean bootSound) {
        boolean on = st.sfx();
        String pack = st.sfxPack();
        float vol = st.sfxVol();
        boolean hov = st.sfxHover();
        io.execute(() -> { sfx.configure(on, pack, vol, hov); if (bootSound) sfx.playWhenReady("boot"); });
    }

    void showBoot(boolean withSound) {
        if (boot != null) return;
        if (S == null) colorsNow();
        boot = new BootView(this, st.boot().equals("off") ? "orbit" : st.boot(), S,
                () -> lastLine.isEmpty() ? status : lastLine, () -> { boot = null; enterMotion(tabScroll != null ? tabScroll.getChildAt(0) : null); });
        host.addView(boot, new FrameLayout.LayoutParams(-1, -1));
        configureSfx(withSound);
    }

    void bootReady() { if (boot != null) boot.ready(); }

    /** M3-ish press / hover motion: shrinks while pressed, lifts a little under the pointer */
    void pressMotion(View v) {
        if (!st.motion()) return;
        android.animation.StateListAnimator sla = new android.animation.StateListAnimator();
        sla.addState(new int[]{android.R.attr.state_pressed}, scaleTo(v, 0.95f, 90));
        sla.addState(new int[]{android.R.attr.state_hovered}, scaleTo(v, 1.035f, 160));
        sla.addState(new int[0], scaleTo(v, 1f, 160));
        v.setStateListAnimator(sla);
    }

    android.animation.Animator scaleTo(View v, float s, long ms) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, s), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, s));
        a.setDuration(mt(ms));
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        return a;
    }

    /** a tab's content comes in: children slide + fade in one after another */
    void enterMotion(View body) {
        if (body == null || !st.motion() || boot != null) return;
        android.view.animation.Interpolator in = new android.view.animation.DecelerateInterpolator(2f);
        if (body instanceof ViewGroup && ((ViewGroup) body).getChildCount() > 0) {
            ViewGroup g = (ViewGroup) body;
            int n = Math.min(g.getChildCount(), 16);
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                c.setAlpha(0f);
                c.setTranslationY(dp(14) * motionDir);
                c.animate().alpha(1f).translationY(0).setStartDelay(mt(i * 26L)).setDuration(mt(300)).setInterpolator(in).start();
            }
        } else {
            body.setAlpha(0f);
            body.animate().alpha(1f).setDuration(mt(250)).start();
        }
    }

    // ---------------------------------------------------------------- window input hooks: cursor, ui sounds
    final int[] hostLoc = new int[2];
    View hoverView;
    float downX, downY;
    boolean downMoved;

    /** deepest visible, enabled, clickable view under (x, y) in v's coordinates */
    static View clickableAt(View v, float x, float y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX(), cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
                if (cx >= 0 && cy >= 0 && cx < c.getWidth() && cy < c.getHeight()) {
                    View r = clickableAt(c, cx, cy);
                    if (r != null) return r;
                }
            }
        }
        return v.isClickable() && v.isEnabled() ? v : null;
    }

    View clickableAtWindow(float wx, float wy) {
        if (host == null) return null;
        host.getLocationInWindow(hostLoc);
        View top = boot != null ? boot : slot;
        return clickableAt(top, wx - hostLoc[0] - top.getLeft(), wy - hostLoc[1] - top.getTop());
    }

    void pointerAt(float wx, float wy, boolean pressed) {
        if (host == null) return;
        host.getLocationInWindow(hostLoc);
        float x = wx - hostLoc[0], y = wy - hostLoc[1];
        View v = clickableAtWindow(wx, wy);
        if (v != hoverView) { if (v != null && !pressed) sfx.play("hover"); hoverView = v; }
        cursor.move(x, y, v != null);
        appBg.px = x; appBg.py = y;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        try {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_ENTER) pointerAt(e.getX(), e.getY(), false);
            else if (a == MotionEvent.ACTION_HOVER_EXIT) { cursor.exit(); hoverView = null; appBg.px = -1; }
        } catch (Throwable ignored) {}
        return super.dispatchGenericMotionEvent(e);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        try {
            if (a == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY(); downMoved = false;
                pointerAt(e.getX(), e.getY(), true);
                host.getLocationInWindow(hostLoc);
                cursor.press(e.getX() - hostLoc[0], e.getY() - hostLoc[1]);
            } else if (a == MotionEvent.ACTION_MOVE) {
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > dp(10)) downMoved = true;
                pointerAt(e.getX(), e.getY(), true);
            }
        } catch (Throwable ignored) {}
        boolean r = super.dispatchTouchEvent(e);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            cursor.release();
            if (a == MotionEvent.ACTION_UP && !downMoved && clickableAtWindow(e.getX(), e.getY()) != null)
                ui.post(() -> { if (!sfx.playedWithin(80)) sfx.play("click"); });   // after performClick, so a widget's own sound wins
        }
        return r;
    }

    // ================================================================ Customize tab
    void styleChanged(boolean rebuild) {
        appLookChanged(false);
        configureSfx(false);
        if (rebuild) rebuild(); else applyHostStyle();
    }

    View sChoice(String label, String key, String[] vals, String[] labels, String cur, boolean rebuild) {
        return row(label, combo(labels, AppStyle.index(vals, cur), i -> { st.put(key, vals[i]); styleChanged(rebuild); }));
    }

    View sBool(String label, String key, boolean cur, String desc, boolean rebuild) {
        return row(label, checkbox(cur, desc, v -> { st.put(key, v); if (rebuild) ui.postDelayed(() -> styleChanged(true), mt(200)); else styleChanged(false); }));
    }

    /** rebuildOnRelease: the value changes layout, so the ui is rebuilt when the slider is let go (not mid-drag) */
    View sSlider(String label, String key, double cur, double min, double max, int dec, boolean rebuildOnRelease) {
        return row(label, slider(cur, min, max, dec, v -> { st.put(key, v); if (!rebuildOnRelease) styleChanged(false); else appLookChanged(false); },
                rebuildOnRelease ? () -> styleChanged(true) : null));
    }

    View themeSwatches() {
        LinearLayout all = vbox();
        String cur = st.theme();
        int perRow = 6;
        LinearLayout r = null;
        for (int i = 0; i < AppStyle.THEMES.length; i++) {
            if (i % perRow == 0) { r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); all.addView(r, lp(-1, -2, 0)); }
            final String id = AppStyle.THEMES[i];
            boolean on = id.equals(cur);
            LinearLayout chip = vbox();
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            chip.setPadding(dp(6), dp(10), dp(6), dp(8));
            int sw = AppStyle.swatch(this, id, st.hue());
            View dot = new View(this) {
                final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c) {
                    float w = getWidth(), h = getHeight(), rad = Math.min(w, h) / 2 - dp(3);
                    if (id.equals("mono")) {
                        pp.setColor(0xFF202020); c.drawCircle(w / 2, h / 2, rad, pp);
                        pp.setColor(0xFFE0E0E0); c.drawArc(w / 2 - rad, h / 2 - rad, w / 2 + rad, h / 2 + rad, -90, 180, true, pp);
                    } else if (id.equals("auto")) {
                        pp.setColor(sw); c.drawCircle(w / 2, h / 2, rad, pp);
                        pp.setColor(0x66FFFFFF); pp.setTextSize(dp(13)); pp.setTextAlign(Paint.Align.CENTER); c.drawText("A", w / 2, h / 2 + dp(5), pp);
                    } else { pp.setColor(sw); c.drawCircle(w / 2, h / 2, rad, pp); }
                    if (on) { pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(dp(2.5f)); pp.setColor(m3("onSurface")); c.drawCircle(w / 2, h / 2, rad + dp(1.5f), pp); pp.setStyle(Paint.Style.FILL); }
                }
            };
            chip.addView(dot, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView lb = text(AppStyle.THEME_LABELS[i], 12, on ? m3("onSurface") : m3("onSurfaceVariant"));
            lb.setGravity(Gravity.CENTER);
            lb.setSingleLine(true);
            lb.setPadding(0, dp(4), 0, 0);
            chip.addView(lb, lp(-1, -2, 0));
            chip.setBackground(ripple(rr(on ? m3("secondaryContainer") : 0, 14), m3("onSurface")));
            chip.setOnClickListener(v -> { st.put("appTheme", id); sfx.play("on"); styleChanged(true); });
            pressMotion(chip);
            r.addView(chip, lpm(0, -2, 1, 3));
        }
        if (r != null) for (int k = AppStyle.THEMES.length % perRow; k > 0 && k < perRow; k++) r.addView(new View(this), lpm(0, 1, 1, 3));
        return all;
    }

    View customizeTab() {
        LinearLayout d = vbox();
        TextView intro = text("How this app looks, moves and sounds. Saved right away, only on this headset (the in-game menu has its own look on the Menu Look tab).", 13, m3("onSurfaceVariant"));
        intro.setPadding(dp(4), dp(4), 0, dp(4));
        d.addView(intro);

        d.addView(section("Theme"));
        d.addView(themeSwatches());
        if (st.theme().equals("custom")) d.addView(sSlider("Custom hue", "hue", st.hue(), 0, 359, 0, true));
        boolean uxLocked = (st.theme().equals("auto") || st.theme().equals("uxpatcher")) && MenuLook.hasUxpatcher(this);
        d.addView(sChoice("Mode" + (uxLocked ? " (UXpatcher decides)" : ""), "mode", AppStyle.MODES, AppStyle.MODE_LABELS, st.mode(), true));

        d.addView(section("Layout"));
        d.addView(sChoice("Navigation", "nav", AppStyle.NAV, AppStyle.NAV_LABELS, st.nav(), true));
        d.addView(sBool("Tab labels", "navLabels", st.navLabels(), "names under the tab icons", true));
        d.addView(sSlider("UI size", "uiScale", st.uiScale(), 0.7, 1.5, 2, true));
        d.addView(sSlider("Corner roundness", "corners", st.corners(), 0, 2.5, 2, true));
        d.addView(sChoice("Font", "font", AppStyle.FONTS, AppStyle.FONT_LABELS, st.font(), true));

        d.addView(section("Background"));
        d.addView(sChoice("App background", "appBg", AppStyle.BGS, AppStyle.BG_LABELS, st.bg(), true));
        d.addView(sSlider("Strength", "bgStrength", st.bgStrength(), 0, 1, 2, false));
        d.addView(sSlider("Speed", "bgSpeed", st.bgSpeed(), 0.1, 3, 2, false));
        d.addView(sBool("Animate", "animate", st.bgAnimate(), "off = still picture (less GPU)", false));

        d.addView(section("Motion"));
        d.addView(sBool("Animations", "motion", st.motion(), "tab transitions, button press / hover, popups", true));
        d.addView(sSlider("Animation speed", "motionSpeed", st.motionSpeed(), 0.3, 3, 2, false));

        d.addView(section("Sounds"));
        d.addView(sBool("UI sounds", "sfx", st.sfx(), "clicks, tab switches, toggles, job done / failed", false));
        d.addView(sChoice("Sound pack", "sfxPack", AppStyle.PACKS, AppStyle.PACK_LABELS, st.sfxPack(), false));
        d.addView(sSlider("Volume", "sfxVol", st.sfxVol(), 0, 1, 2, false));
        d.addView(sBool("Hover ticks", "sfxHover", st.sfxHover(), "soft tick when the pointer moves onto a button", false));
        LinearLayout test = hbox();
        for (String n : new String[]{"click", "tab", "on", "success", "error", "boot"}) {
            TextView b = outlinedButton(n, v -> ui.postDelayed(() -> sfx.play(n), 90), false);
            test.addView(b, lpm(0, -2, 1, 3));
        }
        d.addView(test);

        d.addView(section("Cursor"));
        d.addView(sChoice("Cursor", "cursor", AppStyle.CURSORS, AppStyle.CURSOR_LABELS, st.cursor(), false));
        d.addView(sSlider("Cursor size", "cursorSize", st.cursorSize(), 8, 48, 0, false));
        d.addView(sChoice("Cursor color", "cursorColor", AppStyle.CURSOR_COLORS, AppStyle.CURSOR_COLOR_LABELS, st.cursorColor(), false));
        d.addView(sBool("Trail", "cursorTrail", st.cursorTrail(), "fading dots behind the cursor", false));
        d.addView(sBool("Hide system pointer", "hidePointer", st.hidePointer(), "asks Android to hide its pointer (Horizon OS may still draw its own ray dot)", false));

        d.addView(section("Boot screen"));
        d.addView(sChoice("Boot animation", "boot", AppStyle.BOOTS, AppStyle.BOOT_LABELS, st.boot(), false));
        d.addView(row("Preview", withIcon(button("Play boot screen", false, v -> { showBoot(true); ui.postDelayed(this::bootReady, 400); }, false), "play")));

        d.addView(section("Share / reset"));
        LinearLayout sr = hbox();
        sr.addView(withIcon(button("Copy style code", true, v -> copyText("vrfrida style", "vrstyle:" + android.util.Base64.encodeToString(appLook.root.toString().getBytes(), android.util.Base64.NO_WRAP)), false), "upload"), lpm(0, -2, 1, 3));
        sr.addView(withIcon(button("Paste style code", false, v -> pasteStyle(), false), "download"), lpm(0, -2, 1, 3));
        sr.addView(dangerButton("Reset all", v -> {
            JSONObject o = appLook.root;
            for (String k : new String[]{"appTheme", "mode", "hue", "nav", "navLabels", "uiScale", "corners", "font", "appBg", "bgStrength", "bgSpeed", "animate",
                    "motion", "motionSpeed", "sfx", "sfxVol", "sfxPack", "sfxHover", "cursor", "cursorSize", "cursorColor", "cursorTrail", "hidePointer", "boot"}) o.remove(k);
            log("app style reset to defaults");
            styleChanged(true);
        }), lpm(0, -2, 0.8f, 3));
        d.addView(sr);
        return d;
    }

    void pasteStyle() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence t = cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            String s = t == null ? "" : t.toString().trim();
            if (!s.startsWith("vrstyle:")) { log("clipboard has no style code (it starts with vrstyle:)"); sfx.play("error"); return; }
            JSONObject in = new JSONObject(new String(android.util.Base64.decode(s.substring(8), android.util.Base64.DEFAULT), "UTF-8"));
            java.util.Iterator<String> it = in.keys();
            int n = 0;
            while (it.hasNext()) { String k = it.next(); if (k.equals("menu")) continue; appLook.root.put(k, in.get(k)); n++; }
            log("style pasted (" + n + " settings)");
            sfx.play("success");
            styleChanged(true);
        } catch (Exception e) { log("couldn't read that style code: " + e.getMessage()); sfx.play("error"); }
    }

    // ================================================================ Tools tab
    Map<String, String> props;            // getprop values the Tools tab shows, null = not loaded yet
    String toolOut = "", toolCmd = "";
    String adbInfo = "", selinux = "?", storageInfo = "";

    static final String[][] PERF = {
            {"debug.oculus.refreshRate", "Refresh rate", "Default|72|80|90|120"},
            {"debug.oculus.cpuLevel", "CPU level", "Default|0|1|2|3|4|5"},
            {"debug.oculus.gpuLevel", "GPU level", "Default|0|1|2|3|4|5"},
            {"debug.oculus.textureWidth", "Render resolution (per eye)", "Default|1024|1440|1680|1832|2016|2208|2448|2752|3072"},
            {"debug.oculus.foveation.level", "Fixed foveation", "Default|0|1|2|3|4"},
    };

    void loadTools() {
        toolsExec.execute(() -> {
            Map<String, String> m = new java.util.HashMap<>();
            StringBuilder q = new StringBuilder();
            for (String[] p : PERF) q.append("echo \"").append(p[0]).append("=$(getprop ").append(p[0]).append(")\"\n");
            q.append("echo \"adbport=$(getprop service.adb.tcp.port)\"\n");
            q.append("echo \"ip=$(ip -4 -o addr show wlan0 2>/dev/null | awk '{print $4}' | cut -d/ -f1)\"\n");
            q.append("echo \"selinux=$(getenforce)\"\n");
            Root.Result r = Root.sh(q.toString());
            for (String l : r.out.split("\n")) { int i = l.indexOf('='); if (i > 0) m.put(l.substring(0, i).trim(), l.substring(i + 1).trim()); }
            String port = m.getOrDefault("adbport", ""), ip = m.getOrDefault("ip", "");
            String ai = port.isEmpty() || port.equals("-1") || port.equals("0") ? "off" : "on, " + (ip.isEmpty() ? "no wifi ip" : ip) + ":" + port;
            Root.Result du = Root.sh("cd " + Root.q(Paths.ROOT.getPath()) + " && du -sh .sound_cache logs agents bridges plugins sounds 2>/dev/null");
            ui.post(() -> {
                props = m; adbInfo = ai; selinux = m.getOrDefault("selinux", "?");
                storageInfo = du.out.replace("\t", "  ");
                if (tab == TAB_TOOLS) rebuild();
            });
        });
    }

    void tool(String what, String script) { tool(what, script, true); }

    /** run a root script from the Tools tab, show its output, reload the values */
    void tool(String what, String script, boolean reload) {
        if (!rootOk) { log(what + " needs root"); sfx.play("error"); return; }
        log("── tools: " + what);
        progress(-1);
        toolsExec.execute(() -> {
            Root.Result r = Root.sh(script);
            String out = r.out.isEmpty() ? "(no output, exit " + r.code + ")" : r.out;
            if (out.length() > 20000) out = out.substring(out.length() - 20000);
            String fo = out;
            ui.post(() -> {
                progress(Float.NaN);
                toolOut = "$ " + what + "\n" + fo;
                sfx.play(r.ok() ? "success" : "error");
                log(what + (r.ok() ? ": done" : ": failed (exit " + r.code + ")"));
                if (reload) loadTools(); else if (tab == TAB_TOOLS) rebuild();
            });
        });
    }

    View toolsTab() {
        LinearLayout d = vbox();
        if (!rootOk) {
            TextView w = text("The tools need root (Magisk). Without root there is nothing here to run.", 14, col("NotifWarn"));
            w.setPadding(dp(4), dp(8), 0, dp(8));
            d.addView(w);
            return d;
        }
        if (props == null) { loadTools(); props = new java.util.HashMap<>(); }

        d.addView(section("Performance"));
        TextView ph = text("System properties the VR runtime reads when an app starts: relaunch the game after changing them. They reset on reboot.", 13, m3("onSurfaceVariant"));
        ph.setPadding(dp(4), 0, 0, dp(4));
        d.addView(ph);
        for (String[] p : PERF) {
            String[] opts = p[2].split("\\|");
            String cur = props.getOrDefault(p[0], "");
            int idx = 0;
            for (int i = 1; i < opts.length; i++) if (opts[i].equals(cur)) idx = i;
            String[] labels = opts.clone();
            if (idx == 0 && !cur.isEmpty()) labels[0] = "Custom (" + cur + ")";
            d.addView(row(p[1], combo(labels, idx, i -> {
                String v = i == 0 ? "\"\"" : opts[i];
                String cmd = "setprop " + p[0] + " " + v;
                if (p[0].equals("debug.oculus.textureWidth")) cmd += "\nsetprop debug.oculus.textureHeight " + v;
                tool(p[1] + " -> " + opts[i], cmd);
            })));
        }
        LinearLayout pr = hbox();
        pr.addView(button("Reset all to default", false, v -> {
            StringBuilder s = new StringBuilder();
            for (String[] p : PERF) s.append("setprop ").append(p[0]).append(" \"\"\n");
            s.append("setprop debug.oculus.textureHeight \"\"\n");
            tool("reset performance props", s.toString());
        }, false), lpm(0, -2, 1, 3));
        pr.addView(button("Reload values", false, v -> loadTools(), false), lpm(0, -2, 1, 3));
        d.addView(pr);

        d.addView(section("Device"));
        LinearLayout adb = hbox();
        adb.addView(button("Enable :5555", true, v -> tool("wifi adb on", "setprop service.adb.tcp.port 5555\nstop adbd\nstart adbd"), false), lpm(0, -2, 1, 3));
        adb.addView(button("Disable", false, v -> tool("wifi adb off", "setprop service.adb.tcp.port -1\nstop adbd\nstart adbd"), false), lpm(0, -2, 1, 3));
        d.addView(row("Wi-Fi ADB (" + adbInfo + ")", adb));
        LinearLayout prox = hbox();
        prox.addView(button("Stay awake", false, v -> tool("proximity sensor off", "am broadcast -a com.oculus.vrpowermanager.prox_close"), false), lpm(0, -2, 1, 3));
        prox.addView(button("Normal", false, v -> tool("proximity sensor on", "am broadcast -a com.oculus.vrpowermanager.automation_disable"), false), lpm(0, -2, 1, 3));
        d.addView(row("Proximity sensor", prox));
        LinearLayout se = hbox();
        se.addView(button("Permissive", false, v -> tool("selinux permissive", "setenforce 0\ngetenforce"), false), lpm(0, -2, 1, 3));
        se.addView(button("Enforcing", false, v -> tool("selinux enforcing", "setenforce 1\ngetenforce"), false), lpm(0, -2, 1, 3));
        d.addView(row("SELinux (" + selinux + ")", se));
        d.addView(row("Screenshot", withIcon(button("Take screenshot", false, v -> {
            String f = new File(Paths.ROOT, "captures/shot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png").getPath();
            tool("screenshot", "mkdir -p " + Root.q(new File(f).getParent()) + "\nscreencap -p " + Root.q(f) + " && ls -la " + Root.q(f), false);
        }, false), "download")));

        if (sel != null) {
            String pkg = sel.ai.packageName;
            d.addView(section("Selected game: " + sel.label));
            LinearLayout gr = hbox();
            gr.addView(dangerButton("Force stop", v -> tool("force stop " + pkg, "am force-stop " + Root.q(pkg))), lpm(0, -2, 1, 3));
            gr.addView(button("Clear cache", false, v -> tool("clear cache " + pkg, "rm -rf /data/data/" + pkg + "/cache/* /data/data/" + pkg + "/code_cache/* /sdcard/Android/data/" + pkg + "/cache/* 2>&1; echo cleared"), false), lpm(0, -2, 1, 3));
            gr.addView(button("Process info", false, v -> tool("process info " + pkg,
                    "p=$(pidof " + Root.q(pkg) + ")\nif [ -z \"$p\" ]; then echo 'not running'; exit 0; fi\necho \"pid $p\"\n"
                            + "grep -E 'VmRSS|VmHWM|Threads' /proc/$p/status\necho \"uptime $(ps -o etime= -p $p)\"\n"
                            + "echo \"libs: $(grep -c '\\.so' /proc/$p/maps) so mappings\"\ngrep -q frida /proc/$p/maps && echo 'frida: loaded' || echo 'frida: not loaded'", false), false), lpm(0, -2, 1, 3));
            d.addView(gr);
        }

        d.addView(section("Storage (/sdcard/vrfrida)"));
        if (!storageInfo.isEmpty()) {
            TextView si = text(storageInfo, 13, m3("onSurfaceVariant"));
            si.setTypeface(Typeface.MONOSPACE);
            si.setPadding(dp(12), dp(4), 0, dp(4));
            d.addView(si);
        }
        LinearLayout sc = hbox();
        sc.addView(button("Clear sound cache", false, v -> tool("clear sound cache", "rm -rf " + Root.q(Paths.SOUND_CACHE.getPath()) + "/*; echo cleared (sounds convert again on the next push)"), false), lpm(0, -2, 1, 3));
        sc.addView(button("Clear logs", false, v -> tool("clear logs", "rm -f " + Root.q(Paths.LOGS.getPath()) + "/*.log; echo cleared"), false), lpm(0, -2, 1, 3));
        d.addView(sc);

        d.addView(section("Root shell"));
        LinearLayout sh = hbox();
        EditText cmd = field("command (runs as root)", toolCmd, 2000);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { toolCmd = s.toString(); }
        });
        sh.addView(cmd, lp(0, -2, 1));
        LinearLayout.LayoutParams rp = lp(-2, -2, 0);
        rp.setMargins(dp(8), 0, 0, 0);
        sh.addView(withIcon(button("Run", true, v -> { if (!toolCmd.trim().isEmpty()) tool(toolCmd.trim(), toolCmd, false); }, false), "play"), rp);
        d.addView(sh);
        LinearLayout quick = hbox();
        String[][] qs = {{"oculus props", "getprop | grep -i oculus"}, {"battery", "dumpsys battery"}, {"storage", "df -h /sdcard /data"}, {"top", "top -b -n 1 | head -25"}};
        for (String[] q : qs) quick.addView(outlinedButton(q[0], v -> tool(q[1], q[1], false), false), lpm(0, -2, 1, 3));
        LinearLayout.LayoutParams qp = lp(-1, -2, 0);
        qp.setMargins(0, dp(6), 0, 0);
        d.addView(quick, qp);
        TextView out = terminal(d, "output", 12);
        out.setText(toolOut.isEmpty() ? "run something above" : toolOut);
        out.setTextIsSelectable(true);
        return d;
    }
}
