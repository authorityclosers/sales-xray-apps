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
            if (path.endsWith("/recording-call")) return new JSONObject().put("lead_id", ID).put("event_id", ID);
            if (path.endsWith("/recordings")) { attaches++; return new JSONObject(); }
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
    /** Android's answer to a foreground start, already given. */
    static final class Done implements com.google.common.util.concurrent.ListenableFuture<Void> {
        final Exception refusal;
        Done(Exception refusal) { this.refusal = refusal; }
        @Override public void addListener(Runnable r, java.util.concurrent.Executor e) { e.execute(r); }
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public Void get() throws java.util.concurrent.ExecutionException {
            if (refusal != null) throw new java.util.concurrent.ExecutionException(refusal);
            return null;
        }
        @Override public Void get(long t, java.util.concurrent.TimeUnit u) throws java.util.concurrent.ExecutionException { return get(); }
    }
    /** Records foreground starts; refuses them as Android 12+ does in the background without the battery exemption. */
    static final class Foreground implements ForegroundUpdater {
        final List<ForegroundInfo> started = new ArrayList<>(); boolean refuse;
        @Override public com.google.common.util.concurrent.ListenableFuture<Void> setForegroundAsync(Context context, UUID id, ForegroundInfo info) {
            if (refuse) return new Done(new IllegalStateException("ForegroundServiceStartNotAllowedException"));
            started.add(info);
            return new Done(null);
        }
    }
    SeededWorker worker(Foreground foreground) {
        return TestWorkerBuilder.from(c, SeededWorker.class, Executors.newSingleThreadExecutor())
            .setInputData(new Data.Builder().putString("id", ID).build()).setForegroundUpdater(foreground).build();
    }
    @Test public void bytesMoveInAQuietDataSyncForegroundService() throws Exception {
        Foreground foreground = new Foreground();
        worker(foreground).doWork();
        assertEquals(1, fake.uploads); assertEquals(1, foreground.started.size());
        ForegroundInfo info = foreground.started.get(0);
        if (android.os.Build.VERSION.SDK_INT >= 29)
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.getForegroundServiceType());
        android.app.Notification n = info.getNotification();
        assertEquals("Uploading 1 call recording", n.extras.getString(android.app.Notification.EXTRA_TITLE));
        assertEquals("auto_capture", n.getChannelId());
        assertTrue((n.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0);
    }
    @Test public void aRefusedForegroundStartStillUploads() throws Exception {
        Foreground foreground = new Foreground(); foreground.refuse = true;
        worker(foreground).doWork();
        assertEquals(1, fake.uploads); assertTrue(foreground.started.isEmpty());
    }
    @Test public void analysisPollsAfterTheUploadAreNotForeground() throws Exception {
        worker().doWork(); worker().doWork();
        assertEquals("analysing", AutoCapture.job(c, ID).getString("state"));
        Foreground foreground = new Foreground();
        worker(foreground).doWork();
        assertTrue(foreground.started.isEmpty());
    }
    @After public void cleanup() { AutoCapture.prefs(c).edit().clear().commit(); recording.delete(); }
    @Test public void uploadReconcilesAfterRestartAndAcceptsOneStandingPlan() throws Exception {
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(0, fake.plans);
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(1, fake.plans);
        fake.report = true;
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(1, fake.plans);
        assertEquals("report_ready", AutoCapture.job(c, ID).getString("state"));
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
    @Test public void terminalFileChecksHoldWithoutStartingAPlanOrRepeatingUpload() throws Exception {
        worker().doWork(); assertEquals(1, fake.uploads);
        fake.held = true; worker().doWork();
        assertEquals("held", AutoCapture.job(c, ID).getString("state"));
        worker().doWork(); assertEquals(1, fake.uploads); assertEquals(0, fake.plans);
    }
    @Test public void undoMeansNoUploadOrPlan() throws Exception {
        new CaptureUndoReceiver().onReceive(c, new android.content.Intent().putExtra("id", ID));
        worker().doWork(); assertEquals(0, fake.uploads); assertEquals(0, fake.plans);
        assertEquals("cancelled", AutoCapture.job(c, ID).getString("state"));
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
