package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * Native telemetry as tiers and codes (owner, 24 Oct 2026: more data for a better experience; the page
 * sends it as sx.* events under consent B). Never a number, name, contact or exact value that could
 * identify a phone. Plain Java, for JVM tests; DeviceTelemetry reads Android.
 */
final class TelemetryRules {

    private static final long GB = 1024L * 1024 * 1024;

    private TelemetryRules() {}

    /** The phone's RAM, rounded up to a common size: 2, 3, 4, 6, 8 or 12plus (GB). */
    static String ramTier(long totalBytes) {
        double gb = (double) totalBytes / GB;
        if (gb <= 2.2) return "2";
        if (gb <= 3.2) return "3";
        if (gb <= 4.4) return "4";
        if (gb <= 6.4) return "6";
        if (gb <= 8.5) return "8";
        return "12plus";
    }

    /** Free space for the app's data: lt1, 1to4, 4to16 or 16plus (GB). */
    static String storageTier(long freeBytes) {
        if (freeBytes < GB) return "lt1";
        if (freeBytes < 4 * GB) return "1to4";
        if (freeBytes < 16 * GB) return "4to16";
        return "16plus";
    }

    /** Downstream bandwidth Android estimates for the network: lt1, 1to5, 5to20 or 20plus (Mbps). */
    static String downTier(int kbps) {
        if (kbps <= 0) return "unknown";
        if (kbps < 1000) return "lt1";
        if (kbps < 5000) return "1to5";
        if (kbps < 20000) return "5to20";
        return "20plus";
    }

    /** UsageStatsManager.STANDBY_BUCKET_* for the app itself. */
    static String standbyBucket(int bucket) {
        switch (bucket) {
            case 5:
                return "exempted";
            case 10:
                return "active";
            case 20:
                return "working_set";
            case 30:
                return "frequent";
            case 40:
                return "rare";
            case 45:
                return "restricted";
            default:
                return "unknown";
        }
    }

    /** ApplicationExitInfo.REASON_* of the app's last runs. */
    static String exitReason(int reason) {
        switch (reason) {
            case 6:
                return "anr";
            case 4:
                return "crash";
            case 5:
                return "crash_native";
            case 3:
                return "low_memory";
            case 9:
                return "excessive_resource";
            case 10:
            case 11:
                return "user_requested";
            case 2:
                // Killed by a signal: Android ending the process (or another app's task killer).
                return "killed_by_system";
            default:
                return "other";
        }
    }

    /** Only the exits worth a dashboard line: the app failed or Android took it away. */
    static boolean reportable(String reason) {
        return !"user_requested".equals(reason) && !"other".equals(reason);
    }

    /** TelephonyManager.NETWORK_TYPE_* to a generation. */
    static String cellGeneration(int type) {
        switch (type) {
            case 1:
            case 2:
            case 4:
            case 7:
            case 11:
            case 16:
                return "2g";
            case 3:
            case 5:
            case 6:
            case 8:
            case 9:
            case 10:
            case 12:
            case 14:
            case 15:
            case 17:
                return "3g";
            case 13:
            case 18:
                return "4g";
            case 20:
                return "5g";
            default:
                return "unknown";
        }
    }

    /** "Xiaomi" or "Redmi" is xiaomi; anything else lower-case letters only, at most 16. */
    static String maker(String manufacturer) {
        String raw = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (raw.isEmpty()) return "unknown";
        if (raw.equals("redmi") || raw.equals("poco")) return "xiaomi";
        return raw.length() > 16 ? raw.substring(0, 16) : raw;
    }

    /** The model as Android names it, letters, digits, spaces and dashes only, at most 32. */
    static String modelLabel(String model) {
        String raw = model == null ? "" : model.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (raw.isEmpty()) return "unknown";
        return raw.length() > 32 ? raw.substring(0, 32) : raw;
    }

    /** "141.0.7390.122" is "141": the WebView's major version. */
    static String major(String version) {
        if (version == null) return "unknown";
        int dot = version.indexOf('.');
        String head = dot < 0 ? version : version.substring(0, dot);
        return head.matches("[0-9]{1,4}") ? head : "unknown";
    }

    /**
     * A session's frames, counted as they're drawn (FrameMetrics): slow ones miss a 60 Hz frame
     * (over 16.7 ms), frozen ones take over 700 ms. p90 comes from 1 ms buckets up to 1 s.
     */
    static final class FrameStats {

        private final int[] buckets = new int[1001];
        int frames;
        int slow;
        int frozen;

        void add(long durationNanos) {
            long ms = durationNanos / 1_000_000L;
            frames++;
            if (durationNanos > 16_700_000L) slow++;
            if (ms > 700) frozen++;
            buckets[(int) Math.min(1000, Math.max(0, ms))]++;
        }

        int p90() {
            if (frames == 0) return 0;
            int target = (int) Math.ceil(frames * 0.9);
            int seen = 0;
            for (int ms = 0; ms < buckets.length; ms++) {
                seen += buckets[ms];
                if (seen >= target) return ms;
            }
            return 1000;
        }

        void clear() {
            java.util.Arrays.fill(buckets, 0);
            frames = 0;
            slow = 0;
            frozen = 0;
        }
    }
}
