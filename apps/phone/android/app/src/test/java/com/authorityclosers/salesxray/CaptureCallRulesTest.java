package com.authorityclosers.salesxray;

import static org.junit.Assert.*;

import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

/** Recordings named after a contact reach their lead call through the call history (SX-AUTOUPLOAD, 25 Nov 2026). */
public class CaptureCallRulesTest {
    final TimeZone zone = TimeZone.getTimeZone("Asia/Kolkata");
    // 2026-11-25 14:30:00 IST
    final long start = 1795597200000L;
    final String lead = "+919800000001";

    JSONObject item(String name, long savedMs, long durationMs, String source) throws Exception {
        return new JSONObject().put("name", name).put("addedMs", savedMs).put("durationMs", durationMs).put("source", source);
    }

    @Test public void contactNamedRecordingTakesItsLeadCall() throws Exception {
        CaptureCallRules.Call call = new CaptureCallRules.Call(lead, start, 240);
        // Samsung and OnePlus name a saved contact's recording after the contact, with the call's time.
        JSONObject file = item("Rohan Deshmukh_20261125_143002.m4a", start + 242_000, 239_000, "media:a");
        Map<CaptureCallRules.Call, JSONObject> found = CaptureCallRules.match(List.of(call), List.of(file), new HashSet<>(), zone);
        assertSame(file, found.get(call));
    }

    @Test public void anotherNumbersRecordingNeverMatches() throws Exception {
        CaptureCallRules.Call call = new CaptureCallRules.Call(lead, start, 240);
        JSONObject other = item("9800000002_20261125_143002.m4a", start + 242_000, 239_000, "media:b");
        assertTrue(CaptureCallRules.match(List.of(call), List.of(other), new HashSet<>(), zone).isEmpty());
    }

    @Test public void contactNamedAtAnotherTimeNeverMatches() throws Exception {
        CaptureCallRules.Call call = new CaptureCallRules.Call(lead, start, 240);
        JSONObject earlier = item("Rohan Deshmukh_20261125_120000.m4a", start - 2 * 3_600_000L + 240_000, 240_000, "media:c");
        assertTrue(CaptureCallRules.match(List.of(call), List.of(earlier), new HashSet<>(), zone).isEmpty());
    }

    @Test public void plainNameNeedsItsLengthAndOnlyOneFit() throws Exception {
        CaptureCallRules.Call call = new CaptureCallRules.Call(lead, start, 240);
        JSONObject unknownLength = item("Recording 7.m4a", start + 241_000, 0, "media:d");
        assertTrue(CaptureCallRules.match(List.of(call), List.of(unknownLength), new HashSet<>(), zone).isEmpty());
        JSONObject known = item("Recording 7.m4a", start + 241_000, 238_000, "media:d");
        assertSame(known, CaptureCallRules.match(List.of(call), List.of(known), new HashSet<>(), zone).get(call));
        JSONObject twin = item("Recording 8.m4a", start + 245_000, 238_000, "media:e");
        assertTrue(CaptureCallRules.match(List.of(call), List.of(known, twin), new HashSet<>(), zone).isEmpty());
    }

    @Test public void aRecordingGoesToOneCallAndUsedOnesAreSkipped() throws Exception {
        CaptureCallRules.Call first = new CaptureCallRules.Call(lead, start, 240);
        JSONObject file = item("Rohan Deshmukh_20261125_143002.m4a", start + 242_000, 239_000, "media:a");
        Set<String> used = new HashSet<>(Set.of("media:a"));
        assertTrue(CaptureCallRules.match(List.of(first), List.of(file), used, zone).isEmpty());
        Set<String> fresh = new HashSet<>();
        CaptureCallRules.match(List.of(first), List.of(file), fresh, zone);
        assertTrue(fresh.contains("media:a"));
    }

    @Test public void candidatesAreConnectedSettledCallsInsideTheWindow() {
        long now = start + 600_000;
        assertTrue(CaptureCallRules.candidate(start, 240, start - 1, now, 15_000));
        assertFalse(CaptureCallRules.candidate(start, 0, start - 1, now, 15_000)); // not connected
        assertFalse(CaptureCallRules.candidate(start, 240, start + 1, now, 15_000)); // before uploads were on
        assertFalse(CaptureCallRules.candidate(start, 240, start - 1, start + 245_000, 15_000)); // not settled
        assertFalse(CaptureCallRules.candidate(start, 20_000, start - 1, now + 30_000_000, 15_000)); // over 4 hours
    }

    @Test public void aJobForTheSameCallCoversIt() throws Exception {
        List<JSONObject> jobs = List.of(new JSONObject().put("number", lead).put("stamp", start + 2_000));
        assertTrue(CaptureCallRules.covered(jobs, lead, start));
        assertFalse(CaptureCallRules.covered(jobs, lead, start + 10 * 60_000));
        assertFalse(CaptureCallRules.covered(jobs, "+919800000002", start));
        assertEquals("9800000001", CaptureCallRules.key(lead));
    }
}
