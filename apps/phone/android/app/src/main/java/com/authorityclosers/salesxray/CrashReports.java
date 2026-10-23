package com.authorityclosers.salesxray;

import android.content.Context;
import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Crash reports (S6, owner 23 Oct 2026) through the Firebase project lead
 * alerts already use. Crashlytics starts with that Firebase app
 * (LeadAlerts.init), so a phone reports crashes once Sales X-Ray has sent it
 * the alert settings. A report names the build, never the seller: no user id,
 * no lead, no number, no page address.
 */
final class CrashReports {

    private CrashReports() {}

    static void tag(Context context, BundledWeb bundled) {
        if (FirebaseApp.getApps(context).isEmpty()) return;
        try {
            FirebaseCrashlytics crashes = FirebaseCrashlytics.getInstance();
            crashes.setCustomKey("store", Permissions.store(context));
            crashes.setCustomKey("web_shell", bundled != null && bundled.on() ? bundled.sha() : "live");
        } catch (RuntimeException ignored) {
            // Crash reports are a help, never a reason for the app to stop.
        }
    }
}
