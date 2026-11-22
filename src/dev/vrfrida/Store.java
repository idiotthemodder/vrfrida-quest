package dev.vrfrida;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Plugin store: index.json in the public store repo (raw.githubusercontent.com), plugins installed into
 * /sdcard/vrfrida/store/plugins (vrfrida.py --sync never touches that folder), what's installed in store/installed.json.
 * Official plugins auto-update. Community plugins are untrusted: installs and every update go through the warning
 * dialog in MainActivity. Uploads go to the chat_server Worker's review queue.
 */
final class Store {
    static final Pattern FILE_RE = Pattern.compile("^[A-Za-z0-9_.-]{1,80}\\.(js|txt|png)$");
    static final Pattern ID_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    static final long MAX_FILE = 4 * 1024 * 1024;

    static final class FileRef { String name, path, sha256; long size; }
    static final class Plugin {
        String id, name, author, desc, version, updated;
        boolean trusted;
        final List<String> games = new ArrayList<>();
        final List<FileRef> files = new ArrayList<>();
        int high, medium, low;
        long size() { long s = 0; for (FileRef f : files) s += f.size; return s; }
        boolean forGame(String pkg) { for (String g : games) if (g.equals("*") || g.equalsIgnoreCase(pkg)) return true; return false; }
    }
    static final class Installed { String id, version, name; boolean trusted; final List<String> files = new ArrayList<>(); }

    final Context ctx;
    final Bridge.Log log;
    volatile List<Plugin> index;          // null = not loaded yet
    volatile long indexAt;
    volatile String error = "";
    final Map<String, Installed> installed = new LinkedHashMap<>();

    Store(Context ctx, Bridge.Log log) { this.ctx = ctx; this.log = log; loadInstalled(); }

    static String repo(Context c) { return Settings.prefs(c).getString("storeRepo", BuildInfo.STORE_REPO).trim(); }
    static String uploadUrl() { return BuildInfo.STORE_UPLOAD; }
    volatile String ref = "main";         // commit the loaded index came from: files are fetched from the same one

    String base() { return "https://raw.githubusercontent.com/" + repo(ctx) + "/" + ref + "/"; }

    /** latest commit of main. raw.githubusercontent.com caches /main/ for ~5 min (query strings don't bust it), a commit url never goes stale */
    String latestCommit() {
        try {
            HttpURLConnection c = Updater.open("https://api.github.com/repos/" + repo(ctx) + "/commits/main");
            c.setRequestProperty("Accept", "application/vnd.github.sha");
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return "main";
            try (InputStream in = c.getInputStream()) {
                String sha = new String(Files.readAll(in), "UTF-8").trim();
                return sha.matches("[0-9a-f]{40}") ? sha : "main";
            }
        } catch (Exception e) { return "main"; }   // rate limited / offline: the cached branch is still fine
    }

    // ---------------------------------------------------------------- installed.json
    synchronized void loadInstalled() {
        installed.clear();
        File f = new File(Paths.STORE, "installed.json");
        if (!f.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.read(f), "UTF-8"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject e = o.getJSONObject(id);
                Installed i = new Installed();
                i.id = id; i.version = e.optString("version"); i.name = e.optString("name", id); i.trusted = e.optBoolean("trusted");
                JSONArray fs = e.optJSONArray("files");
                if (fs != null) for (int k = 0; k < fs.length(); k++) i.files.add(fs.getString(k));
                installed.put(id, i);
            }
        } catch (Exception e) { log.log("store: couldn't read installed.json: " + e.getMessage()); }
    }

    synchronized void saveInstalled() throws Exception {
        JSONObject o = new JSONObject();
        for (Installed i : installed.values()) {
            JSONObject e = new JSONObject();
            e.put("version", i.version); e.put("name", i.name); e.put("trusted", i.trusted); e.put("files", new JSONArray(i.files));
            o.put(i.id, e);
        }
        Files.write(new File(Paths.STORE, "installed.json"), o.toString(1).getBytes("UTF-8"));
    }

    synchronized Installed installedOf(String id) { return installed.get(id); }

    // ---------------------------------------------------------------- network
    static byte[] get(String url, long max) throws Exception {
        HttpURLConnection c = Updater.open(url);
        c.setRequestProperty("Accept", "*/*");
        c.setUseCaches(false);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code + " for " + url.replaceAll("\\?.*", ""));
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) {
                o.write(b, 0, n);
                if (o.size() > max) throw new Exception("file too big");
            }
            return o.toByteArray();
        }
    }

    static String sha256(byte[] d) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    /** background thread. also refreshes the scan rules */
    List<Plugin> refresh() {
        try {
            String at = latestCommit();
            String t = at.equals("main") ? "?t=" + (System.currentTimeMillis() / 60000) : "";
            JSONObject o = new JSONObject(new String(get("https://raw.githubusercontent.com/" + repo(ctx) + "/" + at + "/index.json" + t, 2 * 1024 * 1024), "UTF-8"));
            List<Plugin> out = new ArrayList<>();
            JSONArray a = o.getJSONArray("plugins");
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                Plugin p = new Plugin();
                p.id = e.getString("id");
                if (!ID_RE.matcher(p.id).matches()) continue;
                p.name = e.optString("name", p.id); p.author = e.optString("author"); p.desc = e.optString("desc");
                p.version = e.optString("version"); p.updated = e.optString("updated"); p.trusted = e.optBoolean("trusted");
                JSONArray g = e.optJSONArray("game");
                if (g != null) for (int k = 0; k < g.length(); k++) p.games.add(g.getString(k));
                JSONArray fs = e.getJSONArray("files");
                boolean ok = fs.length() > 0;
                for (int k = 0; k < fs.length(); k++) {
                    JSONObject f = fs.getJSONObject(k);
                    FileRef r = new FileRef();
                    r.name = f.getString("name"); r.path = f.getString("path"); r.sha256 = f.getString("sha256"); r.size = f.optLong("size");
                    if (!nameAllowed(p, r.name) || r.path.contains("..") || r.size > MAX_FILE) ok = false;
                    p.files.add(r);
                }
                if (!ok) { log.log("store: skipped " + p.id + " (bad file names in the index)"); continue; }
                JSONObject s = e.optJSONObject("scan");
                if (s != null) { p.high = s.optInt("high"); p.medium = s.optInt("medium"); p.low = s.optInt("low"); }
                out.add(p);
            }
            ref = at;
            try { Files.write(new File(Paths.STORE, "scan_rules.json"), get(base() + "scan_rules.json" + t, 512 * 1024)); } catch (Exception ignored) {}
            index = out; indexAt = System.currentTimeMillis(); error = "";
            return out;
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log.log("store: couldn't load the plugin list: " + error);
            return null;
        }
    }

    /** community files must be c-<id>*.js, so an untrusted plugin can never overwrite an official or local one */
    static boolean nameAllowed(Plugin p, String name) {
        if (!FILE_RE.matcher(name).matches()) return false;
        if (!p.trusted) return name.startsWith("c-" + p.id) && name.endsWith(".js");
        return !name.startsWith("c-");
    }

    Plugin find(String id) {
        List<Plugin> l = index;
        if (l != null) for (Plugin p : l) if (p.id.equals(id)) return p;
        return null;
    }

    /** background thread: every file, sha256-checked against the index */
    Map<String, byte[]> download(Plugin p) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (FileRef f : p.files) {
            byte[] d = get(base() + f.path + "?v=" + p.version, MAX_FILE);
            if (!sha256(d).equals(f.sha256)) throw new Exception(f.name + " doesn't match the index (the store is mid-update, try again in a few minutes)");
            out.put(f.name, d);
        }
        return out;
    }

    static String jsText(Map<String, byte[]> files) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> e : files.entrySet()) if (e.getKey().endsWith(".js")) {
            if (files.size() > 1) sb.append("// ======== ").append(e.getKey()).append(" ========\n");
            sb.append(new String(e.getValue(), "UTF-8")).append('\n');
        }
        return sb.toString();
    }

    /** the installed copy's code, for the diff on updates. "" when not installed */
    String installedText(String id) {
        Installed i = installedOf(id);
        if (i == null) return "";
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (String n : i.files) try { m.put(n, Files.read(new File(Paths.STORE_PLUGINS, n))); } catch (Exception ignored) {}
        try { return jsText(m); } catch (Exception e) { return ""; }
    }

    /** background thread */
    synchronized void install(Plugin p, Map<String, byte[]> files) throws Exception {
        Paths.STORE_PLUGINS.mkdirs();
        // a file another store plugin owns can't be taken over
        for (Installed o : installed.values()) if (!o.id.equals(p.id))
            for (String n : files.keySet()) if (o.files.contains(n)) throw new Exception(n + " belongs to " + o.name + ", remove that first");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!nameAllowed(p, e.getKey())) throw new Exception("refusing file name " + e.getKey());
            File tmp = new File(Paths.STORE_PLUGINS, e.getKey() + ".part");
            Files.write(tmp, e.getValue());
            File dst = new File(Paths.STORE_PLUGINS, e.getKey());
            if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw new Exception("couldn't write " + dst); }
        }
        Installed old = installed.get(p.id);
        if (old != null) for (String n : old.files) if (!files.containsKey(n)) new File(Paths.STORE_PLUGINS, n).delete();
        Installed i = new Installed();
        i.id = p.id; i.version = p.version; i.name = p.name; i.trusted = p.trusted; i.files.addAll(files.keySet());
        installed.put(p.id, i);
        saveInstalled();
        log.log("store: " + (old == null ? "installed " : "updated ") + p.name + (p.trusted ? "" : " (community, untrusted)") + ". It loads on the next Launch / Inject");
    }

    synchronized void remove(String id) throws Exception {
        Installed i = installed.remove(id);
        if (i == null) return;
        for (String n : i.files) new File(Paths.STORE_PLUGINS, n).delete();
        saveInstalled();
        log.log("store: removed " + i.name);
    }

    /** installed plugins whose index version differs */
    List<Plugin> updates(Boolean trusted) {
        List<Plugin> out = new ArrayList<>();
        List<Plugin> l = index;
        if (l == null) return out;
        for (Plugin p : l) {
            Installed i = installedOf(p.id);
            if (i != null && !i.version.equals(p.version) && (trusted == null || p.trusted == trusted)) out.add(p);
        }
        return out;
    }

    /** background thread: official plugins update silently; returns how many community updates wait for review */
    int autoUpdate() {
        synchronized (this) { if (installed.isEmpty()) return 0; }
        if (refresh() == null) return 0;
        for (Plugin p : updates(true)) {
            try { install(p, download(p)); } catch (Exception e) { log.log("store: auto-update of " + p.name + " failed: " + e.getMessage()); }
        }
        List<Plugin> waiting = updates(false);
        for (Plugin p : waiting) log.log("store: update for community plugin " + p.name + " is waiting for you to review it (Store tab)");
        return waiting.size();
    }

    // ---------------------------------------------------------------- upload
    /** background thread: returns the upload id, throws with the server's reason */
    static String submit(String name, String game, String desc, String author, String updates, String code) throws Exception {
        if (uploadUrl().isEmpty()) throw new Exception("no upload server set in this build");
        JSONObject b = new JSONObject();
        b.put("name", name); b.put("game", game); b.put("desc", desc); b.put("author", author); b.put("code", code);
        if (updates != null && !updates.isEmpty()) b.put("updates", updates);
        byte[] body = b.toString().getBytes("UTF-8");
        HttpURLConnection c = Updater.open(uploadUrl() + "/v1/store/submit");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code2 = c.getResponseCode();
        InputStream in = code2 < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = in == null ? "" : new String(Files.readAll(in), "UTF-8");
        JSONObject r;
        try { r = new JSONObject(resp); } catch (Exception e) { throw new Exception("server answered " + code2); }
        if (!r.optBoolean("ok")) throw new Exception(r.optString("err", "server answered " + code2));
        return r.getString("id");
    }
}
