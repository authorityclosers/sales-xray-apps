package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** The files a recordings pick hands the page (3 Nov 2026 regression, U1). */
public class PickRulesTest {

    @Test
    public void aMultiplePickGivesEveryFile() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), PickRules.files(Arrays.asList("a", "b", "c", "d"), null));
    }

    @Test
    public void aSinglePickGivesItsFile() {
        assertEquals(Collections.singletonList("a"), PickRules.files(Collections.<String>emptyList(), "a"));
        assertEquals(Collections.singletonList("a"), PickRules.files(null, "a"));
    }

    @Test
    public void clipItemsWinAndBlanksAreLeftOut() {
        assertEquals(Arrays.asList("a", "b"), PickRules.files(Arrays.asList("a", null, "b"), "a"));
    }

    @Test
    public void nothingPickedGivesNone() {
        assertTrue(PickRules.files(null, null).isEmpty());
        assertTrue(PickRules.files(Arrays.asList((String) null), null).isEmpty());
    }
}
