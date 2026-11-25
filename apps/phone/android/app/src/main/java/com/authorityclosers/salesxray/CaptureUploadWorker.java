package com.authorityclosers.salesxray;

import android.content.Context;
import android.net.*;
import android.database.Cursor;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Restarts from the last server-confirmed stage, always with the same submission and keys. */
public class CaptureUploadWorker extends Worker {
    private static final String API = "/v1/conversation/acquisition/submissions/";
    public CaptureUploadWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    JSONObject gate(Context c, CaptureApi api, JSONObject job) throws Exception {
        JSONObject book = api.book();
        if (!AutoCapture.noticesAllowed(c)) throw new CaptureApi.Hold(CaptureRules.NOTICES);
        if (!AutoCapture.prefs(c).getBoolean("on", false)) throw new CaptureApi.Hold(CaptureRules.OFF);
        if (!AutoCapture.enabled(c, book) || !CaptureRules.identity(book).equals(job.optString("identity")))
            throw new CaptureApi.Hold(CaptureRules.WORKSPACE);
        // The job was inside the upload window when it was made (turning uploads off and on keeps it);
        // the number must still be this seller's lead. A shared recording has the call's number, not a file name's.
        // A shared recording, or one the call history matched (named after a contact), has the call's number.
        String lead = job.optBoolean("shared") || "call_log".equals(job.optString("matched"))
            ? CaptureRules.leadNumber(job.getString("number"), book.optJSONArray("numbers"))
            : CaptureRules.number(job.getString("name"), job.getLong("saved"), System.currentTimeMillis(),
                0, TimeZone.getDefault(), book);
        if (lead == null) throw new CaptureApi.Hold(CaptureRules.REVIEW);
        JSONObject policy = api.policy();
        // New terms hold the call until the seller agrees again (TERMS); only turning consent off stops uploads.
        String gap = CaptureRules.consentGap(api.consent(), policy.optString("policy_sha256"));
        if (gap != null) throw new CaptureApi.Hold(gap);
        if (AutoCapture.prefs(c).getBoolean("wifiOnly", CaptureRules.WIFI_ONLY_DEFAULT)) {
            ConnectivityManager network = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities active = network.getNetworkCapabilities(network.getActiveNetwork());
            if (active == null || !active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || network.isActiveNetworkMetered())
                throw new IOException("waiting_for_wifi");
        }
        if (isStopped()) throw new IOException("stopped");
        return policy;
    }
    InputStream source(Context c, JSONObject job) throws Exception {
        Uri uri = Uri.parse(job.getString("source"));
        if (job.optBoolean("shared")) return shared(c, job, uri);
        String listed = job.getString("source");
        if (listed.startsWith("media:") || listed.startsWith("tree:") || listed.startsWith("file:")) return listed(c, job, listed);
        boolean granted = false;
        for (Uri tree : RecordingFolder.trees(c)) {
            if (uri.getAuthority().equals(tree.getAuthority()) && RecordingFolderRules.inside(DocumentsContract.getTreeDocumentId(tree), DocumentsContract.getDocumentId(uri))) granted = true;
        }
        if (!granted) throw new CaptureApi.Hold(CaptureRules.FOLDER);
        try (Cursor cursor = c.getContentResolver().query(uri, new String[] {
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        }, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new CaptureApi.Hold(CaptureRules.REVIEW);
            unchanged(c, job, cursor.getLong(0), cursor.getLong(1));
        }
        return new FilterInputStream(c.getContentResolver().openInputStream(uri)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (isStopped() || !AutoCapture.prefs(c).getBoolean("on", false)) throw new IOException("stopped");
                return super.read(b, off, len);
            }
        };
    }
    /**
     * A recording from the phone's listing (the picked folder or the media index's call-recording folders, since
     * 25 Nov 2026): opened only through RecordingScanner's own checks, and only while unchanged since it was matched.
     */
    private InputStream listed(Context c, JSONObject job, String source) throws Exception {
        long[] now;
        try { now = RecordingScanner.stat(c, source); }
        catch (IOException | SecurityException | IllegalArgumentException gone) { throw new CaptureApi.Hold(CaptureRules.FOLDER); }
        if (now == null) throw new CaptureApi.Hold(CaptureRules.FOLDER);
        unchanged(c, job, now[0], now[1]);
        InputStream in;
        try { in = RecordingScanner.open(c, source); }
        catch (SecurityException gone) { throw new CaptureApi.Hold(CaptureRules.FOLDER); }
        return new FilterInputStream(in) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (isStopped() || !AutoCapture.prefs(c).getBoolean("on", false)) throw new IOException("stopped");
                return super.read(b, off, len);
            }
        };
    }
    /**
     * The recording is the one matched: unchanged, or the same file finished by its dialler since (taken as it is
     * now; the hash is made again). Still being written: tried again shortly. Anything else is held for the seller
     * (REVIEW), as before. Before 25 Nov 2026 every change was held, and REVIEW is never retried, so a call whose
     * file the recorder touched after the match could wait for ever under "Uploads in 60 seconds".
     */
    static void unchanged(Context c, JSONObject job, long size, long saved) throws Exception {
        String change = CaptureRules.sourceChange(job.getLong("size"), job.getLong("saved"), size, saved, System.currentTimeMillis());
        if (CaptureRules.SOURCE_SAME.equals(change)) return;
        AutoCapture.log(job.optString("id"), "source " + change + " size " + job.getLong("size") + "->" + size + " saved +" + (saved - job.getLong("saved")) + "ms");
        if (CaptureRules.SOURCE_SETTLING.equals(change)) throw new IOException("recording_settling");
        if (CaptureRules.SOURCE_CHANGED.equals(change)) throw new CaptureApi.Hold(CaptureRules.REVIEW);
        job.put("size", size).put("saved", saved).remove("sha");
        AutoCapture.save(c, job, "uploading");
    }
    /** A recording the seller shared (ShareIn): only the app's own copy of it, unchanged since it was matched. */
    private InputStream shared(Context c, JSONObject job, Uri uri) throws Exception {
        File file = "file".equals(uri.getScheme()) && uri.getPath() != null ? new File(uri.getPath()) : null;
        if (file == null || !ShareIn.owns(c, file)) throw new CaptureApi.Hold(CaptureRules.REVIEW);
        if (!file.isFile()) throw new CaptureApi.Hold(CaptureRules.FOLDER);
        if (file.length() != job.getLong("size") || file.lastModified() != job.getLong("saved")) throw new CaptureApi.Hold(CaptureRules.REVIEW);
        return new FilterInputStream(new FileInputStream(file)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (isStopped() || !AutoCapture.prefs(c).getBoolean("on", false)) throw new IOException("stopped");
                return super.read(b, off, len);
            }
        };
    }
    CaptureApi api(Context c) throws Exception { return new CaptureApi(c); }
    @NonNull public Result doWork() {
        Context c = getApplicationContext(); String id = getInputData().getString("id");
        JSONObject job = null;
        CaptureApi api = null;
        CaptureNet.begin();
        try {
            job = AutoCapture.job(c, id);
            if (id == null || !job.has("id")) return Result.success();
            AutoCapture.log(id, "run " + job.optString("state") + " attempt " + getRunAttemptCount() + " due " + (job.optLong("due") - System.currentTimeMillis()) + "ms");
            if (Arrays.asList("cancelled", "held", "report_ready", "received").contains(job.optString("state"))) return Result.success();
            if (System.currentTimeMillis() < job.optLong("due")) {
                if (!CaptureRules.waitInJob(job.optLong("due"), System.currentTimeMillis())) return Result.retry();
                // The Undo minute runs out here, in the job that uploads (CaptureRules.waitInJob).
                if (!untilDue(c, id, job.optLong("due"))) {
                    AutoCapture.log(id, isStopped() ? "stopped in undo minute" : "undone");
                    return isStopped() ? Result.retry() : Result.success();
                }
                job = AutoCapture.job(c, id);
                if (!Arrays.asList("waiting", "uploading", "analysing").contains(job.optString("state"))) return Result.success();
            }
            if (System.currentTimeMillis() - job.optLong("stamp") > CaptureRules.KEEP_MS) {
                AutoCapture.hold(c, job, CaptureRules.EXPIRED); return Result.success();
            }
            // A cached app has no network on Android 15+ until the system runs a job for it (CaptureNet).
            if (!CaptureNet.await(c, this)) { AutoCapture.log(id, "no network"); return Result.retry(); }
            api = api(c);
            JSONObject policy = gate(c, api, job);
            // No bytes before the Undo state has been read again.
            synchronized (AutoCapture.class) {
                if ("cancelled".equals(AutoCapture.job(c, id).optString("state"))) return Result.success();
                boolean first = "waiting".equals(job.optString("state"));
                // A run after the upload only follows the analysis (Result.retry while the server works): the call
                // stays "analysing". Mac, 26 Nov 2026 02:16 (Redmi, 1.0.158): this run said "uploading" again and
                // looked like a second upload; it sends no bytes (the server has the submission) and the attach
                // replays with the same command id.
                if (!"analysing".equals(job.optString("state"))) AutoCapture.save(c, job, "uploading");
                // The Undo minute is over: the notice says what happens now (it said "Uploads in 60 seconds").
                if (first) AutoCapture.notice(c, id, "Uploading call with " + job.optString("leadName", "lead"), "It goes for analysis when the upload ends.", false);
            }
            AutoCapture.log(id, "analysing".equals(job.optString("state")) ? "follow-up, no upload" : "uploading");
            if (!job.has("sha")) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (InputStream in = source(c, job)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        count += n;
                        if (isStopped() || count > CaptureRules.MAX_BYTES) throw new IOException("source_too_large_or_stopped");
                        digest.update(buffer, 0, n);
                    }
                }
                if (count != job.getLong("size")) throw new CaptureApi.Hold(CaptureRules.REVIEW);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                job.put("sha", hex.toString()); AutoCapture.save(c, job, "uploading");
            }
            // Create/reuse the exact lead call BEFORE uploading; a stale/non-owned lead blocks audio.
            if (!job.optBoolean("onLead")) {
                try {
                    JSONObject call = api.json("POST", "/v1/lead-desk/recording-call", new JSONObject()
                        .put("command_id", id).put("number", job.getString("number"))
                        .put("started_at", CallLogRules.iso(job.getLong("stamp")))
                        .put("duration_seconds", job.getLong("seconds")), id, false);
                    job.put("leadId", call.getString("lead_id")).put("eventId", call.getString("event_id"));
                } catch (CaptureApi.Failure refused) {
                    // "Upload anyway" on a call refused for work hours (AutoCapture.uploadAnyway): the recording goes
                    // on its lead as a new call, as the web's "Assign to a lead" puts it (no hours rule there).
                    if (!job.optBoolean("anyway") || !CaptureRules.REFUSED_HOURS.equals(CaptureRules.refused(refused.code, refused.detail))) throw refused;
                    JSONObject lead = api.leadMatch(job.getString("number"));
                    if (lead == null) throw new CaptureApi.Hold(CaptureRules.REVIEW);
                    job.put("leadId", lead.getString("lead_id")).put("leadName", lead.optString("name", job.optString("leadName", "lead")))
                        .put("onLead", true).remove("eventId");
                    AutoCapture.log(id, "anyway: on the lead, no call event");
                }
                AutoCapture.save(c, job, job.optString("state", "uploading"));
            }
            String sid = AutoCapture.submission(job);
            JSONObject progress = null;
            try { progress = api.json("GET", API + sid, null, null, false); }
            catch (CaptureApi.Failure missing) { if (missing.code != 404) throw missing; }
            if (progress == null) {
                policy = gate(c, api, job);
                try (InputStream in = source(c, job)) {
                    progress = api.request("PUT", API + sid + "/source", in, job.getLong("size"),
                        "application/octet-stream", id, true, job.getString("sha"), policy.getString("policy_sha256"));
                }
            }
            if (!job.getString("sha").equals(progress.optString("source_sha256"))) throw new CaptureApi.Hold(CaptureRules.REVIEW);
            JSONObject attach = new JSONObject().put("command_id", CaptureRules.id(id, "attach", 0)).put("submission_id", sid);
            if (!job.optBoolean("onLead")) attach.put("call_event_id", job.getString("eventId"));
            api.json("POST", "/v1/lead-desk/leads/" + job.getString("leadId") + "/recordings", attach, null, false);
            AutoCapture.log(id, "uploaded");
            if (progress.optBoolean("has_report")) {
                AutoCapture.save(c, job, "report_ready");
                AutoCapture.notice(c, id, "Call with " + job.optString("leadName", "lead") + " analysed", "Its report is ready. Tap to open the lead.", false);
                return Result.success();
            }
            // The server has this call's recording (its hash matched above) and its own rules ended it without a
            // report: a short call (transcript and lead details only), a held plan, a run that failed there. Nothing
            // here is for the seller to upload by hand (FGS session on the Redmi, 3 Nov 2026: a 42 s call held as
            // ai_short_call said "A call needs you · upload its recording by hand" after "uploaded for analysis").
            if (Arrays.asList("failed", "cancelled").contains(progress.optString("local_state")) ||
                Arrays.asList("held", "cancelled", "completed").contains(progress.optString("state"))) {
                AutoCapture.save(c, job, "received");
                AutoCapture.notice(c, id, "Call with " + job.optString("leadName", "lead") + " uploaded", "Tap to open the lead.", false);
                return Result.success();
            }
            if (progress.optBoolean("automatic_progression")) {
                boolean first = !"analysing".equals(job.optString("state"));
                AutoCapture.save(c, job, "analysing");
                if (first) AutoCapture.notice(c, id, "Call with " + job.optString("leadName", "lead") + " uploaded for analysis", "Open the lead to see its report when ready.", false);
                return Result.retry();
            }
            if (!"completed".equals(progress.optString("local_state"))) return Result.retry();
            gate(c, api, job);
            JSONObject plan = api.json("POST", API + sid + "/plan/quote", new JSONObject().put("report_language", "en"), "capture-quote:" + sid, false);
            if (!plan.optBoolean("accepted")) {
                gate(c, api, job);
                api.json("POST", API + sid + "/plan", new JSONObject().put("plan_id", plan.getString("id"))
                    .put("plan_fingerprint", plan.getString("plan_fingerprint"))
                    .put("privacy_revision", plan.getString("privacy_revision")).put("accepted", true),
                    "accept-plan:" + plan.getString("id"), true);
            }
            AutoCapture.save(c, job, "analysing");
            AutoCapture.notice(c, id, "Call with " + job.optString("leadName", "lead") + " uploaded for analysis", "Open the lead to see its report when ready.", false);
            return Result.retry();
        } catch (CaptureApi.Hold hold) {
            AutoCapture.log(id, "held " + hold.reason);
            AutoCapture.hold(c, job, hold.reason);
            return Result.success();
        } catch (CaptureApi.Failure error) {
            // A profile 403 or an allowance 409 holds the call, tells the seller and retries once fixed;
            // only a consent loss turns uploads off (CaptureRules.refusal, stops).
            String reason = AutoCapture.explain(api, error);
            AutoCapture.log(id, "refused " + error.code + " -> " + reason);
            if (CaptureRules.RETRY.equals(reason)) return Result.retry();
            AutoCapture.hold(c, job, reason);
            return Result.success();
        } catch (Exception error) {
            // Only the app's own short codes: a system message can carry the file's path, and so a contact's name.
            String code = error.getMessage();
            if (code == null || code.contains("/") || code.contains(" ") || code.length() > 40) code = "";
            AutoCapture.log(id, "retry " + error.getClass().getSimpleName() + " " + code);
            return Result.retry();
        }
        finally { CaptureNet.end(); }
    }
    /** Waits until {@code due}; false once the seller tapped Undo or the system stopped this job. */
    boolean untilDue(Context c, String id, long due) throws JSONException {
        while (System.currentTimeMillis() < due) {
            if (isStopped() || "cancelled".equals(AutoCapture.job(c, id).optString("state"))) return false;
            android.os.SystemClock.sleep(Math.min(500L, Math.max(1L, due - System.currentTimeMillis())));
        }
        return !isStopped() && !"cancelled".equals(AutoCapture.job(c, id).optString("state"));
    }
}
