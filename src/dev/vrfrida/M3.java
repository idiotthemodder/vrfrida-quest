package dev.vrfrida;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * Material 3 color scheme for the app, without the Material library (this app is built with plain aapt2 + javac).
 * Tones are CIELAB L* (what M3's HCT tone is based on), chroma is reduced until the color fits in sRGB.
 *  - UXpatcher installed: its accent is the primary, its background / text are the surface / on-surface, so the app
 *    keeps the system theme exactly; the in-between roles are blended from those three.
 *  - otherwise: a Meta Horizon style dark scheme from Meta blue.
 */
final class M3 {
    static final int META_BLUE = 0xFF0866FF;

    // ---------------------------------------------------------------- color math
    static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
    static double gam(double c) { return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055; }
    static double f(double t) { return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116; }
    static double fi(double t) { return t * t * t > 216.0 / 24389 ? t * t * t : (116 * t - 16) / (24389.0 / 27); }

    // Hues + chroma are OKLCH (blues stay blue when lightened; CIELAB drifts them purple). Tone = CIELAB L*, turned into
    // OKLab L through the gray of the same luminance, so the M3 tone steps (80 / 30 / 90 ...) mean the same lightness.
    // Chroma arguments are in CIELAB-like units (0..~130) and scaled to OKLab.
    static final double C_SCALE = 1.0 / 330;

    /** argb -> {tone, chroma (lab-like units), hue(deg)} */
    static double[] lch(int argb) {
        double r = lin(((argb >> 16) & 255) / 255.0), g = lin(((argb >> 8) & 255) / 255.0), b = lin((argb & 255) / 255.0);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        double A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s, B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return new double[]{116 * f(y) - 16, Math.hypot(A, B) / C_SCALE, (Math.toDegrees(Math.atan2(B, A)) + 360) % 360};
    }

    /** {tone, chroma, hue} -> linear rgb (may be out of gamut) */
    static double[] rgbOf(double tone, double C, double h) {
        double L = Math.cbrt(fi((tone + 16) / 116)), c = C * C_SCALE;
        double A = c * Math.cos(Math.toRadians(h)), B = c * Math.sin(Math.toRadians(h));
        double l = L + 0.3963377774 * A + 0.2158037573 * B, m = L - 0.1055613458 * A - 0.0638541728 * B, s = L - 0.0894841775 * A - 1.2914855480 * B;
        l = l * l * l; m = m * m * m; s = s * s * s;
        return new double[]{4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s, -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    static boolean inGamut(double[] c) { for (double v : c) if (v < -0.0001 || v > 1.0001) return false; return true; }

    /** tone (0..100) + chroma + hue -> argb, chroma lowered until it fits */
    static int tone(double hue, double chroma, double tone) {
        double lo = 0, hi = chroma;
        double[] c = rgbOf(tone, hi, hue);
        if (!inGamut(c)) {
            for (int i = 0; i < 18; i++) { double mid = (lo + hi) / 2; if (inGamut(rgbOf(tone, mid, hue))) lo = mid; else hi = mid; }
            c = rgbOf(tone, lo, hue);
        }
        int r = (int) Math.round(Math.max(0, Math.min(1, gam(c[0]))) * 255), g = (int) Math.round(Math.max(0, Math.min(1, gam(c[1]))) * 255), b = (int) Math.round(Math.max(0, Math.min(1, gam(c[2]))) * 255);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static double luminance(int c) { return 0.2126 * lin(((c >> 16) & 255) / 255.0) + 0.7152 * lin(((c >> 8) & 255) / 255.0) + 0.0722 * lin((c & 255) / 255.0); }
    static double contrast(int a, int b) { double x = luminance(a), y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
    static int onColor(int bg) { return contrast(bg, 0xFFFFFFFF) >= contrast(bg, 0xFF101114) ? 0xFFFFFFFF : 0xFF101114; }

    // ---------------------------------------------------------------- schemes
    /** standard M3 tonal scheme from a seed */
    static Map<String, Integer> fromSeed(int seed, boolean dark, double neutralChroma) {
        double[] s = lch(seed);
        double h = s[2], pc = Math.max(48, s[1]);
        Map<String, Integer> m = new HashMap<>();
        if (dark) {
            m.put("primary", tone(h, pc, 80)); m.put("onPrimary", tone(h, pc, 20));
            m.put("primaryContainer", tone(h, pc, 30)); m.put("onPrimaryContainer", tone(h, pc, 90));
            m.put("secondary", tone(h, 16, 80)); m.put("secondaryContainer", tone(h, 16, 30)); m.put("onSecondaryContainer", tone(h, 16, 90));
            m.put("tertiary", tone(h + 60, 24, 80)); m.put("tertiaryContainer", tone(h + 60, 24, 30)); m.put("onTertiaryContainer", tone(h + 60, 24, 90));
            m.put("surface", tone(h, neutralChroma, 6)); m.put("surfaceContainerLowest", tone(h, neutralChroma, 4));
            m.put("surfaceContainerLow", tone(h, neutralChroma, 10)); m.put("surfaceContainer", tone(h, neutralChroma, 12));
            m.put("surfaceContainerHigh", tone(h, neutralChroma, 17)); m.put("surfaceContainerHighest", tone(h, neutralChroma, 22));
            m.put("onSurface", tone(h, neutralChroma, 90)); m.put("onSurfaceVariant", tone(h, neutralChroma * 2, 78));
            m.put("outline", tone(h, neutralChroma * 2, 60)); m.put("outlineVariant", tone(h, neutralChroma * 2, 30));
            m.put("error", tone(25, 84, 80)); m.put("onError", tone(25, 84, 20)); m.put("errorContainer", tone(25, 84, 30)); m.put("onErrorContainer", tone(25, 84, 90));
            m.put("success", tone(145, 50, 78)); m.put("successContainer", tone(145, 40, 30)); m.put("onSuccessContainer", tone(145, 40, 90));
            m.put("warning", tone(80, 60, 82)); m.put("warningContainer", tone(80, 50, 30)); m.put("onWarningContainer", tone(80, 50, 90));
        } else {
            m.put("primary", tone(h, pc, 40)); m.put("onPrimary", 0xFFFFFFFF);
            m.put("primaryContainer", tone(h, pc, 90)); m.put("onPrimaryContainer", tone(h, pc, 10));
            m.put("secondary", tone(h, 16, 40)); m.put("secondaryContainer", tone(h, 16, 90)); m.put("onSecondaryContainer", tone(h, 16, 10));
            m.put("tertiary", tone(h + 60, 24, 40)); m.put("tertiaryContainer", tone(h + 60, 24, 90)); m.put("onTertiaryContainer", tone(h + 60, 24, 10));
            m.put("surface", tone(h, neutralChroma, 98)); m.put("surfaceContainerLowest", 0xFFFFFFFF);
            m.put("surfaceContainerLow", tone(h, neutralChroma, 96)); m.put("surfaceContainer", tone(h, neutralChroma, 94));
            m.put("surfaceContainerHigh", tone(h, neutralChroma, 92)); m.put("surfaceContainerHighest", tone(h, neutralChroma, 90));
            m.put("onSurface", tone(h, neutralChroma, 10)); m.put("onSurfaceVariant", tone(h, neutralChroma * 2, 30));
            m.put("outline", tone(h, neutralChroma * 2, 50)); m.put("outlineVariant", tone(h, neutralChroma * 2, 80));
            m.put("error", tone(25, 84, 40)); m.put("onError", 0xFFFFFFFF); m.put("errorContainer", tone(25, 84, 90)); m.put("onErrorContainer", tone(25, 84, 10));
            m.put("success", tone(145, 50, 38)); m.put("successContainer", tone(145, 40, 90)); m.put("onSuccessContainer", tone(145, 40, 10));
            m.put("warning", tone(80, 60, 45)); m.put("warningContainer", tone(80, 50, 90)); m.put("onWarningContainer", tone(80, 50, 10));
        }
        return m;
    }

    /** UXpatcher's three colors kept exactly, the rest blended between them */
    static Map<String, Integer> fromUxpatcher(int bg, int accent, int text) {
        boolean dark = luminance(bg) < 0.18;
        Map<String, Integer> m = fromSeed(accent, dark, 6);   // error / success / warning / tertiary from the standard scheme
        m.put("surface", bg);
        m.put("surfaceContainerLowest", mix(bg, dark ? 0xFF000000 : 0xFFFFFFFF, 0.3));
        m.put("surfaceContainerLow", mix(bg, text, 0.04));
        m.put("surfaceContainer", mix(bg, text, 0.07));
        m.put("surfaceContainerHigh", mix(bg, text, 0.11));
        m.put("surfaceContainerHighest", mix(bg, text, 0.15));
        m.put("onSurface", text);
        m.put("onSurfaceVariant", mix(text, bg, 0.28));
        m.put("outline", mix(text, bg, 0.5));
        m.put("outlineVariant", mix(text, bg, 0.78));
        m.put("primary", accent);
        m.put("onPrimary", onColor(accent));
        int pc = mix(bg, accent, 0.32);
        m.put("primaryContainer", pc);
        m.put("onPrimaryContainer", contrast(pc, mix(text, accent, 0.25)) >= 4.5 ? mix(text, accent, 0.25) : onColor(pc));
        int sc = mix(bg, accent, 0.16);
        m.put("secondaryContainer", sc);
        m.put("onSecondaryContainer", contrast(sc, text) >= 4.5 ? text : onColor(sc));
        m.put("secondary", mix(accent, text, 0.35));
        return m;
    }

    /** the scheme for this app: "uxpatcher", "meta" or "auto" (UXpatcher when it's installed, else Meta) */
    static Map<String, Integer> scheme(Context c, String which) {
        boolean ux = "uxpatcher".equals(which) || ("auto".equals(which) && MenuLook.hasUxpatcher(c));
        if (ux && MenuLook.hasUxpatcher(c))
            return fromUxpatcher(0xFF000000 | MenuLook.uxpInt(c, "bg_color", 0x101018), 0xFF000000 | MenuLook.uxpInt(c, "accent_color", 0xB07CFF),
                    0xFF000000 | MenuLook.uxpInt(c, "text_color", 0xFFFFFF));
        return fromSeed(META_BLUE, true, 5);
    }

    static float[] f4(int c) { return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, ((c >>> 24) & 255) / 255f}; }

    /** the old menu color keys the app's widgets were written against, mapped onto M3 roles */
    static Map<String, float[]> legacy(Map<String, Integer> m) {
        Map<String, float[]> o = new HashMap<>();
        o.put("Text", f4(m.get("onSurface"))); o.put("TextDisabled", f4(m.get("onSurfaceVariant")));
        o.put("TextOnActive", f4(m.get("onPrimary"))); o.put("TabActive", f4(m.get("primary")));
        o.put("ButtonActive", f4(m.get("primary"))); o.put("Button", f4(m.get("secondaryContainer")));
        o.put("RowBg", f4(m.get("surfaceContainer"))); o.put("FrameBg", f4(m.get("surfaceContainerHighest")));
        o.put("PopupBg", f4(m.get("surfaceContainerHigh"))); o.put("WindowBg", f4(m.get("surface")));
        o.put("TitleBgActive", f4(m.get("surfaceContainer"))); o.put("Separator", f4(m.get("outlineVariant")));
        o.put("Check", f4(m.get("primary"))); o.put("SliderFill", f4(m.get("primary"))); o.put("SliderGrab", f4(m.get("primary")));
        o.put("NotifError", f4(m.get("error"))); o.put("NotifWarn", f4(m.get("warning"))); o.put("NotifSuccess", f4(m.get("success")));
        o.put("Border", f4(m.get("outline"))); o.put("Tab", f4(m.get("surfaceContainer")));
        return o;
    }
}
