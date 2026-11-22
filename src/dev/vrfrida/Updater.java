package dev.vrfrida;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Updates the app from GitHub Releases: owner/repo -> releases/latest. The release tag carries the versionCode
 * ("v7" or "7"), the asset vrfrida.apk is the new app (must be signed with the same key as this install, checked before
 * installing), an optional asset menu.js replaces /sdcard/vrfrida/menu.js when it is newer than the local file.
 * The repo has to be public (the app has no GitHub token). questapp/release.sh makes the releases.
 */
final class Updater {
    static final String ACTION_RESULT = "dev.vrfrida.SELF_UPDATE_RESULT";

    static final class Release {
        int version; String tag, apkUrl, menuUrl, notes; long apkSize, published;
    }

    final Activity act;
    final Bridge.Log log;
    BroadcastReceiver receiver;

    Updater(Activity act, Bridge.Log log) { this.act = act; this.log = log; }

    static String repo(Context c) { return Settings.prefs(c).getString("updateRepo", BuildInfo.UPDATE_REPO).trim(); }
    static void setRepo(Context c, String r) { Settings.prefs(c).edit().putString("updateRepo", r.trim()).apply(); }
    static boolean validRepo(String r) { return r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"); }

    long installedVersion() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "vrfrida-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);      // release downloads redirect to objects.githubusercontent.com
        return c;
    }

    /** background thread. null = no release / error (logged) */
    Release latest(String repo) {
        if (!validRepo(repo)) { log.log("update repo must look like owner/name (got \"" + repo + "\")"); return null; }
        try {
            HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/latest");
            int code = c.getResponseCode();
            if (code == 404) { log.log("no releases in github.com/" + repo + " (or the repo is private / doesn't exist)"); return null; }
            if (code != 200) { log.log("GitHub answered " + code + " for " + repo + (code == 403 ? " (rate limited, try again in an hour)" : "")); return null; }
            JSONObject o;
            try (InputStream in = c.getInputStream()) { o = new JSONObject(new String(Files.readAll(in), "UTF-8")); }
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            String digits = r.tag.replaceAll("[^0-9]", "");
            r.version = digits.isEmpty() ? 0 : Integer.parseInt(digits.length() > 9 ? digits.substring(0, 9) : digits);
            r.notes = o.optString("body", "").trim();
            r.published = parseTime(o.optString("published_at", ""));
            JSONArray as = o.optJSONArray("assets");
            if (as != null) for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.getJSONObject(i);
                String n = a.optString("name");
                if (n.equals("vrfrida.apk")) { r.apkUrl = a.optString("browser_download_url"); r.apkSize = a.optLong("size"); }
                else if (n.equals("menu.js")) r.menuUrl = a.optString("browser_download_url");
            }
            return r;
        } catch (Exception e) {
            log.log("update check failed: " + e.getMessage());
            return null;
        }
    }

    static long parseTime(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso).getTime();
        } catch (Exception e) { return 0; }
    }

    /** background thread: what's new, said in the log. returns the release if it is newer than this install */
    Release check(boolean quiet) {
        String repo = repo(act);
        if (repo.isEmpty()) { if (!quiet) log.log("no update repo set (Log tab -> Updates)"); return null; }
        Release r = latest(repo);
        if (r == null) return null;
        long have = installedVersion();
        if (r.version > have && r.apkUrl != null) {
            log.log("update available: " + r.tag + " (you have version " + have + "). Press Update in the Log tab");
            if (!r.notes.isEmpty()) log.log("  " + r.notes.replace("\n", "\n  "));
            return r;
        }
        if (r.version > have) log.log("release " + r.tag + " has no vrfrida.apk asset");
        else if (!quiet) log.log("vrfrida is up to date (version " + have + ", latest release " + r.tag + ")");
        return null;
    }

    static void download(String url, File dst, long size, Bridge.Log log) throws Exception {
        dst.getParentFile().mkdirs();
        File tmp = new File(dst.getPath() + ".part");
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/octet-stream");
        if (c.getResponseCode() != 200) throw new Exception("download answered " + c.getResponseCode());
        long total = size > 0 ? size : c.getContentLengthLong(), got = 0, lastLog = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                got += n;
                if (total > 0 && got - lastLog > total / 5) { lastLog = got; log.log(String.format("  %d%%", got * 100 / total)); }
            }
        }
        if (total > 0 && got != total) { tmp.delete(); throw new Exception("download stopped at " + got + " of " + total + " bytes"); }
        if (!tmp.renameTo(dst)) throw new Exception("couldn't save " + dst);
    }

    /** background thread: download, check the signature, hand to the installer (Android asks to confirm) */
    void update(Release r) throws Exception {
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            log.log("allow \"Install unknown apps\" for vrfrida, then press Update again");
            act.runOnUiThread(() -> act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + act.getPackageName()))));
            return;
        }
        if (r.menuUrl != null) {
            try {
                if (!Paths.MENU.isFile() || Paths.MENU.lastModified() < r.published) {
                    download(r.menuUrl, Paths.MENU, 0, log);
                    log.log("menu.js updated from " + r.tag);
                } else log.log("kept your menu.js (it is newer than the release's, e.g. from vrfrida.py --sync)");
            } catch (Exception e) { log.log("menu.js download failed: " + e.getMessage()); }
        }
        File apk = new File(act.getExternalFilesDir("update"), "vrfrida.apk");
        log.log(String.format("downloading %s (%.1f MB)", r.tag, r.apkSize / 1e6));
        download(r.apkUrl, apk, r.apkSize, log);
        PackageManager pm = act.getPackageManager();
        PackageInfo neu = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (neu == null || !act.getPackageName().equals(neu.packageName)) { apk.delete(); throw new Exception("the downloaded file isn't a vrfrida apk"); }
        PackageInfo mine = pm.getPackageInfo(act.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (!sameSigner(mine, neu)) { apk.delete(); throw new Exception("the release is signed with a different key than this install, not installing it"); }
        install(apk);
    }

    static boolean sameSigner(PackageInfo a, PackageInfo b) {
        if (a.signingInfo == null || b.signingInfo == null) return false;
        Signature[] x = a.signingInfo.getApkContentsSigners(), y = b.signingInfo.getApkContentsSigners();
        return x.length > 0 && y.length > 0 && Arrays.equals(x[0].toByteArray(), y[0].toByteArray());
    }

    void install(File apk) throws Exception {
        registerReceiver();
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(act.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("vrfrida.apk", 0, apk.length())) {
                byte[] b = new byte[1 << 20];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent i = new Intent(ACTION_RESULT).setPackage(act.getPackageName());
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(act, id, i, fl).getIntentSender());
        }
        log.log("installing the update (vrfrida restarts when it's done)");
    }

    void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(confirm); }
                    return;
                }
                if (st != PackageInstaller.STATUS_SUCCESS)
                    log.log("update failed (" + st + "): " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
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
