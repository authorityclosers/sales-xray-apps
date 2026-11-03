package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;
import org.junit.Test;

/**
 * A recording shared from Google's Phone app, matched to the seller's own call (owner, 3 Nov
 * 2026: two calls to the lead "testing" at 14:12 and 14:15 IST waited for recordings that could
 * never arrive). Fictional numbers only.
 */
public class ShareRulesTest {

    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    /** 3 Nov 2026, 14:20 IST. */
    private static final long NOW = 1793695800000L;
    private static final long MINUTE = 60_000L;

    private static ShareRules.Call call(long startedAgoMs, long durationMs) {
        return new ShareRules.Call("+91 00000 30702", "testing", NOW - startedAgoMs, durationMs);
    }

    @Test
    public void aRecordingFitsTheCallItWasMadeIn() {
        ShareRules.Call call = call(5 * MINUTE, 151_000);
        assertTrue(ShareRules.fits(149_000, call));
        // Recording starts once answered: a little shorter is the same call.
        assertTrue(ShareRules.fits(143_000, call));
        // Never much longer than the call, nor far shorter.
        assertFalse(ShareRules.fits(158_000, call));
        assertFalse(ShareRules.fits(100_000, call));
        // A short call allows 8 s.
        assertTrue(ShareRules.fits(13_000, call(MINUTE, 20_000)));
        assertFalse(ShareRules.fits(0, call));
        assertFalse(ShareRules.fits(10_000, call(MINUTE, 0)));
    }

    @Test
    public void theCallWithTheClosestLengthIsTheOneAndTheLatestBreaksATie() {
        ShareRules.Call first = call(8 * MINUTE, 42_000);
        ShareRules.Call second = call(5 * MINUTE, 95_000);
        assertSame(second, ShareRules.match(93_000, Arrays.asList(first, second), NOW));
        assertSame(first, ShareRules.match(41_000, Arrays.asList(first, second), NOW));
        ShareRules.Call earlier = call(30 * MINUTE, 60_000);
        ShareRules.Call later = call(10 * MINUTE, 60_000);
        assertSame(later, ShareRules.match(59_000, Arrays.asList(earlier, later), NOW));
    }

    @Test
    public void noCallMatchesOutsideThreeDaysOrWithoutOne() {
        long days = 24 * 60 * MINUTE;
        assertNull(ShareRules.match(60_000, Collections.singletonList(call(4 * days, 60_000)), NOW));
        assertNull(ShareRules.match(60_000, Collections.singletonList(call(-MINUTE, 60_000)), NOW));
        assertNull(ShareRules.match(60_000, Collections.emptyList(), NOW));
        assertNull(ShareRules.match(300_000, Collections.singletonList(call(MINUTE, 60_000)), NOW));
    }

    @Test
    public void theCallIsNamedAsTheSellerKnowsIt() {
        assertEquals("testing · today 14:15 · 2:31", ShareRules.label(call(5 * MINUTE, 151_000), NOW, IST));
        ShareRules.Call unnamed = new ShareRules.Call("+91 00000 30702", null, NOW - 24 * 60 * MINUTE, 9_000);
        assertEquals("+91 00000 30702 · yesterday 14:20 · 0:09", ShareRules.label(unnamed, NOW, IST));
        assertEquals("1 Nov", ShareRules.day(NOW - 2 * 24 * 60 * MINUTE, NOW, IST));
    }

    @Test
    public void aCopyKeepsAnExtensionTheUploadReads() {
        assertEquals("wav", ShareRules.extension("Call recording testing.wav", "audio/x-wav"));
        assertEquals("m4a", ShareRules.extension(null, "audio/mp4"));
        assertEquals("wav", ShareRules.extension("recording", "audio/wav"));
        assertEquals("mp3", ShareRules.extension("a.bin", "audio/mpeg"));
        assertEquals("ogg", ShareRules.extension(null, "audio/ogg"));
    }
}
