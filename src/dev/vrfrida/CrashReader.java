package dev.vrfrida;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What crashed a game, from whatever this headset lets vrfrida read:
 *   root:             newest /data/tombstones/tombstone_NN for the game (signal, abort message, backtrace)
 *   root / READ_LOGS: the crash log buffer (native crashes and Java FATAL EXCEPTIONs)
 *   always:           the end of the patched game's own log (Android/obb/<pkg>/vrfrida-game.log)
 */
final class CrashReader {
    static String read(String pkg, int uid, boolean root, boolean readLogs) {
        StringBuilder s = new StringBuilder();
        if (root) {
            s.append("[tombstone]\n");
            String q = Root.q(">>> " + pkg + " <<<");
            Root.Result r = Root.sh(
                    "f=$(cd /data/tombstones 2>/dev/null && ls -t | grep -E '^tombstone_[0-9]+$' | while read n; do grep -q -F " + q + " \"$n\" && { echo \"$n\"; break; }; done)\n" +
                    "if [ -z \"$f\" ]; then echo 'no tombstone for this game'; else\n" +
                    "  p=/data/tombstones/$f; echo \"file    $p\"; echo \"time    $(date -r $p '+%Y-%m-%d %H:%M:%S')\"\n" +
                    "  grep -m1 -E '^Cmdline:' $p; grep -m1 -E '^pid: ' $p; grep -m1 -E '^signal ' $p; grep -m1 -A3 -E '^Abort message' $p\n" +
                    "  echo; echo 'backtrace:'; sed -n '/^backtrace:/,/^$/p' $p | sed -n '2,24p'\n" +
                    "fi");
            s.append(indent(r.out.isEmpty() ? "(nothing)" : r.out)).append('\n');
        }
        if (root || readLogs) {
            s.append("\n[crash log]\n");
            s.append(indent(crashBuffer(pkg, root))).append('\n');
        }
        s.append("\n[game log, last lines]\n");
        File gl = NoRoot.obbGameLog(pkg);
        if (!gl.isFile()) s.append("  none (games patched by this vrfrida version or newer write it)\n");
        else {
            try {
                String[] lines = new String(Files.read(gl), "UTF-8").split("\n");
                for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) s.append("  ").append(lines[i]).append('\n');
            } catch (Exception e) { s.append("  can't read it: ").append(e.getMessage()).append('\n'); }
        }
        if (!root && !readLogs)
            s.append("\nWithout root vrfrida can't see system crash reports. Once from a PC: adb shell pm grant dev.vrfrida android.permission.READ_LOGS\n");
        return s.toString();
    }

    /** lines of the crash buffer that belong to this game: every line from a process (pid column) that mentions it */
    static String crashBuffer(String pkg, boolean root) {
        List<String> all = new ArrayList<>();
        try {
            String cmd = "logcat -b crash -d -v threadtime";
            Process p = root ? new ProcessBuilder("su", "-mm", "-c", cmd).redirectErrorStream(true).start()
                             : new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = in.readLine()) != null) all.add(l);
            }
            p.waitFor();
        } catch (Exception e) { return "logcat failed: " + e.getMessage(); }
        Set<String> pids = new HashSet<>();
        for (String l : all) if (l.contains(pkg)) { String[] t = l.trim().split("\\s+"); if (t.length > 2) pids.add(t[2]); }
        if (pids.isEmpty()) return "no crash of " + pkg + " in the crash log (it's cleared on reboot)";
        List<String> mine = new ArrayList<>();
        for (String l : all) { String[] t = l.trim().split("\\s+"); if (t.length > 2 && pids.contains(t[2])) mine.add(l); }
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, mine.size() - 80); i < mine.size(); i++) sb.append(mine.get(i)).append('\n');
        return sb.toString().trim();
    }

    static String indent(String t) { return "  " + t.trim().replace("\n", "\n  "); }
}
