package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What this build declares. The Play build leaves out call history, direct
 * calling and all-files access (src/play/AndroidManifest.xml), so asking for
 * them would only fail; the features that need them are not offered there.
 */
final class Permissions {

    private static volatile Set<String> declared;

    private Permissions() {}

    static boolean declared(Context context, String permission) {
        Set<String> known = declared;
        if (known == null) {
            try {
                String[] wanted = context
                    .getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
                known = wanted == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(wanted));
            } catch (PackageManager.NameNotFoundException | RuntimeException error) {
                known = Collections.emptySet();
            }
            declared = known;
        }
        return known.contains(permission);
    }

    /** "direct" or "play". */
    static String store(Context context) {
        return context.getString(R.string.sx_store);
    }
}
