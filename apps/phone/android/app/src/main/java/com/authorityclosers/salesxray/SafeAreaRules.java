package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * Who keeps the page clear of the status bar, a cut-out, the navigation bar and the keyboard (native-feel spec,
 * 3 Nov 2026, section 4: edge to edge, with safe-area variables from the app and env() as the fallback).
 *
 * Until the page says it pads itself (sx-phone.js reads {@code <meta name="sx-safe-area" content="page">}), the app pads
 * it as before and the page's {@code --sx-safe-top/right/bottom/left} are 0, as its env(safe-area-inset-*) are in the
 * app today, so nothing is padded twice. Once it says so, the page draws under the bars and pads by those variables,
 * in CSS px; the app still pads the keyboard, and the bottom variable is 0 while the keyboard covers the navigation bar.
 *
 * Sides are left, top, right, bottom, in pixels. Plain Java, for JVM tests; SafeArea applies it.
 */
final class SafeAreaRules {

    static final String[] VARIABLES = {"--sx-safe-left", "--sx-safe-top", "--sx-safe-right", "--sx-safe-bottom"};

    private SafeAreaRules() {}

    /** The padding the app puts around the page. */
    static int[] appPadding(boolean pagePads, int[] bars, int keyboard) {
        if (!pagePads) return new int[] {bars[0], bars[1], bars[2], Math.max(bars[3], keyboard)};
        return new int[] {0, 0, 0, keyboard > bars[3] ? keyboard : 0};
    }

    /** What the page pads by itself. */
    static int[] pageInsets(boolean pagePads, int[] bars, int keyboard) {
        if (!pagePads) return new int[] {0, 0, 0, 0};
        return new int[] {bars[0], bars[1], bars[2], keyboard > bars[3] ? 0 : bars[3]};
    }

    /** Sets the variables on the page's root, in CSS px (pixels over the screen density); safe before the page exists. */
    static String script(int[] insets, float density) {
        StringBuilder set = new StringBuilder();
        for (int side = 0; side < VARIABLES.length; side++) {
            float css = density > 0 ? insets[side] / density : 0;
            set.append("s.setProperty(\"").append(VARIABLES[side]).append("\",\"")
                .append(String.format(Locale.ROOT, "%.1f", css).replace(".0", "")).append("px\");");
        }
        return "(function(){var d=document.documentElement;if(!d)return;var s=d.style;" + set + "})();";
    }
}
