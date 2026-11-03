package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The app's night mode is the page's theme key: Light unless the seller picked Dark (owner, 26 Oct 2026). */
public class ThemeRulesTest {

    @Test
    public void picksBecomeAndroidNightModes() {
        assertEquals(ThemeRules.LIGHT, ThemeRules.nightMode("light"));
        assertEquals(ThemeRules.DARK, ThemeRules.nightMode("dark"));
        // An old System pick, anything unknown and nothing at all are Light, as the page reads its key: a phone in
        // dark mode no longer opens a light page on a black ground.
        assertEquals(ThemeRules.LIGHT, ThemeRules.nightMode("system"));
        assertEquals(ThemeRules.LIGHT, ThemeRules.nightMode("sepia"));
        assertEquals(ThemeRules.LIGHT, ThemeRules.nightMode(null));
        assertEquals("light", ThemeRules.normalize("DARK"));
        assertEquals("light", ThemeRules.normalize("system"));
    }

    @Test
    public void barsAreKeptPerMode() {
        assertEquals("status_dark", ThemeRules.barsKey("status", true));
        assertEquals("nav_light", ThemeRules.barsKey("nav", false));
    }
}
