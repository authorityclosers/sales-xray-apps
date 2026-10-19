package com.authorityclosers.salesxray;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The page never draws under the status bar, a notch or punch-hole, the navigation
 * bar or the keyboard, on any phone shape (owner's phone test, 19 Oct).
 *
 * Capacitor 8's SystemBars hands the insets to the page once the WebView is 140+ and
 * the page asks for viewport-fit=cover, and Sales Xray's screens don't pad for them,
 * so on Android 15+ (always edge-to-edge) the top of every screen sat under the
 * status bar and the cut-out. SystemBars is off (capacitor.config.json): the app
 * draws edge-to-edge on every Android version and pads the page itself by the
 * system bars, the display cut-out and the keyboard. The page sees no insets at all
 * (its safe-area values are 0), so nothing is padded twice. The padding shows the
 * window background, which SystemBarColors paints in the page's own top and bottom
 * colours.
 */
final class SafeArea {

    private SafeArea() {}

    static void apply(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Landscape too: the page stays clear of the cut-out; the strip beside it
            // takes the bar colour instead of black.
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            activity.getWindow().setAttributes(params);
        }
        View content = activity.findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets padding = padding(insets);
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    /** System bars and cut-out on every side; at the bottom, the keyboard when it is taller. */
    static Insets padding(WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
        return Insets.of(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
    }
}
