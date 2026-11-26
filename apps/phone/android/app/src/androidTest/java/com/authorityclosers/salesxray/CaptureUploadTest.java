package com.authorityclosers.salesxray;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.*;
import androidx.work.testing.TestWorkerBuilder;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Seeded synthetic file + mocked own API. SAF and OEM dialler behavior require real-phone proof. */
@RunWith(AndroidJUnit4.class)
public class CaptureUploadTest {
    static FakeApi fake;
    static File recording;
    Context c;
    static final String ID = "00000000-0000-4000-8000-000000000001";
    static final String HASH = String.join("", Collections.nCopies(64, "a"));
    static final String SCOPE = "00000000-0000-4000-8000-000000000002:00000000-0000-4000-8000-000000000003";

    public static class SeededWorker extends CaptureUploadWorker {
        public SeededWorker(Context c, WorkerParameters p) { super(c, p); }
        @Override CaptureApi api(Context c) { return fake; }
        @Override InputStream source(Context c, JSONObject job) throws Exception { return new FileInputStream(recording); }
    }
    static class FakeApi extends CaptureApi {
        int uploads, plans, attaches; boolean consent = true, report, uploaded, loseReply, otherWorkspace, held;
        String sha;
        // A refusal before any byte is accepted, as the edge and the API send them (Redmi test, 30 Oct 2026).
        int refuse; String refusal = ""; boolean profileComplete = true; Boolean minutes = true;
        // v1.99.0 refuses any call outside the workspace's work hours at /recording-call (Mac, 26 Nov 2026 02:04).
        boolean outsideHours; int recordingCalls, matches; JSONObject lastAttach;
        FakeApi(Context c) throws Exception { super(c); }
        @Override JSONObject book() throws Exception {
            return new JSONObject().put("organisation_workspace", true).put("tenant_id", SCOPE.split(":")[0])
                .put("person_id", otherWorkspace ? ID : SCOPE.split(":")[1]).put("numbers", new JSONArray().put("+12025550123"));
        }
        @Override JSONObject policy() throws Exception { return new JSONObject().put("policy_sha256", HASH); }
        @Override JSONObject consent() throws Exception { return new JSONObject().put("state", consent ? "on" : "off").put("can_start", consent).put("terms_sha256", HASH); }
        @Override boolean eligible() { return profileComplete; }
        @Override Boolean minutesLeft() { return minutes; }
        @Override JSONObject json(String method, String path, JSONObject body, String key, boolean standing) throws Exception {
            if (path.startsWith("/v1/lead-desk/leads/match?phone=")) {
                assertEquals("/v1/lead-desk/leads/match?phone=%2B12025550123", path); matches++;
                return new JSONObject().put("found", true).put("yours", true).put("lead_id", ID).put("name", "Rohan Test");
            }
            if (path.endsWith("/recording-call")) {
                recordingCalls++;
                if (outsideHours) throw new Failure(409, "This call is outside work hours.");
                return new JSONObject().put("lead_id", ID).put("event_id", ID);
            }
            if (path.endsWith("/recordings")) { attaches++; lastAttach = body; return new JSONObject(); }
            if (path.endsWith("/plan/quote")) return new JSONObject().put("id", ID).put("plan_fingerprint", HASH).put("privacy_revision", "sales-xray-processing-plan-v1");
            if (path.endsWith("/plan")) { assertTrue(standing); plans++; return new JSONObject(); }
            if (!uploaded) throw new Failure(404);
            return new JSONObject().put("source_sha256", sha).put("local_state", "completed").put("has_report", report).put("state", held ? "held" : "processing");
        }
        @Override JSONObject request(String method, String path, InputStream in, long size, String mime, String key, boolean standing, String hash, String policy) throws Exception {
            assertEquals("PUT", method); assertTrue(standing); assertEquals(HASH, policy);
            if (refuse != 0) throw new Failure(refuse, refusal);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            assertEquals(size, bytes.size()); uploads++; uploaded = true; sha = hash;
            if (loseReply) { loseReply = false; throw new IOException("lost_response_after_acceptance"); }
            return new JSONObject().put("source_sha256", sha).put("local_state", "running");
        }
    }
    @Before public void setup() throws Exception {
        c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AutoCapture.prefs(c).edit().clear().putBoolean("on", true).putBoolean("wifiOnly", false)
            .putString("identity", SCOPE).putLong("since", System.currentTimeMillis() - 3600000).commit();
        recording = new File(c.getCacheDir(), "Call recording 12025550123_261029120000.wav");
        try (OutputStream out = new FileOutputStream(recording)) { out.write(new byte[32044]); }
        fake = new FakeApi(c);
        long stamp = System.currentTimeMillis() - 120000;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyMMddHHmmss", Locale.ROOT);
        String name = "Call recording 12025550123_" + format.format(new Date(stamp)) + ".wav";
        stamp = RecordingMatch.read(name, TimeZone.getDefault()).stampMs;
        JSONObject job = new JSONObject().put("id", ID).put("identity", SCOPE).put("name", name)
            .put("source", recording.toURI().toString()).put("stamp", stamp).put("saved", stamp + 10000)
            .put("size", recording.length()).put("seconds", 10).put("number", "+12025550123").put("due", 0).put("state", "waiting");
        AutoCapture.prefs(c).edit().putString("job:" + ID, job.toString()).commit();
    }
    SeededWorker worker() {
        return TestWorkerBuilder.from(c, SeededWorker.class, Executors.newSingleThreadExecutor())
            .setInputData(new Data.Builder().putString("id", ID).build()).build();
    }
    @After public void cleanup() { AutoCapture.prefs(c).edit().clear().commit(); recording.delete(); }
    @Test public void uploadReconcilesAfterRestartAndAcceptsOneStandingPlan() throws Exception {
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(0, fake.plans);
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(1, fake.plans);
        fake.report = true;
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(1, fake.plans);
        assertEquals("report_ready", AutoCapture.job(c, ID).getString("state"));
    }
    // Mac, 26 Nov 2026 02:16 (Redmi, 1.0.158): the run that follows the analysis said "uploading" again and looked like
    // a second upload. It sends no bytes and the call stays "analysing" (Settings showed "Uploading" meanwhile).
    @Test public void followingTheAnalysisNeitherUploadsAgainNorSaysUploading() throws Exception {
        worker().doWork(); worker().doWork();
        assertEquals("analysing", AutoCapture.job(c, ID).getString("state"));
        worker().doWork();
        assertEquals(1, fake.uploads);
        assertEquals("analysing", AutoCapture.job(c, ID).getString("state"));
    }
    @Test public void noConsentMeansNoUploadOrPlan() throws Exception {
        fake.consent = false; worker().doWork(); assertEquals(0, fake.uploads); assertEquals(0, fake.plans);
        assertFalse(AutoCapture.prefs(c).getBoolean("on", true));
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
    }
    @Test public void lostUploadResponseReconcilesWithoutASecondPut() throws Exception {
        fake.loseReply = true;
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(0, fake.plans);
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(1, fake.plans);
    }
    @Test public void anotherWorkspaceMeansNoAudioLeavesThePhone() throws Exception {
        fake.otherWorkspace = true; worker().doWork();
        assertEquals(0, fake.uploads); assertEquals(0, fake.plans);
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
    }
    // The server held the uploaded call by its own rules (a short call): received, never "upload it by hand".
    @Test public void aCallTheServerEndsAfterItsUploadIsReceivedNotHeld() throws Exception {
        android.app.NotificationManager manager = c.getSystemService(android.app.NotificationManager.class);
        manager.cancelAll();
        worker().doWork(); assertEquals(1, fake.uploads);
        fake.held = true; worker().doWork();
        assertEquals("received", AutoCapture.job(c, ID).getString("state"));
        assertFalse(AutoCapture.job(c, ID).has("hold"));
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(0, fake.plans);
        // No "A call needs you" notice for it.
        for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications())
            assertNotEquals(String.valueOf(n.getNotification().extras.getCharSequence("android.title")), "A call needs you");
    }
    @Test public void undoMeansNoUploadOrPlan() throws Exception {
        new CaptureUndoReceiver().onReceive(c, new android.content.Intent().putExtra("id", ID));
        worker().doWork(); assertEquals(0, fake.uploads); assertEquals(0, fake.plans);
        assertEquals("cancelled", AutoCapture.job(c, ID).getString("state"));
    }
    void dueIn(long ms) throws Exception {
        AutoCapture.prefs(c).edit().putString("job:" + ID, AutoCapture.job(c, ID).put("due", System.currentTimeMillis() + ms).toString()).commit();
    }
    // Redmi 13, 25 Nov 2026: "Uploads in 60 seconds" and still waiting 33 minutes later. The job that uploads waits
    // the Undo minute out itself, then uploads in the same run.
    @Test public void aCallInItsUndoMinuteUploadsOnceTheMinuteIsOver() throws Exception {
        dueIn(2_000);
        long due = AutoCapture.job(c, ID).getLong("due"), started = System.currentTimeMillis();
        worker().doWork();
        assertTrue(System.currentTimeMillis() >= due);
        assertTrue(System.currentTimeMillis() - started < 20_000);
        assertEquals(1, fake.uploads);
        assertNotEquals("waiting", AutoCapture.job(c, ID).getString("state"));
    }
    @Test public void undoDuringTheMinuteMeansNoUpload() throws Exception {
        dueIn(3_000);
        Thread undo = new Thread(() -> {
            android.os.SystemClock.sleep(800);
            new CaptureUndoReceiver().onReceive(c, new android.content.Intent().putExtra("id", ID));
        });
        undo.start();
        assertEquals(ListenableWorker.Result.success(), worker().doWork());
        undo.join();
        assertEquals(0, fake.uploads);
        assertEquals("cancelled", AutoCapture.job(c, ID).getString("state"));
    }
    // Detection to upload with nothing but WorkManager in between: enqueue as a scan does, and the system runs it.
    @Test public void aDetectedCallUploadsByItselfThroughWorkManager() throws Exception {
        dueIn(2_000);
        WorkManager work = WorkManager.getInstance(c);
        try {
            AutoCapture.enqueue(c, ID, 2_000, SeededWorker.class);
            long until = System.currentTimeMillis() + 90_000;
            while (fake.uploads == 0 && System.currentTimeMillis() < until) Thread.sleep(250);
            assertEquals(1, fake.uploads);
        } finally {
            work.cancelUniqueWork("sx-capture:" + ID).getResult().get();
        }
    }
    // A waiting call whose job is gone (dropped, cancelled, ended) gets one again from the next look or app open.
    @Test public void aWaitingCallWhoseJobIsGoneGetsItBack() throws Exception {
        dueIn(3_600_000); // far off: the real worker stays queued in this test
        WorkManager work = WorkManager.getInstance(c);
        try {
            work.cancelUniqueWork("sx-capture:" + ID).getResult().get();
            assertEquals(1, AutoCapture.flush(c));
            assertEquals(1, pending(work));
            // KEEP: a second flush leaves the queued job alone.
            AutoCapture.flush(c);
            assertEquals(1, pending(work));
        } finally {
            work.cancelUniqueWork("sx-capture:" + ID).getResult().get();
        }
    }
    int pending(WorkManager work) throws Exception {
        int pending = 0;
        for (int i = 0; i < 20; i++) {
            pending = 0;
            for (WorkInfo info : work.getWorkInfosForUniqueWork("sx-capture:" + ID).get()) if (!info.getState().isFinished()) pending++;
            if (pending > 0) return pending;
            Thread.sleep(100);
        }
        return pending;
    }
    // A held call's own notice says it is not uploaded (it said "Uploads in 60 seconds" before).
    @Test public void aHeldCallsOwnNoticeSaysItIsNotUploaded() throws Exception {
        android.app.NotificationManager manager = c.getSystemService(android.app.NotificationManager.class);
        manager.cancelAll();
        fake.otherWorkspace = true; worker().doWork();
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
        boolean told = false;
        for (int i = 0; i < 20 && !told; i++) {
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications())
                told |= n.getId() == ID.hashCode() && String.valueOf(n.getNotification().extras.getCharSequence("android.title")).endsWith("not uploaded yet");
            if (!told) Thread.sleep(100);
        }
        assertTrue(told);
        manager.cancelAll();
    }
    /** A held call goes back in the queue (as the watch does) and runs here, not in WorkManager. */
    void releaseAndRun() throws Exception {
        JSONObject job = AutoCapture.job(c, ID).put("due", System.currentTimeMillis() + 3_600_000);
        AutoCapture.prefs(c).edit().putString("job:" + ID, job.toString()).commit();
        AutoCapture.release(c, fake, SCOPE, System.currentTimeMillis());
        WorkManager.getInstance(c).cancelUniqueWork("sx-capture:" + ID).getResult().get();
        assertEquals("waiting", AutoCapture.job(c, ID).getString("state"));
        AutoCapture.prefs(c).edit().putString("job:" + ID, AutoCapture.job(c, ID).put("due", 0).toString()).commit();
        worker().doWork();
    }
    @Test public void profileRefusalHoldsKeepsUploadsOnAndUploadsOnceComplete() throws Exception {
        fake.refuse = 403; fake.refusal = "Complete your Sales Xray contact profile to continue."; fake.profileComplete = false;
        worker().doWork();
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
        assertEquals(CaptureRules.PROFILE, AutoCapture.job(c, ID).getString("hold"));
        assertTrue(AutoCapture.prefs(c).getBoolean("on", false));
        assertEquals(CaptureRules.PROFILE, AutoCapture.status(c).getString("health"));
        // Still incomplete: the watch leaves it held.
        AutoCapture.release(c, fake, SCOPE, System.currentTimeMillis());
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
        fake.refuse = 0; fake.profileComplete = true;
        releaseAndRun();
        assertEquals(1, fake.uploads);
        assertEquals("ok", AutoCapture.status(c).getString("health"));
    }
    @Test public void allowanceRefusalHoldsAndUploadsOnceMinutesExist() throws Exception {
        fake.refuse = 409; fake.refusal = "Your current free call allowance has been used."; fake.minutes = false;
        worker().doWork();
        assertEquals(0, fake.uploads);
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
        assertEquals(CaptureRules.ALLOWANCE, AutoCapture.job(c, ID).getString("hold"));
        assertTrue(AutoCapture.prefs(c).getBoolean("on", false));
        assertEquals(CaptureRules.ALLOWANCE, AutoCapture.status(c).getString("health"));
        fake.refuse = 0; fake.minutes = true;
        releaseAndRun();
        assertEquals(1, fake.uploads);
    }
    @Test public void signedOutHoldsWithoutTurningUploadsOff() throws Exception {
        fake.refuse = 401;
        worker().doWork();
        assertEquals(CaptureRules.SIGNED_OUT, AutoCapture.job(c, ID).getString("hold"));
        assertTrue(AutoCapture.prefs(c).getBoolean("on", false));
        fake.refuse = 0;
        releaseAndRun();
        assertEquals(1, fake.uploads);
    }
    @Test public void callsMadeBeforeUploadsWereTurnedOnAgainStillUpload() throws Exception {
        // Turning uploads off and on moved "since" past this call; the job made before still goes.
        AutoCapture.prefs(c).edit().putLong("since", System.currentTimeMillis()).commit();
        worker().doWork();
        assertEquals(1, fake.uploads);
    }
    @Test public void anOnlinePhoneNeedsNoWaitForTheNetwork() {
        assertEquals(CaptureRules.NET_OK, CaptureNet.state(c));
        long started = android.os.SystemClock.elapsedRealtime();
        assertTrue(CaptureNet.await(c, worker()));
        assertTrue(android.os.SystemClock.elapsedRealtime() - started < 1000);
    }
    @Test public void aRefusedRecordingIsToldOnceAndNeverPostedAgain() throws Exception {
        android.app.NotificationManager manager = c.getSystemService(android.app.NotificationManager.class);
        int notice = ("refused:" + ID).hashCode();
        CaptureApi.Failure hours = new CaptureApi.Failure(409, "This call is outside work hours.");
        assertTrue(AutoCapture.refused(c, ID, "+12025550123", hours));
        assertTrue(AutoCapture.prefs(c).getString("refused:" + ID, "").startsWith(CaptureRules.REFUSED_HOURS + ":"));
        assertTrue(shown(manager, notice));
        manager.cancel(notice);
        // Android takes the cancel in its own time: wait for it, so the check below can't see the first notice.
        assertTrue(gone(manager, notice));
        // The next scan meets the same file: skipped, and not told again.
        assertTrue(AutoCapture.refused(c, ID, "+12025550123", hours));
        assertFalse(shown(manager, notice));
        // Another workspace's call: not remembered, the next scan may take it.
        assertFalse(AutoCapture.refused(c, "other", "+12025550123",
            new CaptureApi.Failure(409, "This call belongs to another workspace or seller.")));
        assertFalse(AutoCapture.prefs(c).contains("refused:other"));
        AutoCapture.forgetOldRefusals(c, System.currentTimeMillis());
        assertTrue(AutoCapture.prefs(c).contains("refused:" + ID));
        AutoCapture.forgetOldRefusals(c, System.currentTimeMillis() + CaptureRules.KEEP_MS + 60_000);
        assertFalse(AutoCapture.prefs(c).contains("refused:" + ID));
        manager.cancelAll();
    }
    // Mac, 25 Nov 2026 19:20: "Upload anyway" on an outside-work-hours notice forgets the refusal, so the next look asks
    // the server again (same terms); the notice goes. (Uploads off here, so no real look starts in this test.)
    @Test public void uploadAnywayForgetsTheRefusalAndClearsItsNotice() throws Exception {
        android.app.NotificationManager manager = c.getSystemService(android.app.NotificationManager.class);
        int notice = ("refused:" + ID).hashCode();
        assertTrue(AutoCapture.refused(c, ID, "+12025550123", new CaptureApi.Failure(409, "This call is outside work hours.")));
        assertTrue(shown(manager, notice));
        AutoCapture.prefs(c).edit().putBoolean("on", false).commit();
        new CaptureAgainReceiver().onReceive(c, new android.content.Intent().putExtra("id", ID));
        assertFalse(AutoCapture.prefs(c).contains("refused:" + ID));
        // No recording kept (a refusal from 1.0.158): the tap is remembered for the look that meets the call again.
        assertTrue(AutoCapture.prefs(c).contains("asked:" + ID));
        assertTrue(gone(manager, notice));
        manager.cancelAll();
    }
    /** The seeded job as the scan saw it before the server refused it: what refused() keeps for "Upload anyway". */
    JSONObject foundBeforeRefusal() throws Exception {
        JSONObject found = new JSONObject(AutoCapture.job(c, ID).toString());
        found.remove("id"); found.remove("state"); found.remove("due");
        AutoCapture.prefs(c).edit().remove("job:" + ID).commit();
        return found;
    }
    static boolean titled(android.app.NotificationManager manager, int id, String title) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications())
                if (n.getId() == id && title.equals(String.valueOf(n.getNotification().extras.getCharSequence("android.title")))) return true;
            Thread.sleep(100);
        }
        return false;
    }
    // Mac, 26 Nov 2026 02:04, Redmi 13 on 1.0.158: "tapping Upload anyway does NOTHING" (v1.99.0 refused the call again).
    // Now the kept recording uploads through WorkManager and goes on its lead with no call event; the notice names the
    // lead, not the number (Mac 01:45).
    @Test public void uploadAnywayUploadsACallTheServerCallsOutsideWorkHours() throws Exception {
        android.app.NotificationManager manager = c.getSystemService(android.app.NotificationManager.class);
        manager.cancelAll();
        fake.outsideHours = true;
        assertTrue(AutoCapture.refused(c, fake, ID, "+12025550123", new CaptureApi.Failure(409, "This call is outside work hours."), foundBeforeRefusal()));
        assertTrue(titled(manager, ("refused:" + ID).hashCode(), "Call with Rohan Test not uploaded"));
        assertTrue(AutoCapture.prefs(c).contains("anyway:" + ID));
        WorkManager work = WorkManager.getInstance(c);
        try {
            assertTrue(AutoCapture.uploadAnyway(c, ID, SeededWorker.class));
            assertFalse(AutoCapture.prefs(c).contains("refused:" + ID));
            assertFalse(AutoCapture.prefs(c).contains("anyway:" + ID));
            long until = System.currentTimeMillis() + 90_000;
            while (fake.attaches == 0 && System.currentTimeMillis() < until) Thread.sleep(250);
            assertEquals(1, fake.uploads);
            assertEquals(1, fake.attaches);
            assertFalse(fake.lastAttach.has("call_event_id"));
            assertEquals(ID, AutoCapture.job(c, ID).getString("leadId"));
            assertTrue(AutoCapture.job(c, ID).optBoolean("onLead"));
            assertEquals("Rohan Test", AutoCapture.job(c, ID).getString("leadName"));
        } finally {
            work.cancelUniqueWork("sx-capture:" + ID).getResult().get();
            manager.cancelAll();
        }
        // A retry never asks for the call again, and keeps the same submission.
        int asked = fake.recordingCalls;
        String sid = AutoCapture.submission(AutoCapture.job(c, ID));
        AutoCapture.prefs(c).edit().putString("job:" + ID, AutoCapture.job(c, ID).put("state", "uploading").toString()).commit();
        worker().doWork();
        assertEquals(asked, fake.recordingCalls);
        assertEquals(1, fake.uploads);
        assertEquals(sid, AutoCapture.submission(AutoCapture.job(c, ID)));
    }
    // Once the server takes a lead call at any hour (v1.99.1), "Upload anyway" joins the call itself: no second call.
    @Test public void uploadAnywayJoinsTheCallWhenTheServerTakesIt() throws Exception {
        assertTrue(AutoCapture.refused(c, fake, ID, "+12025550123", new CaptureApi.Failure(409, "This call is outside work hours."), foundBeforeRefusal()));
        AutoCapture.prefs(c).edit().putString("job:" + ID, new JSONObject(AutoCapture.prefs(c).getString("anyway:" + ID, "{}"))
            .put("id", ID).put("anyway", true).put("state", "waiting").put("due", 0).toString()).commit();
        worker().doWork();
        assertEquals(1, fake.recordingCalls);
        assertEquals(1, fake.uploads);
        assertEquals(ID, fake.lastAttach.getString("call_event_id"));
        assertFalse(AutoCapture.job(c, ID).optBoolean("onLead"));
        c.getSystemService(android.app.NotificationManager.class).cancelAll();
    }
    /** The real source check, with the own API mocked: a shared recording (ShareIn) is read only from the app's copy. */
    public static class SharedWorker extends CaptureUploadWorker {
        public SharedWorker(Context c, WorkerParameters p) { super(c, p); }
        @Override CaptureApi api(Context c) { return fake; }
    }
    JSONObject sharedJob(File file) throws Exception {
        return AutoCapture.job(c, ID).put("shared", true).put("name", file.getName())
            .put("source", android.net.Uri.fromFile(file).toString()).put("saved", file.lastModified()).put("size", file.length());
    }
    // Owner, 3 Nov 2026: Google's Phone app keeps recordings; the seller shares one, matched to the call.
    @Test public void aSharedRecordingUploadsFromTheAppsOwnCopyOnly() throws Exception {
        File dir = ShareIn.dir(c);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File copy = new File(dir, "00000000-0000-4000-8000-000000000009.wav");
        try (OutputStream out = new FileOutputStream(copy)) { out.write(new byte[32044]); }
        try {
            // No number or clock in the shared name: the call history's number is what counts.
            AutoCapture.prefs(c).edit().putString("job:" + ID, sharedJob(copy).toString()).commit();
            TestWorkerBuilder.from(c, SharedWorker.class, Executors.newSingleThreadExecutor())
                .setInputData(new Data.Builder().putString("id", ID).build()).build().doWork();
            assertEquals(1, fake.uploads);
            // A "shared" job pointing anywhere else is never read.
            fake.uploaded = false; fake.uploads = 0;
            JSONObject elsewhere = sharedJob(recording).put("state", "waiting");
            elsewhere.remove("sha");
            AutoCapture.prefs(c).edit().putString("job:" + ID, elsewhere.toString()).commit();
            TestWorkerBuilder.from(c, SharedWorker.class, Executors.newSingleThreadExecutor())
                .setInputData(new Data.Builder().putString("id", ID).build()).build().doWork();
            assertEquals(0, fake.uploads);
            assertEquals("held", AutoCapture.job(c, ID).getString("state"));
            assertTrue(ShareIn.owns(c, copy));
            assertFalse(ShareIn.owns(c, recording));
            assertFalse(ShareIn.owns(c, new File(dir, "../escape.wav")));
        } finally {
            copy.delete();
        }
    }
    /** Whether the notice has gone within 2 s. */
    static boolean gone(android.app.NotificationManager manager, int id) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            boolean up = false;
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) up |= n.getId() == id;
            if (!up) return true;
            Thread.sleep(100);
        }
        return false;
    }
    /** Whether the notice is up within 2 s (so a notice that stays away takes the full 2 s). */
    static boolean shown(android.app.NotificationManager manager, int id) throws InterruptedException {
        boolean up = false;
        for (int i = 0; i < 20; i++) {
            up = false;
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) up |= n.getId() == id;
            if (up) return true;
            Thread.sleep(100);
        }
        return up;
    }
}
