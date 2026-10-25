package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Alerts by urgency (owner, 25 Oct 2026). */
public class AlertRulesTest {

    private static final String LEAD = "0f8e6b52-7d1c-4f3a-9b2e-5a6c7d8e9f01";

    @Test
    public void urgentWhenThePushOrTheWordsSaySo() {
        assertTrue(AlertRules.urgent("urgent", null));
        assertTrue(AlertRules.urgent(null, " URGENT "));
        assertFalse(AlertRules.urgent(null, null));
        assertFalse(AlertRules.urgent("normal", ""));
        assertFalse(AlertRules.urgent("urgently", null));
    }

    @Test
    public void wordsComeFromTheLeadOrTheKind() {
        assertEquals("/v1/lead-desk/leads/" + LEAD + "/alert", AlertRules.wordsPath(true, LEAD, "new"));
        assertEquals("/v1/lead-desk/alert", AlertRules.wordsPath(false, null, "many"));
        assertEquals("/v1/lead-desk/alert?kind=nudge", AlertRules.wordsPath(false, null, "nudge"));
        // Only nudge goes into the address: the route refuses a query it doesn't know.
        assertEquals("/v1/lead-desk/alert", AlertRules.wordsPath(false, null, "new"));
        assertEquals("/v1/lead-desk/alert", AlertRules.wordsPath(false, null, "nudge&x=1"));
        assertEquals("/v1/lead-desk/alert", AlertRules.wordsPath(false, null, null));
    }

    @Test
    public void aNudgeHasOfflineWordsAndOpensLeadsWithItsCard() {
        assertEquals(2, AlertRules.nudgeWords().length);
        assertEquals("/leads?nudge=1", AlertRules.NUDGE_PATH);
        // The app opens it: Leads with a query is one of its own pages.
        assertTrue(AlertRules.NUDGE_PATH.startsWith("/leads?"));
    }
}
