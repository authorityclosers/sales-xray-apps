package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Logged lead calls waiting for their recording (owner, 22 Oct), and MIUI's hidden folder (#49). */
public class RecordingQueueRulesTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private static JSONObject entry(String event, long startedMs) throws Exception {
        return new JSONObject().put("eventId", event).put("leadId", "lead-1").put("startedMs", startedMs).put("seconds", 60);
    }

    @Test
    public void onlyConnectedCallsWantARecording() {
        assertTrue(CallLogRules.wantsRecording("outgoing", 13));
        assertTrue(CallLogRules.wantsRecording("incoming", 1));
        assertFalse(CallLogRules.wantsRecording("outgoing", 0));
        assertFalse(CallLogRules.wantsRecording("missed", 0));
        assertFalse(CallLogRules.wantsRecording("rejected", 5));
    }

    @Test
    public void theServersAnswerNamesEachCallsEvent() throws Exception {
        Map<String, JSONObject> rows = new HashMap<>();
        rows.put("101", new JSONObject().put("key", "9665278779").put("startedMs", NOW).put("seconds", 134).put("direction", "outgoing"));
        rows.put("102", new JSONObject().put("key", "9665278779").put("startedMs", NOW + HOUR).put("seconds", 0).put("direction", "missed"));
        JSONArray answer = new JSONArray()
            .put(new JSONObject().put("log_id", "101").put("lead_id", "lead-1").put("event_id", "event-1"))
            .put(new JSONObject().put("log_id", "102").put("lead_id", "lead-1").put("event_id", "event-2"))
            .put(new JSONObject().put("log_id", "999").put("lead_id", "lead-1").put("event_id", "event-3"));
        List<JSONObject> waiting = CallLogRules.waitingCalls(answer, rows);
        assertEquals(1, waiting.size());
        assertEquals("event-1", waiting.get(0).getString("eventId"));
        assertEquals("9665278779", waiting.get(0).getString("key"));
        assertEquals(134, waiting.get(0).getLong("seconds"));
        // An older server sends no "calls": nothing waits.
        assertEquals(0, CallLogRules.waitingCalls(null, rows).size());
    }

    @Test
    public void eachCallWaitsOnceNewestFirstForThreeDays() throws Exception {
        JSONArray queue = CallLogRules.enqueue(new JSONArray(), entry("a", NOW - HOUR), NOW);
        queue = CallLogRules.enqueue(queue, entry("b", NOW), NOW);
        queue = CallLogRules.enqueue(queue, entry("a", NOW - HOUR), NOW);
        assertEquals(2, queue.length());
        assertEquals("b", queue.getJSONObject(0).getString("eventId"));
        queue = CallLogRules.enqueue(queue, entry("old", NOW - 4 * 24 * HOUR), NOW);
        assertEquals(2, queue.length());
        assertEquals(1, CallLogRules.dropQueued(queue, "a").length());
    }

    @Test
    public void aCallLooksForItsRecordingForSixHours() throws Exception {
        assertTrue(CallLogRules.stillLooking(entry("a", NOW - HOUR), NOW));
        assertFalse(CallLogRules.stillLooking(entry("a", NOW - 7 * HOUR), NOW));
        JSONObject found = entry("a", NOW - HOUR).put("recording", new JSONObject().put("source", "file:/x.mp3"));
        assertFalse(CallLogRules.stillLooking(found, NOW));
    }

    @Test
    public void usedRecordingsAreRemembered() {
        String used = CallLogRules.remember("", "file:/a.mp3", 2);
        used = CallLogRules.remember(used, "file:/b.mp3", 2);
        used = CallLogRules.remember(used, "file:/c.mp3", 2);
        assertFalse(CallLogRules.remembered(used, "file:/a.mp3"));
        assertTrue(CallLogRules.remembered(used, "file:/c.mp3"));
    }

    @Test
    public void miuiNeedsAllFilesAccessOnAndroid11AndNewer() {
        RecorderRules.Profile miui = RecorderRules.profile("com.android.contacts", "xiaomi");
        assertTrue(RecorderRules.needsAllFiles(miui, 36, false));
        assertFalse(RecorderRules.needsAllFiles(miui, 36, true));
        assertFalse(RecorderRules.needsAllFiles(miui, 29, false));
        assertFalse(RecorderRules.needsAllFiles(RecorderRules.profile("com.samsung.android.dialer", "samsung"), 36, false));
    }
}
