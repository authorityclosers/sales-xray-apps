package com.authorityclosers.salesxray;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.work.*;

/** Held by JobScheduler while capture workers in this process wait for, or use, the network (CaptureNet). */
public class CaptureNetWorker extends Worker {
    static final long HOLD_MS = 8L * 60 * 1000;
    static final long IDLE_MS = 5_000L;
    public CaptureNetWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    @NonNull public Result doWork() {
        long end = SystemClock.elapsedRealtime() + HOLD_MS, idle = 0;
        while (!isStopped() && SystemClock.elapsedRealtime() < end) {
            long now = SystemClock.elapsedRealtime();
            if (CaptureNet.busy()) idle = 0;
            else if (idle == 0) idle = now;
            else if (now - idle >= IDLE_MS) break;
            SystemClock.sleep(500);
        }
        return Result.success();
    }
}
