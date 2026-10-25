package com.authorityclosers.salesxray;

/**
 * Why a call placed from the app came back without a recording, and the one tap that fixes it (demo P1,
 * 25 Oct 2026: of 46 logged calls only 2 had a recording). On Xiaomi with the Play build the dialler
 * records, but MIUI hides the folder from the media index and the app sees nothing until the seller
 * picks it; Google's Phone app keeps recordings to itself; a dialler with recording off makes none.
 * Plain Java, for JVM tests; RecordingReason reads Android and shows it.
 */
final class RecordingReasonRules {

    enum Reason {
        /** Google's Phone app: recordings stay inside it. Nothing to fix in the app. */
        PRIVATE_DIALER,
        /** The dialler's automatic call recording is off. */
        RECORDER_OFF,
        /** Recordings exist but sit in a folder the media index can't see (MIUI): pick it once. */
        PICK_FOLDER,
        /** No permission to read audio files. */
        NO_PERMISSION,
        /** Nothing explains it: ask the seller to check the dialler's recording. */
        UNKNOWN,
    }

    /** The same reason is asked about once every 6 hours at most. */
    static final long ASK_AGAIN_MS = 6L * 60 * 60 * 1000;
    /** A call shorter than this (ringing included) is probably unanswered: no recording expected. */
    static final long SHORTEST_MS = 25_000;

    private RecordingReasonRules() {}

    /**
     * From RecorderProfile.status: kind ("folder", "private" or "unknown"), recording ("on", "off",
     * "private" or "unknown"), hidden (recordings out of the app's sight), and whether a folder can be
     * picked and audio files read.
     */
    static Reason reason(String kind, String recording, boolean hidden, boolean folderPickSupported, boolean canRead) {
        if ("private".equals(kind) || "private".equals(recording)) return Reason.PRIVATE_DIALER;
        if ("off".equals(recording)) return Reason.RECORDER_OFF;
        if (hidden && folderPickSupported) return Reason.PICK_FOLDER;
        if (!canRead) return Reason.NO_PERMISSION;
        return Reason.UNKNOWN;
    }

    /** A call worth explaining: answered, or (with no call history to tell) long enough to have been. */
    static boolean worthExplaining(Boolean connected, long startedMs, long endedMs) {
        if (connected != null) return connected;
        return endedMs - startedMs >= SHORTEST_MS;
    }

    /** Not the same reason again within 6 hours (a clock set back asks again). */
    static boolean ask(Reason reason, String lastReason, long lastAtMs, long nowMs) {
        if (!reason.name().equals(lastReason)) return true;
        return nowMs < lastAtMs || nowMs - lastAtMs >= ASK_AGAIN_MS;
    }

    /** What the one button does: "folder", "recorder", "appSettings", or null (nothing to fix). */
    static String fix(Reason reason) {
        switch (reason) {
            case PICK_FOLDER:
                return "folder";
            case RECORDER_OFF:
            case UNKNOWN:
                return "recorder";
            case NO_PERMISSION:
                return "appSettings";
            default:
                return null;
        }
    }

    /** Title, message and button, in the seller's words. */
    static String[] words(Reason reason) {
        switch (reason) {
            case PRIVATE_DIALER:
                return new String[] {
                    "This call has no recording",
                    "Your phone uses Google's Phone app, which keeps call recordings to itself, so Sales X-Ray can't add them. "
                        + "For a report, upload the recording from the call, or use your phone's own dialler if it has one.",
                    "OK",
                };
            case RECORDER_OFF:
                return new String[] {
                    "Turn on call recording",
                    "Your phone's dialler isn't recording calls, so this call has no recording. Turn on automatic call "
                        + "recording once, and every call gets its recording and a report.",
                    "Turn it on",
                };
            case PICK_FOLDER:
                return new String[] {
                    "Let Sales X-Ray find your recordings",
                    "Your phone keeps call recordings in a hidden folder. Choose it once (tap Use this folder, then Allow), "
                        + "and every call gets its recording and a report.",
                    "Choose folder",
                };
            case NO_PERMISSION:
                return new String[] {
                    "Allow access to recordings",
                    "Sales X-Ray needs to read audio files to find your call recordings. Turn on Music and audio for "
                        + "Sales X-Ray in its settings.",
                    "Open settings",
                };
            default:
                return new String[] {
                    "No recording for this call",
                    "Sales X-Ray couldn't find a recording. Check that your phone records calls automatically; when it "
                        + "does, every call gets its recording and a report.",
                    "Check settings",
                };
        }
    }
}
