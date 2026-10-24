package com.authorityclosers.salesxray;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.HashSet;
import java.util.Set;

/** Plays the app's haptic tokens (HapticRules) and gives our pages window.SXHaptics. */
final class Haptics {

    private Haptics() {}

    /** window.SXHaptics on our pages before their own scripts; PhoneBridge.inject adds it too, for an older WebView. */
    static void install(WebView webView, Set<String> hosts) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        Set<String> origins = new HashSet<>();
        for (String host : hosts) origins.add("https://" + host);
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, HapticRules.script(), origins);
        } catch (RuntimeException ignored) {
            // sx-phone.js's page hook adds it after the page loads.
        }
    }

    /** One token, on the main thread. */
    static void play(Context context, View view, String kind) {
        HapticRules.Feel feel = HapticRules.allowed(HapticRules.feel(kind), silent(context), doNotDisturb(context));
        if (feel == HapticRules.Feel.URGENT && buzz(context)) return;
        view.performHapticFeedback(constant(feel == HapticRules.Feel.URGENT ? HapticRules.Feel.REJECT : feel));
    }

    private static int constant(HapticRules.Feel feel) {
        switch (feel) {
            case CONFIRM:
                return Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
            case LONG_PRESS:
                return HapticFeedbackConstants.LONG_PRESS;
            case REJECT:
                return Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
            default:
                return HapticFeedbackConstants.KEYBOARD_TAP;
        }
    }

    /** Urgent's three pulses, at the phone's notification vibration strength. */
    @SuppressWarnings("deprecation")
    private static boolean buzz(Context context) {
        try {
            Vibrator vibrator = Build.VERSION.SDK_INT >= 31
                ? context.getSystemService(VibratorManager.class).getDefaultVibrator()
                : (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return false;
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            if (Build.VERSION.SDK_INT >= 26) {
                VibrationEffect effect = vibrator.hasAmplitudeControl()
                    ? VibrationEffect.createWaveform(HapticRules.URGENT_TIMINGS, HapticRules.URGENT_AMPLITUDES, -1)
                    : VibrationEffect.createWaveform(HapticRules.URGENT_TIMINGS, -1);
                vibrator.vibrate(effect, attributes);
            } else {
                vibrator.vibrate(HapticRules.URGENT_TIMINGS, -1, attributes);
            }
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean silent(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        return audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
    }

    private static boolean doNotDisturb(Context context) {
        if (Build.VERSION.SDK_INT < 23) return false;
        NotificationManager notices = context.getSystemService(NotificationManager.class);
        if (notices == null) return false;
        int filter = notices.getCurrentInterruptionFilter();
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }
}
