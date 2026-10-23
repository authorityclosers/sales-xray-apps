package com.authorityclosers.salesxray;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** "Picture of this screen" in Report a problem (Redmi bug #13, 23 Oct 2026). */
public class ScreenshotRulesTest {

    @Test
    public void theLongestEdgeFitsWhatThePageAsked() {
        // A Redmi Note 8 Pro's window, 1080 × 2340, asked for 1600 at most.
        assertArrayEquals(new int[] { 738, 1600 }, ScreenshotRules.size(1080, 2340, 1600));
        // Landscape, the same.
        assertArrayEquals(new int[] { 1600, 738 }, ScreenshotRules.size(2340, 1080, 1600));
        // Already small enough: as it is.
        assertArrayEquals(new int[] { 720, 1280 }, ScreenshotRules.size(720, 1280, 1600));
    }

    @Test
    public void oddRequestsStayWithinBounds() {
        assertArrayEquals(new int[] { 738, 1600 }, ScreenshotRules.size(1080, 2340, 0));
        assertArrayEquals(new int[] { 148, 320 }, ScreenshotRules.size(1080, 2340, 10));
        assertArrayEquals(new int[] { 1080, 2340 }, ScreenshotRules.size(1080, 2340, 99999));
        assertArrayEquals(new int[] { 0, 0 }, ScreenshotRules.size(0, 2340, 1600));
    }

    @Test
    public void formatAndQuality() {
        assertTrue(ScreenshotRules.png("PNG"));
        assertFalse(ScreenshotRules.png("jpeg"));
        assertFalse(ScreenshotRules.png(null));
        assertEquals(82, ScreenshotRules.quality(82));
        assertEquals(82, ScreenshotRules.quality(0));
        assertEquals(40, ScreenshotRules.quality(5));
        assertEquals(95, ScreenshotRules.quality(100));
    }
}
