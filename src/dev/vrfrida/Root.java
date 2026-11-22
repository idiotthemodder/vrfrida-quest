package dev.vrfrida;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** runs shell commands as root through Magisk su (-mm = global mount namespace, so /sdcard/Android/data works) */
final class Root {
    static final class Result {
        final int code; final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
        boolean ok() { return code == 0; }
    }

    /** pipes the script into "su -mm" on stdin, so quoting inside it never fights with -c */
    static Result sh(String script) {
        try {
            Process p = new ProcessBuilder("su", "-mm").redirectErrorStream(true).start();
            try (OutputStream in = p.getOutputStream()) {
                in.write((script + "\nexit\n").getBytes("UTF-8"));
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream out = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = out.read(b)) > 0) buf.write(b, 0, n);
            }
            return new Result(p.waitFor(), buf.toString("UTF-8").trim());
        } catch (Exception e) {
            return new Result(-1, "su failed: " + e);
        }
    }

    /** single-quote a string for sh */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static boolean available() {
        Result r = sh("id -u");
        return r.ok() && r.out.trim().endsWith("0");
    }
}
