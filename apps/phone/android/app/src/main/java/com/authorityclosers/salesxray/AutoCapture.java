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
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static JSONObject status(Context c) throws JSONException {
        return new JSONObject().put("supported", true).put("on", prefs(c).getBoolean("on", false))
            .put("wifiOnly", prefs(c).getBoolean("wifiOnly", true));
    }
    static void configure(Context c, JSONObject book, boolean on, boolean wifi) throws Exception {
        String identity = CaptureRules.identity(book);
        if (on && (identity.isEmpty() || RecordingFolder.trees(c).isEmpty())) throw new Exception("Pick your recordings folder inside an organisation workspace first.");
        SharedPreferences p = prefs(c);
        if (on && (!p.getBoolean("on", false) || !identity.equals(p.getString("identity", "")))) {
            p.edit().putLong("since", System.currentTimeMillis()).apply();
        }
        p.edit().putBoolean("on", on).putString("identity", identity).putBoolean("wifiOnly", wifi).commit();
        WorkManager work = WorkManager.getInstance(c);
        if (!on) { work.cancelAllWorkByTag("sx-auto-capture"); return; }
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
    static void scan(Context c) throws Exception {
        CaptureApi api = new CaptureApi(c);
        JSONObject book = api.book();
        if (!enabled(c, book)) return;
        JSONObject policy = api.policy();
        if (!CaptureRules.consent(api.consent(), policy.optString("policy_sha256"))) return;
        long now = System.currentTimeMillis();
        for (RecordingFolder.Document doc : RecordingFolder.files(c)) {
            if (doc.size <= 0 || doc.size > CaptureRules.MAX_BYTES) continue;
            String number = CaptureRules.number(doc.name, doc.modifiedMs, now,
                prefs(c).getLong("since", now), TimeZone.getDefault(), book);
            if (number == null) continue;
            long stamp = RecordingMatch.read(doc.name, TimeZone.getDefault()).stampMs;
            String id = CaptureRules.id(CaptureRules.identity(book), CallLogRules.key(number), stamp);
            if (prefs(c).contains("job:" + id)) continue;
            long seconds;
            try (MediaMetadataRetriever media = new MediaMetadataRetriever()) {
                media.setDataSource(c, doc.uri);
                seconds = Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
            } catch (Exception unsupported) { continue; }
            if (seconds <= 0 || seconds > 14400 || doc.modifiedMs < stamp + seconds * 1000 - 5000) continue;
            JSONObject call = api.json("POST", "/v1/lead-desk/recording-call", new JSONObject()
                .put("command_id", id).put("number", number).put("started_at", CallLogRules.iso(stamp))
                .put("duration_seconds", seconds), id, false);
            JSONObject job = new JSONObject().put("id", id).put("identity", CaptureRules.identity(book))
                .put("source", doc.uri.toString()).put("name", doc.name).put("number", number)
                .put("stamp", stamp).put("saved", doc.modifiedMs).put("size", doc.size)
                .put("seconds", seconds).put("leadId", call.getString("lead_id"))
                .put("eventId", call.getString("event_id")).put("leadName", call.optString("lead_name", "lead"))
                .put("state", "waiting").put("due", now + CaptureRules.UNDO_MS);
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
    static JSONArray states(Context c, JSONObject book) throws JSONException {
        JSONArray states = new JSONArray();
        String identity = CaptureRules.identity(book);
        if (identity.isEmpty()) return states;
        for (Map.Entry<String, ?> entry : prefs(c).getAll().entrySet()) {
            if (!entry.getKey().startsWith("job:")) continue;
            JSONObject job = new JSONObject(String.valueOf(entry.getValue()));
            if (!identity.equals(job.optString("identity"))) continue;
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
