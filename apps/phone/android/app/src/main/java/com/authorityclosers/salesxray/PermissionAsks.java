package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Which permissions Android won't ask about any more ("Set up this phone", 24 Oct 2026): after two
 * denials, or "Don't ask again", the request returns at once and the button seems dead, so the page
 * offers "Open settings" instead. Android says so only after the app has asked once, so the app keeps
 * a note of what it asked for.
 */
final class PermissionAsks {

    private static final String PREFS = "sx_permission_asks";

    private PermissionAsks() {}

    static void asked(Context context, String... permissions) {
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (String permission : permissions) edit.putBoolean(permission, true);
        edit.apply();
    }

    static boolean blocked(Activity activity, String permission) {
        if (!Permissions.declared(activity, permission)) return false;
        boolean granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
        boolean asked = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(permission, false);
        return PermissionRules.blocked(granted, asked, ActivityCompat.shouldShowRequestPermissionRationale(activity, permission));
    }

    static boolean anyBlocked(Activity activity, String[] permissions) {
        for (String permission : permissions) if (blocked(activity, permission)) return true;
        return false;
    }
}
