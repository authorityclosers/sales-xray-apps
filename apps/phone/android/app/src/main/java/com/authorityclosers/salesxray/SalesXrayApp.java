package com.authorityclosers.salesxray;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.work.Configuration;
import androidx.work.WorkManager;
import com.facebook.FacebookSdk;

/** Starts Firebase early when lead alerts are on, so an alert can arrive with the app closed. */
public class SalesXrayApp extends Application implements Configuration.Provider {

    @Override
    public void onCreate() {
        super.onCreate();
        StartTimes.mark("app");
        StartTimes.readMode(this);
        // Probe base: what the manifest's start-up providers did before this probe removed them.
        if (!StartTimes.on("lazywork")) {
            WorkManager.getInstance(this);
            StartTimes.mark("app workmanager");
        }
        if (!StartTimes.on("lazyfb")) {
            try {
                FacebookSdk.sdkInitialize(this);
            } catch (RuntimeException ignored) {}
            StartTimes.mark("app facebook");
        }
        if ((StartTimes.on("webview") || StartTimes.on("reads")) && WebViewStart.forScreen()) {
            if (StartTimes.on("webview")) WebViewStart.engine(this);
            if (StartTimes.on("reads")) WebViewStart.readWebCopy(this);
        }
        // "SalesXrayApp/<this build's version>" for the page and the app's own calls.
        AppUserAgent.remember(this);
        StartTimes.mark("app user agent");
        // The seller's Light, Dark or System pick, before any screen opens.
        NightMode.applySaved(this);
        StartTimes.mark("app night mode");
        LeadAlerts.init(this);
        StartTimes.mark("app firebase");
    }

    /** WorkManager starts the first time something uses it, not with every start of the app. */
    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder().build();
    }
}
