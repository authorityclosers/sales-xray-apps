package com.authorityclosers.salesxray;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Window;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Sales Xray in a Capacitor shell, plus the phone's own call recordings. */
public class MainActivity extends BridgeActivity {

    /** Only these mean "no connection"; anything else keeps the page that was there. */
    private static final Set<Integer> NETWORK_ERRORS = new HashSet<>(
        Arrays.asList(
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_PROXY_AUTHENTICATION
        )
    );

    /** The page's own theme colour for the current light or dark mode. */
    private static final String THEME_COLOR =
        "(function(){var m=Array.prototype.filter.call(document.querySelectorAll('meta[name=\"theme-color\"]')," +
        "function(x){return !x.media||matchMedia(x.media).matches;})[0];return m?m.content:'';})()";

    private PhoneBridge phone;
    private GoogleSignIn google;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = getBridge().getWebView();
        phone = new PhoneBridge(this, webView);
        google = new GoogleSignIn(this, webView);
        phone.attach();
        phone.takeLaunch(getIntent());
        getBridge()
            .setWebViewClient(
                new BridgeWebViewClient(getBridge()) {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        if (request.isForMainFrame() && google.intercept(request.getUrl())) return true;
                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        phone.inject(view, url);
                        paintSystemBars(view);
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                        // "Can't be reached" only when the phone really has no connection.
                        if (request.isForMainFrame() && NETWORK_ERRORS.contains(error.getErrorCode())) {
                            super.onReceivedError(view, request, error);
                        }
                    }

                    @Override
                    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                        // A 4xx or 5xx page is the server's own answer: show it as it is.
                    }
                }
            );
        google.take(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (phone == null) return;
        phone.takeLaunch(intent);
        phone.signal("launch");
        google.take(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (phone != null) phone.onResume();
        if (getBridge() != null) paintSystemBars(getBridge().getWebView());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (phone != null) phone.onPermissionResult(requestCode);
    }

    /** Status and navigation bars take the page's colour, so the app reads as one surface. */
    private void paintSystemBars(WebView view) {
        if (view == null) return;
        view.evaluateJavascript(THEME_COLOR, value -> {
            if (value == null) return;
            int color;
            try {
                color = Color.parseColor(value.replace("\"", "").trim());
            } catch (IllegalArgumentException error) {
                return;
            }
            Window window = getWindow();
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);
            boolean light = ColorUtils.calculateLuminance(color) > 0.5;
            WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
            bars.setAppearanceLightStatusBars(light);
            bars.setAppearanceLightNavigationBars(light);
        });
    }
}
