package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Predictive back: the app listens only while the page can go back (native feel, 3 Nov 2026). */
public class BackRulesTest {

    private static final int ANDROID_16 = 36;
    private static final int ANDROID_11 = 30;

    @Test
    public void aTabRootLeavesBackToAndroid() {
        // The page says it is on a tab root: Android plays its back-to-home, even with history behind it.
        assertFalse(BackRules.appHandles(ANDROID_16, 0, true));
        // Inside a lead (or any sheet the page pushed), the app takes Back through the page.
        assertTrue(BackRules.appHandles(ANDROID_16, 1, true));
        assertTrue(BackRules.appHandles(ANDROID_16, 2, false));
    }

    @Test
    public void aPageThatHasntSaidGoesByTheWebViewsHistory() {
        assertTrue(BackRules.appHandles(ANDROID_16, BackRules.UNKNOWN, true));
        assertFalse(BackRules.appHandles(ANDROID_16, BackRules.UNKNOWN, false));
        assertTrue(BackRules.appHandles(BackRules.SYSTEM_KEEPS_APP, BackRules.UNKNOWN, true));
    }

    @Test
    public void beforeAndroid12TheAppAlwaysListensSoBackNeverClosesTheScreen() {
        assertTrue(BackRules.appHandles(ANDROID_11, 0, false));
        assertTrue(BackRules.appHandles(ANDROID_11, BackRules.UNKNOWN, false));
    }

    @Test
    public void aMissingOrNegativeDepthIsUnknown() {
        assertEquals(BackRules.UNKNOWN, BackRules.depth(-1));
        assertEquals(BackRules.UNKNOWN, BackRules.depth(-7));
        assertEquals(0, BackRules.depth(0));
        assertEquals(3, BackRules.depth(3));
    }
}
