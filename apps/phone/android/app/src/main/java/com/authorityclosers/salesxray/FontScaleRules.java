package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * The phone's font size in the app (owner's OnePlus Nord, 24 Oct 2026: the WebView enlarged only the
 * text, so large sizes overflowed and small made icons look wrong). For now the WebView's text zoom is
 * kept within 85-115%; later the page zooms its whole layout from window.SXFontScale (FontScale.PAGE_ZOOM).
 * Plain Java, for JVM tests; FontScale reads Android.
 */
final class FontScaleRules {

    /** Beyond Android's own largest size (2 on Android 14), only a broken value. */
    private static final float MAX = 3f;
    /** The WebView's text zoom, in percent, stays within these (orchestrator, 24 Oct 2026). */
    static final int MIN_TEXT_ZOOM = 85;
    static final int MAX_TEXT_ZOOM = 115;

    private FontScaleRules() {}

    /** Configuration.fontScale as the page gets it: "0.85", "1", "1.15", "1.3"; "1" for nothing usable. */
    static String value(float fontScale) {
        if (Float.isNaN(fontScale) || fontScale <= 0f) return "1";
        double rounded = Math.round(Math.min(fontScale, MAX) * 100.0) / 100.0;
        String text = String.format(Locale.ROOT, "%.2f", rounded);
        // "1.00" is "1", "1.30" is "1.3".
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * The WebView's text zoom for this font size: round(fontScale x 100) within 85-115, so large system
     * sizes no longer overflow, large-font users keep some enlargement and small sizes never go tiny.
     */
    static int textZoom(float fontScale) {
        if (Float.isNaN(fontScale) || fontScale <= 0f) return 100;
        long percent = Math.round(Math.min(fontScale, MAX) * 100.0);
        return (int) Math.max(MIN_TEXT_ZOOM, Math.min(MAX_TEXT_ZOOM, percent));
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
