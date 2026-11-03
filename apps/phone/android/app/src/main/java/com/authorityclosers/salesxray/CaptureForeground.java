package com.authorityclosers.salesxray;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;

/**
 * While a call recording's bytes move, its upload runs as a dataSync foreground service, so the system neither cuts
 * the app's network (HyperOS netpolicy blocked=APP_BACKGROUND, capture gap 3, 30 Oct 2026) nor stops the upload half
 * way. Quiet: the "Automatic call uploads" channel, no sound, gone when the upload ends. Android 12+ refuses the start
 * from the background unless the seller took the app off battery optimisation (the setup checklist asks); the upload
 * then carries on through CaptureNet's job, as before. Play: docs/play/fgs-declaration.md.
 */
final class CaptureForeground {
    static final int ID = 0x5A4C01;
    static final String TITLE = "Uploading 1 call recording";

    private CaptureForeground() {}

    static ForegroundInfo info(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) manager.createNotificationChannel(
            new NotificationChannel("auto_capture", "Automatic call uploads", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new NotificationCompat.Builder(c, "auto_capture")
            .setSmallIcon(R.drawable.ic_stat_sx).setContentTitle(TITLE).setSilent(true).setOngoing(true)
            .setProgress(0, 0, true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Shown at once: the seller sees what runs, even for a short upload.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(c, ID, new Intent(c, MainActivity.class)
                .putExtra(LeadAlerts.EXTRA_OPEN, "/leads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .build();
        return Build.VERSION.SDK_INT >= 29
            ? new ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            : new ForegroundInfo(ID, notification);
    }
}
