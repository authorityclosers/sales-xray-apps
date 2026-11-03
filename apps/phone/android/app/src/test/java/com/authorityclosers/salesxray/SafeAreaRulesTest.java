package com.authorityclosers.salesxray;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Edge to edge, with safe-area variables from the app (native-feel spec, 3 Nov 2026, section 4). */
public class SafeAreaRulesTest {

    // Redmi 13 portrait at density 2.75: a 99 px status bar, a 66 px gesture bar; the keyboard 880 px.
    private static final int[] BARS = {0, 99, 0, 66};

    @Test
    public void untilThePageSaysSoTheAppPadsItAndItsVariablesAreZero() {
        assertArrayEquals(new int[] {0, 99, 0, 66}, SafeAreaRules.appPadding(false, BARS, 0));
        assertArrayEquals(new int[] {0, 99, 0, 880}, SafeAreaRules.appPadding(false, BARS, 880));
        assertArrayEquals(new int[] {0, 0, 0, 0}, SafeAreaRules.pageInsets(false, BARS, 880));
    }

    @Test
    public void aPageThatPadsItselfDrawsUnderTheBarsAndTheAppPadsOnlyTheKeyboard() {
        assertArrayEquals(new int[] {0, 0, 0, 0}, SafeAreaRules.appPadding(true, BARS, 0));
        assertArrayEquals(new int[] {0, 99, 0, 66}, SafeAreaRules.pageInsets(true, BARS, 0));
        // The keyboard covers the gesture bar: the app pads the keyboard, and the page's bottom is 0.
        assertArrayEquals(new int[] {0, 0, 0, 880}, SafeAreaRules.appPadding(true, BARS, 880));
        assertArrayEquals(new int[] {0, 99, 0, 0}, SafeAreaRules.pageInsets(true, BARS, 880));
    }

    @Test
    public void theVariablesAreCssPixels() {
        String script = SafeAreaRules.script(new int[] {0, 99, 0, 66}, 2.75f);
        assertTrue(script, script.contains("s.setProperty(\"--sx-safe-top\",\"36px\");"));
        assertTrue(script, script.contains("s.setProperty(\"--sx-safe-bottom\",\"24px\");"));
        assertTrue(script, script.contains("s.setProperty(\"--sx-safe-left\",\"0px\");"));
        assertTrue(script, script.contains("s.setProperty(\"--sx-safe-right\",\"0px\");"));
        // A fraction stays (a 3x phone's 80 px bar); nothing runs before the document has a root.
        assertTrue(SafeAreaRules.script(new int[] {0, 80, 0, 0}, 3f).contains("\"--sx-safe-top\",\"26.7px\""));
        assertTrue(script.startsWith("(function(){var d=document.documentElement;if(!d)return;"));
        assertEquals(4, SafeAreaRules.VARIABLES.length);
    }
}
