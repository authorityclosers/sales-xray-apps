package com.authorityclosers.salesxray;

import android.content.*;
import android.app.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** The seller opts in per workspace/person. Only the SAF grant is scanned. */
final class AutoCapture {
    static final String PREFS = "sx_auto_capture";
    static final String DISCLOSURE = "Sales X-Ray reads call history only while you are signed in to an organisation workspace, to log the number, time, duration and direction of calls with that workspace's leads. Other calls are not uploaded. You can turn work-call logging off at any time.";
    static boolean noticesAllowed(Context c) {
        if (!androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = c.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel("auto_capture");
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static JSONObject status(Context c) throws JSONException {
        return new JSONObject().put("supported", true).put("on", prefs(c).getBoolean("on", false))
            .put("wifiOnly", prefs(c).getBoolean("wifiOnly", true));
    }
    static void configure(Context c, JSONObject book, boolean on, boolean wifi) throws Exception {
        String identity = CaptureRules.identity(book);
        if (on && !noticesAllowed(c)) throw new Exception("Allow upload notifications first, so Undo is available.");
        if (on && (identity.isEmpty() || RecordingFolder.trees(c).isEmpty())) throw new Exception("Pick your recordings folder inside an organisation workspace first.");
        SharedPreferences p = prefs(c);
        if (on && (!p.getBoolean("on", false) || !identity.equals(p.getString("identity", "")))) {
            p.edit().putLong("since", System.currentTimeMillis()).apply();
        }
        boolean changedNetwork = wifi != p.getBoolean("wifiOnly", true);
        p.edit().putBoolean("on", on).putString("identity", identity).putBoolean("wifiOnly", wifi).commit();
        WorkManager work = WorkManager.getInstance(c);
        if (!on) { work.cancelAllWorkByTag("sx-auto-capture"); return; }
        if (changedNetwork) work.cancelAllWorkByTag("sx-auto-capture").getResult().get();
        // Jobs check the current network setting again before bytes leave the device.
        work.enqueueUniquePeriodicWork("sx-auto-capture-watch", ExistingPeriodicWorkPolicy.UPDATE,
            new PeriodicWorkRequest.Builder(CaptureWatchWorker.class, 15, TimeUnit.MINUTES)
                .addTag("sx-auto-capture").setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(wifi ? NetworkType.UNMETERED : NetworkType.CONNECTED).build()).build());
        soon(c);
    }
    static boolean enabled(Context c, JSONObject book) {
        return prefs(c).getBoolean("on", false) && !CaptureRules.identity(book).isEmpty() &&
            CaptureRules.identity(book).equals(prefs(c).getString("identity", ""));
    }
    static void soon(Context c) {
        if (!prefs(c).getBoolean("on", false)) return;
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-capture-look", ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
                .setInitialDelay(30, TimeUnit.SECONDS).setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    static void afterCall(Context c, long started) {
        if (!prefs(c).getBoolean("on", false)) return;
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-after-call:" + started, ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
                .setInitialDelay(90, TimeUnit.SECONDS).setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    static void scan(Context c) throws Exception {
        CaptureApi api = new CaptureApi(c);
        JSONObject book = api.book();
        if (!enabled(c, book) || !noticesAllowed(c)) return;
        JSONObject policy = api.policy();
        if (!CaptureRules.consent(api.consent(), policy.optString("policy_sha256"))) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> stored : prefs(c).getAll().entrySet()) {
            if (!stored.getKey().startsWith("job:")) continue;
            JSONObject old = new JSONObject(String.valueOf(stored.getValue()));
            if (now - old.optLong("stamp") > CaptureRules.KEEP_MS) {
                WorkManager.getInstance(c).cancelUniqueWork("sx-capture:" + old.optString("id"));
                prefs(c).edit().remove(stored.getKey()).commit();
            }
        }
        for (RecordingFolder.Document doc : RecordingFolder.files(c)) {
            if (doc.size <= 0 || doc.size > CaptureRules.MAX_BYTES || !Arrays.asList("m4a", "mp3", "mpeg", "wav", "ogg", "flac", "aac", "amr", "awb", "3gp", "opus").contains(RecordingScanner.extension(doc.name))) continue;
            String number = CaptureRules.number(doc.name, doc.modifiedMs, now,
                prefs(c).getLong("since", now), TimeZone.getDefault(), book);
            if (number == null) continue;
            long stamp = RecordingMatch.read(doc.name, TimeZone.getDefault()).stampMs;
            String id = CaptureRules.id(CaptureRules.identity(book), number, stamp);
            if (prefs(c).contains("job:" + id)) continue;
            long seconds;
            MediaMetadataRetriever media = new MediaMetadataRetriever();
            try {
                media.setDataSource(c, doc.uri);
                seconds = Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
            } catch (Exception unsupported) { continue; }
            finally { media.release(); }
            if (seconds <= 0 || seconds > 14400 || doc.modifiedMs < stamp + seconds * 1000 - 5000) continue;
            JSONObject call;
            try { call = api.json("POST", "/v1/lead-desk/recording-call", new JSONObject()
                .put("command_id", id).put("number", number).put("started_at", CallLogRules.iso(stamp))
                .put("duration_seconds", seconds), id, false);
            } catch (CaptureApi.Failure rejected) {
                if (rejected.code == 409 || rejected.code == 422 || rejected.code == 404) continue;
                throw rejected;
            }
            JSONObject job = new JSONObject().put("id", id).put("identity", CaptureRules.identity(book))
                .put("source", doc.uri.toString()).put("name", doc.name).put("number", number)
                .put("stamp", stamp).put("saved", doc.modifiedMs).put("size", doc.size)
                .put("seconds", seconds).put("leadId", call.getString("lead_id"))
                .put("eventId", call.getString("event_id")).put("leadName", call.optString("lead_name", "lead"))
                .put("state", "waiting").put("due", System.currentTimeMillis() + CaptureRules.UNDO_MS);
            // Commit before scheduling. A process restart sees the same job and submission id.
            prefs(c).edit().putString("job:" + id, job.toString()).commit();
            notice(c, id, "Call with " + job.optString("leadName", "lead") + " waiting for upload", "Uploads in 60 seconds. Undo keeps this recording on your phone.", true);
            enqueue(c, id, CaptureRules.UNDO_MS);
        }
        // Recover a process death between saving a job and enqueueing it.
        for (Map.Entry<String, ?> entry : prefs(c).getAll().entrySet()) {
            if (!entry.getKey().startsWith("job:")) continue;
            JSONObject job = new JSONObject(String.valueOf(entry.getValue()));
            if (Arrays.asList("waiting", "uploading", "analysing").contains(job.optString("state")))
                enqueue(c, job.getString("id"), Math.max(0, job.optLong("due") - now));
        }
    }
    static JSONArray states(Context c, CaptureApi api) throws Exception {
        JSONObject book = api.book();
        JSONArray states = new JSONArray();
        String identity = CaptureRules.identity(book);
        if (identity.isEmpty()) return states;
        for (Map.Entry<String, ?> entry : prefs(c).getAll().entrySet()) {
            if (!entry.getKey().startsWith("job:")) continue;
            JSONObject job = new JSONObject(String.valueOf(entry.getValue()));
            if (!identity.equals(job.optString("identity"))) continue;
            if ("analysing".equals(job.optString("state")) && job.has("eventId")) {
                try {
                    String sid = CaptureRules.id(identity, job.getString("eventId"), 0);
                    if (api.json("GET", "/v1/conversation/acquisition/submissions/" + sid, null, null, false).optBoolean("has_report")) save(c, job, "report_ready");
                } catch (Exception unavailable) { /* Keep the last confirmed state. */ }
            }
            states.put(new JSONObject().put("eventId", job.optString("eventId")).put("state", job.optString("state")));
        }
        return states;
    }
    static void enqueue(Context c, String id, long delay) {
        boolean wifi = prefs(c).getBoolean("wifiOnly", true);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-capture:" + id, ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(CaptureUploadWorker.class).addTag("sx-auto-capture")
                .setInputData(new Data.Builder().putString("id", id).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(wifi ? NetworkType.UNMETERED : NetworkType.CONNECTED).build()).build());
    }
    static JSONObject job(Context c, String id) throws JSONException { return new JSONObject(prefs(c).getString("job:" + id, "{}")); }
    static void save(Context c, JSONObject job, String state) throws JSONException {
        job.put("state", state);
        prefs(c).edit().putString("job:" + job.getString("id"), job.toString()).commit();
        PhoneBridge.signalActive("recordings");
    }
    static void notice(Context c, String id, String title, String text, boolean undo) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            new NotificationChannel("auto_capture", "Automatic call uploads", NotificationManager.IMPORTANCE_LOW));
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, "auto_capture")
            .setSmallIcon(R.drawable.ic_stat_sx).setContentTitle(title).setContentText(text).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(c, id.hashCode(), new Intent(c, MainActivity.class)
                .putExtra(LeadAlerts.EXTRA_OPEN, "/leads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (undo) n.addAction(0, "Undo", PendingIntent.getBroadcast(c, id.hashCode(), new Intent(c, CaptureUndoReceiver.class)
            .setData(Uri.parse("sx-capture:" + id)).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try { manager.notify(id.hashCode(), n.build()); } catch (SecurityException ignored) { }
    }
}
