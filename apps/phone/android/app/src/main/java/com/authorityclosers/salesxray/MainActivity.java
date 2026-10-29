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
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
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

    private final QaProbe qa = new QaProbe();
    private PhoneBridge phone;
    private GoogleSignIn google;
    /** The web app's own copy in the app (S9): pages come from the phone, the API from the server. */
    private volatile BundledWeb bundled;
    /** Live web updates: a newer copy downloaded after a release, used from the next start (LiveWeb). */
    private LiveWeb live;
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
        // Capacitor's config with this build's version in the user agent ("SalesXrayApp/1.0.26"), read
        // before the WebView exists; null keeps Capacitor's own config.
        config = AppUserAgent.capConfig(this);
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
        // The app's own copy, or a live web update it downloaded and checked before this start.
        BundledWeb builtIn = BundledWeb.load(this, LeadAlerts.serverUrl(this));
        live = LiveWeb.create(this, LeadAlerts.serverUrl(this), builtIn);
        bundled = live.choose();
        // The page's ground before it paints follows the phone's light or dark mode.
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.sx_ground));
        // The page has its own dark design (prefers-color-scheme follows the DayNight theme), so the
        // WebView never darkens it on top (no double darkening on Xiaomi's "dark mode for apps").
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.getSettings(), false);
        }
        // Text at its designed size whatever the phone's font size: a text zoom grows the px type but not
        // the icons (owner's OnePlus Nord, 24 Oct 2026: overflow and odd icons). See FontScale.
        FontScale.apply(webView, getResources().getConfiguration().fontScale, PhoneBridge.HOSTS);
        // window.SXHaptics: one haptics engine for the whole app (owner, 25 Oct 2026). See Haptics.
        Haptics.install(webView, PhoneBridge.HOSTS);
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
        phone.setLiveWeb(live);
        google = new GoogleSignIn(this, webView);
        updates = new StoreUpdates(this);
        // Asked once the first screen is up, so Play's sheet never covers the start.
        webView.postDelayed(updates::start, UPDATE_CHECK_DELAY_MS);
        phone.attach();
        phone.takeLaunch(getIntent());
        takeOpen(getIntent());
        takeFix(getIntent());
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
                        qa.committed(view, url);
                        if (painted) return;
                        // The first page is on screen: the splash goes, and the start time is logged.
                        painted = true;
                        reportFullyDrawn();
                        Log.i(START_LOG, "first paint " + sinceStart() + " ms after the app started (" + (SystemClock.uptimeMillis() - createdAt) + " ms after the screen opened; " + (bundled.on() ? "pages from the app" : "pages from the server") + ")");
                        DeviceTelemetry.start(MainActivity.this, true, sinceStart(), bundled.on());
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
                public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                    QaProbe.console(MainActivity.this, message);
                    return super.onConsoleMessage(message);
                }

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
        boolean qaOpened = qa.open(this, webView, getIntent());
        if (!qaOpened && bundled.on() && site != null) {
            webView.loadUrl(site.replaceAll("/+$", "") + "/dashboard/");
            // A web update's first start must say it is up, or the app goes back (LiveWeb).
            live.watch(bundled, this::useWeb);
        }
        // Is there a newer web release? Downloaded in the background, used from the next start.
        live.check(true);
        AppShortcuts.publish(this);
        CrashReports.tag(this, bundled);
        google.take(getIntent());
        connect.take(getIntent());
        // After Capacitor's SystemBars has painted the theme's colours at start-up.
        webView.post(() -> SystemBarColors.restore(this));
    }

    /** A web update didn't start in time: the app went back (LiveWeb), and Home opens again from {@code next}. */
    private void useWeb(BundledWeb next) {
        bundled = next;
        if (phone != null) phone.setBundledWeb(next);
        String site = LeadAlerts.serverUrl(this);
        if (site == null) return;
        WebView page = getBridge().getWebView();
        // Nothing the failed copy loaded is used again: the WebView keeps scripts in memory by address, and
        // two copies can share an address (an emulator proof whose broken bundle kept B2's file names).
        page.clearCache(true);
        page.loadUrl(site.replaceAll("/+$", "") + (next.on() ? "/dashboard/" : "/"));
        if (next.on()) live.watch(next, this::useWeb);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Light or dark mode changed (the phone's, or the seller's pick): the ground and the bars
        // take that mode's colours at once, then the page measures and paints them again.
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().setBackgroundColor(ContextCompat.getColor(this, R.color.sx_ground));
        }
        SystemBarColors.restore(this);
        if (phone != null) phone.signal("bars");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (qa.open(this, getBridge().getWebView(), intent)) return;
        if (phone == null) return;
        phone.takeLaunch(intent);
        phone.signal("launch");
        google.take(intent);
        connect.take(intent);
        takeOpen(intent);
        deliverOpen();
        takeFix(intent);
    }

    /** The "no recording" notification's tap: open its fix (RecordingReason). */
    private void takeFix(Intent intent) {
        if (intent == null) return;
        String fix = intent.getStringExtra(RecordingReason.EXTRA_FIX);
        intent.removeExtra(RecordingReason.EXTRA_FIX);
        if (fix != null) getBridge().getWebView().post(() -> RecordingReason.fix(this, fix, PhoneBridge::pickActiveRecordingsFolder));
    }

    @Override
    public void onResume() {
        super.onResume();
        inFront = true;
        if (painted) {
            // Back from the background: how long until the next frame is on screen.
            long resumed = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(frame -> {
                long warm = SystemClock.uptimeMillis() - resumed;
                Log.i(START_LOG, "warm: frame " + warm + " ms after resume");
                DeviceTelemetry.start(this, false, warm, bundled != null && bundled.on());
            });
        }
        // Back in the app after a call: "How did it go?" is here, so its reminder goes (#48).
        AfterCallNotice.clear(this);
        if (phone != null) phone.onResume();
        if (google != null) google.onResume();
        // Back on screen: a web update still waiting to say it is up gets its full time again, and a
        // newer release is looked for (at most every 5 minutes).
        if (live != null) live.resumed();
        DeviceTelemetry.watchFrames(this);
        if (updates != null) updates.resume();
        // Work calls made since the last look reach their leads (UX study item 5).
        CallLogSync.soon(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        inFront = false;
        if (live != null) live.paused();
        DeviceTelemetry.stopFrames(this);
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
        // /settings: call-upload notices open Profile or Privacy → Calls (AutoCapture.attention).
        for (String start : new String[] { "/leads", "/analysis/calls", "/chats", "/dashboard", "/settings" }) {
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
        // A plain anchor (#calls-setup) is a place on the page, not a list of marks: it stays as it is.
        int hash = pendingOpen.indexOf('#');
        String path = hash >= 0 && !pendingOpen.substring(hash).contains("=") ? pendingOpen
            : pendingOpen + (hash >= 0 ? "&" : "#") + "n=" + System.currentTimeMillis();
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
