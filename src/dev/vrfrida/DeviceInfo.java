package dev.vrfrida;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** questfetch (neofetch for the headset) + the debug report on the Info tab. Everything here is read-only */
final class DeviceInfo {
    /** the ringed planet from the user's fastfetch config ($1 / $2 / $3 = colour), drawn like fastfetch does */
    static final String[] LOGO = {
            "                           $3·",
            "  $3·                    $2▄▄▄▒▒▒▒▄▄▄",
            "                    $2▄▒▒██████████▒▒▄                 $3✦",
            "                  $2▄▒████▀▀    ▀▀████▒▄",
            "     $3·           $2▄▒███▀          ▀███▒▄         $3*",
            "      $1▄▄▄▄▄▄▄░░░░▒▒▒$2█              █$1▒▒▒░░░░▄▄▄▄▄▄▄",
            " $1▄░░░░▒▒▒▒▓▓▓█████$2██                ██$1█████▓▓▓▒▒▒▒░░░░▄",
            " $1▀░░░░▒▒▒▒▓▓▓████$2▒██$1▄▄            ▄▄$2██▒$1████▓▓▓▒▒▒▒░░░░▀",
            "      $1▀▀▀▀▀▀▀░░░░▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒░░░░▀▀▀▀▀▀▀    $3·",
            "  $3·              $2▀▒███▄          ▄███▒▀",
            "                  $2▀▒████▄▄    ▄▄████▒▀",
            "                    $2▀▒▒██████████▒▒▀",
            "       $3✧               $2▀▀▀▒▒▒▒▀▀▀                 $3·",
            "            $3·          ·                  ·",
    };
    /** fastfetch colours: logo $1 $2 $3, keys, title, text, terminal background */
    static final int C1 = 0xFFFFB598, C2 = 0xFFE7BEAE, C3 = 0xFFD3C78E, KEY = C1, TITLE = C3, TEXT = 0xFFEDE0DC, DIM = 0xFFA08D86, BG = 0xFF120D0B;
    static final int[] BLOCKS = {0xFF5A4540, 0xFFFFB4AB, 0xFFB5CCB9, 0xFFD3C78E, 0xFFFFB598, 0xFFE7BEAE, 0xFFC9B6AB, 0xFFEDE0DC};

    /** key / value rows for questfetch */
    static List<String[]> fetch(Context c, boolean root, int games, int patched, int plugins, int sounds, long appVersion) {
        List<String[]> r = new ArrayList<>();
        r.add(new String[]{"OS", "Horizon OS, Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"});
        r.add(new String[]{"Build", Build.DISPLAY});
        r.add(new String[]{"Device", Build.MANUFACTURER + " " + Build.MODEL.replace('_', ' ') + " (" + Build.DEVICE + ")"});
        String soc = Build.VERSION.SDK_INT >= 31 && !Build.SOC_MODEL.equals(Build.UNKNOWN) ? Build.SOC_MODEL : Build.HARDWARE;
        r.add(new String[]{"CPU", soc + " · " + Runtime.getRuntime().availableProcessors() + " cores"});
        r.add(new String[]{"Kernel", String.valueOf(System.getProperty("os.version"))});
        r.add(new String[]{"Uptime", duration(SystemClock.elapsedRealtime())});
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        r.add(new String[]{"Memory", mb(mi.totalMem - mi.availMem) + " / " + mb(mi.totalMem) + (mi.lowMemory ? "  (LOW)" : "")});
        StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long tot = fs.getTotalBytes(), free = fs.getAvailableBytes();
        r.add(new String[]{"Storage", gb(tot - free) + " / " + gb(tot) + "  (" + gb(free) + " free" + (free < (3L << 30) ? ", LOW" : "") + ")"});
        r.add(new String[]{"Battery", battery(c)});
        r.add(new String[]{"Wi-Fi IP", ip()});
        r.add(new String[]{"Root", root ? "yes (Magisk su)" : "no (no-root mode, Frida Gadget)"});
        r.add(new String[]{"vrfrida", "v" + appVersion});
        r.add(new String[]{"Games", games + " Unity games, " + patched + " patched"});
        r.add(new String[]{"Plugins", plugins + " installed · " + sounds + " sounds"});
        return r;
    }

    /** plain text debug report, for the Info tab and the Copy button */
    static String report(Context c, boolean root, ApplicationInfo game, String bridge, long appVersion) {
        StringBuilder s = new StringBuilder();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        PackageManager pm = c.getPackageManager();
        s.append("[vrfrida]\n");
        s.append("  version        v").append(appVersion).append('\n');
        try {
            PackageInfo me = pm.getPackageInfo(c.getPackageName(), 0);
            s.append("  installed      ").append(df.format(new Date(me.lastUpdateTime))).append('\n');
        } catch (Exception ignored) {}
        s.append("  menu           ").append(Core.useSdcardMenu(c) ? Paths.MENU + " (" + df.format(new Date(Paths.MENU.lastModified())) + ")" : "bundled in the app").append('\n');
        s.append("\n[permissions]\n");
        s.append("  root           ").append(yn(root)).append('\n');
        s.append("  all files      ").append(yn(Environment.isExternalStorageManager())).append('\n');
        s.append("  install apps   ").append(yn(pm.canRequestPackageInstalls())).append("   (also gives Android/obb access, after a restart of vrfrida)\n");
        s.append("  obb write      ").append(obbWritable(game)).append('\n');
        s.append("  read logs      ").append(yn(c.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED))
         .append(root ? "   (root reads them anyway)" : "   (adb shell pm grant dev.vrfrida android.permission.READ_LOGS)").append('\n');
        s.append("\n[folders]\n");
        s.append("  plugins        ").append(count(Paths.PLUGINS, ".js")).append(" in ").append(Paths.PLUGINS).append('\n');
        s.append("  store plugins  ").append(count(Paths.STORE_PLUGINS, ".js")).append('\n');
        s.append("  sounds         ").append(count(Paths.SOUNDS, null)).append(" files, ").append(mb(size(Paths.SOUNDS))).append('\n');
        s.append("  sound cache    ").append(count(Paths.SOUND_CACHE, ".wav")).append(" wavs, ").append(mb(size(Paths.SOUND_CACHE))).append('\n');
        s.append("  patterns       ").append(count(Paths.PATTERNS, null)).append('\n');
        if (game != null) {
            String pkg = game.packageName;
            s.append("\n[game] ").append(pm.getApplicationLabel(game)).append('\n');
            s.append("  package        ").append(pkg).append('\n');
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
                s.append("  version        ").append(pi.versionName).append(" (").append(Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode).append(")\n");
                s.append("  updated        ").append(df.format(new Date(pi.lastUpdateTime))).append('\n');
                String inst = null;
                try { inst = Build.VERSION.SDK_INT >= 30 ? pm.getInstallSourceInfo(pkg).getInstallingPackageName() : pm.getInstallerPackageName(pkg); } catch (Exception ignored) {}
                s.append("  installer      ").append(inst == null ? "?" : inst).append('\n');
                s.append("  uid            ").append(game.uid).append('\n');
                boolean wantsMic = false;
                if (pi.requestedPermissions != null) for (String p : pi.requestedPermissions) if (android.Manifest.permission.RECORD_AUDIO.equals(p)) wantsMic = true;
                s.append("  microphone     ").append(!wantsMic ? "not requested" : pm.checkPermission(android.Manifest.permission.RECORD_AUDIO, pkg) == PackageManager.PERMISSION_GRANTED ? "granted" : "NOT granted (voice chat stays silent)").append('\n');
            } catch (Exception e) { s.append("  (").append(e.getMessage()).append(")\n"); }
            s.append("  patched        ").append(yn(NoRoot.isPatched(game))).append('\n');
            s.append("  bridge         ").append(bridge == null || bridge.isEmpty() ? "?" : bridge).append('\n');
            File obb = new File("/storage/emulated/0/Android/obb/" + pkg);
            s.append("  obb folder     ").append(obb.isDirectory() ? mb(size(obb)) : "none").append('\n');
            File snd = Core.obbSounds(pkg);
            s.append("  obb sounds     ").append(snd.isDirectory() ? count(snd, ".wav") + " wavs" : "none").append('\n');
            File ag = NoRoot.obbAgentFile(pkg);
            s.append("  obb menu       ").append(ag.isFile() ? df.format(new Date(ag.lastModified())) + ", " + mb(ag.length()) : "none").append('\n');
            File gl = NoRoot.obbGameLog(pkg);
            s.append("  game log       ").append(gl.isFile() ? df.format(new Date(gl.lastModified())) : "none (patched with an older vrfrida, or not started yet)").append('\n');
        }
        s.append("\n[device]\n  ").append(Build.FINGERPRINT).append('\n');
        return s.toString();
    }

    static String obbWritable(ApplicationInfo game) {
        File dir = game != null ? new File("/storage/emulated/0/Android/obb/" + game.packageName) : null;
        if (dir == null || !dir.isDirectory()) return "? (select a game that has an obb folder)";
        File probe = new File(dir, ".vrfrida_probe");
        try {
            Files.write(probe, new byte[]{1});
            boolean ok = probe.delete();
            return ok ? "yes" : "yes (couldn't clean up)";
        } catch (Exception e) {
            return "NO (allow Install unknown apps, then fully close + reopen vrfrida)";
        }
    }

    static String battery(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return "?";
        int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        float t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        String state = st == BatteryManager.BATTERY_STATUS_CHARGING ? "charging" : st == BatteryManager.BATTERY_STATUS_FULL ? "full" : "discharging";
        return Math.round(lvl * 100f / Math.max(1, sc)) + "% · " + state + " · " + String.format(Locale.US, "%.1f°C", t);
    }

    static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress() + " (" + ni.getName() + ")";
            }
        } catch (Exception ignored) {}
        return "offline";
    }

    static String duration(long ms) {
        long m = ms / 60000, h = m / 60, d = h / 24;
        return (d > 0 ? d + "d " : "") + (h % 24) + "h " + (m % 60) + "m";
    }

    static String yn(boolean b) { return b ? "yes" : "NO"; }
    static String mb(long b) { return b >= (1L << 30) ? gb(b) : String.format(Locale.US, "%.0f MB", b / 1048576.0); }
    static String gb(long b) { return String.format(Locale.US, "%.1f GB", b / 1073741824.0); }

    static int count(File dir, String ext) {
        File[] fs = dir.listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.isFile() && (ext == null || f.getName().toLowerCase().endsWith(ext))) n++;
        return n;
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        File[] k = f.listFiles();
        long t = 0;
        if (k != null) for (File c : k) t += size(c);
        return t;
    }
}
