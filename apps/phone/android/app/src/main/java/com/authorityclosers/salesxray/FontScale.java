package com.authorityclosers.salesxray;

import android.util.Log;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.HashSet;
import java.util.Set;

/**
 * How the phone's font size reaches our pages. The WebView never enlarges text by itself: our type is in
 * px and icons don't follow a text zoom, so any text zoom breaks the balance between them (orchestrator,
 * 24 Oct 2026). With PAGE_ZOOM on, the page gets window.SXFontScale before its own scripts and scales its
 * whole layout with it (clamped).
 */
final class FontScale {

    /**
     * Off until the web that scales itself from window.SXFontScale (v0.88) is in the build's web copy and
     * live. Until then the page shows its designed size on every phone.
     */
    static final boolean PAGE_ZOOM = false;

    private static final String TAG = "SXFontScale";

    private FontScale() {}

    /**
     * Once, before the first page commits. A font size change restarts the screen (fontScale is not in
     * the manifest's configChanges), so the page always loads with the current size.
     */
    static void apply(WebView webView, float fontScale, Set<String> hosts) {
        webView.getSettings().setTextZoom(FontScaleRules.TEXT_ZOOM);
        if (!PAGE_ZOOM || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        Set<String> origins = new HashSet<>();
        for (String host : hosts) origins.add("https://" + host);
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, FontScaleRules.script(FontScaleRules.value(fontScale)), origins);
        } catch (RuntimeException e) {
            Log.w(TAG, "the page keeps its designed size: " + e.getClass().getSimpleName());
        }
    }
}
