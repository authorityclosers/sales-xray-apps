package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * "How did the call go?" as a notification, for when another app's after-call
 * screen (Truecaller's, Redmi audit #48) covers Sales Xray after a call placed
 * from a lead. Tapping it brings the app and its after-call sheet back. It goes
 * away as soon as the app is in front again.
 */
final class AfterCallNotice {

    static final String CHANNEL = "after_call";
    private static final int ID = 43;

    private AfterCallNotice() {}

    static void show(Context context, String name) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "After a call", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminds you to log how a call you placed from Sales Xray went.");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pending = PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String who = name == null || name.trim().isEmpty() ? "your lead" : name.trim();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle("How did the call with " + who + " go?")
            .setContentText("Tap to log it in Sales Xray.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            NotificationManagerCompat.from(context).notify(ID, builder.build());
        } catch (SecurityException ignored) {
            // Notifications were turned off after the check; the sheet waits in the app.
        }
    }

    static void clear(Context context) {
        NotificationManagerCompat.from(context).cancel(ID);
    }
}
