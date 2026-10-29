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
        // the number must still be this seller's lead.
        if (CaptureRules.number(job.getString("name"), job.getLong("saved"), System.currentTimeMillis(),
            0, TimeZone.getDefault(), book) == null)
            throw new CaptureApi.Hold(CaptureRules.REVIEW);
        JSONObject policy = api.policy();
        if (!CaptureRules.consent(api.consent(), policy.optString("policy_sha256"))) throw new CaptureApi.Hold(CaptureRules.CONSENT);
        if (AutoCapture.prefs(c).getBoolean("wifiOnly", true)) {
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
        boolean granted = false;
        for (Uri tree : RecordingFolder.trees(c)) {
            if (uri.getAuthority().equals(tree.getAuthority()) && RecordingFolderRules.inside(DocumentsContract.getTreeDocumentId(tree), DocumentsContract.getDocumentId(uri))) granted = true;
        }
        if (!granted) throw new CaptureApi.Hold(CaptureRules.FOLDER);
        try (Cursor cursor = c.getContentResolver().query(uri, new String[] {
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        }, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.getLong(0) != job.getLong("size") ||
                cursor.getLong(1) != job.getLong("saved")) throw new CaptureApi.Hold(CaptureRules.REVIEW);
        }
        return new FilterInputStream(c.getContentResolver().openInputStream(uri)) {
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
            if (Arrays.asList("cancelled", "held", "report_ready").contains(job.optString("state"))) return Result.success();
            if (System.currentTimeMillis() < job.optLong("due")) return Result.retry();
            if (System.currentTimeMillis() - job.optLong("stamp") > CaptureRules.KEEP_MS) {
                AutoCapture.hold(c, job, CaptureRules.EXPIRED); return Result.success();
            }
            // A cached app has no network on Android 15+ until the system runs a job for it (CaptureNet).
            if (!CaptureNet.await(c, this)) return Result.retry();
            api = api(c);
            JSONObject policy = gate(c, api, job);
            // No bytes before the Undo state has been read again.
            synchronized (AutoCapture.class) {
                if ("cancelled".equals(AutoCapture.job(c, id).optString("state"))) return Result.success();
                AutoCapture.save(c, job, "uploading");
            }
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
            JSONObject call = api.json("POST", "/v1/lead-desk/recording-call", new JSONObject()
                .put("command_id", id).put("number", job.getString("number"))
                .put("started_at", CallLogRules.iso(job.getLong("stamp")))
                .put("duration_seconds", job.getLong("seconds")), id, false);
            job.put("leadId", call.getString("lead_id")).put("eventId", call.getString("event_id"));
            AutoCapture.save(c, job, "uploading");
            String sid = CaptureRules.id(job.getString("identity"), job.getString("eventId"), 0);
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
            api.json("POST", "/v1/lead-desk/leads/" + job.getString("leadId") + "/recordings", new JSONObject()
                .put("command_id", CaptureRules.id(id, "attach", 0)).put("submission_id", sid)
                .put("call_event_id", job.getString("eventId")), null, false);
            if (progress.optBoolean("has_report")) { AutoCapture.save(c, job, "report_ready"); return Result.success(); }
            if (Arrays.asList("failed", "cancelled").contains(progress.optString("local_state")) ||
                Arrays.asList("held", "cancelled", "completed").contains(progress.optString("state"))) {
                AutoCapture.hold(c, job, CaptureRules.REVIEW); return Result.success();
            }
            if (progress.optBoolean("automatic_progression")) { AutoCapture.save(c, job, "analysing"); return Result.retry(); }
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
            AutoCapture.hold(c, job, hold.reason);
            return Result.success();
        } catch (CaptureApi.Failure error) {
            // A profile 403 or an allowance 409 holds the call, tells the seller and retries once fixed;
            // only a consent loss turns uploads off (CaptureRules.refusal, stops).
            String reason = AutoCapture.explain(api, error);
            if (CaptureRules.RETRY.equals(reason)) return Result.retry();
            AutoCapture.hold(c, job, reason);
            return Result.success();
        } catch (Exception error) { return Result.retry(); }
        finally { CaptureNet.end(); }
    }
}
