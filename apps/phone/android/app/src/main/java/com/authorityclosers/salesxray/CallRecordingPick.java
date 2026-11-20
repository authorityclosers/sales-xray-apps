package com.authorityclosers.salesxray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONObject;

/**
 * Which of the phone's recordings belongs to the call just placed from the app (CallTracker).
 * Pure rules, so they are tested on the JVM.
 *
 * An Oppo CPH2835 (Wise Life, 19 and 20 Nov 2026) sent the previous call's recording for the
 * next call, twice: the old picker took any file saved up to 5 s before the call, fell back to
 * the newest file when none was named after the lead (which also ended the wait for the real
 * one), and did not remember what it had already given to a call. Now:
 * - a file saved before the call started (2 s of clock slack) belongs to an earlier call;
 * - a file whose name holds another phone number, or a clock from before the call, is another call's;
 * - a file whose length is far from the talk time (20 %, at least 5 s), or longer than the whole
 *   call when the talk time is unknown, is another call's;
 * - a file already given to another call is never given again (the last 200, kept on the phone);
 * - while none fits the phone keeps looking (Oppo saves late), and a file is taken only once its
 *   size has stopped changing;
 * - when two fit equally neither is sent: no recording is better than another call's.
 */
final class CallRecordingPick {

    /** Clock slack between the call's start and the file's time (the media index keeps whole seconds). */
    static final long SKEW_MS = 2_000;
    /** A clock in the file's name earlier than this before the call is an earlier call. */
    static final long NAME_CLOCK_SLACK_MS = 60_000;
    /** The length check's floor: short calls get at least this much either way. */
    static final long SHORT_SLACK_MS = 5_000;
    static final double LENGTH_SLACK = 0.20;
    /** Two files whose lengths fit this close together can't be told apart. */
    static final long TIE_MS = 2_000;
    static final int USED_MAX = 200;

    static final String PICKED = "picked";
    /** No recording was saved since the call started. */
    static final String NONE = "none";
    /** Only files from before the call. */
    static final String STALE = "stale";
    /** Only files already given to another call. */
    static final String USED = "used";
    /** Only files named after another number, or of another call's length. */
    static final String OTHER = "other_call";
    /** Two or more fit and nothing tells them apart. */
    static final String AMBIGUOUS = "ambiguous";

    private CallRecordingPick() {}

    /** A recording's length in ms, 0 when unknown (the media index's, or read from the file). */
    interface Lengths {
        long ms(JSONObject item);
    }

    /** The phone's recordings, newest first (RecordingScanner.list). */
    interface Scan {
        List<JSONObject> items();
    }

    interface Clock {
        long now();
    }

    interface Sleeper {
        void sleep(long ms);
    }

    /** The call: {@code key} names it in the used history (lead and start). */
    static final class Call {

        final String key;
        final long startedMs;
        final long endedMs;
        /** Talk time from the call history; -1 when unknown. */
        final long talkSeconds;
        final String number;
        final String name;

        Call(String key, long startedMs, long endedMs, long talkSeconds, String number, String name) {
            this.key = key;
            this.startedMs = startedMs;
            this.endedMs = endedMs;
            this.talkSeconds = talkSeconds;
            this.number = number == null ? "" : number;
            this.name = name == null ? "" : name;
        }
    }

    static final class Result {

        /** The call's recording, or null. */
        final JSONObject recording;
        /** PICKED, or why nothing was: NONE, STALE, USED, OTHER or AMBIGUOUS. */
        final String reason;
        /** How many files the scan saw. */
        final int files;

        Result(JSONObject recording, String reason, int files) {
            this.recording = recording;
            this.reason = reason;
            this.files = files;
        }
    }

    static String key(String leadId, long startedMs) {
        return (leadId == null ? "" : leadId) + ":" + startedMs;
    }

    /** One look at the files: this call's recording, or why there is none. */
    static Result pick(List<JSONObject> items, Call call, String used, Lengths lengths, TimeZone zone) {
        String digits = call.number.replaceAll("\\D", "");
        String key = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        String tail = digits.length() > 6 ? digits.substring(digits.length() - 6) : digits;
        String first = call.name.trim().isEmpty() ? "" : call.name.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        boolean stale = false, taken = false, other = false;
        List<JSONObject> named = new ArrayList<>();
        List<JSONObject> plain = new ArrayList<>();
        Map<JSONObject, Long> length = new HashMap<>();
        for (JSONObject item : items) {
            if (item == null) continue;
            if (item.optLong("addedMs") < call.startedMs - SKEW_MS) {
                stale = true;
                continue;
            }
            if (usedElsewhere(used, call.key, item)) {
                taken = true;
                continue;
            }
            String file = item.optString("name");
            RecordingMatch.Name read = RecordingMatch.read(file, zone);
            if (read.stampMs > 0 && read.stampMs < call.startedMs - NAME_CLOCK_SLACK_MS) {
                stale = true;
                continue;
            }
            boolean ours = false, theirs = false;
            for (String number : read.numbers) {
                if (RecordingMatch.sameNumber(number, key)) ours = true;
                else if (number.length() >= 10 && key.length() >= 7 && !clock(number, call.startedMs)) theirs = true;
            }
            if (theirs && !ours) {
                other = true;
                continue;
            }
            long ms = lengths == null ? item.optLong("durationMs") : lengths.ms(item);
            if (!lengthFits(ms, call)) {
                other = true;
                continue;
            }
            length.put(item, ms);
            String lower = file.toLowerCase(Locale.ROOT);
            boolean byName = ours ||
                (tail.length() >= 6 && lower.replaceAll("\\D", "").contains(tail)) ||
                (first.length() > 1 && lower.contains(first));
            (byName ? named : plain).add(item);
        }
        List<JSONObject> pool = named.isEmpty() ? plain : named;
        if (pool.isEmpty()) return new Result(null, other ? OTHER : taken ? USED : stale ? STALE : NONE, items.size());
        JSONObject chosen = choose(pool, length, call);
        if (chosen == null) return new Result(null, AMBIGUOUS, items.size());
        long ms = length.get(chosen);
        if (ms > 0 && chosen.optLong("durationMs") <= 0) {
            try {
                chosen.put("durationMs", ms);
            } catch (org.json.JSONException ignored) {
                // Keys and values here are always valid.
            }
        }
        return new Result(chosen, PICKED, items.size());
    }

    /**
     * Looks until this call's recording is there and has stopped growing, or {@code waitMs} has
     * passed. A file that fits is taken once two looks in a row saw it at the same size; at the
     * deadline the one that fits is taken as it is.
     */
    static Result await(Scan scan, Call call, String used, Lengths lengths, TimeZone zone, Clock clock, Sleeper sleeper, long waitMs, long pollMs) {
        long deadline = clock.now() + waitMs;
        Map<String, Long> sizes = new HashMap<>();
        while (true) {
            List<JSONObject> items = scan.items();
            Result result = pick(items, call, used, lengths, zone);
            boolean last = clock.now() >= deadline;
            if (result.recording != null) {
                Long before = sizes.get(result.recording.optString("source"));
                if (last || (before != null && before == result.recording.optLong("size"))) return result;
            }
            if (last) return result;
            sizes.clear();
            for (JSONObject item : items) if (item != null) sizes.put(item.optString("source"), item.optLong("size"));
            sleeper.sleep(pollMs);
        }
    }

    /** A recorder that names files by the epoch (seconds or ms) near the call: a clock, not a phone number. */
    static boolean clock(String digits, long startedMs) {
        if (digits.length() != 10 && digits.length() != 13) return false;
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException tooLong) {
            return false;
        }
        long ms = digits.length() == 10 ? value * 1000L : value;
        return Math.abs(ms - startedMs) <= 2L * 24 * 60 * 60 * 1000;
    }

    /** Known lengths only: near the talk time, or no longer than the whole call when that is all there is. */
    static boolean lengthFits(long ms, Call call) {
        if (ms <= 0) return true;
        if (call.talkSeconds > 0) {
            long talk = call.talkSeconds * 1000L;
            long slack = Math.max(SHORT_SLACK_MS, (long) (talk * LENGTH_SLACK));
            return Math.abs(ms - talk) <= slack;
        }
        long wall = call.endedMs - call.startedMs;
        return wall <= 0 || ms <= wall + SHORT_SLACK_MS;
    }

    /**
     * One of several that fit: the one whose length is clearly nearest the talk time; else the
     * only one the phone's own dialer saved (Sales X-Ray's own recorder may have the same call);
     * else none.
     */
    private static JSONObject choose(List<JSONObject> pool, Map<JSONObject, Long> length, Call call) {
        if (pool.size() == 1) return pool.get(0);
        if (call.talkSeconds > 0) {
            long talk = call.talkSeconds * 1000L;
            JSONObject best = null;
            long bestGap = Long.MAX_VALUE, nextGap = Long.MAX_VALUE;
            boolean allKnown = true;
            for (JSONObject item : pool) {
                long ms = length.get(item);
                if (ms <= 0) {
                    allKnown = false;
                    break;
                }
                long gap = Math.abs(ms - talk);
                if (gap < bestGap) {
                    nextGap = bestGap;
                    bestGap = gap;
                    best = item;
                } else if (gap < nextGap) {
                    nextGap = gap;
                }
            }
            if (allKnown && nextGap - bestGap > TIE_MS) return best;
        }
        JSONObject dialer = null;
        for (JSONObject item : pool) {
            if (!"dialer".equals(item.optString("captured", "dialer"))) continue;
            if (dialer != null) return null;
            dialer = item;
        }
        return dialer;
    }

    /** How a file is known in the used history: its source and, as a source can change, its name and size. */
    static String fingerprint(JSONObject item) {
        long size = item.optLong("size");
        return size > 0 ? item.optString("name").toLowerCase(Locale.ROOT) + ":" + size : "";
    }

    /** True when the history gave this file to another call. */
    static boolean usedElsewhere(String history, String callKey, JSONObject item) {
        if (history == null || history.isEmpty()) return false;
        String source = item.optString("source");
        String print = fingerprint(item);
        for (String line : history.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 3 || parts[0].equals(callKey)) continue;
            if ((!source.isEmpty() && parts[1].equals(source)) || (!print.isEmpty() && parts[2].equals(print))) return true;
        }
        return false;
    }

    /** The history with this call's file added last, keeping the newest {@code max} lines. */
    static String remember(String history, String callKey, JSONObject item, int max) {
        String line = clean(callKey) + "\t" + clean(item.optString("source")) + "\t" + clean(fingerprint(item));
        List<String> lines = new ArrayList<>();
        if (history != null) {
            for (String old : history.split("\n")) if (!old.isEmpty() && !old.equals(line)) lines.add(old);
        }
        lines.add(line);
        int from = Math.max(0, lines.size() - max);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }
}
