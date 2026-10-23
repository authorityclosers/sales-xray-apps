package com.authorityclosers.salesxray;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Records phone calls with the microphone (CEO decision 19 Oct: optional, off by
 * default, sideload builds only). Android lets only an accessibility service
 * capture audio while a call is on, so this is one. It holds that ability only
 * while a call is active, reads NO screen content and NO other app's data: its
 * config asks for no window content and for events from Sales Xray alone, and
 * onAccessibilityEvent does nothing.
 *
 * It records only when the seller switched "Record calls in Sales Xray" on in
 * the app AND turned this service on in Android's Accessibility settings. Mostly
 * the seller's side is heard; the other side clearly only on speaker. Files are
 * named "Sales Xray call recording …" so the way each was captured stays visible.
 * The Play build (the play flavour) removes this service from the manifest.
 */
public class CallRecorderService extends AccessibilityService {

    static final String PREFIX = "Sales Xray call recording";
    private static final String CHANNEL = "call_recorder";
    private static final int NOTICE = 7302;
    private static final String PREFS = "sx_call_recorder";
    private static final String WANTED = "wanted";
    private static final String CONSENT = "consent_shown";
    private static final String LAST = "last";
    private static final long MAX_MS = 4L * 60 * 60 * 1000;

    private static volatile boolean connected;
    private static volatile CallRecorderService running;
    private MediaRecorder recorder;
    private File file;
    /** What started this recording: "phone" (a SIM call), "voip" (WhatsApp, Meet…) or "simulated" (Dev builds). */
    private String trigger;
    private AudioManager audio;
    private Object modeListener;
    private TelephonyManager telephony;
    private Object listener;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Is the service in this build's manifest? A Play build has none. */
    static boolean available(Context context) {
        try {
            context.getPackageManager().getServiceInfo(new ComponentName(context, CallRecorderService.class), 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    /** Has the seller turned the service on in Android's Accessibility settings? */
    static boolean enabledInSettings(Context context) {
        String on = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(on)) return false;
        String mine = new ComponentName(context, CallRecorderService.class).flattenToString();
        for (String part : on.split(":")) if (mine.equalsIgnoreCase(part)) return true;
        return false;
    }

    static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        SharedPreferences prefs = prefs(context);
        try {
            status.put("available", available(context));
            status.put("wanted", prefs.getBoolean(WANTED, false));
            status.put("enabled", enabledInSettings(context));
            status.put("running", connected);
            status.put("consentShown", prefs.getBoolean(CONSENT, false));
            status.put("phoneState", ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED);
            status.put("mic", AppRecorder.hasMic(context));
            String last = prefs.getString(LAST, null);
            status.put("last", last == null ? JSONObject.NULL : "app:" + last);
        } catch (JSONException ignored) {}
        return status;
    }

    static void setWanted(Context context, boolean on) {
        prefs(context).edit().putBoolean(WANTED, on).apply();
    }

    static void consentShown(Context context) {
        prefs(context).edit().putBoolean(CONSENT, true).apply();
    }

    /**
     * Dev builds only (SimulateCallReceiver in src/debug): drive the same start and
     * stop as a real call, for phones without a SIM and for the emulator test.
     */
    static boolean simulate(boolean start) {
        CallRecorderService service = running;
        if (service == null) return false;
        ContextCompat.getMainExecutor(service).execute(() -> {
            if (start) service.begin("simulated");
            else service.finish();
        });
        return true;
    }

    @Override
    protected void onServiceConnected() {
        connected = true;
        running = this;
        listenForVoipCalls();
        telephony = getSystemService(TelephonyManager.class);
        if (telephony == null || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Executor main = ContextCompat.getMainExecutor(this);
                TelephonyCallback callback = new CallState();
                telephony.registerTelephonyCallback(main, callback);
                listener = callback;
            } else {
                PhoneStateListener legacy = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String number) {
                        onCall(state);
                    }
                };
                telephony.listen(legacy, PhoneStateListener.LISTEN_CALL_STATE);
                listener = legacy;
            }
        } catch (SecurityException error) {
            listener = null;
        }
    }

    private final class CallState extends TelephonyCallback implements TelephonyCallback.CallStateListener {

        @Override
        public void onCallStateChanged(int state) {
            onCall(state);
        }
    }

    private void onCall(int state) {
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) begin("phone");
        else if (state == TelephonyManager.CALL_STATE_IDLE && "phone".equals(trigger)) finish();
    }

    /**
     * WhatsApp, Meet and other internet calls put the phone in "in communication"
     * mode (Android 12+ reports the change). Android lets an accessibility service
     * hear the microphone during such a call, as during a SIM call
     * (developer.android.com, "Sharing audio input"). Same switch, same file kind.
     */
    private void listenForVoipCalls() {
        if (Build.VERSION.SDK_INT < 31) return;
        audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        AudioManager.OnModeChangedListener listener = mode -> {
            if (mode == AudioManager.MODE_IN_COMMUNICATION) begin("voip");
            else if (mode == AudioManager.MODE_NORMAL && "voip".equals(trigger)) finish();
        };
        audio.addOnModeChangedListener(ContextCompat.getMainExecutor(this), listener);
        modeListener = listener;
    }

    private void begin(String why) {
        if (recorder != null || !prefs(this).getBoolean(WANTED, false) || !AppRecorder.hasMic(this)) return;
        File dir = AppRecorder.folder(this);
        if (dir == null) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new Date());
        String kind = "voip".equals(why) ? " (internet call)" : "simulated".equals(why) ? " (simulated)" : "";
        file = new File(dir, PREFIX + " " + stamp + kind + ".m4a");
        trigger = why;
        try {
            startForegroundNotice();
            recorder = Mic.start(this, file, MediaRecorder.AudioSource.VOICE_RECOGNITION, MAX_MS);
        } catch (Exception first) {
            try {
                recorder = Mic.start(this, file, MediaRecorder.AudioSource.MIC, MAX_MS);
            } catch (Exception second) {
                recorder = null;
                trigger = null;
                file.delete();
                stopNotice();
            }
        }
    }

    private void finish() {
        if (recorder == null) return;
        trigger = null;
        boolean saved = Mic.stop(recorder);
        recorder = null;
        if (saved && file != null && file.length() > 0) {
            prefs(this).edit().putString(LAST, file.getName()).apply();
            PhoneBridge.signalActive("recordings");
        } else if (file != null) {
            file.delete();
        }
        file = null;
        stopNotice();
    }

    private void startForegroundNotice() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Call recording in Sales Xray", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notice = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lead)
            .setContentTitle("Sales Xray is recording this call")
            .setContentText("Tell the other person the call is recorded.")
            .setOngoing(true)
            .setUsesChronometer(true)
            .build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTICE, notice);
    }

    private void stopNotice() {
        try {
            stopForeground(true);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing: this service reads no screen content and no app's data.
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        finish();
        if (Build.VERSION.SDK_INT >= 31 && audio != null && modeListener instanceof AudioManager.OnModeChangedListener) {
            audio.removeOnModeChangedListener((AudioManager.OnModeChangedListener) modeListener);
        }
        if (telephony != null && listener != null) {
            if (Build.VERSION.SDK_INT >= 31 && listener instanceof TelephonyCallback) {
                telephony.unregisterTelephonyCallback((TelephonyCallback) listener);
            } else if (listener instanceof PhoneStateListener) {
                telephony.listen((PhoneStateListener) listener, PhoneStateListener.LISTEN_NONE);
            }
        }
        connected = false;
        if (running == this) running = null;
        super.onDestroy();
    }
}
