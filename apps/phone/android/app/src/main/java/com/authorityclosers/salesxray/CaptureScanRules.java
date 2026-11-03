package com.authorityclosers.salesxray;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What each capture scan saw, for sx.capture_scan (orchestrator, 3 Nov 2026: the owner's OnePlus
 * waited two hours for recordings, and nobody could tell why without the phone). The Phone app,
 * its kind, each known folder's state and newest file's age, whether a picked folder covers them,
 * how many call recordings the media index holds, and how each recent lead call matched. Never a
 * file name, a number or a contact name. Plain Java, for JVM tests.
 */
final class CaptureScanRules {

    /** Lead calls this recent are checked for their recording. */
    static final long CALLS_MS = 6L * 60 * 60 * 1000;
    /** A recording belongs to a call that started this close to its file's clock. */
    static final long NEAR_MS = 3L * 60 * 1000;
    /** Ages are sent in seconds, at most 30 days. */
    static final long MAX_AGE_S = 30L * 24 * 60 * 60;
    /** Known folders reported, in the profile's order. */
    static final int FOLDERS = 8;

    private CaptureScanRules() {}

    /**
     * A recent lead call's recording: "matched" when one recording (a job, or one the server
     * refused) has its number and a clock near its start, "ambiguous" when several do, else "none".
     */
    static String callResult(long callStartMs, List<Long> stampsForNumber) {
        int near = 0;
        for (Long stamp : stampsForNumber) if (stamp != null && Math.abs(stamp - callStartMs) <= NEAR_MS) near++;
        return near == 0 ? "none" : near == 1 ? "matched" : "ambiguous";
    }

    /** Seconds since {@code atMs}, from 0 to 30 days; -1 when unknown. */
    static long ageSeconds(long atMs, long nowMs) {
        if (atMs <= 0) return -1;
        return Math.max(0, Math.min(MAX_AGE_S, (nowMs - atMs) / 1000));
    }

    /** A media-index folder that holds call recordings: its path says "call" or "record". */
    static boolean recorderPath(String relative) {
        if (relative == null) return false;
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.contains("call") || lower.contains("record");
    }

    /** "Music/Recordings/Call Recordings/" → "Music/Recordings/Call Recordings". */
    static String folder(String relative) {
        if (relative == null) return "";
        String trimmed = relative.replace('\\', '/').replaceAll("/+$", "").replaceAll("^/+", "");
        int storage = trimmed.indexOf("/0/");
        return storage >= 0 ? trimmed.substring(storage + 3) : trimmed;
    }

    /** The media index's folder with the most call recordings (the first on a tie), or null. */
    static String busiest(Map<String, Integer> counts) {
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > most) {
                best = entry.getKey();
                most = entry.getValue();
            }
        }
        return best;
    }

    /**
     * A folder path as a telemetry code (the server's short lower-case token):
     * "Music/Recordings/Call Recordings" → "music_recordings_call_recordings".
     */
    static String token(String path) {
        String clean = (path == null ? "" : path).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (clean.isEmpty()) return "none";
        if (!Character.isLetter(clean.charAt(0))) clean = "p_" + clean;
        return clean.length() > 48 ? clean.substring(0, 48) : clean;
    }

    /** The Phone app's package as a telemetry label ("com.oplus.dialer"): lower case, at most 32 characters. */
    static String label(String value) {
        String clean = (value == null ? "" : value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (clean.isEmpty() || !Character.isLetterOrDigit(clean.charAt(0))) clean = "x" + clean;
        return clean.length() > 32 ? clean.substring(0, 32) : clean;
    }

    /** RecorderProfile's folder state, one of the values the server takes. */
    static String folderState(String state) {
        switch (state == null ? "" : state) {
            case "missing":
            case "empty":
            case "found":
            case "hidden":
            case "no_access":
                return state;
            default:
                return "unknown";
        }
    }

    /** A held call's reason, one of the values the server takes. */
    static String holdReason(String reason) {
        switch (reason == null ? "" : reason) {
            case CaptureRules.PROFILE:
            case CaptureRules.ALLOWANCE:
            case CaptureRules.SIGNED_OUT:
            case CaptureRules.CONSENT:
            case CaptureRules.NOTICES:
            case CaptureRules.WORKSPACE:
            case CaptureRules.FOLDER:
            case CaptureRules.NETWORK:
            case CaptureRules.ACCESS:
            case CaptureRules.OFF:
            case CaptureRules.REVIEW:
            case CaptureRules.EXPIRED:
                return reason;
            default:
                return "other";
        }
    }
}
