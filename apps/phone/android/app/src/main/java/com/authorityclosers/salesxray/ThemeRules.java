package com.authorityclosers.salesxray;

/** The seller's Light, Dark or System pick, as Android's night mode (NightMode). Plain Java, for JVM tests. */
final class ThemeRules {

    // AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, MODE_NIGHT_NO and MODE_NIGHT_YES.
    static final int FOLLOW_SYSTEM = -1;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private ThemeRules() {}

    static String normalize(String mode) {
        if ("light".equals(mode) || "dark".equals(mode)) return mode;
        return "system";
    }

    static int nightMode(String mode) {
        switch (normalize(mode)) {
            case "light":
                return LIGHT;
            case "dark":
                return DARK;
            default:
                return FOLLOW_SYSTEM;
        }
    }

    /** The bars' saved colours are kept per mode, so a phone that turned dark doesn't open in light bars. */
    static String barsKey(String which, boolean dark) {
        return which + (dark ? "_dark" : "_light");
    }
}
