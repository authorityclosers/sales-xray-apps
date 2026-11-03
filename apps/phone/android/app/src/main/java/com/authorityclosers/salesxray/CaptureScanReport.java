package com.authorityclosers.salesxray;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * sx.capture_scan after each capture scan (CaptureScanRules): what this phone's Phone app is, where
 * it saves, what the media index holds, and how recent lead calls matched, so a call that waits for
 * its recording can be explained without the phone. Under consent B, as all the app's telemetry.
 */
final class CaptureScanReport {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private CaptureScanReport() {}

    /** Counts from one scan of the recordings folder. */
    static final class Seen {
        int files;
        int matched;
    }

    /** After a scan that ran ("ran") or stopped early ("workspace", "consent", "notices"). */
    static void send(Context c, String outcome, JSONObject book, Seen seen) {
        if (!DeviceTelemetry.on(c)) return;
        try {
            DeviceTelemetry.captureScan(c, fields(c, outcome, book, seen, System.currentTimeMillis()));
        } catch (JSONException | RuntimeException unreadable) {
            // A report is never worth a failed scan.
        }
    }

    /** Uploads off, so no scan runs: what the phone would scan, at a return to the app, at most hourly. */
    static void sendOff(Context c) {
        android.content.SharedPreferences prefs = c.getSharedPreferences("sx_capture_scan", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("offAt", 0) < 60L * 60 * 1000 && now >= prefs.getLong("offAt", 0)) return;
        prefs.edit().putLong("offAt", now).apply();
        send(c, "off", null, null);
    }

    static JSONObject fields(Context c, String outcome, JSONObject book, Seen seen, long now) throws JSONException {
        JSONObject status = RecorderProfile.status(c);
        JSONObject f = new JSONObject();
        f.put("outcome", outcome);
        String dialer = status.optString("dialer", "");
        if (!dialer.isEmpty() && !"null".equals(dialer)) f.put("dialer", CaptureScanRules.label(dialer));
        f.put("kind", status.optString("kind", "unknown"));
        f.put("rec", status.optString("recording", "unknown"));
        JSONArray folders = status.optJSONArray("folders");
        boolean covers = false;
        for (int i = 0; folders != null && i < Math.min(CaptureScanRules.FOLDERS, folders.length()); i++) {
            JSONObject row = folders.getJSONObject(i);
            String key = "f" + (i + 1);
            f.put(key, CaptureScanRules.token(row.optString("path")));
            f.put(key + "_state", CaptureScanRules.folderState(row.optString("state")));
            long age = CaptureScanRules.ageSeconds(row.optLong("newestMs"), now);
            if (age >= 0) f.put(key + "_age_s", age);
            covers |= row.optBoolean("picked");
        }
        List<String> picked = RecordingFolder.paths(c);
        f.put("picked", Math.min(20, picked.size()));
        if (!picked.isEmpty()) f.put("picked_folder", CaptureScanRules.token(picked.get(0)));
        f.put("picked_covers", covers ? "yes" : "no");
        f.put("hidden", status.optBoolean("needsAllFiles") ? "yes" : "no");
        long newest = CaptureScanRules.ageSeconds(status.optLong("newestMs"), now);
        if (newest >= 0) f.put("newest_age_s", newest);
        Map<String, Integer> media = mediaFolders(c, now - DAY_MS);
        int total = 0;
        for (Integer count : media.values()) total += count;
        f.put("media_calls_24h", Math.min(10_000, total));
        String busiest = CaptureScanRules.busiest(media);
        if (busiest != null) f.put("media_folder", CaptureScanRules.token(busiest));
        if (seen != null) {
            f.put("files_seen", Math.min(10_000, seen.files));
            f.put("files_matched", Math.min(10_000, seen.matched));
        }
        calls(c, book, now, f);
        jobs(c, f);
        return f;
    }

    /**
     * The media index's folders holding audio saved since {@code sinceMs} whose path says "call" or
     * "record", with how many each holds. Paths only, never a file's name.
     */
    static Map<String, Integer> mediaFolders(Context c, long sinceMs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!RecordingScanner.hasAudioAccess(c) && !RecordingScanner.hasAllFilesAccess()) return counts;
        Uri collection = Build.VERSION.SDK_INT >= 29
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String column = Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
        try (Cursor cursor = c.getContentResolver().query(collection, new String[] { column },
            MediaStore.MediaColumns.DATE_ADDED + " >= ?", new String[] { String.valueOf(sinceMs / 1000) }, null)) {
            int rows = 0;
            while (cursor != null && cursor.moveToNext() && rows++ < 5000) {
                String path = cursor.getString(0);
                if (path == null) continue;
                // Before Android 10 the column is the file's path: its folder is what counts.
                String folder = CaptureScanRules.folder(Build.VERSION.SDK_INT >= 29 ? path : new File(path).getParent());
                if (!CaptureScanRules.recorderPath(folder)) continue;
                Integer count = counts.get(folder);
                counts.put(folder, count == null ? 1 : count + 1);
            }
        } catch (RuntimeException unreadable) {
            // No media index here, or the permission went.
        }
        return counts;
    }

    /** How the last 6 hours' lead calls matched their recordings (no numbers: counts and an age). */
    private static void calls(Context c, JSONObject book, long now, JSONObject f) throws JSONException {
        f.put("call_log", CallLogSync.isOn(c) ? "yes" : "no");
        if (book == null) return;
        JSONArray numbers = book.optJSONArray("numbers");
        List<JSONObject> jobs = AutoCapture.jobs(c);
        int lead = 0, matched = 0, none = 0, ambiguous = 0;
        long noneAge = -1;
        for (ShareRules.Call call : ShareIn.calls(c)) {
            if (now - call.startedMs > CaptureScanRules.CALLS_MS) continue;
            String number = CaptureRules.leadNumber(call.number, numbers);
            if (number == null) continue;
            lead++;
            List<Long> stamps = new ArrayList<>();
            for (JSONObject job : jobs) if (number.equals(job.optString("number"))) stamps.add(job.optLong("stamp"));
            String result = CaptureScanRules.callResult(call.startedMs, stamps);
            if ("matched".equals(result)) matched++;
            else if ("ambiguous".equals(result)) ambiguous++;
            else {
                none++;
                long age = CaptureScanRules.ageSeconds(call.startedMs + call.durationMs, now);
                if (noneAge < 0 || age < noneAge) noneAge = age;
            }
        }
        f.put("calls", lead).put("calls_matched", matched).put("calls_none", none).put("calls_ambiguous", ambiguous);
        if (noneAge >= 0) f.put("none_age_s", noneAge);
    }

    /** Calls on their way and held on this phone, and the latest hold's reason. */
    private static void jobs(Context c, JSONObject f) throws JSONException {
        int waiting = 0, held = 0;
        long heldAt = 0;
        String hold = null;
        for (JSONObject job : AutoCapture.jobs(c)) {
            String state = job.optString("state");
            if ("waiting".equals(state) || "uploading".equals(state)) waiting++;
            if ("held".equals(state)) {
                held++;
                if (job.optLong("heldAt") >= heldAt) {
                    heldAt = job.optLong("heldAt");
                    hold = CaptureScanRules.holdReason(AutoCapture.reason(job));
                }
            }
        }
        f.put("jobs_waiting", waiting).put("jobs_held", held);
        if (hold != null) f.put("hold", hold);
    }
}
