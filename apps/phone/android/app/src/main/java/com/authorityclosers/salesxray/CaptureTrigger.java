package com.authorityclosers.salesxray;

import android.content.Context;
import android.media.AudioManager;
import android.provider.CallLog;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/**
 * The look right after a call ends (owner, 17 Nov 2026: "a call connects, then fast, immediate analysis").
 *
 * Measured on production on 17 Nov: the phone took about 11 minutes after a dialler call ended to upload it,
 * because only the 15-minute watch (CaptureWatchWorker) looked at the folder. Android itself now wakes the app
 * when the call history changes (the call ended) or a new audio file is indexed (the dialler saved the
 * recording): a WorkManager content-URI trigger, which is a JobScheduler job the system runs with the app
 * closed, so there is no listener, service or polling of our own and no battery cost between calls.
 *
 * Each trigger fires once, so it arms itself again. A look then runs once the recording has settled
 * (CaptureRules.SETTLE_MS): a burst of changes keeps pushing it back (REPLACE), and the look itself is the
 * same scan as the 15-minute watch, which stays as the fallback. Nothing here runs unless the seller already
 * turned uploads on; the scan checks the workspace's consent again before any job is made, and the 60-second
 * Undo window is unchanged.
 */
public class CaptureTrigger extends Worker {
    static final String NAME = "sx-auto-capture-trigger";
    static final String LOOK = "sx-auto-capture-settled";
    /** Android batches the changes of one call ending into one run (the call-history row, then the file). */
    static final long UPDATE_DELAY_MS = 3_000L;
    static final long MAX_DELAY_MS = 20_000L;
    /** The first trigger after an install or a turn-on looks this far back for a new recording. */
    static final long LOOK_BACK_MS = 10L * 60 * 1000;

    public CaptureTrigger(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    /** A trigger queued by a build before 1.0.160 (WorkManager): it does its look once, then the job service takes over. */
    @NonNull public Result doWork() {
        Context c = getApplicationContext();
        if (!AutoCapture.prefs(c).getBoolean("on", false)) return Result.success();
        changed(c, callEnded(getTriggeredContentUris()), "work");
        CaptureTriggerJob.arm(c);
        return Result.success();
    }

    /** The call history or the audio files changed (CaptureTriggerJob): a look once things settle, if a call ended or a recording came. */
    static void changed(Context c, boolean callChanged, String from) {
        if (!AutoCapture.prefs(c).getBoolean("on", false)) return;
        long now = System.currentTimeMillis();
        long seen = AutoCapture.prefs(c).getLong("triggerSeen", now - LOOK_BACK_MS);
        AutoCapture.prefs(c).edit().putLong("triggerSeen", now).commit();
        // A change during a call (some diallers index the file when recording starts) waits for the end.
        boolean calling = inCall(c);
        boolean look = !calling && (callChanged || newRecording(c, seen));
        AutoCapture.log("trigger", from + (callChanged ? " call history" : " audio") + (calling ? ", in a call" : "") + (look ? ", look queued" : ", no look"));
        if (look) settled(c);
    }

    /** The call history changed: a call just ended. */
    static boolean callEnded(java.util.List<android.net.Uri> changed) {
        for (android.net.Uri uri : changed) if (CallLog.AUTHORITY.equals(uri.getAuthority())) return true;
        return false;
    }

    /**
     * Another app's audio (a voice note, music) also changes the phone's audio files. Only a new audio file in
     * the seller's recordings folder is worth a look; this reads the folder on the phone, and nothing is sent.
     */
    static boolean newRecording(Context c, long seenMs) {
        for (RecordingFolder.Document doc : AutoCapture.recordings(c, seenMs - 5_000L))
            if (doc.modifiedMs >= seenMs - 5_000L && AutoCapture.audio(doc)) return true;
        return false;
    }

    /**
     * Waits for the next change to the call history or to the phone's audio files: the app's own job
     * (CaptureTriggerJob), since 1.0.160. The WorkManager trigger before it was cancelled by WorkManager itself when
     * a swiped-away app's process started for it (Mac, 26 Nov 2026 02:06); any such trigger still queued goes.
     */
    static void arm(Context c) {
        if (!AutoCapture.prefs(c).getBoolean("on", false)) { CaptureTriggerJob.disarm(c); return; }
        WorkManager.getInstance(c).cancelUniqueWork(NAME);
        CaptureTriggerJob.arm(c);
    }

    /** One look once the newest recording has been still for SETTLE_MS; a later change moves it back. */
    static void settled(Context c) {
        if (!AutoCapture.prefs(c).getBoolean("on", false)) return;
        WorkManager.getInstance(c).enqueueUniqueWork(LOOK, ExistingWorkPolicy.REPLACE,
            new OneTimeWorkRequest.Builder(CaptureWatchWorker.class).addTag("sx-auto-capture")
                .setInitialDelay(CaptureRules.SETTLE_MS + 2_000L, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }

    /** In a phone or VoIP call; needs no permission (READ_PHONE_STATE may be off). */
    static boolean inCall(Context c) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        return audio != null && CaptureRules.inCall(audio.getMode());
    }
}
