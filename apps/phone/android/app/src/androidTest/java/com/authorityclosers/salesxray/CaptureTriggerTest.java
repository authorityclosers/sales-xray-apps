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
 * Synthetic silent audio in Music/, deleted afterwards. The 15-minute watch is not involved.
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
        WorkManager.getInstance(c).cancelAllWorkByTag("sx-auto-capture").getResult().get();
        AutoCapture.prefs(c).edit().clear().commit();
    }

    private WorkInfo.State trigger() throws Exception {
        WorkInfo.State last = null;
        for (WorkInfo info : WorkManager.getInstance(c).getWorkInfosForUniqueWork(CaptureTrigger.NAME).get()) {
            if (!info.getState().isFinished()) return info.getState();
            last = info.getState();
        }
        return last;
    }

    @Test public void aNewAudioFileWakesCaptureAndTheTriggerArmsAgain() throws Exception {
        CaptureTrigger.arm(c);
        assertEquals(WorkInfo.State.ENQUEUED, trigger());
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
        // Within the trigger's own maximum delay plus scheduling slack, not the 15-minute watch.
        assertTrue("trigger took " + (seen - before) + " ms", seen - before <= CaptureTrigger.MAX_DELAY_MS + 10_000);
        // It waits for the next change again.
        long armed = SystemClock.elapsedRealtime() + 10_000;
        while (trigger() != WorkInfo.State.ENQUEUED && SystemClock.elapsedRealtime() < armed) SystemClock.sleep(250);
        assertEquals(WorkInfo.State.ENQUEUED, trigger());
        // An audio file outside the recordings folder (none is picked here) is no reason for a look.
        assertTrue(WorkManager.getInstance(c).getWorkInfosForUniqueWork(CaptureTrigger.LOOK).get().isEmpty());
    }

    @Test public void uploadsOffArmsNothing() throws Exception {
        AutoCapture.prefs(c).edit().putBoolean("on", false).commit();
        CaptureTrigger.arm(c);
        assertNull(trigger());
    }
}
