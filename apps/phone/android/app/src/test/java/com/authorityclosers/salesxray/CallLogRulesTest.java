package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** UX study item 5: which call-history rows are work calls with a lead. */
public class CallLogRulesTest {

    @Test
    public void everyKindOfCallWithSomeoneHasADirection() {
        assertEquals("incoming", CallLogRules.direction(1));
        assertEquals("outgoing", CallLogRules.direction(2));
        assertEquals("missed", CallLogRules.direction(3));
        assertEquals("rejected", CallLogRules.direction(5));
        assertEquals("blocked", CallLogRules.direction(6));
        // Voicemail and calls answered on another device are not calls with the lead.
        assertNull(CallLogRules.direction(4));
        assertNull(CallLogRules.direction(7));
    }

    @Test
    public void numbersMatchOnTheirLastTenDigits() {
        assertEquals("9876543210", CallLogRules.key("+91 98765 43210"));
        assertEquals("9876543210", CallLogRules.key("098765-43210"));
        assertEquals("9876543210", CallLogRules.key("919876543210"));
        assertEquals("12345", CallLogRules.key("12345"));
        assertEquals("", CallLogRules.key(null));
    }

    @Test
    public void onlyTheWorkSimCounts() {
        assertTrue(CallLogRules.onWorkSim("8991-2", "", ""));
        assertTrue(CallLogRules.onWorkSim("8991-2", "8991-2", "3"));
        assertTrue(CallLogRules.onWorkSim("3", "8991-2", "3"));
        assertFalse(CallLogRules.onWorkSim("8991-1", "8991-2", "3"));
        assertFalse(CallLogRules.onWorkSim("1", "8991-2", ""));
        // A row that names no SIM is kept: it is a lead's number either way.
        assertTrue(CallLogRules.onWorkSim("", "8991-2", "3"));
        assertTrue(CallLogRules.onWorkSim(null, "8991-2", "3"));
    }

    @Test
    public void aCallPlacedFromTheAppIsNotLoggedTwice() {
        long now = 1_760_000_000_000L;
        String stored = CallLogRules.keepInApp("", CallLogRules.inAppLine("+91 98765 43210", now), now);
        assertTrue(CallLogRules.placedInApp(stored, "9876543210", now + 3_000));
        assertTrue(CallLogRules.placedInApp(stored, "9876543210", now - 10_000));
        // A later call to the same lead from the dialer is its own call.
        assertFalse(CallLogRules.placedInApp(stored, "9876543210", now + 10 * 60_000));
        assertFalse(CallLogRules.placedInApp(stored, "9123456789", now));
        assertFalse(CallLogRules.placedInApp(null, "9876543210", now));
    }

    @Test
    public void anInAppCallsRowGoesAgainUntilItTimedItsDial() throws Exception {
        // Orchestrator, 3 Nov 2026: the row times the app's own dial, which the page logs only
        // when the seller comes back, so it goes again until Sales Xray says it timed it.
        long now = 1_760_000_000_000L;
        JSONObject first = new JSONObject()
            .put("log_id", "701").put("number", "+919876543210").put("started_at", "x")
            .put("duration_seconds", 95).put("direction", "outgoing").put("in_app", true)
            .put("startedMs", now - 60_000);
        JSONObject second = new JSONObject(first.toString())
            .put("log_id", "702").put("startedMs", now - 30_000);
        JSONObject old = new JSONObject(first.toString())
            .put("log_id", "690").put("startedMs", now - CallLogRules.IN_APP_RESEND_MS - 1);
        JSONArray kept = CallLogRules.keepPending(
            new JSONArray().put(old).put(first),
            Arrays.asList(first, second),
            new JSONArray().put("701"),
            now
        );
        // 701 timed its dial, 690 is too old: only 702 goes again, once.
        assertEquals(1, kept.length());
        assertEquals("702", kept.getJSONObject(0).getString("log_id"));
        // Sent as Sales Xray takes it: the phone's own note of when it started stays here.
        JSONObject sent = CallLogRules.sendable(kept.getJSONObject(0));
        assertTrue(sent.getBoolean("in_app"));
        assertFalse(sent.has("startedMs"));
        assertEquals(95, sent.getInt("duration_seconds"));
    }

    @Test
    public void rememberedCallsLastADayAndStaySmall() {
        long now = 1_760_000_000_000L;
        String stored = "";
        for (int i = 0; i < 80; i++) stored = CallLogRules.keepInApp(stored, CallLogRules.inAppLine("98765432" + (10 + i), now + i), now + i);
        assertEquals(50, stored.split("\n").length);
        String later = CallLogRules.keepInApp(stored, CallLogRules.inAppLine("9123456789", now + 25L * 3600_000), now + 25L * 3600_000);
        assertEquals(1, later.split("\n").length);
        assertTrue(CallLogRules.keepInApp("junk\n,5", "9876543210,1", now).endsWith("9876543210,1"));
    }

    @Test
    public void aCallThatNeverConnectedSaysWhy() {
        // No SIM, no service or airplane mode wins over everything else.
        assertEquals("no_network", CallLogRules.missedResult(2, true, true));
        assertEquals("no_network", CallLogRules.missedResult(5, true, false));
        // Declined or blocked: "Cut the call".
        assertEquals("rejected", CallLogRules.missedResult(5, false, false));
        assertEquals("rejected", CallLogRules.missedResult(6, false, true));
        assertEquals("no_answer", CallLogRules.missedResult(2, false, true));
        assertEquals("unknown", CallLogRules.missedResult(2, false, false));
    }

    @Test
    public void phonesAreGroupedForTheSetupSteps() {
        assertEquals("xiaomi", CallLogRules.family("Xiaomi", "Redmi"));
        assertEquals("xiaomi", CallLogRules.family("Xiaomi", "POCO"));
        assertEquals("samsung", CallLogRules.family("samsung", "samsung"));
        assertEquals("vivo", CallLogRules.family("vivo", "iQOO"));
        assertEquals("realme", CallLogRules.family("realme", "realme"));
        assertEquals("oppo", CallLogRules.family("OPPO", "OPPO"));
        assertEquals("other", CallLogRules.family("Google", "google"));
        assertEquals("other", CallLogRules.family(null, null));
        assertTrue(CallLogRules.hasAutostart("xiaomi"));
        assertTrue(CallLogRules.hasAutostart("vivo"));
        assertFalse(CallLogRules.hasAutostart("samsung"));
        assertFalse(CallLogRules.hasAutostart("other"));
    }

    @Test
    public void callsOutsideWorkHoursStayOnThePhone() {
        int[] monToSat = { 0, 1, 2, 3, 4, 5 };
        int start = CallLogRules.minutes("09:30");
        int end = CallLogRules.minutes("19:00");
        assertEquals(570, start);
        assertEquals(-1, CallLogRules.minutes("9:30"));
        // Monday 19 Oct 2026, 09:30 India time is 04:00 UTC.
        long mondayOpen = 1_792_382_400_000L;
        assertTrue(CallLogRules.inWorkHours(mondayOpen, monToSat, start, end));
        assertFalse(CallLogRules.inWorkHours(mondayOpen - 60_000, monToSat, start, end));
        assertTrue(CallLogRules.inWorkHours(mondayOpen + (9 * 60 + 29) * 60_000L, monToSat, start, end));
        assertFalse(CallLogRules.inWorkHours(mondayOpen + (9 * 60 + 30) * 60_000L, monToSat, start, end));
        // Sunday 18 Oct at the same hour is not a work day.
        assertFalse(CallLogRules.inWorkHours(mondayOpen - 86_400_000L, monToSat, start, end));
        // No work hours set: every call counts.
        assertTrue(CallLogRules.inWorkHours(mondayOpen - 86_400_000L, new int[0], start, end));
        assertTrue(CallLogRules.inWorkHours(mondayOpen - 86_400_000L, null, start, end));
    }

    @Test
    public void unknownWorkNumbersStayOnThePhoneToOfferSaveAsLead() throws Exception {
        long now = 1_792_382_400_000L;
        long day = 86_400_000L;
        assertTrue(CallLogRules.worthSaving("9123456789", "outgoing", 40, ""));
        assertTrue(CallLogRules.worthSaving("9123456789", "missed", 0, ""));
        assertFalse(CallLogRules.worthSaving("9123456789", "outgoing", 0, ""));
        assertFalse(CallLogRules.worthSaving("121", "incoming", 60, ""));
        assertFalse(CallLogRules.worthSaving("9123456789", "rejected", 0, ""));
        String ignored = CallLogRules.addIgnored("", "9123456789", 200);
        assertFalse(CallLogRules.worthSaving("9123456789", "incoming", 60, ignored));
        assertEquals(ignored, CallLogRules.addIgnored(ignored, "9123456789", 200));
        assertEquals("2\n3", CallLogRules.addIgnored("1\n2", "3", 2));

        JSONArray list = CallLogRules.keepUnknown(new JSONArray(), "+91 91234 56789", "9123456789", now - 3_600_000, "outgoing", 40, now, 7 * day, 30);
        list = CallLogRules.keepUnknown(list, "09123456789", "9123456789", now - 60_000, "missed", 0, now, 7 * day, 30);
        list = CallLogRules.keepUnknown(list, "9988776655", "9988776655", now - 8 * day, "incoming", 30, now, 7 * day, 30);
        assertEquals(1, list.length());
        assertEquals(2, list.getJSONObject(0).getInt("count"));
        assertEquals("missed", list.getJSONObject(0).getString("direction"));
        list = CallLogRules.keepUnknown(list, "9988776655", "9988776655", now - 2 * day, "incoming", 30, now, 7 * day, 30);
        assertEquals("9123456789", list.getJSONObject(0).getString("key"));
        assertEquals(2, list.length());
        // A week later both are gone.
        assertEquals(0, CallLogRules.keepUnknown(list, null, null, 0, null, 0, now + 7 * day, 7 * day, 30).length());
        assertEquals(1, CallLogRules.dropUnknown(list, "9123456789").length());
    }

    @Test
    public void timesGoOutInUtc() {
        assertEquals("2025-10-09T08:53:20.000Z", CallLogRules.iso(1_760_000_000_000L));
    }
}
