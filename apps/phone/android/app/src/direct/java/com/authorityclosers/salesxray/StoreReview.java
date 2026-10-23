package com.authorityclosers.salesxray;

import android.app.Activity;

/** The download-link build has no Play Store to review in: Play In-App Review is in src/play only. */
final class StoreReview {

    interface Done {
        void shown(boolean shown);
    }

    private StoreReview() {}

    static boolean available() {
        return false;
    }

    static void ask(Activity activity, Done done) {
        done.shown(false);
    }
}
