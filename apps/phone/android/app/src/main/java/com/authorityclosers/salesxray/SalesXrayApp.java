package com.authorityclosers.salesxray;

import android.app.Application;

/** Starts Firebase early when lead alerts are on, so an alert can arrive with the app closed. */
public class SalesXrayApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LeadAlerts.init(this);
    }
}
