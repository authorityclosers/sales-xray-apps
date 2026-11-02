package com.authorityclosers.salesxray;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;
import androidx.work.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android 15+ cuts a cached app off the network (netpolicy BLOCKED_REASON_APP_BACKGROUND; Redmi 13, HyperOS,
 * 30 Oct 2026). WorkManager then runs a capture worker inside the cached process, its first request fails in
 * about 9 ms, and the retry loop never ends until the seller opens the app. A JobScheduler job with a network
 * constraint lifts the block for the app while it runs, so a blocked worker starts one (expedited from Android
 * 12) and waits for the network instead of failing. No foreground service: the Play bundle has none (play.yml).
 */
final class CaptureNet {
    static final long WAIT_MS = 90_000L;
    private static final AtomicInteger BUSY = new AtomicInteger();

    static void begin() { BUSY.incrementAndGet(); }
    static void end() { BUSY.decrementAndGet(); }
    static boolean busy() { return BUSY.get() > 0; }

    @SuppressWarnings("deprecation")
    static String state(Context c) {
        ConnectivityManager network = c.getSystemService(ConnectivityManager.class);
        if (network == null) return CaptureRules.NET_OK;
        // getActiveNetwork() is null and the NetworkInfo says BLOCKED while this app's network is cut off.
        NetworkInfo info = network.getActiveNetworkInfo();
        return CaptureRules.netState(info != null,
            info != null && info.getDetailedState() == NetworkInfo.DetailedState.BLOCKED, network.getActiveNetwork() != null);
    }

    /** True once this app may use the network; false when offline or still blocked after WAIT_MS. */
    static boolean await(Context c, ListenableWorker worker) {
        String state = state(c);
        if (CaptureRules.NET_OK.equals(state)) { AutoCapture.networkOk(c); return true; }
        if (CaptureRules.NET_OFFLINE.equals(state)) return false;
        keep(c);
        long until = SystemClock.elapsedRealtime() + WAIT_MS;
        while (SystemClock.elapsedRealtime() < until && !worker.isStopped()) {
            SystemClock.sleep(250);
            if (CaptureRules.NET_OK.equals(state(c))) { AutoCapture.networkOk(c); return true; }
        }
        AutoCapture.networkBlocked(c);
        return false;
    }

    /** A job the system itself runs, which lifts the background block while any capture worker needs it. */
    static void keep(Context c) {
        OneTimeWorkRequest.Builder keeper = new OneTimeWorkRequest.Builder(CaptureNetWorker.class)
            .addTag("sx-auto-capture").setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build());
        // Before Android 12 WorkManager runs expedited work as a foreground service, which this app has none of.
        if (Build.VERSION.SDK_INT >= 31) keeper.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        WorkManager.getInstance(c).enqueueUniqueWork("sx-capture-net", ExistingWorkPolicy.KEEP, keeper.build());
    }
}
