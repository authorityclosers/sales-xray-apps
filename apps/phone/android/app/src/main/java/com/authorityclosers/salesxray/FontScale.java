package com.authorityclosers.salesxray;

import android.util.Log;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.HashSet;
import java.util.Set;

/**
 * The phone's font size reaches our pages as window.SXFontScale, before their own scripts, and the page
 * scales its layout with it (clamped, so it never breaks). The WebView then keeps text at 100% instead
 * of enlarging only the text. See FontScaleRules.
 */
final class FontScale {

    private static final String TAG = "SXFontScale";

    private FontScale() {}

    /**
     * Once, before the first page commits. A font size change restarts the screen (fontScale is not in
     * the manifest's configChanges), so the page always loads with the current size. A WebView that can't
     * run a script before the page's own keeps scaling text itself, as before.
     */
    static void apply(WebView webView, float fontScale, Set<String> hosts) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        Set<String> origins = new HashSet<>();
        for (String host : hosts) origins.add("https://" + host);
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, FontScaleRules.script(FontScaleRules.value(fontScale)), origins);
            webView.getSettings().setTextZoom(100);
        } catch (RuntimeException e) {
            Log.w(TAG, "font size stays with the WebView: " + e.getClass().getSimpleName());
        }
    }
}
