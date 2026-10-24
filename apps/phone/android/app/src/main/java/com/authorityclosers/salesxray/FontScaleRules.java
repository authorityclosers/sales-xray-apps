package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * The phone's font size in the app (owner's OnePlus Nord, 24 Oct 2026: the WebView enlarged only the
 * text, so large sizes overflowed and small made icons look wrong). For now the WebView keeps text at
 * 100%; later the page scales its whole layout from window.SXFontScale (FontScale.PAGE_ZOOM).
 * Plain Java, for JVM tests; FontScale reads Android.
 */
final class FontScaleRules {

    /** Beyond Android's own largest size (2 on Android 14), only a broken value. */
    private static final float MAX = 3f;
    /** The WebView's text zoom, in percent: the designed size, whatever the phone's font size. */
    static final int TEXT_ZOOM = 100;

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
