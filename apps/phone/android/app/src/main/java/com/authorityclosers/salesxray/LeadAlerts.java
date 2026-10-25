package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.CapConfig;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lead alerts (Sales Xray CRM): a push for a new lead or a call that is due.
 *
 * Firebase starts with the client settings Sales Xray sends when the seller
 * turns alerts on, so no Firebase file lives in this public repo or the APK.
 * A push carries only ids and a count. The words on the alert are read from
 * Sales Xray on the seller's own session, so lead names never pass through
 * Google. Tapping the alert opens that lead; its Call button opens it and
 * dials.
 */
final class LeadAlerts {

    static final String CHANNEL = "lead_alerts";
    /** Nudges back to work and urgent alerts: its own sound (res/raw/sx_urgent), do-not-disturb still applies. */
    static final String URGENT_CHANNEL = "urgent_work";
    static final String EXTRA_OPEN = "sx_open";
    private static final String PREFS = "sx_lead_alerts";
    private static final String CONFIG = "firebase";
    private static final String TOKEN = "token";
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final int SUMMARY_ID = 7300;
    private static final int SAMPLE_ID = 7301;

    private LeadAlerts() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Starts Firebase from the saved client settings; false until alerts were turned on once. */
    static boolean init(Context context) {
        if (!FirebaseApp.getApps(context).isEmpty()) return true;
        String saved = prefs(context).getString(CONFIG, null);
        if (saved == null) return false;
        try {
            JSONObject config = new JSONObject(saved);
            FirebaseApp.initializeApp(context, options(config));
            return true;
        } catch (JSONException | IllegalArgumentException | IllegalStateException error) {
            return false;
        }
    }

    /** Keeps the client settings Sales Xray sent, then starts Firebase with them. */
    static boolean configure(Context context, JSONObject config) {
        try {
            options(config);
        } catch (IllegalArgumentException | JSONException error) {
            return false;
        }
        prefs(context).edit().putString(CONFIG, config.toString()).apply();
        return init(context);
    }

    private static FirebaseOptions options(JSONObject config) throws JSONException {
        String project = config.getString("project_id");
        String app = config.getString("app_id");
        String key = config.getString("api_key");
        String sender = config.getString("sender_id");
        if (project.isEmpty() || app.isEmpty() || key.isEmpty() || sender.isEmpty()) {
            throw new IllegalArgumentException("incomplete");
        }
        return new FirebaseOptions.Builder().setProjectId(project).setApplicationId(app).setApiKey(key).setGcmSenderId(sender).build();
    }

    static String token(Context context) {
        return prefs(context).getString(TOKEN, null);
    }

    static void saveToken(Context context, String token) {
        prefs(context).edit().putString(TOKEN, token).apply();
    }

    static void forget(Context context) {
        prefs(context).edit().remove(TOKEN).apply();
    }

    static boolean allowed(Context context) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * Shows one alert from a push: ids in, words read from Sales Xray, or plain words offline. Urgency
     * "urgent" (in the push or the words) puts it on the Urgent work channel (AlertRules).
     */
    static void show(Context context, String kind, String leadId, int waiting, String urgency) {
        if (!allowed(context)) return;
        boolean one = !"many".equals(kind) && !"nudge".equals(kind) && leadId != null && ID.matcher(leadId).matches();
        String base = serverUrl(context);
        JSONObject words = base == null ? null : read(base + AlertRules.wordsPath(one, leadId, kind));
        boolean urgent = AlertRules.urgent(urgency, words == null ? null : words.optString("urgency", null));
        String title;
        String body;
        String path;
        if (words != null) {
            title = words.optString("title");
            body = words.optString("body");
            path = words.optString("url", "/leads");
        } else if ("nudge".equals(kind)) {
            String[] nudge = AlertRules.nudgeWords();
            title = nudge[0];
            body = nudge[1];
            path = AlertRules.NUDGE_PATH;
        } else if (one) {
            title = "new".equals(kind) ? "New lead waiting" : "A call is due now";
            body = "Tap to open it in Sales X-Ray.";
            path = "/leads#lead=" + leadId;
        } else {
            title = waiting > 1 ? waiting + " leads to call now" : "Leads to call now";
            body = "Tap to see who in Sales X-Ray.";
            path = "/leads";
        }
        if (!path.startsWith("/leads")) path = "/leads";
        if (urgent) urgentChannel(context);
        else channel(context);
        int id = one ? leadId.hashCode() : SUMMARY_ID;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, urgent ? URGENT_CHANNEL : CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sx)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open(context, id, path))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (waiting > 0) builder.setNumber(waiting);
        if (urgent) urgentBefore26(context, builder);
        if (one) builder.addAction(R.drawable.ic_stat_lead, "Call", open(context, id + 1, path + "&call=1"));
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException ignored) {
            // Notifications were turned off after the check; nothing to do.
        }
        PhoneBridge.signalActive("alert");
    }

    /** A sample, so the seller can hear how a normal or an urgent alert sounds (the page's Settings). */
    static boolean sample(Context context, boolean urgent) {
        if (!allowed(context)) return false;
        if (urgent) urgentChannel(context);
        else channel(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, urgent ? URGENT_CHANNEL : CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sx)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(urgent ? "This is an urgent alert" : "This is a lead alert")
            .setContentText(urgent ? "Nudges back to work and urgent alerts sound like this." : "New leads and calls that are due sound like this.")
            .setContentIntent(open(context, SAMPLE_ID, "/leads"))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (urgent) urgentBefore26(context, builder);
        try {
            NotificationManagerCompat.from(context).notify(SAMPLE_ID, builder.build());
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    /** Before Android 8 there are no channels: the alert itself carries the sound and the pulses. */
    @SuppressWarnings("deprecation")
    private static void urgentBefore26(Context context, NotificationCompat.Builder builder) {
        if (Build.VERSION.SDK_INT >= 26) return;
        builder
            .setSound(urgentSound(context), AudioManager.STREAM_NOTIFICATION)
            .setVibrate(HapticRules.URGENT_TIMINGS)
            .setPriority(NotificationCompat.PRIORITY_MAX);
    }

    /** By name, so the channel's sound survives builds that renumber resources. */
    static Uri urgentSound(Context context) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/raw/sx_urgent");
    }

    private static void urgentChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(URGENT_CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(URGENT_CHANNEL, "Urgent work", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Nudges to get back to your leads, and urgent alerts. They have their own sound; Do Not Disturb still applies.");
        channel.setSound(
            urgentSound(context),
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        );
        channel.enableVibration(true);
        channel.setVibrationPattern(HapticRules.URGENT_TIMINGS);
        channel.enableLights(true);
        channel.setLightColor(ContextCompat.getColor(context, R.color.notification_accent));
        channel.setShowBadge(true);
        // Never bypasses do-not-disturb: the seller's quiet time wins (owner, 25 Oct 2026).
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent open(Context context, int request, String path) {
        Intent intent = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, path);
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void channel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Lead alerts", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("A new lead for you, or a call that is due now.");
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    /** The site this app opens (production, or dev in a test build). */
    static String serverUrl(Context context) {
        try {
            String url = CapConfig.loadDefault(context).getServerUrl();
            if (url == null) return null;
            Uri uri = Uri.parse(url);
            if (!"https".equals(uri.getScheme()) || !PhoneBridge.HOSTS.contains(uri.getHost())) return null;
            return "https://" + uri.getHost();
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** The words for a lead alert, or null. */
    private static JSONObject read(String address) {
        JSONObject words = readJson(address);
        return words == null || words.optString("title").isEmpty() ? null : words;
    }

    /** A small JSON read on the signed-in person's session (the web view's cookies), or null. */
    static JSONObject readJson(String address) {
        HttpURLConnection connection = null;
        try {
            String cookies = CookieManager.getInstance().getCookie(address);
            if (cookies == null || cookies.isEmpty()) return null;
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            SessionCookies.attach(connection, cookies);
            connection.setRequestProperty("User-Agent", AppUserAgent.current() + " (alerts)");
            if (connection.getResponseCode() != 200) return null;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                int total = 0;
                while ((count = stream.read(buffer)) > 0 && total < 64 * 1024) {
                    out.write(buffer, 0, count);
                    total += count;
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (Exception error) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Clears this lead's alerts once it is open in the app. */
    static void clear(Context context, String leadId) {
        if (leadId == null || !ID.matcher(leadId).matches()) return;
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.cancel(leadId.hashCode());
        manager.cancel(SUMMARY_ID);
    }
}
