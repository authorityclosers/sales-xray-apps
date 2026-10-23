package com.authorityclosers.salesxray;

/**
 * "Picture of this screen" in Report a problem (Redmi bug #13): the size and
 * format of the picture the page asks for. Plain Java, for JVM tests;
 * Screenshot.java takes the picture.
 */
final class ScreenshotRules {

    static final int DEFAULT_EDGE = 1600;
    static final int MIN_EDGE = 320;
    static final int MAX_EDGE = 2400;

    private ScreenshotRules() {}

    /** The picture's width and height: the window's own, shrunk so its longest edge fits maxEdge. */
    static int[] size(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0) return new int[] { 0, 0 };
        int edge = maxEdge <= 0 ? DEFAULT_EDGE : Math.max(MIN_EDGE, Math.min(MAX_EDGE, maxEdge));
        int longest = Math.max(width, height);
        if (longest <= edge) return new int[] { width, height };
        double scale = (double) edge / longest;
        return new int[] { Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)) };
    }

    /** "png" when asked; anything else is JPEG. */
    static boolean png(String format) {
        return "png".equalsIgnoreCase(format);
    }

    static int quality(int asked) {
        if (asked <= 0) return 82;
        return Math.max(40, Math.min(95, asked));
    }
}
