package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** "Find my old calls" (20 Nov 2026): the last two weeks' lead calls matched to the phone's recordings. */
public class OldCallsRulesTest {

    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    /** 22 Oct 2026 02:18:12 IST (as RecordingMatchTest). */
    private static final long CALL = 1_792_615_692_000L;
    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = CALL + 2 * DAY;

    private static JSONObject item(String source, String name, long addedMs) throws Exception {
        return new JSONObject()
            .put("source", source)
            .put("name", name)
            .put("addedMs", addedMs)
            .put("durationMs", 0)
            .put("size", 1000 + name.length())
            .put("supported", true)
            .put("captured", "dialer");
    }

    private static JSONObject media(String name, long addedMs) throws Exception {
        return item("media:content://media/external/audio/media/" + Math.abs(name.hashCode()), name, addedMs);
    }

    private static JSONObject call(String event, String number, long startedMs, long seconds) throws Exception {
        return new JSONObject().put("eventId", event).put("number", number).put("startedMs", startedMs).put("seconds", seconds);
    }

    private static OldCallsRules.Result match(JSONArray calls, List<JSONObject> recordings) {
        return OldCallsRules.match(OldCallsRules.calls(calls), recordings, IST);
    }

    @Test
    public void theWindowIsTwoWeeksByDefaultAndNeverMoreThanAMonth() {
        assertEquals(NOW - 30 * DAY, OldCallsRules.since(0, NOW));
        assertEquals(NOW - 14 * DAY, OldCallsRules.since(NOW - 14 * DAY, NOW));
        assertEquals(NOW - 31 * DAY, OldCallsRules.since(NOW - 90 * DAY, NOW));
        assertEquals(NOW, OldCallsRules.since(NOW + DAY, NOW));
    }

    @Test
    public void accessSaysWhetherThePhoneCanSeeRecordings() {
        assertEquals("none", OldCallsRules.access(false, false, false));
        assertEquals("none", OldCallsRules.access(false, false, true));
        assertEquals("hidden", OldCallsRules.access(true, false, true));
        assertEquals("ok", OldCallsRules.access(true, false, false));
        // A picked folder is read through its own grant.
        assertEquals("ok", OldCallsRules.access(false, true, false));
    }

    @Test
    public void callsComeOldestFirstEachOnceAndAtMostThreeHundred() throws Exception {
        JSONArray raw = new JSONArray()
            .put(call("b", "+91 96652 78779", CALL + HOUR, 60))
            .put(call("a", "9665278779", CALL, 20))
            .put(call("a", "9665278779", CALL, 20))
            .put(new JSONObject().put("number", "9665278779").put("startedMs", CALL))
            .put(new JSONObject().put("eventId", JSONObject.NULL).put("startedMs", CALL))
            .put(call("no-start", "9665278779", 0, 20))
            .put(call("long", "9665278779", CALL + 2 * HOUR, 99_999));
        List<OldCallsRules.Call> calls = OldCallsRules.calls(raw);
        assertEquals(3, calls.size());
        assertEquals("a", calls.get(0).eventId);
        assertEquals("b", calls.get(1).eventId);
        assertEquals("9665278779", calls.get(1).key);
        assertEquals(14400, calls.get(2).seconds);
        assertEquals(0, OldCallsRules.calls(null).size());

        JSONArray many = new JSONArray();
        for (int i = 0; i < 350; i++) many.put(call("e" + i, "9665278779", CALL + i * HOUR, 30));
        List<OldCallsRules.Call> kept = OldCallsRules.calls(many);
        assertEquals(300, kept.size());
        // The newest 300 are kept, oldest of them first.
        assertEquals("e50", kept.get(0).eventId);
        assertEquals("e349", kept.get(299).eventId);
    }

    @Test
    public void eachFitIsNamedAsThePageReadsIt() {
        assertEquals("number", OldCallsRules.fitName(RecordingMatch.NUMBER));
        assertEquals("contact", OldCallsRules.fitName(RecordingMatch.CONTACT));
        assertEquals("time", OldCallsRules.fitName(RecordingMatch.PLAIN));
        assertNull(OldCallsRules.fitName(RecordingMatch.NONE));
    }

    @Test
    public void callsFindTheirRecordingsByNumberContactAndTime() throws Exception {
        JSONObject numbered = media("QA Test(00919665278779)_20261022021815.mp3", CALL + 21_000);
        // 04:18:15 IST: three seconds after the second call started.
        JSONObject contact = media("Call recording Ravi_261022_041815.m4a", CALL + 2 * HOUR + 80_000);
        JSONObject plain = media("Recording 7.m4a", CALL + 4 * HOUR + 40_000);
        // Another number, 09:18:15 IST: no call of the page's; the seller may put it on a lead.
        JSONObject other = media("(00918999438781)_20261022091815.mp3", CALL + 7 * HOUR + 60_000).put("durationMs", 45_000);
        JSONArray calls = new JSONArray()
            .put(call("e1", "+919665278779", CALL, 13))
            .put(call("e2", "9876543210", CALL + 2 * HOUR, 60))
            .put(call("e3", "9123456789", CALL + 4 * HOUR, 30));
        OldCallsRules.Result result = match(calls, Arrays.asList(other, plain, contact, numbered));
        assertEquals(3, result.items.size());
        assertEquals("e1", result.items.get(0).getString("eventId"));
        assertEquals("number", result.items.get(0).getString("fit"));
        assertSame(numbered, result.items.get(0).getJSONObject("recording"));
        assertEquals("contact", result.items.get(1).getString("fit"));
        assertSame(contact, result.items.get(1).getJSONObject("recording"));
        assertEquals("time", result.items.get(2).getString("fit"));
        assertSame(plain, result.items.get(2).getJSONObject("recording"));

        assertEquals(Collections.singletonList(other), result.left);
        JSONObject entry = OldCallsRules.unmatched(other, IST);
        // Its own clock: the time in its name.
        assertEquals(CALL + 7 * HOUR + 3_000, entry.getLong("startedMs"));
        assertEquals(45, entry.getLong("seconds"));
        assertSame(other, entry.getJSONObject("recording"));
        // No time in the name: when it was saved, less its length.
        JSONObject unnamed = media("Recording 12.m4a", CALL + HOUR).put("durationMs", 30_000);
        assertEquals(CALL + HOUR - 30_000, OldCallsRules.unmatched(unnamed, IST).getLong("startedMs"));
    }

    @Test
    public void theOldestCallChoosesFirstAndARecordingGoesToOneCall() throws Exception {
        JSONObject numbered = media("+919665278779.amr", CALL + 30_000);
        // The page sends the newer call first; the older one still gets the recording.
        JSONArray calls = new JSONArray()
            .put(call("newer", "9665278779", CALL + 20_000, 13))
            .put(call("older", "9665278779", CALL, 13));
        OldCallsRules.Result result = match(calls, Collections.singletonList(numbered));
        assertEquals(1, result.items.size());
        assertEquals("older", result.items.get(0).getString("eventId"));
        assertEquals(0, result.left.size());
    }

    @Test
    public void aSilentRecordingIsNeverOffered() throws Exception {
        JSONObject silent = media("Recording 9.m4a", CALL + 65_000).put("check", new JSONObject().put("verdict", "silent"));
        JSONObject heard = media("Recording 10.m4a", CALL + 5 * HOUR).put("check", new JSONObject().put("verdict", "ok"));
        JSONObject quiet = media("Recording 11.m4a", CALL + 6 * HOUR).put("check", new JSONObject().put("verdict", "silent"));
        assertTrue(OldCallsRules.silent(silent));
        assertFalse(OldCallsRules.silent(heard));
        OldCallsRules.Result result = match(new JSONArray().put(call("e1", "9000000001", CALL, 60)), Arrays.asList(silent, heard, quiet));
        // The silent file is this call's, but useless: the call stays without one, and it is not listed.
        assertEquals(0, result.items.size());
        assertEquals(Collections.singletonList(heard), result.left);
    }

    @Test
    public void onlyTheDialersRecordingsInsideTheWindowAreLookedAt() throws Exception {
        long since = NOW - 14 * DAY;
        JSONObject inside = media("Recording 1.m4a", since + HOUR);
        JSONObject before = media("Recording 2.m4a", since - HOUR);
        JSONObject file = item("file:/storage/emulated/0/Recordings/Call/Recording 3.m4a", "Recording 3.m4a", since + 2 * HOUR);
        JSONObject tree = item("tree:content://com.android.externalstorage.documents/tree/x/document/y", "Recording 4.m4a", since + 3 * HOUR);
        JSONObject app = item("app:sx-call-1.m4a", "sx-call-1.m4a", since + HOUR).put("captured", "accessibility");
        JSONObject spoken = item("app:note.m4a", "note.m4a", since + HOUR).put("captured", "in_app");
        assertEquals(
            Arrays.asList(inside, file, tree),
            OldCallsRules.window(Arrays.asList(inside, before, file, tree, app, spoken), since)
        );
    }

    @Test
    public void aRecordingAlreadyGivenToACallIsTaken() throws Exception {
        JSONObject item = media("Recording 5.m4a", CALL);
        String print = CallRecordingPick.fingerprint(item);
        assertFalse(OldCallsRules.taken(item, new HashSet<>(), new HashSet<>(), ""));
        // RecordingQueue or an automatic upload holds its source.
        assertTrue(OldCallsRules.taken(item, new HashSet<>(Collections.singletonList(item.getString("source"))), null, null));
        // The same file reached another way (an automatic upload read it from the picked folder).
        assertTrue(OldCallsRules.taken(item, null, new HashSet<>(Collections.singletonList(print)), null));
        // The app's own dial took it (CallRecordingPick's history: call, source, name and size).
        assertTrue(OldCallsRules.taken(item, null, null, "lead-1:123\tmedia:elsewhere\t" + print));
        assertTrue(OldCallsRules.taken(item, null, null, "lead-1:123\t" + item.getString("source") + "\t"));
        assertFalse(OldCallsRules.taken(item, null, null, "lead-1:123\tmedia:elsewhere\tother.m4a:5"));
    }

    @Test
    public void whatNoCallTookComesNewestFirstAtMostThirty() throws Exception {
        List<JSONObject> recordings = new ArrayList<>();
        for (int i = 0; i < 40; i++) recordings.add(media("Recording " + (100 + i) + ".m4a", CALL + i * HOUR));
        recordings.add(media("Recording 999.xyz", CALL + 50 * HOUR).put("supported", false));
        OldCallsRules.Result result = match(new JSONArray(), recordings);
        assertEquals(0, result.items.size());
        assertEquals(30, result.left.size());
        assertEquals("Recording 139.m4a", result.left.get(0).getString("name"));
        assertEquals("Recording 110.m4a", result.left.get(29).getString("name"));
    }
}
