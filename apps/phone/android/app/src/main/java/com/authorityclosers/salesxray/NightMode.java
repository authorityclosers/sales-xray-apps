package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * Light or dark (owner, 24 Oct 2026: "app does not follow theme of the mobile … implement dark and
 * light mode on the app properly"). The app follows the phone unless the seller picks Light or Dark
 * in Sales X-Ray; the page tells the app through the bridge's "theme" call. The pick is kept and set
 * before any screen opens, so the ground behind the page, the bars and Android's own sheets match it
 * from the first frame. MainActivity handles uiMode itself, so a change never reloads the page.
 */
final class NightMode {

    private static final String PREFS = "sx_theme";
    private static final String MODE = "mode";

    private NightMode() {}

    static String saved(Context context) {
        return ThemeRules.normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "system"));
    }

    /** At app start, before any screen. */
    static void applySaved(Context context) {
        AppCompatDelegate.setDefaultNightMode(ThemeRules.nightMode(saved(context)));
    }

    /** The seller's pick from the page: "system", "light" or "dark" (anything else is "system"). On the main thread. */
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
