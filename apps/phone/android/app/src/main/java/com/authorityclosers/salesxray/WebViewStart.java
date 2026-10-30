package com.authorityclosers.salesxray;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

/**
 * WebView's engine starts on a background thread as the app opens (owner bug #67, 30 Oct 2026). The screen's
 * WebView otherwise loads and starts it on the main thread before any page can load (about 0.5 s on a Note 8 Pro);
 * started here, the parts that can run off the main thread are done while Android creates the screen. Only when the
 * process starts to show a screen: an alert, a job or the call recorder never needs a WebView.
 */
final class WebViewStart {

    private static final String TAG = "SXStart";

    private WebViewStart() {}

    /** First thing in the Application, before anything touches WebView. */
    static void ifOpening(Context context) {
        if (!opening()) return;
        try {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(command -> new Thread(command, "sx-webview-start").start()).build();
            WebViewCompat.startUpWebView(
                context.getApplicationContext(),
                config,
                new WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>() {
                    @Override
                    public void onResult(WebViewStartUpResult result) {
                        Log.i(TAG, "WebView started (" + result.getTotalTimeInUiThreadMillis() + " ms on the main thread)");
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
    }

    /** Android started this process for a screen in front (a launch, a notification's tap). */
    private static boolean opening() {
        ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(state);
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }
}
