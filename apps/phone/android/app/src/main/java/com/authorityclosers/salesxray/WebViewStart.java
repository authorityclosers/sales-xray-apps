package com.authorityclosers.salesxray;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.StartUpLocation;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;
import java.util.List;

/**
 * WebView's engine starts on a background thread as the app opens (owner bug #67, 30 Oct 2026). The screen's
 * WebView otherwise loads and starts it on the main thread before any page can load (about 0.5 s on a Note 8 Pro);
 * started here, the parts that can run off the main thread are done while Android creates the screen. Only when the
 * process starts to show a screen: an alert, a job or the call recorder never needs a WebView.
 *
 * Once it has started, the app connects to the server ahead of the page (native feel, 3 Nov 2026): the page's first
 * calls to the server then find the connection open instead of opening it themselves.
 */
final class WebViewStart {

    private static final String TAG = "SXStart";

    private WebViewStart() {}

    /** First thing in the Application, before anything touches WebView. True when the process started for a screen. */
    static boolean ifOpening(Context context) {
        if (!opening()) return false;
        Context app = context.getApplicationContext();
        try {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(command -> new Thread(command, "sx-webview-start").start()).build();
            WebViewCompat.startUpWebView(
                app,
                config,
                new WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>() {
                    @Override
                    public void onResult(WebViewStartUpResult result) {
                        Log.i(TAG, "WebView started (" + result.getTotalTimeInUiThreadMillis() + " ms on the main thread)");
                        // Anything that still started WebView on the main thread first (a new WebView, a cookie read).
                        List<StartUpLocation> blocking = result.getUiThreadBlockingStartUpLocations();
                        if (blocking != null) {
                            for (StartUpLocation location : blocking) Log.w(TAG, "WebView start blocked the main thread", location.getStackInformation());
                        }
                        String site = LeadAlerts.serverUrl(app);
                        if (site != null) new Handler(Looper.getMainLooper()).post(() -> preconnect(site));
                    }

                    @Override
                    public void onError(WebViewStartupException error) {
                        // The screen's WebView starts it the usual way.
                        Log.w(TAG, "WebView did not start early: " + error.getClass().getSimpleName());
                    }
                }
            );
        } catch (RuntimeException error) {
            Log.w(TAG, "WebView did not start early: " + error.getClass().getSimpleName());
        }
        return true;
    }

    /** The server's connection opens now, before the page asks (DNS, TCP and TLS). Main thread, WebView started. */
    @OptIn(markerClass = Profile.ExperimentalPreconnect.class)
    @SuppressLint("RequiresFeature")
    private static void preconnect(String site) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return;
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.ENQUEUE_PRECONNECT)) return;
            Profile profile = ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME);
            if (profile == null) return;
            profile.enqueuePreconnect(site);
            Log.i(TAG, "connecting to " + site + " ahead of the page");
        } catch (RuntimeException error) {
            // The page connects by itself, as before.
            Log.w(TAG, "no early connection: " + error.getClass().getSimpleName());
        }
    }

    /** Android started this process for a screen in front (a launch, a notification's tap). */
    private static boolean opening() {
        ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(state);
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }
}
