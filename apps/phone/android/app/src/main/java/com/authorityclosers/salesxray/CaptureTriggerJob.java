package com.authorityclosers.salesxray;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The look right after a call ends, as a JobScheduler job of the app's own (CaptureTrigger has the why and the rules).
 *
 * Mac, 26 Nov 2026 02:06 (Redmi 13, HyperOS, Play 1.0.158, app swiped away): the call-history trigger, then a
 * WorkManager content-URI worker, started at hang-up and was cancelled within 0.2 s ("ENQUEUED; not doing any work
 * and rescheduling for later execution"); the call waited for the 15-minute watch and uploaded 10 minutes late. A
 * process started after the app was swiped away counts as force-stopped to WorkManager, which then cancels and
 * reschedules every job of its own (SystemJobService), the running trigger included, and a content trigger scheduled
 * again waits for the NEXT change. WorkManager never touches a job of another service, so this one survives that
 * start: it hands the look to WorkManager (CaptureTrigger.settled, kept in its database) and arms itself again.
 * Content-URI jobs don't survive a reboot; every look (CaptureWatchWorker) and every app start arms it again.
 */
public class CaptureTriggerJob extends JobService {
    /** Above WorkManager's own range (SalesXrayApp: 0 to 999,999,999). */
    static final int JOB_ID = 1_548_156_929;

    @Override public boolean onStartJob(JobParameters params) {
        Context c = getApplicationContext();
        if (!AutoCapture.prefs(c).getBoolean("on", false)) return false;
        List<Uri> changed = new ArrayList<>();
        if (params.getTriggeredContentUris() != null) changed.addAll(Arrays.asList(params.getTriggeredContentUris()));
        // Too many changes at once: Android names only the authorities.
        boolean calls = CaptureTrigger.callEnded(changed);
        String[] authorities = params.getTriggeredContentAuthorities();
        if (authorities != null) for (String authority : authorities) calls |= CallLog.AUTHORITY.equals(authority);
        final boolean callChanged = calls;
        // Reading the recordings folder is file work: off the main thread.
        new Thread(() -> {
            try { CaptureTrigger.changed(c, callChanged, "job"); }
            catch (RuntimeException unexpected) { AutoCapture.log("trigger", "job error " + unexpected.getClass().getSimpleName()); }
            finally {
                jobFinished(params, false);
                arm(c);
            }
        }, "sx-capture-trigger").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; }

    /** Waits for the next change to the call history or to the phone's audio files; nothing while uploads are off. */
    static void arm(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        if (!AutoCapture.prefs(c).getBoolean("on", false)) { jobs.cancel(JOB_ID); return; }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, CaptureTriggerJob.class))
            .addTriggerContentUri(new JobInfo.TriggerContentUri(CallLog.Calls.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(CaptureTrigger.UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(CaptureTrigger.MAX_DELAY_MS)
            .build();
        try { jobs.schedule(job); }
        catch (RuntimeException refused) { AutoCapture.log("trigger", "arm refused " + refused.getClass().getSimpleName()); }
    }

    static void disarm(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (jobs != null) jobs.cancel(JOB_ID);
    }

    static boolean armed(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        return jobs != null && jobs.getPendingJob(JOB_ID) != null;
    }
}
