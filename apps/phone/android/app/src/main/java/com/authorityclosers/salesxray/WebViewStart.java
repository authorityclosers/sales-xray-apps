package com.authorityclosers.salesxray;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Experiment (bug #67): WebView's engine and the web copy started off the main thread as the app opens. */
final class WebViewStart {

    private static final String TAG = "SXStart";
    /** The web copy this start uses, read on a background thread: {live, chosen}. */
    private static volatile FutureTask<Object[]> webCopy;

    private WebViewStart() {}

    /** The process started to show a screen (not for an alert, a job or a call). */
    static boolean forScreen() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        StartTimes.mark("importance " + info.importance);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    static void engine(Context context) {
        try {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(command -> new Thread(command, "sx-webview-start").start()).build();
            WebViewCompat.startUpWebView(
                context.getApplicationContext(),
                config,
                new WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>() {
                    @Override
                    public void onResult(WebViewStartUpResult result) {
                        StartTimes.mark("webview started (ui " + result.getTotalTimeInUiThreadMillis() + " ms, longest task " + result.getMaxTimePerTaskInUiThreadMillis() + " ms)");
                    }

                    @Override
                    public void onError(WebViewStartupException error) {
                        Log.w(TAG, "webview start failed: " + error);
                    }
                }
            );
        } catch (RuntimeException error) {
            Log.w(TAG, "webview start not run: " + error);
        }
    }

    static void readWebCopy(Context context) {
        Context app = context.getApplicationContext();
        FutureTask<Object[]> task = new FutureTask<>(
            (Callable<Object[]>) () -> {
                String site = LeadAlerts.serverUrl(app);
                LiveWeb live = LiveWeb.create(app, site, BundledWeb.load(app, site));
                BundledWeb chosen = live.choose();
                StartTimes.mark("web copy read");
                return new Object[] { live, chosen };
            }
        );
        webCopy = task;
        new Thread(task, "sx-web-copy").start();
    }

    /** {live, chosen} read in the background, or null to read them now. */
    static Object[] takeWebCopy() {
        FutureTask<Object[]> task = webCopy;
        webCopy = null;
        if (task == null) return null;
        try {
            return task.get();
        } catch (Exception error) {
            return null;
        }
    }
}
