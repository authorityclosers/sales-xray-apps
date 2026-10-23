package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lead calls the phone logged by itself, waiting for their recording (owner,
 * 22 Oct: a dialler call with a lead is logged with its recording). Call
 * logging adds each connected call here with the call event Sales Xray made
 * for it. The phone then looks for the recording the dialer saved
 * (RecordingMatch: the lead's number and the call's time) and checks it is
 * not silent (AudioLevels). The page shows what was found and uploads a
 * recording only once the seller agrees to the terms, as for any upload.
 * Kept on the phone for three days; recordings never leave it from here.
 */
final class RecordingQueue {

    private static final String PREFS = "sx_recording_queue";
    private static final String QUEUE = "queue";
    private static final String USED = "used";
    private static final String NOTIFIED = "notified";
    private static final int USED_MAX = 200;
    /** Each look checks at most this many new recordings (a few seconds each). */
    private static final int CHECKS_PER_LOOK = 3;
    private static final int NOTICE_ID = 44;
    private static final Object LOCK = new Object();

    private RecordingQueue() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray load(Context context) {
        try {
            return new JSONArray(prefs(context).getString(QUEUE, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    /** Logged lead calls that connected, each with its call event. */
    static void add(Context context, List<JSONObject> entries) {
        if (entries.isEmpty()) return;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            JSONArray queue = load(context);
            for (JSONObject entry : entries) queue = CallLogRules.enqueue(queue, entry, now);
            prefs(context).edit().putString(QUEUE, queue.toString()).apply();
        }
    }

    /**
     * Finds recordings for the calls still waiting and checks new ones; returns
     * how many calls found their recording now.
     */
    static int look(Context context) {
        long now = System.currentTimeMillis();
        JSONArray queue;
        int found = 0;
        synchronized (LOCK) {
            queue = CallLogRules.keepQueue(load(context), now);
            boolean waiting = false;
            for (int i = 0; i < queue.length(); i++) {
                if (CallLogRules.stillLooking(queue.optJSONObject(i), now)) waiting = true;
            }
            if (waiting) {
                List<JSONObject> items = RecordingScanner.list(context, 200);
                Set<String> used = used(context, queue);
                // Oldest call first, so each recording goes to the call it belongs to.
                for (int i = queue.length() - 1; i >= 0; i--) {
                    JSONObject entry = queue.optJSONObject(i);
                    if (!CallLogRules.stillLooking(entry, now)) continue;
                    JSONObject match = RecordingMatch.best(
                        items,
                        entry.optString("key"),
                        entry.optLong("startedMs"),
                        entry.optLong("seconds"),
                        used,
                        TimeZone.getDefault()
                    );
                    if (match == null) continue;
                    put(entry, "recording", match);
                    used.add(match.optString("source"));
                    found++;
                }
            }
            prefs(context).edit().putString(QUEUE, queue.toString()).apply();
        }
        // Checking decodes audio, so it runs outside the lock.
        int checks = 0;
        List<String[]> checked = new ArrayList<>();
        for (int i = 0; i < queue.length() && checks < CHECKS_PER_LOOK; i++) {
            JSONObject recording = queue.optJSONObject(i).optJSONObject("recording");
            if (recording == null || recording.has("check")) continue;
            checks++;
            String source = recording.optString("source");
            checked.add(new String[] { source, AudioLevels.check(context, source).toString() });
        }
        if (!checked.isEmpty()) {
            synchronized (LOCK) {
                JSONArray current = load(context);
                for (int i = 0; i < current.length(); i++) {
                    JSONObject recording = current.optJSONObject(i).optJSONObject("recording");
                    if (recording == null) continue;
                    for (String[] pair : checked) {
                        if (!pair[0].equals(recording.optString("source"))) continue;
                        try {
                            recording.put("check", new JSONObject(pair[1]));
                        } catch (JSONException ignored) {}
                    }
                }
                prefs(context).edit().putString(QUEUE, current.toString()).apply();
            }
        }
        return found;
    }

    /** What the page shows: the waiting calls, newest first, and whether the recordings folder is hidden. */
    static JSONObject pending(Context context) {
        look(context);
        JSONObject result = new JSONObject();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            JSONArray queue = CallLogRules.keepQueue(load(context), now);
            JSONArray items = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject entry = queue.optJSONObject(i);
                JSONObject item = new JSONObject();
                put(item, "eventId", entry.optString("eventId"));
                put(item, "leadId", entry.optString("leadId"));
                put(item, "startedMs", entry.optLong("startedMs"));
                put(item, "seconds", entry.optLong("seconds"));
                put(item, "direction", entry.optString("direction"));
                JSONObject recording = entry.optJSONObject("recording");
                put(item, "recording", recording == null ? JSONObject.NULL : recording);
                put(item, "looking", CallLogRules.stillLooking(entry, now));
                items.put(item);
            }
            put(result, "items", items);
        }
        JSONObject recorder = RecorderProfile.status(context);
        put(result, "needsAllFiles", recorder.optBoolean("needsAllFiles"));
        put(result, "allFilesSupported", recorder.optBoolean("allFilesSupported"));
        return result;
    }

    /** The page analysed the call's recording, or the seller dismissed it: it leaves the list. */
    static void handled(Context context, String eventId) {
        synchronized (LOCK) {
            JSONArray queue = load(context);
            SharedPreferences prefs = prefs(context);
            String used = prefs.getString(USED, "");
            for (int i = 0; i < queue.length(); i++) {
                JSONObject entry = queue.optJSONObject(i);
                JSONObject recording = entry.optJSONObject("recording");
                if (entry.optString("eventId").equals(eventId) && recording != null) {
                    used = CallLogRules.remember(used, recording.optString("source"), USED_MAX);
                }
            }
            prefs.edit().putString(QUEUE, CallLogRules.dropQueued(queue, eventId).toString()).putString(USED, used).apply();
        }
    }

    /** Recordings already taken: used before, or found for another waiting call. */
    private static Set<String> used(Context context, JSONArray queue) {
        Set<String> used = new HashSet<>();
        for (String line : prefs(context).getString(USED, "").split("\n")) if (!line.isEmpty()) used.add(line);
        for (int i = 0; i < queue.length(); i++) {
            JSONObject recording = queue.optJSONObject(i).optJSONObject("recording");
            if (recording != null) used.add(recording.optString("source"));
        }
        return used;
    }

    /**
     * New recordings found: once per call, a notification brings the seller to
     * Leads, where the recorded calls wait. With the app in front ({@code post}
     * false) the page shows them already, so they are only marked as told.
     */
    static void announce(Context context, boolean post) {
        int fresh = 0;
        synchronized (LOCK) {
            JSONArray queue = load(context);
            String told = prefs(context).getString(NOTIFIED, "");
            for (int i = 0; i < queue.length(); i++) {
                JSONObject entry = queue.optJSONObject(i);
                if (entry.optJSONObject("recording") == null || CallLogRules.remembered(told, entry.optString("eventId"))) continue;
                told = CallLogRules.remember(told, entry.optString("eventId"), USED_MAX);
                fresh++;
            }
            if (fresh == 0) return;
            prefs(context).edit().putString(NOTIFIED, told).apply();
        }
        if (!post) return;
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(RecordingWatchWorker.CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(RecordingWatchWorker.CHANNEL, "New call recordings", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Tells you when your phone saved a call recording you can analyse.");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LeadAlerts.EXTRA_OPEN, "/leads");
        PendingIntent pending = PendingIntent.getActivity(context, 4, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = fresh == 1 ? "A lead call was recorded" : fresh + " lead calls were recorded";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RecordingWatchWorker.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(title)
            .setContentText("Tap to analyse in Sales X-Ray.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(context).notify(NOTICE_ID, builder.build());
        } catch (SecurityException ignored) {
            // Notifications were turned off after the check; Leads still shows them.
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
