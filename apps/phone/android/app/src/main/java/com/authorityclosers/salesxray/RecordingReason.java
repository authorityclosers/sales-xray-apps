package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tells the seller why a call placed from the app has no recording, with the one tap that fixes it
 * (RecordingReasonRules): a centred dialog when the app is in front, otherwise a notification on the
 * "After a call" channel whose tap opens the fix.
 */
final class RecordingReason {

    /** On MainActivity's intent: the fix to open ("folder", "recorder" or "appSettings"). */
    static final String EXTRA_FIX = "sx_recording_fix";
    private static final String PREFS = "sx_recording_reason";
    private static final String LAST = "last_reason";
    private static final String LAST_AT = "last_at";
    /** Not 43 (after a call) or 44 (new recordings). */
    private static final int NOTICE_ID = 45;

    private RecordingReason() {}

    /** For a finished call with no recording: why, in words, with its fix; null when nothing needs saying. */
    static JSONObject check(Context context, JSONObject call) {
        Boolean connected = call.isNull("connected") ? null : call.optBoolean("connected");
        if (!RecordingReasonRules.worthExplaining(connected, call.optLong("startedMs"), call.optLong("endedMs"))) return null;
        JSONObject status = RecorderProfile.status(context);
        RecordingReasonRules.Reason reason = RecordingReasonRules.reason(
            status.optString("kind"),
            status.optString("recording"),
            status.optBoolean("needsAllFiles"),
            status.optBoolean("folderPickSupported"),
            status.optBoolean("canRead", true)
        );
        String[] words = RecordingReasonRules.words(reason);
        String fix = RecordingReasonRules.fix(reason);
        JSONObject missing = new JSONObject();
        try {
            missing.put("reason", reason.name().toLowerCase(Locale.ROOT));
            missing.put("title", words[0]);
            missing.put("body", words[1]);
            missing.put("button", words[2]);
            missing.put("fix", fix == null ? JSONObject.NULL : fix);
        } catch (JSONException ignored) {}
        return missing;
    }

    /** Once per reason every 6 hours: the dialog with the app in front, otherwise the notification. */
    static void prompt(AppCompatActivity activity, JSONObject missing, Runnable pickFolder) {
        if (missing == null || activity.isFinishing() || activity.isDestroyed()) return;
        RecordingReasonRules.Reason reason;
        try {
            reason = RecordingReasonRules.Reason.valueOf(missing.optString("reason").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!RecordingReasonRules.ask(reason, prefs.getString(LAST, null), prefs.getLong(LAST_AT, 0), now)) return;
        prefs.edit().putString(LAST, reason.name()).putLong(LAST_AT, now).apply();
        String fix = missing.isNull("fix") ? null : missing.optString("fix");
        String title = missing.optString("title");
        String body = missing.optString("body");
        String button = missing.optString("button");
        if (activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle(title).setMessage(body);
            if (fix == null) {
                dialog.setPositiveButton(button, null);
            } else {
                dialog.setPositiveButton(button, (shown, which) -> fix(activity, fix, pickFolder)).setNegativeButton("Later", null);
            }
            dialog.show();
        } else {
            notify(activity, title, body, fix);
        }
    }

    /** The one tap: the recordings folder picker, the dialler's recording setting, or the app's settings. */
    static void fix(AppCompatActivity activity, String fix, Runnable pickFolder) {
        if ("folder".equals(fix)) pickFolder.run();
        else if ("recorder".equals(fix) || "appSettings".equals(fix)) PhoneSetup.screen(activity, fix);
    }

    private static void notify(Context context, String title, String body, String fix) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(AfterCallNotice.CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(AfterCallNotice.CHANNEL, "After a call", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminds you to log how a call you placed from Sales X-Ray went.");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (fix != null) open.putExtra(EXTRA_FIX, fix);
        PendingIntent pending = PendingIntent.getActivity(context, NOTICE_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AfterCallNotice.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sx)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        try {
            NotificationManagerCompat.from(context).notify(NOTICE_ID, builder.build());
        } catch (SecurityException ignored) {
            // Notifications were turned off after the check.
        }
    }
}
