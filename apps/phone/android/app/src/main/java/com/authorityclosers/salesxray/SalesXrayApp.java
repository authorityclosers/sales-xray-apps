package com.authorityclosers.salesxray;

import android.app.Application;

/** Starts Firebase early when lead alerts are on, so an alert can arrive with the app closed. */
public class SalesXrayApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        StartTimes.mark("app");
        StartTimes.readMode(this);
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
}
