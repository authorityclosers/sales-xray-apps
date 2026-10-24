package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * The phone's font size, for the page to scale its whole layout with (owner's OnePlus Nord, 24 Oct
 * 2026: the WebView enlarged only the text, so the default size overflowed and small made icons look
 * wrong). The page clamps it; the WebView keeps text at 100%. Plain Java, for JVM tests; FontScale
 * reads Android.
 */
final class FontScaleRules {

    /** Beyond Android's own largest size (2 on Android 14), only a broken value. */
    private static final float MAX = 3f;

    private FontScaleRules() {}

    /** Configuration.fontScale as the page gets it: "0.85", "1", "1.15", "1.3"; "1" for nothing usable. */
    static String value(float fontScale) {
        if (Float.isNaN(fontScale) || fontScale <= 0f) return "1";
        double rounded = Math.round(Math.min(fontScale, MAX) * 100.0) / 100.0;
        String text = String.format(Locale.ROOT, "%.2f", rounded);
        // "1.00" is "1", "1.30" is "1.3".
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** The number itself, for the bridge's status. */
    static double number(float fontScale) {
        return Double.parseDouble(value(fontScale));
    }

    /** Runs in our pages before any of their own scripts. */
    static String script(String value) {
        return "window.SXFontScale=" + value + ";";
    }
}
