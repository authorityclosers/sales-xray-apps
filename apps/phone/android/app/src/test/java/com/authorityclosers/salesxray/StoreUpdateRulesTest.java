package com.authorityclosers.salesxray;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When the Play build offers a new version (in-app updates, 23 Oct 2026). */
public class StoreUpdateRulesTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void anAvailableFlexibleUpdateIsOffered() {
        assertTrue(StoreUpdateRules.ask(true, true, 100023, -1, 0, NOW));
        assertFalse(StoreUpdateRules.ask(false, true, 100023, -1, 0, NOW));
        assertFalse(StoreUpdateRules.ask(true, false, 100023, -1, 0, NOW));
    }

    @Test
    public void noThanksHoldsThreeDaysForThatVersionOnly() {
        assertFalse(StoreUpdateRules.ask(true, true, 100023, 100023, NOW - DAY, NOW));
        assertTrue(StoreUpdateRules.ask(true, true, 100023, 100023, NOW - 4 * DAY, NOW));
        assertTrue(StoreUpdateRules.ask(true, true, 100024, 100023, NOW - DAY, NOW));
        // A clock set back before the "no thanks" doesn't hide updates for good.
        assertTrue(StoreUpdateRules.ask(true, true, 100023, 100023, NOW + DAY, NOW));
    }
}
