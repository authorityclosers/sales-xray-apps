package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Gold review alerts: which page an alert opens, and its words. */
public class ReviewAlertRulesTest {

    private static final String ID = "0b6f7c1e-2d3a-4b5c-8d9e-0f1a2b3c4d5e";

    @Test
    public void oneNewReportOpensItsReview() {
        assertEquals("/review#call=" + ID, ReviewAlertRules.path("one", ID));
        assertEquals("/review#call=" + ID, ReviewAlertRules.path(null, ID));
        // Several at once, or an id that is not one, open the review list.
        assertEquals("/review", ReviewAlertRules.path("many", ID));
        assertEquals("/review", ReviewAlertRules.path("one", "x&call=other"));
        assertEquals("/review", ReviewAlertRules.path("one", ID.toUpperCase()));
        assertEquals("/review", ReviewAlertRules.path("one", null));
    }

    @Test
    public void alertsOpenOnlyTheReviewPages() {
        assertTrue(ReviewAlertRules.isPath("/review"));
        assertTrue(ReviewAlertRules.isPath("/review#call=" + ID));
        assertTrue(ReviewAlertRules.isPath("/review/figures"));
        assertFalse(ReviewAlertRules.isPath("/reviewer"));
        assertFalse(ReviewAlertRules.isPath("/leads"));
        assertFalse(ReviewAlertRules.isPath("//example.com/review"));
        assertFalse(ReviewAlertRules.isPath("https://example.com/review"));
        assertFalse(ReviewAlertRules.isPath(null));
    }

    @Test
    public void wordsSayHowManyWait() {
        assertEquals("New Report to review", ReviewAlertRules.title(1));
        assertEquals("New Report to review", ReviewAlertRules.title(0));
        assertEquals("12 Reports to review", ReviewAlertRules.title(12));
        assertEquals("Tap to open it in Sales X-Ray.", ReviewAlertRules.body(true, 1));
        assertEquals("A new one is ready. Tap to open it in Sales X-Ray.", ReviewAlertRules.body(true, 4));
        assertEquals("New ones are ready. Tap to see them in Sales X-Ray.", ReviewAlertRules.body(false, 4));
    }
}
