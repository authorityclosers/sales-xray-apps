package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Light, Dark or System in the app (owner, 24 Oct 2026). */
public class ThemeRulesTest {

    @Test
    public void picksBecomeAndroidNightModes() {
        assertEquals(ThemeRules.LIGHT, ThemeRules.nightMode("light"));
        assertEquals(ThemeRules.DARK, ThemeRules.nightMode("dark"));
        assertEquals(ThemeRules.FOLLOW_SYSTEM, ThemeRules.nightMode("system"));
        // Anything unknown follows the phone.
        assertEquals(ThemeRules.FOLLOW_SYSTEM, ThemeRules.nightMode("sepia"));
        assertEquals(ThemeRules.FOLLOW_SYSTEM, ThemeRules.nightMode(null));
        assertEquals("system", ThemeRules.normalize("DARK"));
    }

    @Test
    public void barsAreKeptPerMode() {
        assertEquals("status_dark", ThemeRules.barsKey("status", true));
        assertEquals("nav_light", ThemeRules.barsKey("nav", false));
    }
}
