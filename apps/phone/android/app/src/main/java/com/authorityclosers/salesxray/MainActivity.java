package com.authorityclosers.salesxray;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
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

    /** The app is the one in front (call logging tells the seller about new recordings otherwise). */
    static volatile boolean inFront;

    /** The splash stays until the page paints, but never longer than this (a slow network). */
    private static final long SPLASH_MAX_MS = 6_000;
    /** How long after the screen opens the Play build asks Play for a newer version. */
    private static final long UPDATE_CHECK_DELAY_MS = 4000;
    private static final String START_LOG = "SXStart";
    private long createdAt;
    private boolean painted;
    private boolean finishedOnce;

    private PhoneBridge phone;
    private GoogleSignIn google;
    /** The web app's own copy in the app (S9): pages come from the phone, the API from the server. */
    private BundledWeb bundled;
    /** The page's pending audio file pick (RecordingPicker), and the picker it waits on. */
    private ValueCallback<Uri[]> pickCallback;
    private ActivityResultLauncher<Intent> recordingPicker;
    private ConnectFlow connect;
    /** Google Play in-app updates in the Play build; nothing in the download-link build. */
    private StoreUpdates updates;
    /** A lead or Report to open from an alert (a /leads or /review path), once the page is there. */
    private String pendingOpen;
    private boolean pageReady;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // The splash (the app's ground and mark) stays up until the page has painted, so the
        // seller never sees an empty screen between the two (23 Oct 2026: about 1 s on a Note 8 Pro).
        createdAt = SystemClock.uptimeMillis();
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setKeepOnScreenCondition(() -> !painted && SystemClock.uptimeMillis() - createdAt < SPLASH_MAX_MS);
        super.onCreate(savedInstanceState);
        SafeArea.apply(this);
        recordingPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            ValueCallback<Uri[]> callback = pickCallback;
            pickCallback = null;
            if (callback == null) return;
            Intent data = result.getData();
            callback.onReceiveValue(
                result.getResultCode() == RESULT_OK && data != null ? WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), data) : null
            );
        });
        WebView webView = getBridge().getWebView();
        bundled = BundledWeb.load(this, LeadAlerts.serverUrl(this));
        // The page's ground before it paints follows the phone's light or dark mode.
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.sx_ground));
        // Back: through the page's history (it closes sheets and dialogs first); on the first page,
        // the app goes to the background and stays ready, so opening it again is quick. Capacitor's
        // own handler did nothing on the first page.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView page = getBridge().getWebView();
                if (page.canGoBack()) page.goBack();
                else moveTaskToBack(true);
            }
        });
        connect = new ConnectFlow(this, webView);
        phone = new PhoneBridge(this, webView, connect);
        phone.setBundledWeb(bundled);
        google = new GoogleSignIn(this, webView);
        updates = new StoreUpdates(this);
        // Asked once the first screen is up, so Play's sheet never covers the start.
        webView.postDelayed(updates::start, UPDATE_CHECK_DELAY_MS);
        phone.attach();
        phone.takeLaunch(getIntent());
        takeOpen(getIntent());
        getBridge()
            .setWebViewClient(
                new BridgeWebViewClient(getBridge()) {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        // Pages, screens, scripts and styles from the app's own copy; the rest from the server.
                        WebResourceResponse local = bundled.serve(request);
                        return local != null ? local : super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        if (request.isForMainFrame() && google.intercept(request.getUrl(), view)) return true;
                        return super.shouldOverrideUrlLoading(view, request);
                    }

                    @Override
                    public void onPageCommitVisible(WebView view, String url) {
                        super.onPageCommitVisible(view, url);
                        if (painted) return;
                        // The first page is on screen: the splash goes, and the start time is logged.
                        painted = true;
                        reportFullyDrawn();
                        Log.i(START_LOG, "first paint " + sinceStart() + " ms after the app started (" + (SystemClock.uptimeMillis() - createdAt) + " ms after the screen opened; " + (bundled.on() ? "pages from the app" : "pages from the server") + ")");
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        if (!finishedOnce) {
                            finishedOnce = true;
                            Log.i(START_LOG, "page loaded " + sinceStart() + " ms after the app started");
                        }
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

                @Override
                public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                    // "Add the recording" opens in the phone's call recordings folder (#54).
                    if (!RecordingPicker.wantsAudio(params)) return super.onShowFileChooser(view, callback, params);
                    if (pickCallback != null) pickCallback.onReceiveValue(null);
                    pickCallback = callback;
                    try {
                        recordingPicker.launch(RecordingPicker.intent(MainActivity.this, params));
                        return true;
                    } catch (ActivityNotFoundException error) {
                        pickCallback = null;
                        return super.onShowFileChooser(view, callback, params);
                    }
                }
            }
        );
        // Capacitor started loading the site before this client was in place: with the app's own
        // copy on, load the home screen again so it comes from the phone (the live site sends / there).
        String site = LeadAlerts.serverUrl(this);
        if (bundled.on() && site != null) webView.loadUrl(site.replaceAll("/+$", "") + "/dashboard/");
        AppShortcuts.publish(this);
        CrashReports.tag(this, bundled);
        google.take(getIntent());
        connect.take(getIntent());
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
        connect.take(intent);
        takeOpen(intent);
        deliverOpen();
    }

    @Override
    public void onResume() {
        super.onResume();
        inFront = true;
        if (painted) {
            // Back from the background: how long until the next frame is on screen.
            long resumed = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(frame -> Log.i(START_LOG, "warm: frame " + (SystemClock.uptimeMillis() - resumed) + " ms after resume"));
        }
        // Back in the app after a call: "How did it go?" is here, so its reminder goes (#48).
        AfterCallNotice.clear(this);
        if (phone != null) phone.onResume();
        if (google != null) google.onResume();
        if (updates != null) updates.resume();
        // Work calls made since the last look reach their leads (UX study item 5).
        CallLogSync.soon(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        inFront = false;
        if (phone != null) phone.onPause();
    }

    @Override
    public void onDestroy() {
        if (updates != null) updates.stop();
        super.onDestroy();
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

    /** Screens an alert or a long-press shortcut may open: Leads, Calls, Chats and Home. */
    static boolean opensInApp(String path) {
        for (String start : new String[] { "/leads", "/analysis/calls", "/chats", "/dashboard" }) {
            if (path.equals(start) || path.startsWith(start + "/") || path.startsWith(start + "#") || path.startsWith(start + "?")) return true;
        }
        return false;
    }

    /** Milliseconds since Android started this app's process. */
    private static long sinceStart() {
        return SystemClock.uptimeMillis() - Process.getStartUptimeMillis();
    }

    private void takeOpen(Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra(LeadAlerts.EXTRA_OPEN);
        intent.removeExtra(LeadAlerts.EXTRA_OPEN);
        if (path != null && (opensInApp(path) || ReviewAlertRules.isPath(path))) pendingOpen = path;
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
