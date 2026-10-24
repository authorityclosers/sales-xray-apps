package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import com.authorityclosers.salesxray.StoreUpdateRules.Offer;
import org.junit.Test;

/** When the Play build offers a new version (in-app updates, 23 Oct 2026; priorities, 25 Oct 2026). */
public class StoreUpdateRulesTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    private static Offer offer(boolean available, int priority, int declinedVersion, long declinedAt) {
        return StoreUpdateRules.offer(available, priority, true, true, 100029, declinedVersion, declinedAt, NOW);
    }

    @Test
    public void anAvailableUpdateIsOfferedInThePrompt() {
        assertEquals(Offer.PROMPT, offer(true, 0, -1, 0));
        assertEquals(Offer.PROMPT, offer(true, 3, -1, 0));
        assertEquals(Offer.NONE, offer(false, 0, -1, 0));
        assertEquals(Offer.NONE, StoreUpdateRules.offer(true, 0, false, true, 100029, -1, 0, NOW));
    }

    @Test
    public void laterHoldsThreeDaysForThatVersionOnly() {
        assertEquals(Offer.NONE, offer(true, 0, 100029, NOW - DAY));
        assertEquals(Offer.PROMPT, offer(true, 0, 100029, NOW - 4 * DAY));
        assertEquals(Offer.PROMPT, StoreUpdateRules.offer(true, 0, true, true, 100030, 100029, NOW - DAY, NOW));
        // A clock set back before the "Later" doesn't hide updates for good.
        assertEquals(Offer.PROMPT, offer(true, 0, 100029, NOW + DAY));
    }

    @Test
    public void priorityFourOrFiveForcesTheUpdateEvenAfterLater() {
        assertEquals(Offer.IMMEDIATE, offer(true, 4, -1, 0));
        assertEquals(Offer.IMMEDIATE, offer(true, 5, 100029, NOW - DAY));
        // Play doesn't allow an immediate update for it: the prompt still offers it.
        assertEquals(Offer.PROMPT, StoreUpdateRules.offer(true, 5, true, false, 100029, -1, 0, NOW));
        assertEquals(Offer.NONE, offer(false, 5, -1, 0));
    }
}
