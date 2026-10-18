package com.authorityclosers.salesxray;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;

/**
 * Review alerts (gold review): a new Report is ready for a gold reviewer.
 *
 * Same Firebase set-up and phone address as lead alerts (LeadAlerts.java). A
 * push carries only the assignment's id and a count. How many Reports wait is
 * read from Sales Xray on the reviewer's own session
 * (/v1/sales-xray/review/badge), so nothing about the call passes through
 * Google. Tapping the alert opens that Report's review (/review#call=<id>), or
 * the review list when several came at once. One alert at a time: a newer one
 * replaces it with the new count.
 */
final class ReviewAlerts {

    static final String CHANNEL = "review_alerts";
    private static final int ALERT_ID = 7400;

    private ReviewAlerts() {}

    static void show(Context context, String kind, String assignmentId, int pushed) {
        if (!LeadAlerts.allowed(context)) return;
        String base = LeadAlerts.serverUrl(context);
        JSONObject badge = base == null ? null : LeadAlerts.readJson(base + "/v1/sales-xray/review/badge");
        int waiting = pushed;
        if (badge != null) {
            // No longer a reviewer, or already reviewed: nothing to say.
            if (!badge.optBoolean("reviewer", false)) return;
            waiting = badge.optInt("waiting", 0);
            if (waiting <= 0) return;
        }
        boolean one = ReviewAlertRules.one(kind, assignmentId);
        String path = ReviewAlertRules.path(kind, assignmentId);
        String title = ReviewAlertRules.title(waiting);
        String body = ReviewAlertRules.body(one, waiting);
        channel(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_review)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open(context, path))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (waiting > 0) builder.setNumber(waiting);
        try {
            NotificationManagerCompat.from(context).notify(ALERT_ID, builder.build());
        } catch (SecurityException ignored) {
            // Notifications were turned off after the check; nothing to do.
        }
    }

    private static PendingIntent open(Context context, String path) {
        Intent intent = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LeadAlerts.EXTRA_OPEN, path);
        return PendingIntent.getActivity(context, ALERT_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void channel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Review alerts", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("A new Report is ready for your review.");
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    /** Clears the review alert once the review page is open in the app. */
    static void clear(Context context) {
        NotificationManagerCompat.from(context).cancel(ALERT_ID);
    }
}
