package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The phone setup checklist (UX study item 7): what this phone needs so every
 * work call is logged and its recording found. Android lets an app check most
 * lines itself (access given, recordings folder, battery). Two it can't see,
 * so the seller ticks them: the dialer's own call recorder, and the autostart
 * switch some brands add. A recording found after a call from the app ticks
 * "test call recorded" by itself.
 */
final class PhoneSetup {

    private static final String PREFS = "sx_setup";
    private static final String AUTOSTART = "autostart";
    private static final String RECORDER = "recorder";
    private static final String TEST_CALL = "test_call";
    private static final long RECENT_MS = 30L * 24 * 60 * 60 * 1000;

    /** Autostart screens, newest first, for brands that stop apps in the background. */
    private static final String[][] AUTOSTART_SCREENS = {
        { "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity" },
        { "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity" },
        { "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity" },
        { "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity" },
        { "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity" },
        { "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity" },
        { "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity" },
        { "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity" },
    };

    private PhoneSetup() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The phone's family for the recorder steps: xiaomi, samsung, vivo, oppo, realme, oneplus, huawei or other. */
    static String family() {
        return CallLogRules.family(Build.MANUFACTURER, Build.BRAND);
    }

    static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        JSONObject checks = new JSONObject();
        SharedPreferences prefs = prefs(context);
        String family = family();
        boolean autostartNeeded = CallLogRules.hasAutostart(family);
        boolean recordings = RecordingScanner.hasAudioAccess(context) || RecordingScanner.hasAllFilesAccess();
        // Which dialer records, where it saves, and whether it's on (19 Oct: check, don't ask).
        JSONObject recorder = RecorderProfile.status(context);
        String recording = recorder.optString("recording", "unknown");
        long newest = recorder.optLong("newestMs", 0);
        try {
            checks.put("calls", CallTracker.hasCallAccess(context));
            checks.put("recordings", recordings);
            checks.put("notifications", notificationsAllowed(context));
            // The dialer records calls: its own switch says so, a recent recording
            // shows it, or (where the phone can't tell) the seller said so. Never
            // ticked for Google's Phone app, whose recordings no app can read.
            boolean saidSo = prefs.getBoolean(RECORDER, false) && !"private".equals(recording) && !"off".equals(recording);
            checks.put("recorder", "on".equals(recording) || saidSo || (!"private".equals(recording) && System.currentTimeMillis() - newest < RECENT_MS && newest > 0));
            checks.put("folder", newest > 0);
            checks.put("battery", batteryUnrestricted(context));
            checks.put("autostart", !autostartNeeded || prefs.getBoolean(AUTOSTART, false));
            checks.put("testCall", prefs.getBoolean(TEST_CALL, false));
            checks.put("callLog", CallLogSync.isOn(context));
            status.put("checks", checks);
            status.put("family", family);
            status.put("model", Build.MODEL == null ? "" : Build.MODEL);
            status.put("autostartNeeded", autostartNeeded);
            status.put("newestRecordingMs", newest > 0 ? newest : JSONObject.NULL);
            status.put("device", CallLogSync.deviceId(context));
            status.put("recorderProfile", recorder);
        } catch (JSONException ignored) {}
        return status;
    }

    static boolean notificationsAllowed(Context context) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    static boolean batteryUnrestricted(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** The seller ticks what the phone can't tell: "autostart" or "recorder". */
    static void mark(Context context, String what, boolean done) {
        if (AUTOSTART.equals(what) || RECORDER.equals(what)) prefs(context).edit().putBoolean(what, done).apply();
    }

    /** A call placed from the app found its recording: recording works end to end. */
    static void testCallRecorded(Context context) {
        prefs(context).edit().putBoolean(TEST_CALL, true).apply();
    }

    /** Opens the screen where the seller fixes a line; false when this phone has none. */
    static boolean open(Activity activity, String what) {
        String app = activity.getPackageName();
        switch (what) {
            case "battery":
                return start(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + app))) ||
                    start(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
                    appDetails(activity);
            case "autostart":
                for (String[] screen : AUTOSTART_SCREENS) {
                    Intent intent = new Intent().setComponent(new ComponentName(screen[0], screen[1]));
                    if (start(activity, intent)) return true;
                }
                return appDetails(activity);
            case "recorder":
                // The default dialer's call settings hold "Record calls automatically".
                return RecorderProfile.open(activity, "settings");
            case "notifications":
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app);
                    if (start(activity, intent)) return true;
                }
                return appDetails(activity);
            default:
                return appDetails(activity);
        }
    }

    private static boolean appDetails(Activity activity) {
        return start(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            return false;
        }
    }
}
