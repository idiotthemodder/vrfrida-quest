package dev.vrfrida;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safety scan for store plugins: the pattern rules from the store repo's scan_rules.json (downloaded with the index into
 * /sdcard/vrfrida/store, falling back to the copy bundled in the apk). Same rules + logic as store/tools/store.py.
 * A pattern check only: it lists risky things the code does, it can't prove code is safe.
 */
final class Scanner {
    static final class Rule { String id, sev, label, why; Pattern re; boolean ownGameOk; }
    static final class Finding {
        String id, sev, label, why;
        final List<String> hits = new ArrayList<>();
        int count;
    }

    static List<Rule> cache;
    static long cacheStamp = -1;

    static synchronized List<Rule> rules(Context ctx) {
        File f = new File(Paths.STORE, "scan_rules.json");
        long stamp = f.isFile() ? f.lastModified() : 0;
        if (cache != null && stamp == cacheStamp) return cache;
        List<Rule> out = null;
        if (f.isFile()) try { out = parse(new String(Files.read(f), "UTF-8")); } catch (Exception ignored) {}
        if (out == null || out.isEmpty()) try { out = parse(Files.readAsset(ctx, "scan_rules.json")); } catch (Exception e) { out = new ArrayList<>(); }
        cache = out; cacheStamp = stamp;
        return out;
    }

    static List<Rule> parse(String json) throws Exception {
        JSONArray a = new JSONObject(json).getJSONArray("rules");
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Rule r = new Rule();
            r.id = o.optString("id"); r.sev = o.optString("sev", "medium"); r.label = o.optString("label"); r.why = o.optString("why");
            r.ownGameOk = o.optBoolean("own_game_ok", false);
            try { r.re = Pattern.compile(o.getString("re")); } catch (Exception e) { continue; }   // a rule Java can't compile is skipped
            out.add(r);
        }
        return out;
    }

    static int rank(String sev) { return sev.equals("high") ? 0 : sev.equals("medium") ? 1 : 2; }

    /** most severe first */
    static List<Finding> scan(Context ctx, String code) {
        List<Finding> out = new ArrayList<>();
        String[] lines = code.split("\n", -1);
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(40, lines.length); i++) head.append(lines[i]).append('\n');
        List<String> own = new ArrayList<>();
        Matcher gm = Core.GAME_LINE.matcher(head);
        if (gm.find()) for (String g : gm.group(1).replaceAll("\\*/\\s*$", "").split("[,\\s;]+")) if (!g.isEmpty() && !g.equals("*")) own.add(g);
        own.add("APP_ID");
        for (Rule r : rules(ctx)) {
            Finding f = null;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].length() > 4000 ? lines[i].substring(0, 4000) : lines[i];
                Matcher m = r.re.matcher(line);
                if (!m.find()) continue;
                if (r.ownGameOk) { boolean mine = false; for (String g : own) if (line.contains(g)) mine = true; if (mine) continue; }
                if (f == null) { f = new Finding(); f.id = r.id; f.sev = r.sev; f.label = r.label; f.why = r.why; }
                f.count++;
                if (f.hits.size() < 6) {
                    int a = Math.max(0, m.start() - 40), b = Math.min(line.length(), m.end() + 40);
                    f.hits.add("line " + (i + 1) + ":  " + line.substring(a, b).trim());
                }
                if (f.count >= 50) break;
            }
            if (f != null) out.add(f);
        }
        int longest = 0;
        for (String l : lines) longest = Math.max(longest, l.length());
        if (longest > 5000) {
            Finding f = new Finding();
            f.id = "long-line"; f.sev = "medium"; f.label = "Has a " + longest + "-character line (minified / packed code)";
            f.why = "Minified code is very hard to review. Ask for the readable source.";
            out.add(f);
        }
        out.sort((x, y) -> rank(x.sev) - rank(y.sev));
        return out;
    }

    static int[] counts(List<Finding> fs) {
        int[] c = new int[3];
        for (Finding f : fs) c[rank(f.sev)]++;
        return c;
    }
}
