package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.authorityclosers.salesxray.RecordingReasonRules.Reason;
import org.junit.Test;

/** Why a call came back without a recording (demo P1, 25 Oct 2026). */
public class RecordingReasonRulesTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void theReasonFollowsWhatThePhoneTellsUs() {
        // The Note 8 Pro: Google's Phone app.
        assertEquals(Reason.PRIVATE_DIALER, RecordingReasonRules.reason("private", "private", false, true, true));
        // A dialler with recording off.
        assertEquals(Reason.RECORDER_OFF, RecordingReasonRules.reason("folder", "off", true, true, true));
        // The Redmi 13 on the Play build: recording on, the MIUI folder hidden, never picked.
        assertEquals(Reason.PICK_FOLDER, RecordingReasonRules.reason("folder", "on", true, true, true));
        assertEquals(Reason.NO_PERMISSION, RecordingReasonRules.reason("folder", "on", false, true, false));
        assertEquals(Reason.UNKNOWN, RecordingReasonRules.reason("folder", "unknown", false, true, true));
        // Hidden but no folder picker (Android 7): nothing better than checking settings.
        assertEquals(Reason.UNKNOWN, RecordingReasonRules.reason("folder", "on", true, false, true));
    }

    @Test
    public void onlyAnAnsweredOrLongCallIsExplained() {
        assertTrue(RecordingReasonRules.worthExplaining(true, 0, 1_000));
        assertFalse(RecordingReasonRules.worthExplaining(false, 0, 200_000));
        // No call history (the Play build): long enough to have been answered.
        assertTrue(RecordingReasonRules.worthExplaining(null, NOW, NOW + 40_000));
        assertFalse(RecordingReasonRules.worthExplaining(null, NOW, NOW + 10_000));
    }

    @Test
    public void theSameReasonWaitsSixHours() {
        assertTrue(RecordingReasonRules.ask(Reason.PICK_FOLDER, null, 0, NOW));
        assertFalse(RecordingReasonRules.ask(Reason.PICK_FOLDER, "PICK_FOLDER", NOW - HOUR, NOW));
        assertTrue(RecordingReasonRules.ask(Reason.PICK_FOLDER, "PICK_FOLDER", NOW - 7 * HOUR, NOW));
        assertTrue(RecordingReasonRules.ask(Reason.RECORDER_OFF, "PICK_FOLDER", NOW - HOUR, NOW));
        assertTrue(RecordingReasonRules.ask(Reason.PICK_FOLDER, "PICK_FOLDER", NOW + HOUR, NOW));
    }

    @Test
    public void eachReasonHasItsFixAndWords() {
        assertEquals("folder", RecordingReasonRules.fix(Reason.PICK_FOLDER));
        assertEquals("recorder", RecordingReasonRules.fix(Reason.RECORDER_OFF));
        assertEquals("recorder", RecordingReasonRules.fix(Reason.UNKNOWN));
        assertEquals("appSettings", RecordingReasonRules.fix(Reason.NO_PERMISSION));
        assertNull(RecordingReasonRules.fix(Reason.PRIVATE_DIALER));
        for (Reason reason : Reason.values()) assertEquals(3, RecordingReasonRules.words(reason).length);
    }
}
