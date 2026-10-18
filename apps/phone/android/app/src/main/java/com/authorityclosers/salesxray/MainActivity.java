package com.authorityclosers.salesxray;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;
import java.util.Arrays;
import org.json.JSONObject;
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

    private PhoneBridge phone;
    private GoogleSignIn google;
    /** A lead or Report to open from an alert (a /leads or /review path), once the page is there. */
    private String pendingOpen;
    private boolean pageReady;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = getBridge().getWebView();
        phone = new PhoneBridge(this, webView);
        google = new GoogleSignIn(this, webView);
        phone.attach();
        phone.takeLaunch(getIntent());
        takeOpen(getIntent());
        getBridge()
            .setWebViewClient(
                new BridgeWebViewClient(getBridge()) {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        if (request.isForMainFrame() && google.intercept(request.getUrl(), view)) return true;
                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        phone.inject(view, url);
                        google.onPageFinished(view, url);
                        Uri page = Uri.parse(url);
                        pageReady = "https".equals(page.getScheme()) && PhoneBridge.HOSTS.contains(page.getHost());
                        deliverOpen();
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
        // The page's Google sign-in opens a window and waits for its message;
        // in the app that window is a hidden web view (see openSignInWindow).
        webView.getSettings().setSupportMultipleWindows(true);
        webView.setWebChromeClient(
            new BridgeWebChromeClient(getBridge()) {
                @Override
                public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                    if (!isUserGesture) return false;
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(openSignInWindow(view));
                    resultMsg.sendToTarget();
                    return true;
                }

                @Override
                public void onCloseWindow(WebView window) {
                    closeSignInWindow(window);
                }
            }
        );
        google.take(getIntent());
        // After Capacitor's SystemBars has painted the theme's colours at start-up.
        webView.post(() -> SystemBarColors.restore(this));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Light or dark mode changed: Capacitor repainted the bars, so the page measures again.
        if (phone != null) phone.signal("bars");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (phone == null) return;
        phone.takeLaunch(intent);
        phone.signal("launch");
        google.take(intent);
        takeOpen(intent);
        deliverOpen();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (phone != null) phone.onResume();
        if (google != null) google.onResume();
        // Work calls made since the last look reach their leads (UX study item 5).
        CallLogSync.soon(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (google != null) google.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (phone != null) phone.onPermissionResult(requestCode);
    }

    private void takeOpen(Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra(LeadAlerts.EXTRA_OPEN);
        intent.removeExtra(LeadAlerts.EXTRA_OPEN);
        if (path != null && (path.startsWith("/leads") || ReviewAlertRules.isPath(path))) pendingOpen = path;
    }

    /** Opens the alert's lead or Report in the page (a fresh mark each time, so the same one opens again). */
    private void deliverOpen() {
        if (pendingOpen == null || !pageReady) return;
        String path = pendingOpen + (pendingOpen.contains("#") ? "&" : "#") + "n=" + System.currentTimeMillis();
        pendingOpen = null;
        Uri parsed = Uri.parse("https://x" + path.replace('#', '?'));
        if (ReviewAlertRules.isPath(path)) ReviewAlerts.clear(this);
        else LeadAlerts.clear(this, parsed.getQueryParameter("lead"));
        getBridge().getWebView().evaluateJavascript("location.assign(" + JSONObject.quote(path) + ")", null);
    }

    /**
     * A window the page opened (window.open). It stays invisible: it only hosts
     * Google sign-in's start, which goes to a Custom Tab, and its /auth/complete,
     * which messages the page. Any other page it tries to show goes to the app
     * (Sales Xray) or the browser (anything else).
     */
    private WebView openSignInWindow(WebView opener) {
        WebView window = new WebView(this);
        WebSettings settings = window.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(opener.getSettings().getUserAgentString());
        window.setBackgroundColor(Color.TRANSPARENT);
        window.setAlpha(0f);
        window.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return routeSignInWindow(view, request.getUrl());
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    if (routeSignInWindow(view, Uri.parse(url))) view.stopLoading();
                }
            }
        );
        ((ViewGroup) opener.getParent()).addView(window, new ViewGroup.LayoutParams(1, 1));
        return window;
    }

    /** True when the sign-in window must not load this URL itself. */
    private boolean routeSignInWindow(WebView window, Uri url) {
        if (url == null || "about".equals(url.getScheme())) return false;
        if (google.intercept(url, window)) return true;
        if (GoogleSignIn.staysInSignInWindow(url)) return false;
        window.post(() -> closeSignInWindow(window));
        if ("https".equals(url.getScheme()) && PhoneBridge.HOSTS.contains(url.getHost())) {
            getBridge().getWebView().loadUrl(url.toString());
        } else {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (ActivityNotFoundException ignored) {
                // Nothing on the phone opens it; the page stays as it is.
            }
        }
        return true;
    }

    private void closeSignInWindow(WebView window) {
        if (window.getParent() == null) return;
        ((ViewGroup) window.getParent()).removeView(window);
        google.signInWindowClosed(window);
        window.destroy();
    }
}
