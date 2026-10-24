package com.authorityclosers.salesxray;

import android.util.Log;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.HashSet;
import java.util.Set;

/**
 * How the phone's font size reaches our pages. Until the page zooms itself, the WebView enlarges the text
 * within 85–115% (FontScaleRules.textZoom). With PAGE_ZOOM on, the page gets window.SXFontScale before its
 * own scripts and zooms its whole layout with it (clamped), and the WebView keeps text at 100%.
 */
final class FontScale {

    /**
     * Off until the web that zooms itself from window.SXFontScale (v0.88, CSS zoom) is in the build's web
     * copy and live (orchestrator, 24 Oct 2026). Never on before then: that web zooms whenever the value
     * is there, so text zoom and page zoom would add up.
     */
    static final boolean PAGE_ZOOM = false;

    private static final String TAG = "SXFontScale";

    private FontScale() {}

    /**
     * Once, before the first page commits. A font size change restarts the screen (fontScale is not in
     * the manifest's configChanges), so the page always loads with the current size.
     */
    static void apply(WebView webView, float fontScale, Set<String> hosts) {
        // A WebView that can't run a script before the page's own keeps the clamped text zoom.
        if (!PAGE_ZOOM || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            webView.getSettings().setTextZoom(FontScaleRules.textZoom(fontScale));
            return;
        }
        Set<String> origins = new HashSet<>();
        for (String host : hosts) origins.add("https://" + host);
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, FontScaleRules.script(FontScaleRules.value(fontScale)), origins);
            webView.getSettings().setTextZoom(100);
        } catch (RuntimeException e) {
            Log.w(TAG, "font size stays with the WebView: " + e.getClass().getSimpleName());
            webView.getSettings().setTextZoom(FontScaleRules.textZoom(fontScale));
        }
    }
}
