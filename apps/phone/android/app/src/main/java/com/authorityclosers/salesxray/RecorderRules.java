package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * Where this phone's own dialer saves call recordings, worked out from the
 * default dialer app and the maker (owner, 19 Oct: "check the phone's
 * call-recording setting and the exact folder"). Pure rules, so they're
 * tested on the JVM.
 *
 * Three kinds:
 * - "folder": the maker's dialer saves ordinary audio files Sales X-Ray can read
 *   (MIUI/HyperOS, Samsung, OnePlus/Oppo/Realme, Vivo, Huawei/Honor).
 * - "private": Google's Phone app keeps recordings inside itself; no other app
 *   can read them on any phone. The seller switches to the maker's dialer, or
 *   records in Sales Xray.
 * - "unknown": another dialer; every known folder is searched.
 */
final class RecorderRules {

    static final String GOOGLE_DIALER = "com.google.android.dialer";

    /** Where each maker's dialer saves, newest Android first, relative to shared storage. */
    static final String[] XIAOMI = { "MIUI/sound_recorder/call_rec", "Recordings/Call", "Recordings/Call recordings" };
    static final String[] SAMSUNG = { "Recordings/Call", "Call" };
    static final String[] OPLUS = {
        "Music/Recordings/Call Recordings",
        "Recordings/Call Recordings",
        "Recordings/Call recordings",
        "Record/PhoneRecord",
        "Recordings/PhoneRecord",
    };
    static final String[] VIVO = { "Recordings/Record/Call", "Record/Call" };
    static final String[] HUAWEI = { "Sounds/CallRecord", "Recordings/CallRecord", "record" };
    static final String[] OTHER = { "Recordings/Call", "Recordings/Call Recordings", "Music/Call Recordings", "CallRecordings", "Call Recordings", "PhoneRecord" };

    private RecorderRules() {}

    static final class Profile {

        final String kind;
        final String maker;
        final String[] folders;
        /** A Settings.System key the maker's dialer keeps "record automatically" in, or null. */
        final String settingKey;

        Profile(String kind, String maker, String[] folders, String settingKey) {
            this.kind = kind;
            this.maker = maker;
            this.folders = folders;
            this.settingKey = settingKey;
        }
    }

    /** The profile for this phone's default dialer ({@code dialer} may be null) and maker family. */
    static Profile profile(String dialer, String family) {
        String app = dialer == null ? "" : dialer.toLowerCase(Locale.ROOT);
        if (app.equals(GOOGLE_DIALER)) return new Profile("private", "google", new String[0], null);
        if (app.startsWith("com.samsung.")) return new Profile("folder", "samsung", SAMSUNG, null);
        if (app.startsWith("com.oplus.") || app.startsWith("com.coloros.") || app.startsWith("com.oneplus.")) {
            return new Profile("folder", "oplus", OPLUS, null);
        }
        if (app.startsWith("com.vivo.") || app.startsWith("com.iqoo.")) return new Profile("folder", "vivo", VIVO, null);
        if (app.startsWith("com.huawei.") || app.startsWith("com.hihonor.")) return new Profile("folder", "huawei", HUAWEI, null);
        if (app.startsWith("com.miui.") || app.startsWith("com.xiaomi.")) return new Profile("folder", "xiaomi", XIAOMI, "button_auto_record_call");
        // The AOSP package names: the maker decides what they do.
        if (app.equals("com.android.contacts") || app.equals("com.android.dialer") || app.equals("com.android.incallui") || app.isEmpty()) {
            switch (family == null ? "other" : family) {
                case "xiaomi":
                    return new Profile("folder", "xiaomi", XIAOMI, "button_auto_record_call");
                case "samsung":
                    return new Profile("folder", "samsung", SAMSUNG, null);
                case "oppo":
                case "realme":
                case "oneplus":
                    return new Profile("folder", "oplus", OPLUS, null);
                case "vivo":
                    return new Profile("folder", "vivo", VIVO, null);
                case "huawei":
                    return new Profile("folder", "huawei", HUAWEI, null);
                default:
                    return new Profile("unknown", "other", OTHER, null);
            }
        }
        return new Profile("unknown", "other", OTHER, null);
    }

    /**
     * Is recording on? "on"/"off" from the maker's own setting when the phone
     * shows it, else "on" when a recording appeared in the last 30 days, else
     * "unknown". Google's Phone app: "private" (it may record, but not for us).
     */
    static String recording(Profile profile, Integer setting, long newestMs, long nowMs) {
        if ("private".equals(profile.kind)) return "private";
        if (setting != null) return setting != 0 ? "on" : "off";
        if (newestMs > 0 && nowMs - newestMs < 30L * 24 * 60 * 60 * 1000) return "on";
        return "unknown";
    }

    /**
     * MIUI and HyperOS keep a .nomedia file in MIUI/sound_recorder, so their call
     * recordings never reach Android's media index (Redmi 13, 22 Oct, #49). On
     * Android 11 and newer an app then sees them only with All files access.
     */
    static boolean hidesFromMediaIndex(String maker) {
        return "xiaomi".equals(maker);
    }

    /** True when this phone's recordings stay out of sight until All files access is allowed. */
    static boolean needsAllFiles(Profile profile, int sdk, boolean allFiles) {
        return sdk >= 30 && !allFiles && hidesFromMediaIndex(profile.maker);
    }

    /** The same, when a folder the seller picked (S8, RecordingFolder) already lets the app read them. */
    static boolean needsAllFiles(Profile profile, int sdk, boolean allFiles, java.util.List<String> picked) {
        return needsAllFiles(profile, sdk, allFiles) && !RecordingFolderRules.coversAny(picked, profile.folders);
    }

    /** Dialer apps that save to a folder, for "switch to your phone's own dialer". */
    static boolean savesToFolder(String dialer, String family) {
        return "folder".equals(profile(dialer, family).kind);
    }
}
