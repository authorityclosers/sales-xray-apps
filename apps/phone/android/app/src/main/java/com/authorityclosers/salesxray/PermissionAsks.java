package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Which permissions Android won't ask about any more ("Set up this phone", 24 Oct 2026): after two
 * denials, or "Don't ask again", the request returns at once and the button seems dead, so the page
 * offers "Open settings" instead. The app keeps what Android answered to its requests (PermissionRules),
 * so a permission that was only reset is asked for again rather than sent to Settings.
 */
final class PermissionAsks {

    /** Android's answers; the older "sx_permission_asks" (asked or not) misread a reset as blocked. */
    private static final String PREFS = "sx_permission_answers";

    private PermissionAsks() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Just before Android's request: when it was made, to tell a question from an instant no. */
    static void asked(Context context, String... permissions) {
        SharedPreferences.Editor edit = prefs(context).edit();
        for (String permission : permissions) edit.putLong("at:" + permission, SystemClock.elapsedRealtime());
        edit.apply();
    }

    /** Android's answer to a request (onRequestPermissionsResult, or a permission launcher's result). */
    static void answered(Activity activity, String[] permissions, int[] results) {
        for (int i = 0; i < permissions.length && i < results.length; i++) {
            answered(activity, permissions[i], results[i] == PackageManager.PERMISSION_GRANTED);
        }
    }

    static void answered(Activity activity, String permission, boolean granted) {
        SharedPreferences prefs = prefs(activity);
        long at = prefs.getLong("at:" + permission, -1);
        long elapsed = at < 0 ? -1 : SystemClock.elapsedRealtime() - at;
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
        remember(prefs, permission, PermissionRules.after(memory(prefs, permission), granted, rationale, elapsed));
    }

    static boolean blocked(Activity activity, String permission) {
        if (!Permissions.declared(activity, permission)) return false;
        SharedPreferences prefs = prefs(activity);
        PermissionRules.Memory memory = memory(prefs, permission);
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            // Allowed since (in Settings, say): a later reset is asked for again.
            if (memory.deniedOnce || memory.deniedForGood) remember(prefs, permission, PermissionRules.NOTHING);
            return false;
        }
        return PermissionRules.blocked(false, memory.deniedForGood, ActivityCompat.shouldShowRequestPermissionRationale(activity, permission));
    }

    static boolean anyBlocked(Activity activity, String[] permissions) {
        for (String permission : permissions) if (blocked(activity, permission)) return true;
        return false;
    }

    private static PermissionRules.Memory memory(SharedPreferences prefs, String permission) {
        return new PermissionRules.Memory(prefs.getBoolean("once:" + permission, false), prefs.getBoolean("never:" + permission, false));
    }

    private static void remember(SharedPreferences prefs, String permission, PermissionRules.Memory memory) {
        prefs
            .edit()
            .putBoolean("once:" + permission, memory.deniedOnce)
            .putBoolean("never:" + permission, memory.deniedForGood)
            .apply();
    }
}
