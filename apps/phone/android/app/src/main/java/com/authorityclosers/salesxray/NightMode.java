package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * Light or dark, the same as the page's own theme key (ThemeRules): Light unless the seller picks Dark in
 * Sales X-Ray; the page tells the app through the bridge's "theme" call on every load and on each change.
 * The pick is kept and set before any screen opens, so the ground behind the page, the bars, the WebView's
 * colour scheme and Android's own sheets match it from the first frame. MainActivity handles uiMode itself,
 * so a change never reloads the page.
 */
final class NightMode {

    private static final String PREFS = "sx_theme";
    private static final String MODE = "mode";

    private NightMode() {}

    static String saved(Context context) {
        return ThemeRules.normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "light"));
    }

    /** At app start, before any screen. */
    static void applySaved(Context context) {
        AppCompatDelegate.setDefaultNightMode(ThemeRules.nightMode(saved(context)));
    }

    /** The seller's pick from the page: "light" or "dark" (anything else is "light"). On the main thread. */
    static String set(Context context, String mode) {
        String chosen = ThemeRules.normalize(mode);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE, chosen).apply();
        AppCompatDelegate.setDefaultNightMode(ThemeRules.nightMode(chosen));
        return chosen;
    }

    static boolean dark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
