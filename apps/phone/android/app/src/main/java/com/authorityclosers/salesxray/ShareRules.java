package com.authorityclosers.salesxray;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A call recording shared to Sales X-Ray (ShareIn), matched to the seller's own call. Google's
 * Phone app keeps its recordings inside itself (RecorderRules "private"), so on such a phone the
 * seller shares each one from the call's recording (owner, 3 Nov 2026: his OnePlus calls waited
 * two hours for recordings that could never arrive). The call history gives each call's start and
 * length, and the recording's own length picks the call. Plain Java, for JVM tests.
 */
final class ShareRules {

    /** A call from the call history: number as Android has it, cached contact name, start, connected length. */
    static final class Call {
        final String number;
        final String name;
        final long startedMs;
        final long durationMs;

        Call(String number, String name, long startedMs, long durationMs) {
            this.number = number == null ? "" : number;
            this.name = name == null ? "" : name;
            this.startedMs = startedMs;
            this.durationMs = durationMs;
        }
    }

    /** A recording can be this much longer than its call (the dialler's own clock and rounding). */
    static final long LONGER_MS = 5_000;
    /** And this much shorter: at least 8 s, or 15 % of the call (recording starts once answered). */
    static final long SHORTER_MS = 8_000;
    static final double SHORTER_SHARE = 0.15;

    private ShareRules() {}

    /** True when an audio recording of {@code audioMs} could be the whole of {@code call}. */
    static boolean fits(long audioMs, Call call) {
        if (audioMs <= 0 || call.durationMs <= 0) return false;
        if (audioMs > call.durationMs + LONGER_MS) return false;
        long shorter = Math.max(SHORTER_MS, Math.round(call.durationMs * SHORTER_SHARE));
        return call.durationMs - audioMs <= shorter;
    }

    /**
     * The call this recording belongs to: an answered call in the last 3 days (the longest any
     * recording waits, CaptureRules.KEEP_MS) whose length fits, the closest length first and, at
     * the same length, the latest. Null when none fits.
     */
    static Call match(long audioMs, List<Call> calls, long nowMs) {
        Call best = null;
        long bestGap = Long.MAX_VALUE;
        for (Call call : calls) {
            if (call.startedMs > nowMs || nowMs - call.startedMs > CaptureRules.KEEP_MS) continue;
            if (!fits(audioMs, call)) continue;
            long gap = Math.abs(call.durationMs - audioMs);
            if (best == null || gap < bestGap || (gap == bestGap && call.startedMs > best.startedMs)) {
                best = call;
                bestGap = gap;
            }
        }
        return best;
    }

    /** "Asha · today 14:15 · 2:31", the call as the seller knows it. */
    static String label(Call call, long nowMs, TimeZone zone) {
        String who = !call.name.isEmpty() ? call.name : !call.number.isEmpty() ? call.number : "Unknown number";
        return who + " · " + day(call.startedMs, nowMs, zone) + " " + clock(call.startedMs, zone) + " · " + length(call.durationMs);
    }

    static String day(long atMs, long nowMs, TimeZone zone) {
        Calendar at = Calendar.getInstance(zone, Locale.ROOT);
        at.setTimeInMillis(atMs);
        Calendar now = Calendar.getInstance(zone, Locale.ROOT);
        now.setTimeInMillis(nowMs);
        int days = now.get(Calendar.DAY_OF_YEAR) - at.get(Calendar.DAY_OF_YEAR)
            + 365 * (now.get(Calendar.YEAR) - at.get(Calendar.YEAR));
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return String.format(Locale.ROOT, "%d %s", at.get(Calendar.DAY_OF_MONTH),
            new String[] { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" }[at.get(Calendar.MONTH)]);
    }

    static String clock(long atMs, TimeZone zone) {
        Calendar at = Calendar.getInstance(zone, Locale.ROOT);
        at.setTimeInMillis(atMs);
        return String.format(Locale.ROOT, "%02d:%02d", at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE));
    }

    static String length(long ms) {
        long seconds = Math.max(0, Math.round(ms / 1000.0));
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** A file name's extension the capture upload and analysis read, from the shared name or its type. */
    static String extension(String name, String mime) {
        String file = name == null ? "" : name;
        int dot = file.lastIndexOf('.');
        String fromName = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (java.util.Arrays.asList("m4a", "mp3", "wav", "ogg", "opus", "aac", "amr", "awb", "3gp", "flac").contains(fromName)) return fromName;
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (type.contains("wav")) return "wav";
        if (type.contains("mpeg") || type.contains("mp3")) return "mp3";
        if (type.contains("ogg") || type.contains("opus")) return "ogg";
        if (type.contains("amr")) return "amr";
        if (type.contains("aac")) return "aac";
        if (type.contains("3gp")) return "3gp";
        if (type.contains("flac")) return "flac";
        return "m4a";
    }
}
