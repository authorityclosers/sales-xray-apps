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
    // 7400 is ReviewAlerts' alert: the most urgent capture notice and a review alert replaced each other.
    private static final int ATTENTION_ID = 7500;
    // Refused recordings fold into one stack under this summary (simulation lab, 2 Nov 2026).
    private static final int REFUSED_SUMMARY_ID = 7600;
    private static final String REFUSED_GROUP = "sx_capture_refused";
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
        int failed = 0;
        for (Map.Entry<String, Integer> entry : held.entrySet())
            if (!CaptureRules.recoverable(entry.getKey())) failed += entry.getValue();
        JSONArray folders = new JSONArray();
        for (String path : RecordingFolder.paths(c)) folders.put(path);
        return new JSONObject().put("supported", true).put("on", on)
            // The upload-status screen (SX-AUTOUPLOAD, 25 Nov 2026): last upload, waiting, failed, the folder.
            .put("lastUploadAt", p.getLong("lastUploadAt", 0) > 0 ? p.getLong("lastUploadAt", 0) : JSONObject.NULL)
            .put("lastUploadLead", p.getString("lastUploadLead", null) == null ? JSONObject.NULL : p.getString("lastUploadLead", null))
            .put("lastScanAt", p.getLong("lastScanAt", 0) > 0 ? p.getLong("lastScanAt", 0) : JSONObject.NULL)
            .put("failed", failed).put("folders", folders)
            .put("callLogMatch", CallLogSync.hasAccess(c))
            .put("wifiOnly", p.getBoolean("wifiOnly", CaptureRules.WIFI_ONLY_DEFAULT)).put("health", health)
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
        if (on && (identity.isEmpty() || (RecordingFolder.trees(c).isEmpty() && !RecordingScanner.hasAudioAccess(c)))) throw new Exception("Pick your recordings folder inside an organisation workspace first.");
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
        boolean changedNetwork = wifi != p.getBoolean("wifiOnly", CaptureRules.WIFI_ONLY_DEFAULT);
        edit.putBoolean("on", on).putString("identity", identity).putBoolean("wifiOnly", wifi).commit();
        WorkManager work = WorkManager.getInstance(c);
        if (!on) { work.cancelAllWorkByTag("sx-auto-capture"); CaptureTriggerJob.disarm(c); PhoneBridge.signalActive("recordings"); return; }
        clearAttention(c, CaptureRules.CONSENT); clearAttention(c, CaptureRules.NOTICES); clearAttention(c, CaptureRules.SIGNED_OUT);
        if (changedNetwork) work.cancelAllWorkByTag("sx-auto-capture").getResult().get();
        // Jobs check the current network setting again before bytes leave the device.
        work.enqueueUniquePeriodicWork("sx-auto-capture-watch", ExistingPeriodicWorkPolicy.UPDATE,
            new PeriodicWorkRequest.Builder(CaptureWatchWorker.class, 15, TimeUnit.MINUTES)
                .addTag("sx-auto-capture").setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(wifi ? NetworkType.UNMETERED : NetworkType.CONNECTED).build()).build());
        // The look right after each call ends (CaptureTrigger); the watch above stays the fallback.
        CaptureTrigger.arm(c);
        // Held and waiting calls go back in the queue on this first look.
        now(c);
        OutcomeBackfill.now(c);
    }
    /** Only consent loss, blocked notices or a sign-out on this phone turn uploads off, and the seller is told. */
    static void stop(Context c, String reason) {
        SharedPreferences p = prefs(c);
        boolean was = p.getBoolean("on", false);
        SharedPreferences.Editor edit = p.edit().putBoolean("on", false).putString("stopped", reason);
        if (was) edit.putLong("offAt", System.currentTimeMillis());
        edit.commit();
        WorkManager.getInstance(c).cancelAllWorkByTag("sx-auto-capture");
        CaptureTriggerJob.disarm(c);
        if (was) attention(c, reason, pending(c));
        PhoneBridge.signalActive("recordings");
    }
    static boolean enabled(Context c, JSONObject book) {
        return prefs(c).getBoolean("on", false) && !CaptureRules.identity(book).isEmpty() &&
            CaptureRules.identity(book).equals(prefs(c).getString("identity", ""));
    }
    static void soon(Context c) {
        if (!prefs(c).getBoolean("on", false)) return;
        // Opening the app sends what is already waiting at once (Mac, 25 Nov 2026: "opening the app must flush the queue").
        flush(c);
        CaptureTrigger.arm(c);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-capture-look", ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
                .setInitialDelay(30, TimeUnit.SECONDS).setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    /** A look right away (turned on, or Try again on the capture-health card). */
    static void now(Context c) {
        if (!prefs(c).getBoolean("on", false)) return;
        flush(c);
        OneTimeWorkRequest.Builder look = new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build());
        if (android.os.Build.VERSION.SDK_INT >= 31) look.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-capture-now", ExistingWorkPolicy.KEEP, look.build());
    }
    static void afterCall(Context c, long started) {
        if (!prefs(c).getBoolean("on", false)) return;
        // The settled look comes first; this later one catches a dialler that saves its file slowly.
        CaptureTrigger.settled(c);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-auto-after-call:" + started, ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
                .setInitialDelay(90, TimeUnit.SECONDS).setConstraints(new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    /**
     * The phone's call recordings saved since {@code afterMs}: the folder the seller picked, and the call-recording
     * folders Android's audio access shows (SX-AUTOUPLOAD, production 21-25 Nov 2026: sellers picked "Recordings"
     * while the dialer saved to "Music/Recordings/Call Recordings", and the scan, reading the picked folder only,
     * saw none of 364 lead calls' recordings the media index held). Never Sales X-Ray's own recordings.
     */
    static List<RecordingFolder.Document> recordings(Context c, long afterMs) {
        List<RecordingFolder.Document> docs = new ArrayList<>();
        for (JSONObject item : RecordingScanner.list(c, 500, afterMs)) {
            String source = item.optString("source");
            Uri uri;
            if (source.startsWith("media:")) uri = Uri.parse(source.substring(6));
            else if (source.startsWith("tree:")) uri = Uri.parse(source.substring(5));
            else if (source.startsWith("file:")) uri = Uri.fromFile(new java.io.File(source.substring(5)));
            else continue;
            RecordingFolder.Document doc = new RecordingFolder.Document();
            doc.source = source;
            doc.uri = uri;
            doc.name = item.optString("name", null);
            doc.folder = item.optString("folder", "");
            doc.modifiedMs = item.optLong("modifiedMs", item.optLong("addedMs"));
            doc.size = item.optLong("size");
            doc.mime = item.optString("mime", null);
            if (doc.name != null && doc.modifiedMs >= afterMs) docs.add(doc);
        }
        return docs;
    }
    static boolean audio(RecordingFolder.Document doc) {
        return doc.size > 0 && doc.size <= CaptureRules.MAX_BYTES && Arrays.asList("m4a", "mp3", "mpeg", "wav", "ogg", "flac", "aac", "amr", "awb", "3gp", "opus").contains(RecordingScanner.extension(doc.name));
    }
    static void scan(Context c) throws Exception {
        // Before anything that can end this look early: a call already on its way always has its upload job.
        flush(c);
        CaptureApi api = new CaptureApi(c);
        JSONObject book = api.book();
        // What each scan saw goes out as sx.capture_scan (CaptureScanReport), stopped early or not.
        if (!noticesAllowed(c)) { stop(c, CaptureRules.NOTICES); CaptureScanReport.send(c, "notices", book, null); return; }
        // Another workspace (or Personal) pauses uploads; they never go to the wrong one, and resume back here.
        if (!enabled(c, book)) { pause(c, CaptureRules.WORKSPACE); CaptureScanReport.send(c, "workspace", book, null); return; }
        JSONObject policy = api.policy();
        String gap = CaptureRules.consentGap(api.consent(), policy.optString("policy_sha256"));
        if (gap != null) {
            // Turned off by the seller: uploads off, as before. New terms: calls wait and the seller is asked again.
            if (CaptureRules.CONSENT.equals(gap)) stop(c, CaptureRules.CONSENT);
            else pause(c, gap);
            CaptureScanReport.send(c, "consent", book, null);
            return;
        }
        prefs(c).edit().putLong("lastScanAt", System.currentTimeMillis()).apply();
        CaptureScanReport.Seen seen = new CaptureScanReport.Seen();
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
        forgetOldRefusals(c, now);
        ShareIn.cleanup(c);
        if (expired > 0) attention(c, CaptureRules.EXPIRED, expired);
        boolean calling = CaptureTrigger.inCall(c);
        long window = Math.max(prefs(c).getLong("since", now), now - CaptureRules.KEEP_MS);
        List<RecordingFolder.Document> unnamed = new ArrayList<>();
        for (RecordingFolder.Document doc : recordings(c, window)) {
            if (!audio(doc)) continue;
            seen.files++;
            if (!CaptureRules.settled(doc.modifiedMs, now, calling)) continue;
            String number = CaptureRules.number(doc.name, doc.modifiedMs, now,
                prefs(c).getLong("since", now), TimeZone.getDefault(), book);
            if (number == null) { unnamed.add(doc); continue; }
            seen.matched++;
            long stamp = RecordingMatch.read(doc.name, TimeZone.getDefault()).stampMs;
            String id = CaptureRules.id(CaptureRules.identity(book), number, stamp);
            if (prefs(c).contains("job:" + id) || prefs(c).contains("refused:" + id)) continue;
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
                JSONObject found = new JSONObject().put("identity", CaptureRules.identity(book))
                    .put("source", doc.source).put("name", doc.name).put("number", number)
                    .put("stamp", stamp).put("saved", doc.modifiedMs).put("size", doc.size).put("seconds", seconds);
                if (refused(c, api, id, number, rejected, found)) continue;
                // Another workspace's call: the next scan (back in this workspace) may take it.
                if (rejected.code == 409 || rejected.code == 422 || rejected.code == 404) continue;
                throw rejected;
            }
            JSONObject job = new JSONObject().put("id", id).put("identity", CaptureRules.identity(book))
                .put("source", doc.source).put("name", doc.name).put("number", number)
                .put("stamp", stamp).put("saved", doc.modifiedMs).put("size", doc.size)
                .put("seconds", seconds).put("leadId", call.getString("lead_id"))
                .put("eventId", call.getString("event_id")).put("leadName", call.optString("lead_name", "lead"))
                .put("state", "waiting").put("due", System.currentTimeMillis() + CaptureRules.UNDO_MS);
            // Commit before scheduling. A process restart sees the same job and submission id.
            prefs(c).edit().putString("job:" + id, job.toString()).commit();
            notice(c, id, "Call with " + job.optString("leadName", "lead") + " waiting for upload", "Uploads in 60 seconds. Undo keeps this recording on your phone.", true);
            enqueue(c, id, CaptureRules.UNDO_MS);
            log(id, "made by name, " + doc.source.substring(0, Math.max(0, doc.source.indexOf(':'))));
        }
        // Recordings named after a contact (or with no number): the call history says which lead call they are.
        seen.matched += byCallLog(c, api, book, unnamed, window, now);
        release(c, api, CaptureRules.identity(book), now);
        // Recover a process death between saving a job and enqueueing it.
        flush(c);
        log("scan", "files " + seen.files + " matched " + seen.matched);
        CaptureScanReport.send(c, "ran", book, seen);
    }
    /**
     * Lead calls in the call history that no file name named, each matched to the one recording that fits its time
     * (CaptureCallRules): the same lead call, job, 60-second Undo and upload as a recording named after the number.
     * Needs call history access (the work-call logging the seller turned on); without it nothing changes.
     */
    static int byCallLog(Context c, CaptureApi api, JSONObject book, List<RecordingFolder.Document> unnamed, long window, long now) throws Exception {
        if (unnamed.isEmpty() || !CallLogSync.hasAccess(c)) return 0;
        String identity = CaptureRules.identity(book);
        List<JSONObject> jobs = jobs(c);
        List<CaptureCallRules.Call> calls = new ArrayList<>();
        for (CaptureCallRules.Call call : leadCalls(c, book, window, now)) {
            String id = CaptureRules.id(identity, call.number, call.startedMs);
            if (prefs(c).contains("job:" + id) || prefs(c).contains("refused:" + id)) continue;
            if (CaptureCallRules.covered(jobs, call.number, call.startedMs)) continue;
            calls.add(call);
        }
        if (calls.isEmpty()) return 0;
        Set<String> used = new HashSet<>();
        for (JSONObject job : jobs) {
            // Jobs before 25 Nov 2026 keep the picked folder's document without "tree:".
            used.add(job.optString("source"));
            used.add("tree:" + job.optString("source"));
        }
        long first = Long.MAX_VALUE, last = 0;
        for (CaptureCallRules.Call call : calls) {
            first = Math.min(first, call.startedMs);
            last = Math.max(last, call.startedMs + call.seconds * 1000L);
        }
        List<JSONObject> items = new ArrayList<>();
        Map<String, RecordingFolder.Document> docs = new HashMap<>();
        for (RecordingFolder.Document doc : unnamed) {
            // Only files saved around one of these calls are opened for their length.
            if (doc.modifiedMs < first - RecordingMatch.SAVED_BEFORE_MS || doc.modifiedMs > last + RecordingMatch.SAVED_AFTER_MS) continue;
            String source = doc.source;
            if (used.contains(source)) continue;
            long length = 0;
            MediaMetadataRetriever media = new MediaMetadataRetriever();
            try {
                media.setDataSource(c, doc.uri);
                length = Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception unsupported) { length = 0; }
            finally { try { media.release(); } catch (Exception ignored) { } }
            items.add(new JSONObject().put("name", doc.name).put("addedMs", doc.modifiedMs)
                .put("durationMs", length).put("source", source));
            docs.put(source, doc);
        }
        int made = 0;
        for (Map.Entry<CaptureCallRules.Call, JSONObject> found : CaptureCallRules.match(calls, items, used, TimeZone.getDefault()).entrySet()) {
            CaptureCallRules.Call call = found.getKey();
            RecordingFolder.Document doc = docs.get(found.getValue().optString("source"));
            if (doc == null) continue;
            String id = CaptureRules.id(identity, call.number, call.startedMs);
            JSONObject logged;
            try {
                logged = api.json("POST", "/v1/lead-desk/recording-call", new JSONObject()
                    .put("command_id", id).put("number", call.number).put("started_at", CallLogRules.iso(call.startedMs))
                    .put("duration_seconds", call.seconds), id, false);
            } catch (CaptureApi.Failure rejected) {
                JSONObject kept = new JSONObject().put("identity", identity).put("matched", "call_log")
                    .put("source", doc.source).put("name", doc.name).put("number", call.number)
                    .put("stamp", call.startedMs).put("saved", doc.modifiedMs).put("size", doc.size).put("seconds", call.seconds);
                if (refused(c, api, id, call.number, rejected, kept)) continue;
                if (rejected.code == 409 || rejected.code == 422 || rejected.code == 404) continue;
                throw rejected;
            }
            JSONObject job = new JSONObject().put("id", id).put("identity", identity)
                .put("matched", "call_log").put("source", doc.source).put("name", doc.name).put("number", call.number)
                .put("stamp", call.startedMs).put("saved", doc.modifiedMs).put("size", doc.size)
                .put("seconds", call.seconds).put("leadId", logged.getString("lead_id"))
                .put("eventId", logged.getString("event_id")).put("leadName", logged.optString("lead_name", "lead"))
                .put("state", "waiting").put("due", System.currentTimeMillis() + CaptureRules.UNDO_MS);
            prefs(c).edit().putString("job:" + id, job.toString()).commit();
            notice(c, id, "Call with " + job.optString("leadName", "lead") + " waiting for upload", "Uploads in 60 seconds. Undo keeps this recording on your phone.", true);
            enqueue(c, id, CaptureRules.UNDO_MS);
            log(id, "made by call history, " + doc.source.substring(0, Math.max(0, doc.source.indexOf(':'))));
            made++;
        }
        return made;
    }

    /** Connected calls with this seller's leads since {@code from}, from the phone's call history; never another number. */
    static List<CaptureCallRules.Call> leadCalls(Context c, JSONObject book, long from, long now) {
        List<CaptureCallRules.Call> calls = new ArrayList<>();
        JSONArray numbers = book.optJSONArray("numbers");
        String[] columns = { android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION };
        try (android.database.Cursor cursor = c.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI, columns,
                android.provider.CallLog.Calls.DATE + " > ?", new String[] { String.valueOf(from) },
                android.provider.CallLog.Calls.DATE + " DESC")) {
            while (cursor != null && cursor.moveToNext() && calls.size() < 300) {
                long started = cursor.getLong(1), seconds = cursor.getLong(2);
                if (!CaptureCallRules.candidate(started, seconds, from, now, CaptureRules.SETTLE_MS)) continue;
                String number = CaptureRules.leadNumber(cursor.getString(0), numbers);
                if (number != null) calls.add(new CaptureCallRules.Call(number, started, seconds));
            }
        } catch (RuntimeException unreadable) {
            // No call history access or no provider: the file names alone decide, as before.
        }
        return calls;
    }

    // What became of a recording shared to the app (shared): queued, or why not.
    static final String SHARED_OK = "ok";
    /** Uploads are off here (or notices, consent, another workspace): Attach to a lead instead. */
    static final String SHARED_OFF = "off";
    /** The call's number is no lead of this seller's (or the server finds none). */
    static final String SHARED_NO_LEAD = "no_lead";
    /** This call already has its recording on its way. */
    static final String SHARED_ADDED = "added";

    /**
     * A recording shared from the Phone app (ShareIn) for one of the seller's own calls, matched
     * by the call history (ShareRules): the same lead call, job, 60-second Undo and upload as a
     * recording found in the folder, under the same standing consent. The seller's share stands
     * in for the folder's file name and time. Returns SHARED_* or a CaptureRules refusal.
     */
    static String shared(Context c, java.io.File file, ShareRules.Call call, long seconds) throws Exception {
        CaptureApi api = new CaptureApi(c);
        JSONObject book = api.book();
        if (!noticesAllowed(c) || !prefs(c).getBoolean("on", false) || !enabled(c, book)) return SHARED_OFF;
        JSONObject policy = api.policy();
        if (!CaptureRules.consent(api.consent(), policy.optString("policy_sha256"))) return SHARED_OFF;
        String number = CaptureRules.leadNumber(call.number, book.optJSONArray("numbers"));
        if (number == null) return SHARED_NO_LEAD;
        String id = CaptureRules.id(CaptureRules.identity(book), number, call.startedMs);
        if (prefs(c).contains("job:" + id)) return SHARED_ADDED;
        String before = prefs(c).getString("refused:" + id, null);
        if (before != null) return before.substring(0, before.indexOf(':') < 0 ? before.length() : before.indexOf(':'));
        JSONObject made;
        try {
            made = api.json("POST", "/v1/lead-desk/recording-call", new JSONObject()
                .put("command_id", id).put("number", number).put("started_at", CallLogRules.iso(call.startedMs))
                .put("duration_seconds", seconds), id, false);
        } catch (CaptureApi.Failure rejected) {
            if (refused(c, id, number, rejected)) return CaptureRules.refused(rejected.code, rejected.detail);
            if (rejected.code == 409 || rejected.code == 422 || rejected.code == 404) return SHARED_NO_LEAD;
            throw rejected;
        }
        JSONObject job = new JSONObject().put("id", id).put("identity", CaptureRules.identity(book))
            .put("shared", true).put("source", Uri.fromFile(file).toString()).put("name", file.getName())
            .put("number", number).put("stamp", call.startedMs).put("saved", file.lastModified())
            .put("size", file.length()).put("seconds", seconds).put("leadId", made.getString("lead_id"))
            .put("eventId", made.getString("event_id")).put("leadName", made.optString("lead_name", "lead"))
            .put("state", "waiting").put("due", System.currentTimeMillis() + CaptureRules.UNDO_MS);
        prefs(c).edit().putString("job:" + id, job.toString()).commit();
        notice(c, id, "Call with " + job.optString("leadName", "lead") + " waiting for upload", "Uploads in 60 seconds. Undo keeps this recording on your phone.", true);
        enqueue(c, id, CaptureRules.UNDO_MS);
        PhoneBridge.signalActive("recordings");
        return SHARED_OK;
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
        // The call's own notice no longer says "Uploads in 60 seconds" once it waits for something else.
        String[] words = CaptureRules.heldNotice(reason, job.optString("leadName", "lead"), CaptureRules.stops(reason));
        String id = job.optString("id");
        if (words != null) notice(c, id, words[0], words[1], false);
        else {
            NotificationManager manager = c.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(id.hashCode());
        }
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
        for (RecordingFolder.Document doc : recordings(c, from)) {
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
            if ("analysing".equals(job.optString("state")) && (job.has("eventId") || job.optBoolean("onLead"))) {
                try {
                    String sid = submission(job);
                    if (api.json("GET", "/v1/conversation/acquisition/submissions/" + sid, null, null, false).optBoolean("has_report")) save(c, job, "report_ready");
                } catch (Exception unavailable) { /* Keep the last confirmed state. */ }
            }
            JSONObject state = new JSONObject().put("eventId", job.optString("eventId")).put("state", job.optString("state"))
                .put("id", job.optString("id")).put("leadId", job.optString("leadId")).put("leadName", job.optString("leadName", "lead"))
                .put("stamp", job.optLong("stamp")).put("seconds", job.optLong("seconds")).put("due", job.optLong("due"))
                .put("uploadedAt", job.optLong("uploadedAt")).put("matched", job.optString("matched", "name"));
            if ("held".equals(job.optString("state"))) state.put("hold", reason(job));
            states.put(state);
        }
        return states;
    }
    /**
     * A call's submission id: from its call event, or, for a recording "Upload anyway" put on its lead with no call
     * event (onLead), from the job itself. The same id on every retry.
     */
    static String submission(JSONObject job) throws JSONException {
        return job.optBoolean("onLead")
            ? CaptureRules.id(job.getString("identity"), "anyway:" + job.getString("id"), 0)
            : CaptureRules.id(job.getString("identity"), job.getString("eventId"), 0);
    }
    static void enqueue(Context c, String id, long delay) { enqueue(c, id, delay, CaptureUploadWorker.class); }
    /**
     * One upload job per call (KEEP: a pending one stays, a finished or lost one is made again). Due within the Undo
     * minute: it starts now, expedited from Android 12, and waits the minute out itself (CaptureRules.waitInJob).
     * Redmi 13 (HyperOS, 25 Nov 2026): the minute-delayed job was gone from the system's job list and the call
     * stayed "waiting" for 33 minutes.
     */
    static void enqueue(Context c, String id, long delay, Class<? extends ListenableWorker> worker) {
        boolean wifi = prefs(c).getBoolean("wifiOnly", CaptureRules.WIFI_ONLY_DEFAULT);
        OneTimeWorkRequest.Builder upload = new OneTimeWorkRequest.Builder(worker).addTag("sx-auto-capture")
            .setInputData(new Data.Builder().putString("id", id).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(wifi ? NetworkType.UNMETERED : NetworkType.CONNECTED).build());
        boolean startNow = delay <= CaptureRules.UNDO_MS + CaptureRules.UNDO_SLACK_MS;
        if (!startNow) upload.setInitialDelay(delay, TimeUnit.MILLISECONDS);
        // Before Android 12 WorkManager runs expedited work as a foreground service, which this app has none of.
        else if (android.os.Build.VERSION.SDK_INT >= 31) upload.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-capture:" + id, ExistingWorkPolicy.KEEP, upload.build());
    }
    /**
     * Every call on its way (waiting, uploading, analysing) has its upload job again: run at the start of every look,
     * when the app opens and on Try again, before anything that can stop a look early (consent, workspace, an error).
     */
    static int flush(Context c) {
        long now = System.currentTimeMillis();
        int queued = 0;
        for (JSONObject job : jobs(c)) {
            if (!Arrays.asList("waiting", "uploading", "analysing").contains(job.optString("state"))) continue;
            enqueue(c, job.optString("id"), Math.max(0, job.optLong("due") - now));
            queued++;
        }
        if (queued > 0) log("flush", queued + " on their way");
        return queued;
    }
    /** One line per upload step under SXCapture (no numbers, names or files): what a device test reads back. */
    static void log(String id, String what) {
        String tag = id == null ? "-" : id.length() > 8 ? id.substring(0, 8) : id;
        android.util.Log.i("SXCapture", tag + " " + what);
    }
    static JSONObject job(Context c, String id) throws JSONException { return new JSONObject(prefs(c).getString("job:" + id, "{}")); }
    /** Retry on the upload-status screen: one held call goes back in the queue now; the upload checks everything again. */
    static boolean retry(Context c, String id) throws JSONException {
        JSONObject job = job(c, id);
        if (!job.has("id") || !"held".equals(job.optString("state")) || CaptureRules.EXPIRED.equals(reason(job))) return false;
        job.remove("hold"); job.remove("heldAt");
        job.put("due", System.currentTimeMillis());
        save(c, job, "waiting");
        WorkManager.getInstance(c).cancelUniqueWork("sx-capture:" + id);
        enqueue(c, id, 0);
        return true;
    }
    static void save(Context c, JSONObject job, String state) throws JSONException {
        job.put("state", state);
        SharedPreferences.Editor edit = prefs(c).edit();
        if (Arrays.asList("analysing", "report_ready", "received").contains(state) && !job.has("uploadedAt")) {
            long at = System.currentTimeMillis();
            job.put("uploadedAt", at);
            edit.putLong("lastUploadAt", at).putString("lastUploadLead", job.optString("leadName", "lead"));
        }
        edit.putString("job:" + job.getString("id"), job.toString()).commit();
        PhoneBridge.signalActive("recordings");
    }
    /**
     * A recording the server won't make a call from: remembered by file and reason so no later scan posts it again,
     * and told once, quietly, when the reason is one the seller should know (outside work hours, before the lead).
     * False when a later scan may succeed (another workspace).
     */
    static boolean refused(Context c, String id, String number, CaptureApi.Failure rejected) {
        return refused(c, null, id, number, rejected, null);
    }
    /**
     * As above, and for a call refused for work hours, the recording it came from ({@code found}: the job the scan
     * would have made) is kept with its lead, so "Upload anyway" can upload it (uploadAnyway). The notice names the
     * lead (Mac, 26 Nov 2026 01:45: it showed the number), asked of the server only when the seller is told.
     */
    static boolean refused(Context c, CaptureApi api, String id, String number, CaptureApi.Failure rejected, JSONObject found) {
        String reason = CaptureRules.refused(rejected.code, rejected.detail);
        if (reason == null) return false;
        SharedPreferences p = prefs(c);
        if (p.contains("refused:" + id)) return true;
        String who = number;
        if (api != null && CaptureRules.refusedNotice(reason, number) != null) {
            try {
                JSONObject lead = api.leadMatch(number);
                if (lead != null) {
                    who = lead.optString("name", number);
                    if (found != null) found.put("leadId", lead.getString("lead_id")).put("leadName", who);
                }
            } catch (Exception unknown) { /* The number, as before. */ }
        }
        SharedPreferences.Editor edit = p.edit().putString("refused:" + id, reason + ":" + System.currentTimeMillis());
        boolean keep = found != null && CaptureRules.REFUSED_HOURS.equals(reason);
        if (keep) edit.putString("anyway:" + id, found.toString());
        edit.commit();
        // "Upload anyway" was tapped on this call's notice from an older build, which kept no recording: upload it now.
        if (keep && p.contains("asked:" + id)) {
            p.edit().remove("asked:" + id).commit();
            log(id, "refused " + reason + ", upload anyway asked before");
            uploadAnyway(c, id, CaptureUploadWorker.class);
            return true;
        }
        String[] words = CaptureRules.refusedNotice(reason, who);
        if (words != null) refusedNotice(c, id, words);
        log(id, "refused " + reason);
        return true;
    }
    /**
     * "Upload anyway" (CaptureAgainReceiver): the recording a work-hours refusal kept becomes a job at once, with no Undo
     * minute (the seller just asked), under the same upload terms and checks as any other. Its upload asks for the call
     * again and, if the server still says outside work hours (v1.99.0 refuses any call outside them), puts the
     * recording on its lead as the web's "Assign to a lead" does (CaptureUploadWorker). Mac, 26 Nov 2026 02:04,
     * Redmi 13 on 1.0.158: the tap only forgot the refusal and looked again, the server refused again, and nothing was
     * uploaded. False when nothing was kept for this call (a refusal from an older build): the caller looks again.
     */
    static boolean uploadAnyway(Context c, String id, Class<? extends ListenableWorker> worker) {
        SharedPreferences p = prefs(c);
        String kept = p.getString("anyway:" + id, null);
        if (kept == null) return false;
        JSONObject job;
        try {
            job = new JSONObject(kept).put("id", id).put("anyway", true).put("state", "waiting").put("due", System.currentTimeMillis());
            if (!job.has("leadName")) job.put("leadName", "lead");
        } catch (JSONException unreadable) { p.edit().remove("anyway:" + id).commit(); return false; }
        if (!p.contains("job:" + id)) {
            p.edit().putString("job:" + id, job.toString()).remove("anyway:" + id).remove("refused:" + id).commit();
            notice(c, id, "Uploading call with " + job.optString("leadName", "lead"), "It goes for analysis when the upload ends.", false);
        } else p.edit().remove("anyway:" + id).remove("refused:" + id).commit();
        enqueue(c, id, 0, worker);
        log(id, "upload anyway: job made");
        PhoneBridge.signalActive("recordings");
        return true;
    }
    /** Refusals older than the recordings any scan reads (KEEP_MS) are forgotten. */
    static void forgetOldRefusals(Context c, long now) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor edit = p.edit();
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            if (!entry.getKey().startsWith("refused:")) continue;
            String value = String.valueOf(entry.getValue());
            long at;
            try { at = Long.parseLong(value.substring(value.lastIndexOf(':') + 1)); } catch (NumberFormatException bad) { at = 0; }
            if (now - at > CaptureRules.KEEP_MS) edit.remove(entry.getKey()).remove("anyway:" + entry.getKey().substring("refused:".length()));
        }
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            if (entry.getKey().startsWith("asked:") && entry.getValue() instanceof Long && now - (Long) entry.getValue() > CaptureRules.KEEP_MS)
                edit.remove(entry.getKey());
        }
        edit.commit();
    }
    /** Silent, on the upload channel, and grouped: an evening of refused calls folds into one stack. */
    private static void refusedNotice(Context c, String id, String[] words) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            new NotificationChannel("auto_capture", "Automatic call uploads", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(c, REFUSED_SUMMARY_ID, new Intent(c, MainActivity.class)
            .putExtra(LeadAlerts.EXTRA_OPEN, "/leads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent page = words.length > 3 && words[3] != null ? PendingIntent.getActivity(c, ("refused:" + id).hashCode(),
            new Intent(c, MainActivity.class).putExtra(LeadAlerts.EXTRA_OPEN, words[3]).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : open;
        NotificationCompat.Builder one = new NotificationCompat.Builder(c, "auto_capture")
            .setSmallIcon(R.drawable.ic_stat_sx).setContentTitle(words[0]).setContentText(words[1]).setSilent(true)
            .setAutoCancel(true).setGroup(REFUSED_GROUP).setContentIntent(page);
        // One tap, same upload terms: the server decides again (CaptureAgainReceiver).
        if (words.length > 2 && words[2] != null) one.addAction(0, words[2], PendingIntent.getBroadcast(c, ("again:" + id).hashCode(),
            new Intent(c, CaptureAgainReceiver.class).setData(Uri.parse("sx-again:" + id)).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        NotificationCompat.Builder summary = new NotificationCompat.Builder(c, "auto_capture")
            .setSmallIcon(R.drawable.ic_stat_sx).setContentTitle("Lead calls not uploaded").setContentText(words[1]).setSilent(true)
            .setAutoCancel(true).setGroup(REFUSED_GROUP).setGroupSummary(true).setContentIntent(open);
        try {
            manager.notify(("refused:" + id).hashCode(), one.build());
            manager.notify(REFUSED_SUMMARY_ID, summary.build());
        } catch (SecurityException ignored) { }
    }
    static void notice(Context c, String id, String title, String text, boolean undo) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            new NotificationChannel("auto_capture", "Automatic call uploads", NotificationManager.IMPORTANCE_LOW));
        // Every caller saved the job first: its tap opens that call's lead, not the Leads list (lab, 2 Nov 2026).
        String lead;
        try { lead = job(c, id).optString("leadId", null); } catch (JSONException unreadable) { lead = null; }
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, "auto_capture")
            .setSmallIcon(R.drawable.ic_stat_sx).setContentTitle(title).setContentText(text).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(c, id.hashCode(), new Intent(c, MainActivity.class)
                .putExtra(LeadAlerts.EXTRA_OPEN, CaptureRules.leadPath(lead)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
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
