package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.HashSet;
import java.util.Set;

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
 *
 * A page that pads itself (native feel, 3 Nov 2026) draws under the bars instead and
 * pads by the --sx-safe-* variables the app sets on it (SafeAreaRules); the app then
 * pads only the keyboard. The page's choice is kept, so the next start opens the
 * same way before the page has said anything.
 */
final class SafeArea {

    /** What the page pads by itself now, in pixels (left, top, right, bottom); all 0 while the app pads it. */
    interface Listener {
        void pageInsets(int[] insets);
    }

    private static final String PREFS = "sx.safe_area";
    private static final String PAGE_PADS = "page_pads";

    private final WebView page;
    private final Set<String> origins = new HashSet<>();
    private ScriptHandler variables;
    private String script;

    private SafeArea(WebView page, Set<String> hosts) {
        this.page = page;
        for (String host : hosts) origins.add("https://" + host);
    }

    /** Pads the page or hands it its insets; {@code listener} hears what the page pads by itself. */
    static SafeArea apply(Activity activity, WebView page, Set<String> hosts, Listener listener) {
        SafeArea area = new SafeArea(page, hosts);
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
            boolean pagePads = pagePads(activity);
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int[] sides = {bars.left, bars.top, bars.right, bars.bottom};
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int[] padding = SafeAreaRules.appPadding(pagePads, sides, keyboard);
            view.setPadding(padding[0], padding[1], padding[2], padding[3]);
            int[] own = SafeAreaRules.pageInsets(pagePads, sides, keyboard);
            area.setVariables(own, view.getResources().getDisplayMetrics().density);
            listener.pageInsets(own);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
        return area;
    }

    static boolean pagePads(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAGE_PADS, false);
    }

    /** The page says whether it pads itself (sx-phone.js, on every page load). Main thread. */
    static void setPagePads(Activity activity, boolean pagePads) {
        if (pagePads(activity) == pagePads) return;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAGE_PADS, pagePads).apply();
        ViewCompat.requestApplyInsets(activity.findViewById(android.R.id.content));
    }

    /** A page loaded: a WebView without document-start scripts gets the variables now. Main thread. */
    void pageLoaded() {
        if (script != null && variables == null) page.evaluateJavascript(script, null);
    }

    /** The variables before the page's own scripts on every new page, and at once on the open one. */
    private void setVariables(int[] insets, float density) {
        String next = SafeAreaRules.script(insets, density);
        if (next.equals(script)) return;
        script = next;
        if (variables != null) variables.remove();
        variables = null;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                variables = WebViewCompat.addDocumentStartJavaScript(page, next, origins);
            } catch (RuntimeException ignored) {
                // pageLoaded sets them after each load instead.
            }
        }
        page.evaluateJavascript(next, null);
    }
}
