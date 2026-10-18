package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void timesGoOutInUtc() {
        assertEquals("2025-10-09T08:53:20.000Z", CallLogRules.iso(1_760_000_000_000L));
    }
}
