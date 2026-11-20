package com.authorityclosers.salesxray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Find my old calls" (20 Nov 2026): the seller's connected lead calls of the
 * last two weeks that Sales X-Ray has no recording for, matched to the
 * recordings still on the phone. The page sends the calls (it reads them from
 * the server); the phone says which recording belongs to which call, and lists
 * the call recordings it could not place so the seller can put them on a lead.
 * Pure rules, so they are tested on the JVM; OldCalls reads the phone.
 *
 * Read-only: nothing is uploaded, no queue changes, no notice. A wrong match
 * would put someone's private call on a lead, so it is as strict as the calls
 * the phone logs by itself (RecordingMatch), and:
 * - the oldest call chooses first and a recording goes to one call only;
 * - a recording already given to a call (RecordingQueue, the app's own dials,
 *   automatic uploads) is never offered again;
 * - a recording the phone already heard as silent is never offered;
 * - only the phone dialer's recordings, from call-recorder folders or the
 *   folder the seller picked, inside the window; never Sales X-Ray's own.
 */
final class OldCallsRules {

    static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** The page asks for two weeks; when it says nothing, two weeks. */
    static final long DEFAULT_WINDOW_MS = 14 * DAY_MS;
    /** Never further back than a month, however far the page asks. */
    static final long MAX_WINDOW_MS = 31 * DAY_MS;
    /** At most this many calls per ask (the newest are kept). */
    static final int MAX_CALLS = 300;
    /** At most this many of the phone's recordings in the window are looked at. */
    static final int LIST_LIMIT = 1000;
    /** Recordings no call took, newest first, at most this many. */
    static final int MAX_UNMATCHED = 30;
    /** A call's talk time is never longer than four hours (as call logging sends it). */
    static final long MAX_SECONDS = 14400;

    static final String ACCESS_OK = "ok";
    /** No audio or all-files access, and no picked folder: the phone can't see any recording. */
    static final String ACCESS_NONE = "none";
    /** This phone hides its recordings folder until All files access (or a picked folder). */
    static final String ACCESS_HIDDEN = "hidden";

    private OldCallsRules() {}

    /** Where the window starts: what the page asked, at most a month back and never in the future. */
    static long since(long askedMs, long now) {
        if (askedMs <= 0) return now - DEFAULT_WINDOW_MS;
        return Math.min(now, Math.max(askedMs, now - MAX_WINDOW_MS));
    }

    /**
     * What the phone can see: "none" when it can read no recording at all,
     * "hidden" when this phone's recorder folder is hidden until All files
     * access (what pendingRecordings calls needsAllFiles), else "ok".
     */
    static String access(boolean audio, boolean picked, boolean hidden) {
        if (!audio && !picked) return ACCESS_NONE;
        if (hidden) return ACCESS_HIDDEN;
        return ACCESS_OK;
    }

    /** One of the page's calls: its event, the lead's number as call logging keys it, start and talk time. */
    static final class Call {

        final String eventId;
        final String key;
        final long startedMs;
        final long seconds;

        Call(String eventId, String key, long startedMs, long seconds) {
            this.eventId = eventId;
            this.key = key;
            this.startedMs = startedMs;
            this.seconds = seconds;
        }
    }

    /**
     * The page's calls, oldest first: each event once, only ones with an event
     * and a start time, the newest {@link #MAX_CALLS} when it sends more.
     */
    static List<Call> calls(JSONArray raw) {
        List<Call> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; raw != null && i < raw.length(); i++) {
            JSONObject call = raw.optJSONObject(i);
            if (call == null) continue;
            String event = call.isNull("eventId") ? "" : call.optString("eventId", "");
            long started = call.optLong("startedMs");
            if (event.isEmpty() || started <= 0 || !seen.add(event)) continue;
            long seconds = Math.max(0, Math.min(MAX_SECONDS, call.optLong("seconds")));
            String number = call.isNull("number") ? "" : call.optString("number", "");
            calls.add(new Call(event, CallLogRules.key(number), started, seconds));
        }
        // Newest first to keep the newest, then oldest first to match.
        calls.sort((a, b) -> Long.compare(b.startedMs, a.startedMs));
        List<Call> kept = new ArrayList<>(calls.subList(0, Math.min(MAX_CALLS, calls.size())));
        kept.sort((a, b) -> Long.compare(a.startedMs, b.startedMs));
        return kept;
    }

    /**
     * A recording the phone's dialer saved: from the media index's call-recorder
     * folders, the maker's folders read by path, or the folder the seller picked
     * (RecordingScanner). Sales X-Ray's own files ("app:") are not old calls.
     */
    static boolean dialerRecording(JSONObject item) {
        if (item == null || !"dialer".equals(item.optString("captured", "dialer"))) return false;
        String source = item.optString("source", "");
        return source.startsWith("media:") || source.startsWith("file:") || source.startsWith("tree:");
    }

    /** The dialer's recordings saved inside the window, in the order given. */
    static List<JSONObject> window(List<JSONObject> items, long sinceMs) {
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject item : items) {
            if (dialerRecording(item) && item.optLong("addedMs") >= sinceMs) kept.add(item);
        }
        return kept;
    }

    /**
     * True when this recording was already given to a call: its source is one
     * taken ({@code sources}: RecordingQueue, automatic uploads), its name and
     * size are ({@code prints}: the same file reached another way), or the app's
     * own dials gave it to a call ({@code history}: CallRecordingPick's lines).
     */
    static boolean taken(JSONObject item, Set<String> sources, Set<String> prints, String history) {
        if (sources != null && sources.contains(item.optString("source"))) return true;
        String print = CallRecordingPick.fingerprint(item);
        if (!print.isEmpty() && prints != null && prints.contains(print)) return true;
        // No call of ours is named null, so every line in the history counts.
        return CallRecordingPick.usedElsewhere(history, null, item);
    }

    /** The phone already heard this recording and no one speaks in it (AudioLevels, kept on the item). */
    static boolean silent(JSONObject item) {
        JSONObject check = item.optJSONObject("check");
        return check != null && "silent".equals(check.optString("verdict"));
    }

    /** How sure the match is, as the page reads it. */
    static String fitName(int fit) {
        switch (fit) {
            case RecordingMatch.NUMBER:
                return "number";
            case RecordingMatch.CONTACT:
                return "contact";
            case RecordingMatch.PLAIN:
                return "time";
            default:
                return null;
        }
    }

    /** The calls that found their recording, and the recordings no call took. */
    static final class Result {

        final List<JSONObject> items = new ArrayList<>();
        final List<JSONObject> left = new ArrayList<>();
    }

    /**
     * Matches each call to one of {@code recordings} (in the window, none taken
     * already): the oldest call first, each recording once. A recording the
     * phone heard as silent is that call's, but useless: the call stays without
     * one. What is left (supported, not silent) comes back newest first, at most
     * {@link #MAX_UNMATCHED}.
     */
    static Result match(List<Call> calls, List<JSONObject> recordings, TimeZone zone) {
        Result result = new Result();
        Set<String> used = new HashSet<>();
        for (Call call : calls) {
            JSONObject chosen = RecordingMatch.best(recordings, call.key, call.startedMs, call.seconds, used, zone);
            if (chosen == null) continue;
            used.add(chosen.optString("source"));
            if (silent(chosen)) continue;
            String fit = fitName(RecordingMatch.fit(chosen, call.key, call.startedMs, call.seconds, zone));
            if (fit == null) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("eventId", call.eventId);
                item.put("fit", fit);
                item.put("recording", chosen);
            } catch (JSONException ignored) {
                // Keys and values here are always valid.
            }
            result.items.add(item);
        }
        List<JSONObject> left = new ArrayList<>();
        for (JSONObject recording : recordings) {
            if (used.contains(recording.optString("source"))) continue;
            if (!recording.optBoolean("supported", true) || silent(recording)) continue;
            left.add(recording);
        }
        left.sort((a, b) -> Long.compare(b.optLong("addedMs"), a.optLong("addedMs")));
        result.left.addAll(left.subList(0, Math.min(MAX_UNMATCHED, left.size())));
        return result;
    }

    /**
     * A recording no call took, as the page lists it: when it started by its own
     * clock (the time in its name, else when it was saved less its length) and
     * how long it is in seconds (0 when unknown).
     */
    static JSONObject unmatched(JSONObject recording, TimeZone zone) {
        long length = Math.max(0, recording.optLong("durationMs"));
        long stamp = RecordingMatch.read(recording.optString("name"), zone).stampMs;
        long started = stamp > 0 ? stamp : recording.optLong("addedMs") - length;
        JSONObject entry = new JSONObject();
        try {
            entry.put("startedMs", started);
            entry.put("seconds", length / 1000);
            entry.put("recording", recording);
        } catch (JSONException ignored) {
            // Keys and values here are always valid.
        }
        return entry;
    }
}
