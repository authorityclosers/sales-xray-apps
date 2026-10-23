package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * Google Play in-app updates for the Play build (owner 23 Oct 2026, so testers
 * stay current): Play offers a newer version, it downloads while the seller
 * works, and one tap restarts into it. A build that didn't come from Play
 * hears nothing back and carries on.
 */
final class StoreUpdates {

    private static final String PREFS = "sx_store_updates";
    private static final String DECLINED = "declined_version";
    private static final String DECLINED_AT = "declined_at";

    private final AppCompatActivity activity;
    private final AppUpdateManager manager;
    private final ActivityResultLauncher<IntentSenderRequest> flow;
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
        this.flow = activity.registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            // "No thanks" (or backing out): don't ask about this version again for three days.
            if (result.getResultCode() != Activity.RESULT_OK && offeredVersion >= 0) {
                prefs().edit().putInt(DECLINED, offeredVersion).putLong(DECLINED_AT, System.currentTimeMillis()).apply();
            }
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
                    boolean ask = StoreUpdateRules.ask(
                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                        info.availableVersionCode(),
                        prefs.getInt(DECLINED, -1),
                        prefs.getLong(DECLINED_AT, 0),
                        System.currentTimeMillis()
                    );
                    if (!ask || asked || activity.isFinishing()) return;
                    asked = true;
                    offeredVersion = info.availableVersionCode();
                    try {
                        manager.startUpdateFlowForResult(info, flow, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
                    } catch (RuntimeException ignored) {
                        // Play's sheet couldn't open: the next start asks again.
                    }
                })
                .addOnFailureListener(error -> {
                    // Not installed from Play (a test or sideloaded build): no updates to offer.
                });
        } catch (RuntimeException ignored) {
            // No Play Store on this phone.
        }
    }

    /** Back in the app: a version that finished downloading meanwhile is offered once. */
    void resume() {
        try {
            manager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (info.installStatus() == InstallStatus.DOWNLOADED) offerRestart();
            });
        } catch (RuntimeException ignored) {}
    }

    void stop() {
        try {
            manager.unregisterListener(downloads);
        } catch (RuntimeException ignored) {}
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void offerRestart() {
        if (restartOffered || activity.isFinishing() || activity.isDestroyed()) return;
        restartOffered = true;
        dialog = new AlertDialog.Builder(activity)
            .setTitle("Update ready")
            .setMessage("A new version of Sales X-Ray has downloaded. Restart to use it; it takes a few seconds.")
            .setPositiveButton("Restart", (shown, which) -> manager.completeUpdate())
            .setNegativeButton("Later", null)
            .show();
    }
}
