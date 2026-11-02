package com.authorityclosers.salesxray;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.work.Configuration;

/** Starts Firebase early when lead alerts are on, so an alert can arrive with the app closed. */
public class SalesXrayApp extends Application implements Configuration.Provider {

    /** The main thread has run the message posted at the process's start (see {@link #startedForScreen}). */
    private static volatile boolean pastStart;

    /**
     * A screen created now is the one Android started this process for (a launch, a notification's tap): Android
     * queues that screen before the message posted in onCreate. A process a lead alert or a background job started is
     * past that message when the seller opens the app, so its age says nothing about the start (sx.app_launch).
     */
    static boolean startedForScreen() {
        return !pastStart;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        new Handler(Looper.getMainLooper()).post(() -> pastStart = true);
        // Opening a screen: WebView's engine starts in the background while Android creates it (bug #67).
        WebViewStart.ifOpening(this);
        // "SalesXrayApp/<this build's version>" for the page and the app's own calls.
        AppUserAgent.remember(this);
        // The seller's Light, Dark or System pick, before any screen opens.
        NightMode.applySaved(this);
        LeadAlerts.init(this);
    }

    /**
     * WorkManager starts the first time the app uses it (call logging, call capture), on that thread, not on the
     * main thread at every start (about 0.24 s on a Note 8 Pro; the manifest turns its start-up provider off).
     */
    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }
}
