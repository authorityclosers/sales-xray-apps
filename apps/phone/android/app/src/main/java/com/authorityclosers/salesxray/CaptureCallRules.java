package com.authorityclosers.salesxray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONObject;

/**
 * Automatic uploads for recordings named after a contact, or with no number in their name (SX-AUTOUPLOAD, owner,
 * 25 Nov 2026: "auto upload is not working for any of the people who are using it"). The scan took a recording only
 * when a lead's number was in its file name, so a lead saved in the phone's contacts (Samsung, OnePlus, Vivo and
 * Google save "Rohan Deshmukh_241125_143000.m4a") never uploaded. Now a lead call in the call history (the lead's
 * number, a connected call) takes the one recording whose own clock fits it, by RecordingMatch's strict rules:
 * a file named after another number never matches; a contact-named one only when the time in its name and the time
 * it was saved both fit; a plain one only when its length is known, it was saved as the call ended, and no other
 * plain file fits. A recording goes to one call at most. Pure, so it is tested on the JVM.
 */
final class CaptureCallRules {

    /** A connected call with a lead, from the call history: the lead's number as the book holds it. */
    static final class Call {
        final String number;
        final long startedMs;
        final long seconds;

        Call(String number, long startedMs, long seconds) {
            this.number = number;
            this.startedMs = startedMs;
            this.seconds = seconds;
        }
    }

    /** Two calls with one lead this close together are one call to the server (it reuses a call within 90 s). */
    static final long SAME_CALL_MS = 120_000L;
    static final long MAX_SECONDS = 14_400L;

    private CaptureCallRules() {}

    /** The lead number's last ten digits, as RecordingMatch compares them. */
    static String key(String number) {
        String digits = number == null ? "" : number.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /**
     * A call-history row worth a recording: connected (talk time), at most four hours, started inside the upload
     * window ({@code from}, after uploads were turned on) and ended at least {@code settleMs} ago.
     */
    static boolean candidate(long startedMs, long seconds, long from, long now, long settleMs) {
        if (seconds <= 0 || seconds > MAX_SECONDS || startedMs < from || startedMs > now) return false;
        return now - (startedMs + seconds * 1000L) >= settleMs;
    }

    /** A job already made for this call (by the file's name, or an earlier scan): the same lead, the same start. */
    static boolean covered(List<JSONObject> jobs, String number, long startedMs) {
        for (JSONObject job : jobs) {
            if (!number.equals(job.optString("number"))) continue;
            if (Math.abs(job.optLong("stamp") - startedMs) <= SAME_CALL_MS) return true;
        }
        return false;
    }

    /**
     * Each call's recording among {@code items} ({name, addedMs, durationMs, source}), newest call first. Items in
     * {@code used} belong to other calls and are never taken; each one taken here joins {@code used}. A plain match
     * (no number or time in the file's name) needs the file's length.
     */
    static Map<Call, JSONObject> match(List<Call> calls, List<JSONObject> items, Set<String> used, TimeZone zone) {
        List<Call> newest = new ArrayList<>(calls);
        Collections.sort(newest, (a, b) -> Long.compare(b.startedMs, a.startedMs));
        Map<Call, JSONObject> found = new LinkedHashMap<>();
        for (Call call : newest) {
            String key = key(call.number);
            if (key.length() < 10) continue;
            JSONObject best = RecordingMatch.best(items, key, call.startedMs, call.seconds, used, zone);
            if (best == null) continue;
            int fit = RecordingMatch.fit(best, key, call.startedMs, call.seconds, zone);
            if (fit == RecordingMatch.NONE) continue;
            if (fit == RecordingMatch.PLAIN && best.optLong("durationMs") <= 0) continue;
            found.put(call, best);
            used.add(best.optString("source"));
        }
        return found;
    }
}
