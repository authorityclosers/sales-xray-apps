package com.authorityclosers.salesxray;

import android.app.Application;

/** Starts Firebase early when lead alerts are on, so an alert can arrive with the app closed. */
public class SalesXrayApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // "SalesXrayApp/<this build's version>" for the page and the app's own calls.
        AppUserAgent.remember(this);
        // The seller's Light, Dark or System pick, before any screen opens.
        NightMode.applySaved(this);
        LeadAlerts.init(this);
    }
}
