package com.authorityclosers.salesxray;

/**
 * The app's theme key, as Android's night mode (NightMode): Light unless the seller picked Dark, the same as the page reads
 * its key (owner, 26 Oct 2026: Light and Dark only, Light by default). An old "system" pick is Light, as on the page, so
 * the ground before the page paints, the bars and the WebView's colour scheme always match the page (native feel,
 * 3 Nov 2026: a phone in dark mode opened a light page on a black ground). Plain Java, for JVM tests.
 */
final class ThemeRules {

    // AppCompatDelegate.MODE_NIGHT_NO and MODE_NIGHT_YES.
    static final int LIGHT = 1;
    static final int DARK = 2;

    private ThemeRules() {}

    static String normalize(String mode) {
        return "dark".equals(mode) ? "dark" : "light";
    }

    static int nightMode(String mode) {
        return "dark".equals(normalize(mode)) ? DARK : LIGHT;
    }

    /** The bars' saved colours are kept per mode, so a phone that turned dark doesn't open in light bars. */
    static String barsKey(String which, boolean dark) {
        return which + (dark ? "_dark" : "_light");
    }
}
