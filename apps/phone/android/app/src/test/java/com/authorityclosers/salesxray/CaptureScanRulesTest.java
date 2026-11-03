package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * sx.capture_scan (orchestrator, 3 Nov 2026): why a call waits for its recording, without the
 * phone. The codes must pass the server's patterns (telemetry/ingest.py), and nothing carries a
 * file name, a number or a name.
 */
public class CaptureScanRulesTest {

    private static final Pattern TOKEN = Pattern.compile("^[a-z][a-z0-9_]{0,47}$");
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$");
    private static final long NOW = 1_793_695_800_000L;
    private static final long MINUTE = 60_000L;

    @Test
    public void aLeadCallMatchesOneRecordingNearItsStart() {
        long start = NOW - 10 * MINUTE;
        assertEquals("matched", CaptureScanRules.callResult(start, Collections.singletonList(start + 20_000)));
        assertEquals("none", CaptureScanRules.callResult(start, Collections.singletonList(start + 30 * MINUTE)));
        assertEquals("none", CaptureScanRules.callResult(start, Collections.emptyList()));
        assertEquals("ambiguous", CaptureScanRules.callResult(start, Arrays.asList(start - MINUTE, start + MINUTE)));
    }

    @Test
    public void agesAreSecondsUpToThirtyDays() {
        assertEquals(120, CaptureScanRules.ageSeconds(NOW - 2 * MINUTE, NOW));
        assertEquals(-1, CaptureScanRules.ageSeconds(0, NOW));
        assertEquals(0, CaptureScanRules.ageSeconds(NOW + MINUTE, NOW));
        assertEquals(CaptureScanRules.MAX_AGE_S, CaptureScanRules.ageSeconds(1, NOW));
    }

    @Test
    public void theMediaIndexsRecorderFolderIsTheBusiestOneSayingCallOrRecord() {
        assertTrue(CaptureScanRules.recorderPath("Recordings/Call/"));
        assertTrue(CaptureScanRules.recorderPath("Music/Recordings/Call Recordings/"));
        assertTrue(CaptureScanRules.recorderPath("Record/PhoneRecord"));
        assertFalse(CaptureScanRules.recorderPath("Music/WhatsApp Audio"));
        assertFalse(CaptureScanRules.recorderPath(null));
        assertEquals("Music/Recordings/Call", CaptureScanRules.folder("Music/Recordings/Call/"));
        assertEquals("Recordings/Call", CaptureScanRules.folder("/storage/emulated/0/Recordings/Call"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Recordings/Sound records", 2);
        counts.put("Music/Recordings/Call", 9);
        assertEquals("Music/Recordings/Call", CaptureScanRules.busiest(counts));
        assertNull(CaptureScanRules.busiest(new LinkedHashMap<>()));
    }

    @Test
    public void pathsAndThePhoneAppPassTheServersPatterns() {
        for (String path : RecordingScanner.folders()) assertTrue(path, TOKEN.matcher(CaptureScanRules.token(path)).matches());
        assertEquals("music_recordings_call_recordings", CaptureScanRules.token("Music/Recordings/Call Recordings"));
        assertEquals("p_0_calls", CaptureScanRules.token("0 Calls"));
        assertEquals("none", CaptureScanRules.token(""));
        assertTrue(CaptureScanRules.token("a/" + String.join("", Collections.nCopies(60, "b"))).length() <= 48);
        for (String app : new String[] { "com.oplus.dialer", "com.google.android.dialer", "com.android.contacts", "com.a.very.long.package.name.for.a.dialer.app" })
            assertTrue(app, LABEL.matcher(CaptureScanRules.label(app)).matches());
        assertEquals("com.oplus.dialer", CaptureScanRules.label("com.oplus.dialer"));
    }

    @Test
    public void statesAndHoldsAreOnlyTheServersValues() {
        assertEquals("found", CaptureScanRules.folderState("found"));
        assertEquals("missing", CaptureScanRules.folderState("missing"));
        assertEquals("unknown", CaptureScanRules.folderState("something new"));
        assertEquals("allowance", CaptureScanRules.holdReason(CaptureRules.ALLOWANCE));
        assertEquals("review", CaptureScanRules.holdReason(CaptureRules.REVIEW));
        assertEquals("other", CaptureScanRules.holdReason(null));
    }
}
