package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Find my old calls" (20 Nov 2026), the phone's side: the page sends the
 * seller's connected lead calls of the last two weeks that have no recording on
 * Sales X-Ray, and the phone says which of its recordings belongs to which call
 * (OldCallsRules), plus the call recordings no call took. Read-only: nothing is
 * uploaded, no queue or used list changes and no notice is shown; the page
 * reads a recording through the bridge's "read" only once the seller picks it.
 * Nothing about a call or a number is logged here.
 */
final class OldCalls {

    private OldCalls() {}

    /** The bridge's findOldRecordings: {calls, sinceMs} in, {access, scanned, items, unmatched} out. */
    static JSONObject find(Context context, JSONObject args) {
        long now = System.currentTimeMillis();
        long since = OldCallsRules.since(args.optLong("sinceMs"), now);
        TimeZone zone = TimeZone.getDefault();
        boolean audio = RecordingScanner.hasAudioAccess(context) || RecordingScanner.hasAllFilesAccess();
        boolean picked = !RecordingFolder.trees(context).isEmpty();
        String access = OldCallsRules.ACCESS_NONE;
        if (audio || picked) {
            // The same "hidden" as pendingRecordings' needsAllFiles (MIUI hides its folder, #49).
            boolean hidden = RecorderProfile.status(context).optBoolean("needsAllFiles");
            access = OldCallsRules.access(audio, picked, hidden);
        }
        JSONObject result = new JSONObject();
        JSONArray items = new JSONArray();
        JSONArray unmatched = new JSONArray();
        int scanned = 0;
        if (!OldCallsRules.ACCESS_NONE.equals(access)) {
            List<JSONObject> inWindow = OldCallsRules.window(RecordingScanner.list(context, OldCallsRules.LIST_LIMIT, since), since);
            scanned = inWindow.size();
            // What the phone already knows of each recording: its silence check, and whether a call took it.
            Map<String, JSONObject> checks = RecordingQueue.checks(context);
            Set<String> sources = new HashSet<>(RecordingQueue.taken(context));
            Set<String> prints = new HashSet<>();
            for (JSONObject job : AutoCapture.jobs(context)) {
                // Automatic uploads keep the picked folder's document; the list names it "tree:".
                String source = job.optString("source");
                if (!source.isEmpty()) {
                    sources.add(source);
                    sources.add("tree:" + source);
                }
                String print = CallRecordingPick.fingerprint(job);
                if (!print.isEmpty()) prints.add(print);
            }
            SharedPreferences calls = context.getSharedPreferences(CallTracker.PREFS, Context.MODE_PRIVATE);
            String history = calls.getString(CallTracker.USED, "");
            List<JSONObject> free = new ArrayList<>();
            for (JSONObject item : inWindow) {
                if (OldCallsRules.taken(item, sources, prints, history)) continue;
                JSONObject check = checks.get(item.optString("source"));
                if (check != null) put(item, "check", check);
                free.add(item);
            }
            OldCallsRules.Result found = OldCallsRules.match(OldCallsRules.calls(args.optJSONArray("calls")), free, zone);
            for (JSONObject item : found.items) items.put(item);
            for (JSONObject recording : found.left) {
                // Only these few are opened, for a length the page can show beside the start time.
                if (recording.optLong("durationMs") <= 0) {
                    long length = length(context, recording.optString("source"));
                    if (length > 0) put(recording, "durationMs", length);
                }
                unmatched.put(OldCallsRules.unmatched(recording, zone));
            }
        }
        put(result, "access", access);
        put(result, "scanned", scanned);
        put(result, "items", items);
        put(result, "unmatched", unmatched);
        return result;
    }

    /** A recording's length in ms, read through the same checks as the bridge's "read"; 0 when unknown. */
    private static long length(Context context, String source) {
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try (ParcelFileDescriptor descriptor = RecordingScanner.descriptor(context, source)) {
            media.setDataSource(descriptor.getFileDescriptor());
            String ms = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? 0 : Math.max(0, Long.parseLong(ms));
        } catch (Exception unreadable) {
            return 0;
        } finally {
            try {
                media.release();
            } catch (Exception ignored) {
                // Already released.
            }
        }
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException ignored) {
            // Keys and values here are always valid.
        }
    }
}
