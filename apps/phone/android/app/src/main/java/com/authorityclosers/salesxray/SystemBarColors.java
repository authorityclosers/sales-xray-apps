package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.regex.Pattern;

/**
 * The status bar and the navigation bar take the colours at the very top and
 * bottom of the page (sx-phone.js measures them), with icons that stay readable.
 *
 * SafeArea pads the page by the bars and the cut-out, so what shows behind the
 * bars is the window's root view background, not the window's status bar
 * colour (ignored on Android 15+): both are set. The last colours are kept so the
 * next launch opens in them instead of the theme's grey.
 */
final class SystemBarColors {

    private static final String PREFS = "sx.system_bars";
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private SystemBarColors() {}

    /** Paints both bars; anything but #rrggbb is ignored. Kept per light and dark mode. */
    static void paint(Activity activity, String status, String nav) {
        if (status == null || !HEX.matcher(status).matches()) return;
        if (nav == null || !HEX.matcher(nav).matches()) nav = status;
        apply(activity, Color.parseColor(status), Color.parseColor(nav));
        boolean dark = NightMode.dark(activity);
        prefs(activity).edit().putString(ThemeRules.barsKey("status", dark), status).putString(ThemeRules.barsKey("nav", dark), nav).apply();
    }

    /**
     * The colours the app last showed in this mode, before the page has said anything; the page's own
     * ground when there are none yet, so a phone that just turned dark never opens in light bars.
     */
    static void restore(Activity activity) {
        SharedPreferences prefs = prefs(activity);
        boolean dark = NightMode.dark(activity);
        String status = prefs.getString(ThemeRules.barsKey("status", dark), null);
        String nav = prefs.getString(ThemeRules.barsKey("nav", dark), null);
        if (status == null || !HEX.matcher(status).matches()) {
            int ground = androidx.core.content.ContextCompat.getColor(activity, R.color.sx_ground);
            apply(activity, ground, ground);
            return;
        }
        if (nav == null || !HEX.matcher(nav).matches()) nav = status;
        apply(activity, Color.parseColor(status), Color.parseColor(nav));
    }

    private static void apply(Activity activity, int status, int nav) {
        Window window = activity.getWindow();
        View root = window.getDecorView();
        // Top of the padded root view behind the status bar, bottom behind the
        // navigation bar; the page covers the middle.
        root.setBackground(
            new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { status, status, status, nav, nav, nav })
        );
        window.setStatusBarColor(status);
        window.setNavigationBarColor(nav);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, root);
        bars.setAppearanceLightStatusBars(ColorUtils.calculateLuminance(status) > 0.5);
        bars.setAppearanceLightNavigationBars(ColorUtils.calculateLuminance(nav) > 0.5);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
