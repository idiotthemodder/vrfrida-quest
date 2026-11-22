package dev.vrfrida;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * logcat for one game: every line the game's uid writes (Unity, Normcore, the game's own Android code...).
 * Root reads it through su. Without root Android only hands vrfrida its own lines, unless READ_LOGS was granted once
 * over adb. Lines are kept in memory (last MAX) and written to /sdcard/vrfrida/logs/<pkg>-logcat.txt.
 */
final class GameLogcat {
    static final int MAX = 3000;
    final ArrayDeque<String> lines = new ArrayDeque<>();
    volatile String pkg;
    volatile boolean running;
    volatile int total;
    Process proc;
    Thread thread;
    File file;

    static File fileFor(String pkg) { return new File(Paths.LOGS, pkg + "-logcat.txt"); }

    /** uid -> the name logcat's "-v uid" prints for app uids (u0_a251) */
    static String uidName(int uid) {
        int app = uid % 100000;
        return app >= 10000 ? "u" + (uid / 100000) + "_a" + (app - 10000) : String.valueOf(uid);
    }

    synchronized void start(String pkg, int uid, boolean root, boolean warnOnly, Runnable changed) {
        stop();
        this.pkg = pkg;
        synchronized (lines) { lines.clear(); }
        total = 0;
        file = fileFor(pkg);
        Paths.LOGS.mkdirs();
        // -T 2000: the last 2000 lines from before the start too (catches the game's boot when started right after Launch)
        String cmd = "logcat -v threadtime -v uid -T 2000" + (warnOnly ? " *:W" : "");
        // "-v uid" prints the uid as a number on Horizon OS (10252), as a name (u0_a252) on some other builds.
        // line: date time uid pid tid level tag: message -> compare the 3rd column exactly
        String wantNum = String.valueOf(uid), wantName = uidName(uid);
        running = true;
        thread = new Thread(() -> {
            try (FileWriter out = new FileWriter(file, false)) {
                proc = root ? new ProcessBuilder("su", "-mm", "-c", cmd).redirectErrorStream(true).start()
                            : new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
                BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String l;
                long lastUi = 0;
                while (running && (l = in.readLine()) != null) {
                    String[] tk = l.trim().split("\\s+", 4);
                    if (tk.length < 4 || !(tk[2].equals(wantNum) || tk[2].equals(wantName))) continue;
                    String shown = tk[0] + " " + tk[1] + "  " + tk[3];   // uid column dropped, it's the same on every line
                    synchronized (lines) { lines.addLast(shown); if (lines.size() > MAX) lines.removeFirst(); }
                    total++;
                    out.write(shown); out.write('\n');
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 400) { lastUi = now; out.flush(); changed.run(); }
                }
                out.flush();
            } catch (Exception e) {
                synchronized (lines) { lines.addLast("logcat stopped: " + e); }
            } finally {
                running = false;
                changed.run();
            }
        }, "logcat");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (proc != null) { proc.destroy(); proc = null; }
    }

    String text(int maxChars) {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) { for (String l : lines) sb.append(l).append('\n'); }
        return sb.length() > maxChars ? sb.substring(sb.length() - maxChars) : sb.toString();
    }
}
