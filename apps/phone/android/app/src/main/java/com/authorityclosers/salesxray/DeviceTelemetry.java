package com.authorityclosers.salesxray;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.StatFs;
import android.telephony.TelephonyManager;
import android.view.FrameMetrics;
import android.view.Window;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;
import java.util.ArrayDeque;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The app's own telemetry (1.0.25; owner, 24 Oct 2026: more data to improve the experience). Start
 * times, frames, exits, the device and its state as tiers and codes, kept in memory (at most 50) until
 * the page asks for them with the bridge's "telemetry" call and sends them as sx.* events. The page
 * holds consent B: "telemetryConsent" off empties the buffer, stops collecting and turns Crashlytics
 * off. The app sends nothing by itself, and nothing here names a person, a lead, a number or a call.
 */
final class DeviceTelemetry {

    private static final String PREFS = "sx_telemetry";
    private static final String CONSENT = "consent";
    private static final String LAST_EXIT = "last_exit_ms";
    private static final String PROFILE_DAY = "profile_day";
    private static final String STATE_AT = "state_at_ms";
    private static final String PERMISSIONS = "permissions";
    private static final int MAX_EVENTS = 50;
    private static final long STATE_EVERY_MS = 10 * 60 * 1000L;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<JSONObject> EVENTS = new ArrayDeque<>();
    private static final TelemetryRules.FrameStats FRAMES = new TelemetryRules.FrameStats();
    private static HandlerThread frameThread;
    private static Window.OnFrameMetricsAvailableListener frameListener;
    private static boolean watching;

    private DeviceTelemetry() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Consent B as the page last said; on until it says otherwise, as on the web. */
    static boolean on(Context context) {
        return prefs(context).getBoolean(CONSENT, true);
    }

    static void setConsent(Context context, boolean on) {
        prefs(context).edit().putBoolean(CONSENT, on).apply();
        if (!on) {
            synchronized (LOCK) {
                EVENTS.clear();
                FRAMES.clear();
            }
        }
        CrashReports.setEnabled(context, on);
    }

    private static void record(Context context, String name, JSONObject fields) {
        if (!on(context)) return;
        JSONObject event = new JSONObject();
        try {
            event.put("name", name);
            event.put("fields", fields);
        } catch (JSONException ignored) {}
        synchronized (LOCK) {
            if (EVENTS.size() >= MAX_EVENTS) EVENTS.pollFirst();
            EVENTS.addLast(event);
        }
    }

    /** sx.app_start: cold (process start to first paint) or warm (resume to the next frame). */
    static void start(Context context, boolean cold, long ms, boolean pagesFromApp) {
        JSONObject fields = new JSONObject();
        try {
            fields.put("kind", cold ? "cold" : "warm");
            fields.put("ms", Math.max(0, Math.min(60_000, ms)));
            fields.put("pages", pagesFromApp ? "app" : "live");
        } catch (JSONException ignored) {}
        record(context, "sx.app_start", fields);
    }

    /** Frames while the app is in front (Android 7+): counted, never their content. */
    static void watchFrames(Activity activity) {
        if (!on(activity) || watching) return;
        try {
            if (frameThread == null) {
                frameThread = new HandlerThread("sx-frames");
                frameThread.start();
            }
            if (frameListener == null) {
                frameListener = (window, metrics, dropped) -> {
                    long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
                    synchronized (LOCK) {
                        FRAMES.add(total);
                    }
                };
            }
            activity.getWindow().addOnFrameMetricsAvailableListener(frameListener, new Handler(frameThread.getLooper()));
            watching = true;
        } catch (RuntimeException ignored) {
            // No frame metrics on this phone: the rest still counts.
        }
    }

    /** Back to the background: the session's frames become one sx.app_frames. */
    static void stopFrames(Activity activity) {
        try {
            if (watching && frameListener != null) activity.getWindow().removeOnFrameMetricsAvailableListener(frameListener);
        } catch (RuntimeException ignored) {}
        watching = false;
        JSONObject fields = new JSONObject();
        synchronized (LOCK) {
            if (FRAMES.frames == 0) return;
            try {
                fields.put("frames", Math.min(1_000_000, FRAMES.frames));
                fields.put("slow", Math.min(1_000_000, FRAMES.slow));
                fields.put("frozen", Math.min(100_000, FRAMES.frozen));
                fields.put("p90_ms", FRAMES.p90());
            } catch (JSONException ignored) {}
            FRAMES.clear();
        }
        record(activity, "sx.app_frames", fields);
    }

    /** What the page sends: every waiting event, plus the device and its state when due. */
    static JSONObject drain(Context context) {
        JSONObject result = new JSONObject();
        JSONArray events = new JSONArray();
        if (on(context)) {
            exits(context);
            dueSnapshots(context);
            synchronized (LOCK) {
                while (!EVENTS.isEmpty()) events.put(EVENTS.pollFirst());
            }
        }
        try {
            result.put("on", on(context));
            result.put("events", events);
        } catch (JSONException ignored) {}
        return result;
    }

    private static void dueSnapshots(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        long day = now / (24 * 60 * 60 * 1000L);
        if (prefs.getLong(PROFILE_DAY, -1) != day) {
            record(context, "sx.device_profile", profile(context));
            prefs.edit().putLong(PROFILE_DAY, day).apply();
        }
        if (now - prefs.getLong(STATE_AT, 0) >= STATE_EVERY_MS || now < prefs.getLong(STATE_AT, 0)) {
            record(context, "sx.device_state", state(context));
            record(context, "sx.network_state", network(context));
            prefs.edit().putLong(STATE_AT, now).apply();
        }
        JSONObject permissions = permissions(context);
        String seen = permissions.toString();
        if (!seen.equals(prefs.getString(PERMISSIONS, null))) {
            record(context, "sx.permission_snapshot", permissions);
            prefs.edit().putString(PERMISSIONS, seen).apply();
        }
    }

    /** sx.app_exit for each failed or system-ended run since the last look (Android 11+). */
    private static void exits(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            if (activities == null) return;
            List<ApplicationExitInfo> runs = activities.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            SharedPreferences prefs = prefs(context);
            long last = prefs.getLong(LAST_EXIT, 0);
            long newest = last;
            for (ApplicationExitInfo run : runs) {
                if (run.getTimestamp() <= last) continue;
                newest = Math.max(newest, run.getTimestamp());
                String reason = TelemetryRules.exitReason(run.getReason());
                if (!TelemetryRules.reportable(reason)) continue;
                JSONObject fields = new JSONObject();
                fields.put("reason", reason);
                fields.put("foreground", run.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ? "yes" : "no");
                record(context, "sx.app_exit", fields);
            }
            if (newest != last) prefs.edit().putLong(LAST_EXIT, newest).apply();
        } catch (RuntimeException | JSONException ignored) {}
    }

    static JSONObject profile(Context context) {
        JSONObject fields = new JSONObject();
        try {
            fields.put("android", Build.VERSION.SDK_INT);
            fields.put("maker", TelemetryRules.maker(Build.MANUFACTURER));
            fields.put("model_label", TelemetryRules.modelLabel(Build.MODEL));
            PackageInfo webView = WebViewCompat.getCurrentWebViewPackage(context);
            fields.put("webview", TelemetryRules.major(webView == null ? null : webView.versionName));
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            if (activities != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activities.getMemoryInfo(memory);
                fields.put("ram_tier", TelemetryRules.ramTier(memory.totalMem));
                fields.put("memory_class", activities.getMemoryClass());
                fields.put("low_ram", activities.isLowRamDevice() ? "yes" : "no");
            }
            fields.put("storage_free", TelemetryRules.storageTier(new StatFs(context.getFilesDir().getPath()).getAvailableBytes()));
            fields.put("app_version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
            fields.put("store", Permissions.store(context));
        } catch (JSONException | PackageManager.NameNotFoundException | RuntimeException ignored) {}
        return fields;
    }

    static JSONObject state(Context context) {
        JSONObject fields = new JSONObject();
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null) {
                fields.put("battery_saver", power.isPowerSaveMode() ? "yes" : "no");
                fields.put("battery_unrestricted", power.isIgnoringBatteryOptimizations(context.getPackageName()) ? "yes" : "no");
            }
            String bucket = "unknown";
            if (Build.VERSION.SDK_INT >= 28) {
                UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
                if (usage != null) bucket = TelemetryRules.standbyBucket(usage.getAppStandbyBucket());
            }
            fields.put("standby_bucket", bucket);
            fields.put("notifications", PhoneSetup.notificationsAllowed(context) ? "on" : "off");
        } catch (JSONException | RuntimeException ignored) {}
        return fields;
    }

    // getDataNetworkType runs only after granted(READ_PHONE_STATE) below.
    @SuppressLint("MissingPermission")
    static JSONObject network(Context context) {
        JSONObject fields = new JSONObject();
        try {
            String transport = "none";
            boolean metered = false;
            boolean validated = false;
            int down = 0;
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            Network active = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport = "vpn";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "wifi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "cellular";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ethernet";
                else transport = "other";
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                down = caps.getLinkDownstreamBandwidthKbps();
            }
            fields.put("transport", transport);
            fields.put("metered", metered ? "yes" : "no");
            fields.put("validated", validated ? "yes" : "no");
            fields.put("down_tier", TelemetryRules.downTier(down));
            String generation = "unknown";
            // Only when the phone permission is already granted (calls placed from the app); never asked for this.
            if ("cellular".equals(transport) && granted(context, Manifest.permission.READ_PHONE_STATE)) {
                TelephonyManager phone = context.getSystemService(TelephonyManager.class);
                if (phone != null) generation = TelemetryRules.cellGeneration(phone.getDataNetworkType());
            }
            fields.put("cell_gen", generation);
        } catch (JSONException | RuntimeException ignored) {}
        return fields;
    }

    /** Which Sales X-Ray permissions this build declares and the seller has granted. */
    static JSONObject permissions(Context context) {
        JSONObject fields = new JSONObject();
        try {
            fields.put("calls", CallTracker.hasCallAccess(context) ? "granted" : "denied");
            fields.put("recordings", RecordingScanner.hasAudioAccess(context) ? "granted" : "denied");
            fields.put("notifications", PhoneSetup.notificationsAllowed(context) ? "granted" : "denied");
            fields.put("microphone", state(context, Manifest.permission.RECORD_AUDIO));
            fields.put("all_files", !RecorderProfile.allFilesSupported(context) ? "not_in_build" : RecordingScanner.hasAllFilesAccess() ? "granted" : "denied");
            fields.put("folder", !RecordingFolder.supported() ? "not_in_build" : RecordingFolder.paths(context).isEmpty() ? "denied" : "granted");
            fields.put("call_log", !CallLogSync.supported(context) ? "not_in_build" : state(context, Manifest.permission.READ_CALL_LOG));
        } catch (JSONException | RuntimeException ignored) {}
        return fields;
    }

    private static String state(Context context, String permission) {
        if (!Permissions.declared(context, permission)) return "not_in_build";
        return granted(context, permission) ? "granted" : "denied";
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }
}
