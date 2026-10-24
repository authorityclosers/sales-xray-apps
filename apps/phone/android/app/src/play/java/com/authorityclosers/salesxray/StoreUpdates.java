package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * Google Play in-app updates for the Play build (owner 23 Oct 2026, so testers stay current; owner
 * 25 Oct 2026: the prompt in the middle of the screen, and a forced update for a fix that must be
 * taken). A release's priority decides (StoreUpdateRules): 0-3 is "Update available" with Later, and
 * it downloads while the seller works; 4-5 is Play's full-screen update with no Later. A build that
 * didn't come from Play hears nothing back and carries on.
 */
final class StoreUpdates {

    private static final String PREFS = "sx_store_updates";
    private static final String DECLINED = "declined_version";
    private static final String DECLINED_AT = "declined_at";

    private final AppCompatActivity activity;
    private final AppUpdateManager manager;
    private final ActivityResultLauncher<IntentSenderRequest> flexibleFlow;
    private final ActivityResultLauncher<IntentSenderRequest> immediateFlow;
    private final InstallStateUpdatedListener downloads = state -> {
        if (state.installStatus() == InstallStatus.DOWNLOADED) offerRestart();
    };
    private int offeredVersion = -1;
    private boolean asked;
    private boolean restartOffered;
    private AlertDialog dialog;

    StoreUpdates(AppCompatActivity activity) {
        this.activity = activity;
        this.manager = AppUpdateManagerFactory.create(activity);
        this.flexibleFlow = activity.registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            // Backing out of Play's sheet counts as Later.
            if (result.getResultCode() != Activity.RESULT_OK) hold();
        });
        this.immediateFlow = activity.registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            // A required update the seller backed out of: ask again, with no way past it.
            if (result.getResultCode() != Activity.RESULT_OK) required();
        });
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** After the first screen is up: listen for downloads and ask Play once. */
    void start() {
        try {
            manager.registerListener(downloads);
            manager
                .getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    if (info.installStatus() == InstallStatus.DOWNLOADED) {
                        offerRestart();
                        return;
                    }
                    SharedPreferences prefs = prefs();
                    StoreUpdateRules.Offer offer = StoreUpdateRules.offer(
                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
                        info.updatePriority(),
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                        info.availableVersionCode(),
                        prefs.getInt(DECLINED, -1),
                        prefs.getLong(DECLINED_AT, 0),
                        System.currentTimeMillis()
                    );
                    if (offer == StoreUpdateRules.Offer.NONE || asked || gone()) return;
                    asked = true;
                    offeredVersion = info.availableVersionCode();
                    if (offer == StoreUpdateRules.Offer.IMMEDIATE) startImmediate(info);
                    else prompt(info);
                })
                .addOnFailureListener(error -> {
                    // Not installed from Play (a test or sideloaded build): no updates to offer.
                });
        } catch (RuntimeException ignored) {
            // No Play Store on this phone.
        }
    }

    /** Back in the app: a finished download is offered once, and a required update left half-way goes on. */
    void resume() {
        try {
            manager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (info.installStatus() == InstallStatus.DOWNLOADED) offerRestart();
                else if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) startImmediate(info);
            });
        } catch (RuntimeException ignored) {}
    }

    void stop() {
        try {
            manager.unregisterListener(downloads);
        } catch (RuntimeException ignored) {}
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private boolean gone() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    /** "Update available" in the middle of the screen; Update hands over to Play's download. */
    private void prompt(AppUpdateInfo info) {
        dialog = new AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("A new version of Sales X-Ray is on Google Play. Update now; it downloads while you keep working.")
            .setPositiveButton("Update", (shown, which) -> startFlexible(info))
            .setNegativeButton("Later", (shown, which) -> hold())
            .setOnCancelListener(shown -> hold())
            .show();
    }

    private void startFlexible(AppUpdateInfo info) {
        try {
            manager.startUpdateFlowForResult(info, flexibleFlow, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
        } catch (RuntimeException ignored) {
            // Play's sheet couldn't open: the next start asks again.
        }
    }

    private void startImmediate(AppUpdateInfo info) {
        if (gone()) return;
        try {
            manager.startUpdateFlowForResult(info, immediateFlow, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build());
        } catch (RuntimeException ignored) {
            required();
        }
    }

    /** A required update: one button, and Play's update starts again from fresh details. */
    private void required() {
        if (gone() || (dialog != null && dialog.isShowing())) return;
        dialog = new AlertDialog.Builder(activity)
            .setTitle("Update needed")
            .setMessage("This version of Sales X-Ray has a fix you need. Update from Google Play to keep using the app.")
            .setCancelable(false)
            .setPositiveButton("Update", (shown, which) -> {
                try {
                    manager.getAppUpdateInfo().addOnSuccessListener(this::startImmediate);
                } catch (RuntimeException ignored) {}
            })
            .show();
    }

    /** Later: this version isn't offered again for three days (StoreUpdateRules). */
    private void hold() {
        if (offeredVersion >= 0) {
            prefs().edit().putInt(DECLINED, offeredVersion).putLong(DECLINED_AT, System.currentTimeMillis()).apply();
        }
    }

    private void offerRestart() {
        if (restartOffered || gone()) return;
        restartOffered = true;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = new AlertDialog.Builder(activity)
            .setTitle("Update ready")
            .setMessage("A new version of Sales X-Ray has downloaded. Restart to use it; it takes a few seconds.")
            .setPositiveButton("Restart", (shown, which) -> manager.completeUpdate())
            .setNegativeButton("Later", null)
            .show();
    }
}
