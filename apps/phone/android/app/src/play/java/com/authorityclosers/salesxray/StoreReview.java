package com.authorityclosers.salesxray;

import android.app.Activity;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

/**
 * Google Play In-App Review for the Play build (owner, 23 Oct 2026; the page asks after a "Great"
 * rating, at most once every 60 days). Google's quota decides whether the card appears, and Play
 * never says: {@code shown} is true when Play ran its review flow, false when it couldn't.
 */
final class StoreReview {

    interface Done {
        void shown(boolean shown);
    }

    private StoreReview() {}

    static boolean available() {
        return true;
    }

    static void ask(Activity activity, Done done) {
        try {
            ReviewManager manager = ReviewManagerFactory.create(activity);
            manager.requestReviewFlow().addOnCompleteListener(request -> {
                if (!request.isSuccessful() || activity.isFinishing()) {
                    done.shown(false);
                    return;
                }
                manager.launchReviewFlow(activity, request.getResult()).addOnCompleteListener(flow -> done.shown(flow.isSuccessful()));
            });
        } catch (RuntimeException error) {
            // No Play Store on this phone.
            done.shown(false);
        }
    }
}
