package com.authorityclosers.salesxray;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Records a conversation in Sales X-Ray itself (owner, 19 Oct: "in-app recording
 * as a second way"): a meeting, a walk-in, or anything said near the phone.
 * It runs as a microphone foreground service with a Stop button in the
 * notification, and saves AAC .m4a files in the app's own Recordings folder,
 * which the recordings list shows like any other call recording.
 *
 * It does not start during a phone call: Android gives ordinary apps silence
 * while a call is on (developer.android.com, "Sharing audio input").
 */
public class AppRecorder extends Service {

    static final String FOLDER = "Recordings";
    private static final String CHANNEL = "in_app_recorder";
    private static final int NOTICE = 7301;
    private static final String START = "com.authorityclosers.salesxray.RECORD_START";
    private static final String STOP = "com.authorityclosers.salesxray.RECORD_STOP";
    private static final String PREFS = "sx_recorder";
    private static final String ON = "on";
    private static final String STARTED = "started_ms";
    private static final String FILE = "file";
    private static final String LEAD = "lead";
    private static final String LAST = "last";
    private static final long MAX_MS = 3L * 60 * 60 * 1000;

    private MediaRecorder recorder;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File folder(Context context) {
        File dir = context.getExternalFilesDir(FOLDER);
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir;
    }

    static boolean hasMic(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean inCall(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return false;
        int mode = audio.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    /**
     * This build records conversations: the service is in its manifest. The Play build has the microphone
     * for voice notes (1.0.35) but not this service (src/play/AndroidManifest.xml).
     */
    static boolean available(Context context) {
        try {
            context.getPackageManager().getServiceInfo(new ComponentName(context, AppRecorder.class), 0);
            return Permissions.declared(context, android.Manifest.permission.RECORD_AUDIO);
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static JSONObject state(Context context) {
        SharedPreferences prefs = prefs(context);
        JSONObject state = new JSONObject();
        try {
            state.put("available", available(context));
            boolean on = prefs.getBoolean(ON, false);
            state.put("recording", on);
            state.put("startedMs", on ? prefs.getLong(STARTED, 0) : JSONObject.NULL);
            state.put("leadId", on ? prefs.getString(LEAD, "") : JSONObject.NULL);
            state.put("mic", hasMic(context));
            state.put("inCall", inCall(context));
            String last = prefs.getString(LAST, null);
            state.put("last", last == null ? JSONObject.NULL : "app:" + last);
        } catch (JSONException ignored) {}
        return state;
    }

    /** "started", "no_mic", "in_call" or "busy". */
    static String start(Context context, String leadId) {
        if (!available(context)) return "unavailable";
        if (!hasMic(context)) return "no_mic";
        if (inCall(context)) return "in_call";
        if (prefs(context).getBoolean(ON, false)) return "busy";
        Intent intent = new Intent(context, AppRecorder.class).setAction(START).putExtra(LEAD, leadId == null ? "" : leadId);
        ContextCompat.startForegroundService(context, intent);
        return "started";
    }

    static void stop(Context context) {
        context.startService(new Intent(context, AppRecorder.class).setAction(STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        if (START.equals(action) && recorder == null) {
            begin(intent.getStringExtra(LEAD));
        } else if (STOP.equals(action)) {
            finish();
        }
        return START_NOT_STICKY;
    }

    private Notification notice() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording in Sales X-Ray", NotificationManager.IMPORTANCE_LOW));
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | immutable
        );
        PendingIntent stop = PendingIntent.getService(
            this,
            1,
            new Intent(this, AppRecorder.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | immutable
        );
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sx)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentTitle("Recording in Sales X-Ray")
            .setContentText("Tap Stop when the conversation ends.")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build();
    }

    private void begin(String leadId) {
        Notification notice = notice();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTICE, notice);
        }
        File dir = folder(this);
        if (dir == null) {
            stopSelf();
            return;
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new Date());
        File file = new File(dir, "Sales Xray recording " + stamp + ".m4a");
        try {
            recorder = Mic.start(this, file, MediaRecorder.AudioSource.MIC, MAX_MS);
            recorder.setOnInfoListener((media, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish();
            });
        } catch (Exception error) {
            release();
            file.delete();
            stopForeground(true);
            stopSelf();
            return;
        }
        prefs(this)
            .edit()
            .putBoolean(ON, true)
            .putLong(STARTED, System.currentTimeMillis())
            .putString(FILE, file.getName())
            .putString(LEAD, leadId == null ? "" : leadId)
            .apply();
    }

    private void finish() {
        SharedPreferences prefs = prefs(this);
        boolean saved = false;
        if (recorder != null) {
            saved = Mic.stop(recorder);
            recorder = null;
        }
        String name = prefs.getString(FILE, null);
        SharedPreferences.Editor edit = prefs.edit().putBoolean(ON, false);
        if (name != null) {
            File dir = folder(this);
            File file = dir == null ? null : new File(dir, name);
            if (saved && file != null && file.length() > 0) edit.putString(LAST, name);
            else if (file != null) file.delete();
        }
        edit.remove(FILE).apply();
        PhoneBridge.signalActive("recordings");
        stopForeground(true);
        stopSelf();
    }

    private void release() {
        if (recorder == null) return;
        try {
            recorder.release();
        } catch (RuntimeException ignored) {}
        recorder = null;
    }

    @Override
    public void onDestroy() {
        if (recorder != null) finish();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
