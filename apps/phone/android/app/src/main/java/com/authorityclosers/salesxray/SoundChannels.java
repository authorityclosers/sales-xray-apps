package com.authorityclosers.salesxray;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.ContextCompat;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The picked sound for each moment, on the phone (round-2 board 4, 20 Nov 2026). The picks are read from Sales
 * X-Ray on the person's own session with each alert (/v1/lead-desk/sounds, what plays for them: their own, else
 * the team's, else the library's), so a changed pick sounds on the next alert; the last read is kept for a phone
 * that is offline. Each (moment, sound) has its own channel, made when first needed; the moment's other
 * channels are deleted, so Settings › Notifications shows one per moment. Redmi (MIUI), Samsung and Oppo can
 * still change a channel's sound in their own settings; the page says so.
 */
final class SoundChannels {

    private static final String PREFS = "sx_sounds";
    private static final String PLAYS = "plays";
    private static final String READ_AT = "read_at";

    private SoundChannels() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Keeps what plays for each moment ({"sx.new_lead": "glass-v2.mention", …}); unknown moments are left out. */
    static void keep(Context context, JSONObject plays) {
        JSONObject kept = new JSONObject();
        Iterator<String> keys = plays.keys();
        while (keys.hasNext()) {
            String event = keys.next();
            String choice = plays.optString(event, null);
            if (SoundRules.valid(event, choice)) {
                try {
                    kept.put(event, choice);
                } catch (org.json.JSONException ignored) {
                    // A moment left out plays the library's default.
                }
            }
        }
        prefs(context).edit().putString(PLAYS, kept.toString()).putLong(READ_AT, System.currentTimeMillis()).apply();
    }

    /** From the page's read: {"events": [{"id": …, "plays": …}, …]}. */
    static void keepView(Context context, JSONObject view) {
        JSONArray events = view == null ? null : view.optJSONArray("events");
        if (events == null) return;
        JSONObject plays = new JSONObject();
        for (int index = 0; index < events.length(); index++) {
            JSONObject row = events.optJSONObject(index);
            if (row == null) continue;
            try {
                plays.put(row.optString("id"), row.optString("plays"));
            } catch (org.json.JSONException ignored) {
                // Skipped: its default plays.
            }
        }
        keep(context, plays);
    }

    /** Reads the picks again (off the main thread: a push is); offline, the last ones stay. */
    static void refresh(Context context) {
        String base = LeadAlerts.serverUrl(context);
        if (base == null) return;
        JSONObject view = LeadAlerts.readJson(base + "/v1/lead-desk/sounds");
        if (view != null) keepView(context, view);
    }

    static String plays(Context context, String event) {
        try {
            JSONObject kept = new JSONObject(prefs(context).getString(PLAYS, "{}"));
            return SoundRules.resolve(event, kept.optString(event, null));
        } catch (org.json.JSONException error) {
            return SoundRules.defaultFor(event);
        }
    }

    static Uri soundUri(Context context, String choice) {
        String raw = SoundRules.rawName(choice);
        return raw == null ? null : Uri.parse("android.resource://" + context.getPackageName() + "/raw/" + raw);
    }

    /** The channel for this moment's picked sound, made when first needed; the moment's old ones deleted. */
    static String channel(Context context, String event) {
        String choice = plays(context, event);
        String id = SoundRules.channelId(event, choice);
        if (Build.VERSION.SDK_INT < 26) return id;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return id;
        if (manager.getNotificationChannel(id) == null) {
            NotificationChannel made = new NotificationChannel(id, SoundRules.label(event), NotificationManager.IMPORTANCE_HIGH);
            made.setDescription("Sales X-Ray plays the sound picked in Settings › Sounds & alerts.");
            made.setShowBadge(true);
            if (SoundRules.NONE.equals(choice)) {
                made.setSound(null, null);
            } else {
                Uri sound = soundUri(context, choice);
                if (sound != null) {
                    made.setSound(
                        sound,
                        new AudioAttributes.Builder()
                            .setUsage(
                                SoundRules.INCOMING_CALL.equals(event)
                                    ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                                    : AudioAttributes.USAGE_NOTIFICATION
                            )
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    );
                }
                // "system": the channel keeps the phone's own notification sound.
            }
            made.enableLights(true);
            made.setLightColor(ContextCompat.getColor(context, R.color.notification_accent));
            manager.createNotificationChannel(made);
        }
        // Lead alerts' one old channel: each moment has its own now.
        if (manager.getNotificationChannel(LeadAlerts.CHANNEL) != null) manager.deleteNotificationChannel(LeadAlerts.CHANNEL);
        String prefix = SoundRules.channelPrefix(event);
        for (NotificationChannel old : manager.getNotificationChannels()) {
            if (old.getId().startsWith(prefix) && !old.getId().equals(id)) manager.deleteNotificationChannel(old.getId());
        }
        return id;
    }
}
