package com.authorityclosers.salesxray;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fills in the outcome of the seller's Call-button dials from the recordings their dialer saved (owner via
 * the orchestrator, 2 Nov 2026: 35 of a tester's 36 attempts in a day had none). With the recordings folder
 * chosen, in an organisation workspace, it reads each lead recording's number, start and length (never its
 * audio) up to 30 days back and sends just those; Sales X-Ray puts the seconds beside the matching dial
 * and adds nothing else. Uploads may stay off: nothing is uploaded or analysed here.
 */
final class OutcomeBackfill {

    private static final String PREFS = "sx_outcomes";
    private static final String LOOKED = "looked_at";
    private static final String THROUGH = "through";
    private static final String IDENTITY = "identity";
    private static final String CHANNEL = "call_outcomes";
    private static final int NOTICE_ID = 7480;
    /** After the app opens, so the read never competes with the first screen. */
    private static final long AFTER_OPEN_SECONDS = 15;
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor();

    private OutcomeBackfill() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** As the app opens: at most once an hour, off the main thread, once the first screen is up. */
    static void soon(Context context) {
        Context app = context.getApplicationContext();
        IO.schedule(() -> {
            if (OutcomeRules.due(prefs(app).getLong(LOOKED, 0), System.currentTimeMillis())) run(app);
        }, AFTER_OPEN_SECONDS, TimeUnit.SECONDS);
    }

    /** Right away (the seller turned uploads on). */
    static void now(Context context) {
        Context app = context.getApplicationContext();
        IO.execute(() -> run(app));
    }

    private static void run(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        prefs.edit().putLong(LOOKED, now).apply();
        if (RecordingFolder.trees(context).isEmpty()) return;
        try {
            CaptureApi api = new CaptureApi(context);
            // Also sets the identity the server checks, so no call goes to another workspace.
            JSONObject book = api.book();
            String identity = CaptureRules.identity(book);
            if (identity.isEmpty()) return;
            // Another seller or workspace on this phone starts its own 30 days.
            long through = identity.equals(prefs.getString(IDENTITY, "")) ? prefs.getLong(THROUGH, 0) : 0;
            long from = OutcomeRules.from(through, now);
            TimeZone zone = TimeZone.getDefault();
            JSONArray calls = new JSONArray();
            int filled = 0;
            for (RecordingFolder.Document doc : RecordingFolder.files(context, from)) {
                if (!AutoCapture.audio(doc)) continue;
                String number = CaptureRules.number(doc.name, doc.modifiedMs, now, from, zone, book, OutcomeRules.FILL_MS);
                if (number == null) continue;
                long stamp = RecordingMatch.read(doc.name, zone).stampMs;
                long seconds = seconds(context, doc);
                if (seconds <= 0 || seconds > 14400 || doc.modifiedMs < stamp + seconds * 1000 - 5000) continue;
                calls.put(new JSONObject().put("key", CaptureRules.id(identity, number, stamp)).put("number", number)
                    .put("started_at", CallLogRules.iso(stamp)).put("duration_seconds", seconds));
                if (calls.length() == OutcomeRules.BATCH) {
                    filled += send(context, api, calls);
                    calls = new JSONArray();
                }
            }
            if (calls.length() > 0) filled += send(context, api, calls);
            prefs.edit().putString(IDENTITY, identity).putLong(THROUGH, now).apply();
            if (filled > 0) {
                tell(context, filled);
                PhoneBridge.signalActive("calls");
            }
        } catch (Exception unavailable) {
            // Signed out, offline or refused: the next read starts from the same place.
        }
    }

    private static long seconds(Context context, RecordingFolder.Document doc) {
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            media.setDataSource(context, doc.uri);
            return Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
        } catch (Exception unsupported) {
            return 0;
        } finally {
            try {
                media.release();
            } catch (Exception ignored) {
                // Released already.
            }
        }
    }

    /** One batch: how many dials got their outcome now. */
    private static int send(Context context, CaptureApi api, JSONArray calls) throws Exception {
        JSONObject body = new JSONObject().put("device_id", CallLogSync.deviceId(context)).put("source", "recording").put("calls", calls);
        return api.json("POST", "/v1/lead-desk/call-outcomes", body, null, false).optInt("filled");
    }

    /** The seller's one line: a quiet notice that opens Calls. */
    private static void tell(Context context, int filled) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Call outcomes from your phone", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(context, MainActivity.class).putExtra(LeadAlerts.EXTRA_OPEN, "/analysis/calls").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        NotificationCompat.Builder notice = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sx)
            .setContentTitle(OutcomeRules.filledLine(filled))
            .setContentText("From your call recordings. Nothing was uploaded.")
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, NOTICE_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            manager.notify(NOTICE_ID, notice.build());
        } catch (SecurityException ignored) {
            // Notices are off for the app.
        }
    }
}
