package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The phone's font size for the page (owner's OnePlus Nord, 24 Oct 2026). */
public class FontScaleRulesTest {

    @Test
    public void thePhonesSizesReachThePageAsTheyAre() {
        // Android's Small, Default, Large and Largest; the page does the clamping.
        assertEquals("0.85", FontScaleRules.value(0.85f));
        assertEquals("1", FontScaleRules.value(1.0f));
        assertEquals("1.15", FontScaleRules.value(1.15f));
        assertEquals("1.3", FontScaleRules.value(1.3f));
        assertEquals("2", FontScaleRules.value(2.0f));
        assertEquals("1.1", FontScaleRules.value(1.1f));
        assertEquals(1.15, FontScaleRules.number(1.15f), 0.0);
    }

    @Test
    public void aBrokenValueIsTheDefault() {
        assertEquals("1", FontScaleRules.value(0f));
        assertEquals("1", FontScaleRules.value(-1f));
        assertEquals("1", FontScaleRules.value(Float.NaN));
        assertEquals("3", FontScaleRules.value(Float.POSITIVE_INFINITY));
    }

    @Test
    public void textStaysAtItsDesignedSize() {
        // Any text zoom grows the px type but not the icons (orchestrator, 24 Oct 2026).
        assertEquals(100, FontScaleRules.TEXT_ZOOM);
    }

    @Test
    public void pageZoomWaitsForTheWebThatDoesIt() {
        // The page scales itself only from the v0.88 web on; until then it keeps its designed size.
        assertEquals(false, FontScale.PAGE_ZOOM);
    }

    @Test
    public void theScriptOnlySetsTheNumber() {
        assertEquals("window.SXFontScale=1.15;", FontScaleRules.script("1.15"));
    }
}
