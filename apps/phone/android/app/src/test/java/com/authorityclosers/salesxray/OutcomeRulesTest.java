package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TimeZone;
import org.junit.Test;

/** Call outcomes from the phone (owner via the orchestrator, 2 Nov 2026). */
public class OutcomeRulesTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_762_000_000_000L;

    @Test
    public void recordingsAreReadThirtyDaysBackOnceThenFromJustBeforeTheLastRead() {
        assertEquals(NOW - 30 * DAY, OutcomeRules.from(0, NOW));
        assertEquals(NOW - 2 * HOUR - 6 * HOUR, OutcomeRules.from(NOW - 2 * HOUR, NOW));
        // Never past 30 days, and a clock that went back never reads the future.
        assertEquals(NOW - 30 * DAY, OutcomeRules.from(NOW - 40 * DAY, NOW));
        assertEquals(NOW - 6 * HOUR, OutcomeRules.from(NOW + DAY, NOW));
    }

    @Test
    public void atMostOneReadAnHour() {
        assertTrue(OutcomeRules.due(0, NOW));
        assertFalse(OutcomeRules.due(NOW - 10 * 60_000, NOW));
        assertTrue(OutcomeRules.due(NOW - HOUR, NOW));
        assertTrue(OutcomeRules.due(NOW + HOUR, NOW));
    }

    @Test
    public void aFirstCallHistoryReadSendsOnlyCallsOutPastAWeek() {
        assertTrue(OutcomeRules.backfillRow("missed", NOW - 2 * DAY, NOW));
        assertTrue(OutcomeRules.backfillRow("outgoing", NOW - 20 * DAY, NOW));
        assertFalse(OutcomeRules.backfillRow("incoming", NOW - 20 * DAY, NOW));
        assertFalse(OutcomeRules.backfillRow("missed", NOW - 8 * DAY, NOW));
    }

    @Test
    public void theSellersOneLine() {
        assertNull(OutcomeRules.filledLine(0));
        assertEquals("Filled in 1 call outcome from your phone", OutcomeRules.filledLine(1));
        assertEquals("Filled in 31 call outcomes from your phone", OutcomeRules.filledLine(31));
    }

    @Test
    public void aTenDayOldRecordingCountsForOutcomesButNotForUploads() throws Exception {
        TimeZone zone = TimeZone.getTimeZone("UTC");
        JSONObject book = new JSONObject().put("numbers", new JSONArray().put("+12025550123"));
        long stamp = 1793275200000L; // 2026-10-29 12:00 UTC
        String name = "Call recording 12025550123_261029120000.mp3";
        long saved = stamp + 20_000;
        long now = stamp + 10 * DAY;
        assertNull(CaptureRules.number(name, saved, now, 0, zone, book));
        assertEquals("+12025550123", CaptureRules.number(name, saved, now, 0, zone, book, OutcomeRules.FILL_MS));
        // Older than 30 days: never.
        assertNull(CaptureRules.number(name, saved, stamp + 31 * DAY, 0, zone, book, OutcomeRules.FILL_MS));
    }
}
