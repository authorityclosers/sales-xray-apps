package com.authorityclosers.salesxray;

import androidx.appcompat.app.AppCompatActivity;

/**
 * The download-link build has no Play Store to update from: Google Play
 * in-app updates live in the Play build only (src/play). A version prompt for
 * this build is S5's version.json, not built yet.
 */
final class StoreUpdates {

    StoreUpdates(AppCompatActivity activity) {}

    void start() {}

    void resume() {}

    void stop() {}
}
