package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.telecom.TelecomManager;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * This phone's call recorder as Sales Xray sees it: the default dialer, where
 * it saves recordings, whether those folders exist and hold recordings,
 * whether recording is switched on (when the phone shows it), and the other
 * dialers installed. The web setup checklist turns it into plain steps.
 */
final class RecorderProfile {

    private RecorderProfile() {}

    static String defaultDialer(Context context) {
        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            return telecom == null ? null : telecom.getDefaultDialerPackage();
        } catch (RuntimeException error) {
            return null;
        }
    }

    static RecorderRules.Profile profile(Context context) {
        return RecorderRules.profile(defaultDialer(context), PhoneSetup.family());
    }

    /** The maker's own "record calls automatically" switch, when it is a readable system setting. */
    static Integer setting(Context context, RecorderRules.Profile profile) {
        if (profile.settingKey == null) return null;
        try {
            return Settings.System.getInt(context.getContentResolver(), profile.settingKey);
        } catch (Settings.SettingNotFoundException | RuntimeException error) {
            return null;
        }
    }

    private static String label(PackageManager packages, String name) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(name, 0);
            CharSequence label = packages.getApplicationLabel(info);
            return label == null ? name : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return name;
        }
    }

    static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        PackageManager packages = context.getPackageManager();
        String family = PhoneSetup.family();
        String dialer = defaultDialer(context);
        RecorderRules.Profile profile = RecorderRules.profile(dialer, family);
        boolean canRead = RecordingScanner.canReadFolders(context);
        // The folders the seller picked (S8): their recordings are read through that grant.
        List<String> picked = RecordingFolder.paths(context);
        List<RecordingFolder.Document> pickedFiles = picked.isEmpty() ? java.util.Collections.emptyList() : RecordingFolder.files(context);
        // MIUI hides its recordings folder from the media index (#49): without All
        // files access (or a picked folder) it only looks empty, so say it is hidden instead.
        boolean hidden = RecorderRules.needsAllFiles(profile, Build.VERSION.SDK_INT, RecordingScanner.hasAllFilesAccess(), picked);
        File root = Environment.getExternalStorageDirectory();
        long newest = 0;
        try {
            JSONArray folders = new JSONArray();
            for (String relative : profile.folders) {
                File folder = new File(root, relative);
                JSONObject row = new JSONObject();
                row.put("path", relative);
                if (coveredBy(picked, relative)) {
                    int recordings = 0;
                    long newestHere = 0;
                    for (RecordingFolder.Document file : pickedFiles) {
                        if (!file.folder.equalsIgnoreCase(relative)) continue;
                        recordings++;
                        newestHere = Math.max(newestHere, file.modifiedMs);
                    }
                    row.put("state", recordings > 0 ? "found" : "empty");
                    row.put("picked", true);
                    row.put("recordings", recordings);
                    row.put("newestMs", newestHere > 0 ? newestHere : JSONObject.NULL);
                    newest = Math.max(newest, newestHere);
                } else if (!canRead) {
                    row.put("state", "no_access");
                } else if (hidden) {
                    row.put("state", "hidden");
                } else {
                    RecordingScanner.FolderCount count = RecordingScanner.count(folder);
                    row.put("state", !folder.isDirectory() ? "missing" : count.recordings > 0 ? "found" : "empty");
                    row.put("recordings", count.recordings);
                    row.put("newestMs", count.newestMs > 0 ? count.newestMs : JSONObject.NULL);
                    newest = Math.max(newest, count.newestMs);
                }
                folders.put(row);
            }
            // The media index also knows recorder folders on phones that hide paths.
            if (RecordingScanner.hasAudioAccess(context) || RecordingScanner.hasAllFilesAccess()) {
                List<JSONObject> indexed = RecordingScanner.list(context, 1);
                if (!indexed.isEmpty()) newest = Math.max(newest, indexed.get(0).optLong("addedMs"));
            }
            Integer setting = setting(context, profile);
            status.put("dialer", dialer == null ? JSONObject.NULL : dialer);
            status.put("dialerLabel", dialer == null ? JSONObject.NULL : label(packages, dialer));
            status.put("kind", profile.kind);
            status.put("maker", profile.maker);
            status.put("family", family);
            status.put("sdk", Build.VERSION.SDK_INT);
            status.put("folders", folders);
            status.put("canRead", canRead);
            status.put("allFiles", RecordingScanner.hasAllFilesAccess());
            status.put("needsAllFiles", hidden);
            status.put("allFilesSupported", allFilesSupported(context));
            status.put("folderPickSupported", RecordingFolder.supported());
            status.put("pickedFolders", new JSONArray(picked));
            status.put("setting", setting == null ? JSONObject.NULL : setting != 0);
            status.put("recording", RecorderRules.recording(profile, setting, newest, System.currentTimeMillis()));
            status.put("newestMs", newest > 0 ? newest : JSONObject.NULL);
            status.put("otherDialers", otherDialers(context, dialer, family));
            status.put("appRecorder", AppRecorder.state(context));
            status.put("callRecorder", CallRecorderService.status(context));
        } catch (JSONException ignored) {}
        return status;
    }

    private static boolean coveredBy(List<String> picked, String folder) {
        for (String one : picked) if (RecordingFolderRules.covers(one, folder)) return true;
        return false;
    }

    /** This build may ask for All files access (download builds; a Play build leaves it out). */
    static boolean allFilesSupported(Context context) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            String[] wanted = context
                .getPackageManager()
                .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                .requestedPermissions;
            if (wanted == null) return false;
            for (String permission : wanted) if (Manifest.permission.MANAGE_EXTERNAL_STORAGE.equals(permission)) return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            // Unknown: don't offer a screen that may not list the app.
        }
        return false;
    }

    /** Installed dialers other than the default; "folder" ones are offered as the switch. */
    private static JSONArray otherDialers(Context context, String current, String family) {
        JSONArray list = new JSONArray();
        PackageManager packages = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        try {
            List<ResolveInfo> found = packages.queryIntentActivities(new Intent(Intent.ACTION_DIAL), 0);
            for (ResolveInfo info : found) {
                String name = info.activityInfo == null ? null : info.activityInfo.packageName;
                if (name == null || name.equals(current) || !seen.add(name)) continue;
                JSONObject row = new JSONObject();
                row.put("package", name);
                row.put("label", label(packages, name));
                row.put("savesToFolder", RecorderRules.savesToFolder(name, family));
                list.put(row);
            }
        } catch (RuntimeException | JSONException ignored) {
            // No list on this phone: the steps still say where to switch.
        }
        return list;
    }

    /**
     * Opens the screen for one recorder step: "settings" (the default dialer's
     * call settings, where "Record calls" lives), "defaultDialer" (choose the
     * phone app) or "allFiles" (read hidden recorder folders).
     */
    static boolean open(Activity activity, String what) {
        switch (what) {
            case "settings":
                return start(activity, new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)) ||
                    start(activity, new Intent(Intent.ACTION_DIAL));
            case "defaultDialer":
                if (Build.VERSION.SDK_INT >= 24 && start(activity, new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return true;
                return start(activity, new Intent(Settings.ACTION_SETTINGS));
            case "allFiles":
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.getPackageName()));
                    if (start(activity, intent)) return true;
                    return start(activity, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
                return false;
            default:
                return false;
        }
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
