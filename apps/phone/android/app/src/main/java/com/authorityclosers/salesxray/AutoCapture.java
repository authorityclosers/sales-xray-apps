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
    private static final int ATTENTION_ID = 7400;
    private static final long NETWORK_NOTICE_MS = 15L * 60 * 1000;
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
    /** Uploads on or off, and what the capture-health card says (newer web reads health, pending, held). */
    static JSONObject status(Context c) throws JSONException {
        SharedPreferences p = prefs(c);
        Map<String, Integer> held = new HashMap<>();
        int pending = 0;
        for (JSONObject job : jobs(c)) {
            if ("held".equals(job.optString("state"))) held.merge(reason(job), 1, Integer::sum);
            else if (Arrays.asList("waiting", "uploading").contains(job.optString("state"))) pending++;
        }
        boolean on = p.getBoolean("on", false);
        String health = CaptureRules.health(on, p.getString("stopped", null), p.getString("paused", null), held,
            p.getLong("netBlockedAt", 0), System.currentTimeMillis());
        Integer calls = held.get(health);
        return new JSONObject().put("supported", true).put("on", on)
            .put("wifiOnly", p.getBoolean("wifiOnly", true)).put("health", health)
            .put("healthCalls", calls == null ? pending : calls).put("pending", pending).put("held", new JSONObject(held))
            .put("offSinceMs", !on && p.getLong("offAt", 0) > 0 ? p.getLong("offAt", 0) : JSONObject.NULL);
    }
    static void configure(Context c, JSONObject book, boolean on, boolean wifi) throws Exception { configure(c, book, on, wifi, false); }
    /** includeOffPeriod: the seller chose to upload the lead calls saved while uploads were off (offPeriodCalls). */
    static void configure(Context c, JSONObject book, boolean on, boolean wifi, boolean includeOffPeriod) throws Exception {
        SharedPreferences p = prefs(c);
        // Turning off keeps whose uploads these were, so turning on again resumes the same seller's calls.
        String identity = on ? CaptureRules.identity(book) : p.getString("identity", "");
        if (on && !noticesAllowed(c)) throw new Exception("Allow upload notifications first, so Undo is available.");
        if (on && (identity.isEmpty() || RecordingFolder.trees(c).isEmpty())) throw new Exception("Pick your recordings folder inside an organisation workspace first.");
        long now = System.currentTimeMillis();
        boolean wasOn = p.getBoolean("on", false);
        boolean same = identity.equals(p.getString("identity", ""));
        SharedPreferences.Editor edit = p.edit();
        if (on && (!wasOn || !same)) {
            // Another seller or workspace starts from now; the same one chose about the calls saved while off.
            edit.putLong("since", same ? CaptureRules.resumeSince(p.getLong("offAt", 0), now, includeOffPeriod) : now);
        }
        if (!on && wasOn) edit.putLong("offAt", now).putString("stopped", CaptureRules.OFF);
        if (on) edit.remove("offAt").remove("stopped").remove("paused");
        boolean changedNetwork = wifi != p.getBoolean("wifiOnly", true);
        edit.putBoolean("on", on).putString("identity", identity).putBoolean("wifiOnly", wifi).commit();
        WorkManager work = WorkManager.getInstance(c);
        if (!on) { work.cancelAllWorkByTag("sx-auto-capture"); PhoneBridge.signalActive("recordings"); return; }
        clearAttention(c, CaptureRules.CONSENT); clearAttention(c, CaptureRules.NOTICES); clearAttention(c, CaptureRules.SIGNED_OUT);
        if (changedNetwork) work.cancelAllWorkByTag("sx-auto-capture").getResult().get();
        // Jobs check the current network setting again before bytes leave the device.
        work.enqueueUniquePeriodicWork("sx-auto-capture-watch", ExistingPeriodicWorkPolicy.UPDATE,
            new PeriodicWorkRequest.Builder(CaptureWatchWorker.class, 15, TimeUnit.MINUTES)
                .addTag("sx-auto-capture").setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(wifi ? NetworkType.UNMETERED : NetworkType.CONNECTED).build()).build());
        // Held and waiting calls go back in the queue on this first look.
        now(c);
    }
    /** Only consent loss, blocked notices or a sign-out on this phone turn uploads off, and the seller is told. */
    static void stop(Context c, String reason) {
        SharedPreferences p = prefs(c);
        boolean was = p.getBoolean("on", false);
        SharedPreferences.Editor edit = p.edit().putBoolean("on", false).putString("stopped", reason);
        if (was) edit.putLong("offAt", System.currentTimeMillis());
        edit.commit();
        WorkManager.getInstance(c).cancelAllWorkByTag("sx-auto-capture");
        if (was) attention(c, reason, pending(c));
        PhoneBridge.signalActive("recordings");
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
    /** A look right away (turned on, or Try again on the capture-health card). */
    static void now(Context c) {
        if (!prefs(c).getBoolean("on", false)) return;
        OneTimeWorkRequest.Builder look = new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build());
        if (android.os.Build.VERSION.SDK_INT >= 31) look.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-capture-now", ExistingWorkPolicy.KEEP, look.build());
    }
    static void afterCall(Context c, long started) {
        if (!prefs(c).getBoolean("on", false)) return;
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-after-call:" + started, ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
                .setInitialDelay(90, TimeUnit.SECONDS).setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    static boolean audio(RecordingFolder.Document doc) {
        return doc.size > 0 && doc.size <= CaptureRules.MAX_BYTES && Arrays.asList("m4a", "mp3", "mpeg", "wav", "ogg", "flac", "aac", "amr", "awb", "3gp", "opus").contains(RecordingScanner.extension(doc.name));
    }
    static void scan(Context c) throws Exception {
        CaptureApi api = new CaptureApi(c);
        JSONObject book = api.book();
        if (!noticesAllowed(c)) { stop(c, CaptureRules.NOTICES); return; }
        // Another workspace (or Personal) pauses uploads; they never go to the wrong one, and resume back here.
        if (!enabled(c, book)) { pause(c, CaptureRules.WORKSPACE); return; }
        JSONObject policy = api.policy();
        if (!CaptureRules.consent(api.consent(), policy.optString("policy_sha256"))) { stop(c, CaptureRules.CONSENT); return; }
        resume(c);
        long now = System.currentTimeMillis();
        int expired = 0;
        for (JSONObject old : jobs(c)) {
            if (now - old.optLong("stamp") > CaptureRules.KEEP_MS) {
                String state = old.optString("state");
                if (Arrays.asList("waiting", "uploading").contains(state) || ("held".equals(state) && CaptureRules.recoverable(reason(old)))) expired++;
                WorkManager.getInstance(c).cancelUniqueWork("sx-capture:" + old.optString("id"));
                prefs(c).edit().remove("job:" + old.optString("id")).commit();
            }
        }
        if (expired > 0) attention(c, CaptureRules.EXPIRED, expired);
        for (RecordingFolder.Document doc : RecordingFolder.files(c, Math.max(prefs(c).getLong("since", now), now - CaptureRules.KEEP_MS))) {
            if (!audio(doc)) continue;
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
        release(c, api, CaptureRules.identity(book), now);
        // Recover a process death between saving a job and enqueueing it.
        for (JSONObject job : jobs(c)) {
            if (Arrays.asList("waiting", "uploading", "analysing").contains(job.optString("state")))
                enqueue(c, job.getString("id"), Math.max(0, job.optLong("due") - now));
        }
    }
    /** Held calls whose cause is gone go back in the queue (profile done, minutes added, signed in, back here). */
    static void release(Context c, CaptureApi api, String identity, long now) throws JSONException {
        Boolean profile = null, minutes = null;
        boolean askedProfile = false, askedMinutes = false;
        Set<String> cleared = new HashSet<>();
        for (JSONObject job : jobs(c)) {
            if (!"held".equals(job.optString("state"))) continue;
            String reason = reason(job);
            if (CaptureRules.PROFILE.equals(reason) && !askedProfile) {
                askedProfile = true;
                try { profile = api.eligible(); } catch (Exception unknown) { /* Try on the next look. */ }
            }
            if (CaptureRules.ALLOWANCE.equals(reason) && !askedMinutes) {
                askedMinutes = true;
                try { minutes = api.minutesLeft(); } catch (Exception unknown) { /* Try on the next look. */ }
            }
            if (!CaptureRules.release(reason, identity.equals(job.optString("identity")), profile, minutes, job.optLong("heldAt"), now)) continue;
            job.remove("hold"); job.remove("heldAt");
            save(c, job, "waiting");
            enqueue(c, job.getString("id"), Math.max(0, job.optLong("due") - now));
            cleared.add(reason);
        }
        for (String reason : cleared) clearAttention(c, reason);
    }
    /** Why a held call waits. Calls held before 30 Oct 2026 say nothing: they are tried again hourly. */
    static String reason(JSONObject job) {
        String reason = job.optString("hold", "");
        return reason.isEmpty() ? CaptureRules.ACCESS : reason;
    }
    /** A call waits on this phone: the seller hears why, and the watch retries it when it can. */
    static void hold(Context c, JSONObject job, String reason) {
        if (job == null || !job.has("id")) return;
        try {
            job.put("hold", reason).put("heldAt", System.currentTimeMillis());
            save(c, job, "held");
        } catch (JSONException unreadable) { return; }
        if (CaptureRules.stops(reason)) stop(c, reason);
        else if (!CaptureRules.OFF.equals(reason)) attention(c, reason, held(c, reason));
    }
    /** The watch can't look (signed out, another workspace): uploads stay on and resume by themselves. */
    static void pause(Context c, String reason) {
        if (CaptureRules.REVIEW.equals(reason)) reason = CaptureRules.ACCESS;
        prefs(c).edit().putString("paused", reason).commit();
        int calls = pending(c) + held(c, null);
        // Choosing Personal on purpose is no news, unless calls are waiting for the organisation.
        if (!CaptureRules.WORKSPACE.equals(reason) || calls > 0) attention(c, reason, calls);
        PhoneBridge.signalActive("recordings");
    }
    private static void resume(Context c) {
        String paused = prefs(c).getString("paused", null);
        if (paused == null) return;
        prefs(c).edit().remove("paused").commit();
        clearAttention(c, paused);
    }
    static void networkBlocked(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (p.getLong("netBlockedSince", 0) == 0) p.edit().putLong("netBlockedSince", now).commit();
        p.edit().putLong("netBlockedAt", now).commit();
        // One blocked look can be the system being slow; a quarter of an hour of them is the phone's setting.
        if (now - p.getLong("netBlockedSince", now) >= NETWORK_NOTICE_MS) attention(c, CaptureRules.NETWORK, pending(c));
    }
    static void networkOk(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getLong("netBlockedSince", 0) == 0 && p.getLong("netBlockedAt", 0) == 0) return;
        p.edit().remove("netBlockedSince").remove("netBlockedAt").commit();
        clearAttention(c, CaptureRules.NETWORK);
    }
    /** A refused request, read back against write-eligibility and the allowance (CaptureRules.refusal). */
    static String explain(CaptureApi api, CaptureApi.Failure error) {
        Boolean profile = null, minutes = null;
        if (api != null && error.code == 403) try { profile = api.eligible(); } catch (Exception unknown) { /* The words decide. */ }
        if (api != null && error.code == 409) try { minutes = api.minutesLeft(); } catch (Exception unknown) { /* The words decide. */ }
        return CaptureRules.refusal(error.code, error.detail, profile, minutes);
    }
    /** Lead call recordings saved while uploads were off that no job has yet: offered when turning on again. */
    static int offPeriodCalls(Context c, JSONObject book) {
        SharedPreferences p = prefs(c);
        String identity = CaptureRules.identity(book);
        long now = System.currentTimeMillis();
        long from = CaptureRules.resumeSince(p.getLong("offAt", 0), now, true);
        if (p.getBoolean("on", false) || identity.isEmpty() || !identity.equals(p.getString("identity", "")) || from >= now) return 0;
        int count = 0;
        for (RecordingFolder.Document doc : RecordingFolder.files(c, from)) {
            if (!audio(doc)) continue;
            String number = CaptureRules.number(doc.name, doc.modifiedMs, now, from, TimeZone.getDefault(), book);
            if (number == null) continue;
            long stamp = RecordingMatch.read(doc.name, TimeZone.getDefault()).stampMs;
            if (!p.contains("job:" + CaptureRules.id(identity, number, stamp))) count++;
        }
        return count;
    }
    static long offAt(Context c) { return prefs(c).getLong("offAt", 0); }
    static List<JSONObject> jobs(Context c) {
        List<JSONObject> jobs = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs(c).getAll().entrySet()) {
            if (!entry.getKey().startsWith("job:")) continue;
            try { jobs.add(new JSONObject(String.valueOf(entry.getValue()))); } catch (JSONException unreadable) { /* Skipped. */ }
        }
        return jobs;
    }
    /** Calls on their way (waiting or uploading). */
    private static int pending(Context c) {
        int count = 0;
        for (JSONObject job : jobs(c)) if (Arrays.asList("waiting", "uploading").contains(job.optString("state"))) count++;
        return count;
    }
    /** Held calls for one reason, or for every reason when it is null. */
    private static int held(Context c, String reason) {
        int count = 0;
        for (JSONObject job : jobs(c))
            if ("held".equals(job.optString("state")) && (reason == null || reason.equals(reason(job)))) count++;
        return count;
    }
    static JSONArray states(Context c, CaptureApi api) throws Exception {
        JSONObject book = api.book();
        JSONArray states = new JSONArray();
        String identity = CaptureRules.identity(book);
        if (identity.isEmpty()) return states;
        for (JSONObject job : jobs(c)) {
            if (!identity.equals(job.optString("identity"))) continue;
            if ("analysing".equals(job.optString("state")) && job.has("eventId")) {
                try {
                    String sid = CaptureRules.id(identity, job.getString("eventId"), 0);
                    if (api.json("GET", "/v1/conversation/acquisition/submissions/" + sid, null, null, false).optBoolean("has_report")) save(c, job, "report_ready");
                } catch (Exception unavailable) { /* Keep the last confirmed state. */ }
            }
            JSONObject state = new JSONObject().put("eventId", job.optString("eventId")).put("state", job.optString("state"));
            if ("held".equals(job.optString("state"))) state.put("hold", reason(job));
            states.put(state);
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
    /** Why calls wait, on its own channel (it may make a sound, unlike the upload progress), one notice per reason. */
    static void attention(Context c, String reason, int calls) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (!CaptureRules.notifyAgain(p.getLong("told:" + reason, 0), p.getInt("toldCount:" + reason, 0), calls, now)) return;
        p.edit().putLong("told:" + reason, now).putInt("toldCount:" + reason, calls).commit();
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            new NotificationChannel("capture_attention", "Call uploads that need you", NotificationManager.IMPORTANCE_DEFAULT));
        String[] words = CaptureRules.attention(reason, calls, !p.getBoolean("on", false));
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, "capture_attention")
            .setSmallIcon(R.drawable.ic_stat_sx).setContentTitle(words[0]).setContentText(words[1])
            .setStyle(new NotificationCompat.BigTextStyle().bigText(words[1])).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, ATTENTION_ID + CaptureRules.PRIORITY.indexOf(reason), new Intent(c, MainActivity.class)
                .putExtra(LeadAlerts.EXTRA_OPEN, words[2]).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try { manager.notify(ATTENTION_ID + CaptureRules.PRIORITY.indexOf(reason), n.build()); } catch (SecurityException ignored) { }
    }
    static void clearAttention(Context c, String reason) {
        prefs(c).edit().remove("told:" + reason).remove("toldCount:" + reason).commit();
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ATTENTION_ID + CaptureRules.PRIORITY.indexOf(reason));
    }
}
