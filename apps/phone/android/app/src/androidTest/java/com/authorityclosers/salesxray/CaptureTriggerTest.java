package com.authorityclosers.salesxray;

import static org.junit.Assert.*;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.*;
import java.io.OutputStream;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Fast upload (18 Nov 2026): Android itself wakes capture when a new audio file is indexed, as when the dialler
 * saves a call recording, with no service or polling of ours. Times trigger → run (SXTEST triggerMs).
 * Synthetic silent audio in Music/, deleted afterwards. The 15-minute watch is not involved. Since 1.0.160 the
 * trigger is the app's own job (CaptureTriggerJob), not a WorkManager worker.
 */
@RunWith(AndroidJUnit4.class)
public class CaptureTriggerTest {
    Context c;
    Uri inserted;

    @Before public void setup() throws Exception {
        c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AutoCapture.prefs(c).edit().clear().putBoolean("on", true).commit();
        WorkManager.getInstance(c).cancelAllWorkByTag("sx-auto-capture").getResult().get();
        WorkManager.getInstance(c).pruneWork().getResult().get();
    }

    @After public void cleanup() throws Exception {
        if (inserted != null) c.getContentResolver().delete(inserted, null, null);
        CaptureTriggerJob.disarm(c);
        WorkManager.getInstance(c).cancelAllWorkByTag("sx-auto-capture").getResult().get();
        AutoCapture.prefs(c).edit().clear().commit();
    }

    private WorkInfo.State legacyTrigger() throws Exception {
        WorkInfo.State last = null;
        for (WorkInfo info : WorkManager.getInstance(c).getWorkInfosForUniqueWork(CaptureTrigger.NAME).get()) {
            if (!info.getState().isFinished()) return info.getState();
            last = info.getState();
        }
        return last;
    }

    /** A new audio file, as a dialler saving a call recording; returns how long the trigger took to run (ms). */
    private long newAudioFile() throws Exception {
        SystemClock.sleep(2_000);
        long before = System.currentTimeMillis();
        ContentResolver resolver = c.getContentResolver();
        ContentValues row = new ContentValues();
        row.put(MediaStore.Audio.Media.DISPLAY_NAME, "sx-trigger-test-" + before + ".wav");
        row.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        row.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/sx-trigger-test");
        inserted = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row);
        assertNotNull(inserted);
        try (OutputStream out = resolver.openOutputStream(inserted)) { out.write(new byte[4096]); }
        long seen = 0;
        long until = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < until) {
            seen = AutoCapture.prefs(c).getLong("triggerSeen", 0);
            if (seen >= before) break;
            SystemClock.sleep(250);
        }
        Log.i("SXTEST", "triggerMs " + (seen - before));
        assertTrue("the trigger never ran", seen >= before);
        return seen - before;
    }

    private void armedAgain() {
        long armed = SystemClock.elapsedRealtime() + 10_000;
        while (!CaptureTriggerJob.armed(c) && SystemClock.elapsedRealtime() < armed) SystemClock.sleep(250);
        assertTrue(CaptureTriggerJob.armed(c));
    }

    @Test public void aNewAudioFileWakesCaptureAndTheTriggerArmsAgain() throws Exception {
        CaptureTrigger.arm(c);
        assertTrue(CaptureTriggerJob.armed(c));
        // The WorkManager trigger of builds before 1.0.160 is gone.
        WorkInfo.State legacy = legacyTrigger();
        assertTrue(legacy == null || legacy.isFinished());
        long took = newAudioFile();
        // Within the trigger's own maximum delay plus scheduling slack, not the 15-minute watch.
        assertTrue("trigger took " + took + " ms", took <= CaptureTrigger.MAX_DELAY_MS + 10_000);
        // It waits for the next change again.
        armedAgain();
        // An audio file outside the recordings folder (none is picked here) is no reason for a look.
        assertTrue(WorkManager.getInstance(c).getWorkInfosForUniqueWork(CaptureTrigger.LOOK).get().isEmpty());
    }

    // Mac, 26 Nov 2026 02:06 (Redmi 13, app swiped away): the process that started for the trigger counted as
    // force-stopped to WorkManager, which cancelled every job of its own (SystemJobService), the running trigger
    // included. That clean-up, done here as WorkManager does it, leaves the app's own trigger armed and working.
    @Test public void workManagersForceStopCleanUpLeavesTheTriggerArmed() throws Exception {
        CaptureTrigger.arm(c);
        android.app.job.JobScheduler jobs = c.getSystemService(android.app.job.JobScheduler.class);
        int cancelled = 0;
        for (android.app.job.JobInfo job : jobs.getAllPendingJobs()) {
            if (job.getService().getClassName().equals("androidx.work.impl.background.systemjob.SystemJobService")) {
                jobs.cancel(job.getId()); cancelled++;
            }
        }
        Log.i("SXTEST", "WorkManager jobs cancelled " + cancelled);
        assertTrue(CaptureTriggerJob.armed(c));
        long took = newAudioFile();
        assertTrue("trigger took " + took + " ms", took <= CaptureTrigger.MAX_DELAY_MS + 10_000);
        armedAgain();
    }

    @Test public void uploadsOffArmsNothing() throws Exception {
        AutoCapture.prefs(c).edit().putBoolean("on", false).commit();
        CaptureTrigger.arm(c);
        assertFalse(CaptureTriggerJob.armed(c));
        assertNull(legacyTrigger());
    }
}
