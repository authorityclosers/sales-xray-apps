package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.authorityclosers.salesxray.HapticRules.Feel;
import org.junit.Test;

/** One haptics engine for the whole app (owner, 25 Oct 2026). */
public class HapticRulesTest {

    @Test
    public void tokensHaveTheirFeel() {
        assertEquals(Feel.TAP, HapticRules.feel("tap"));
        assertEquals(Feel.CONFIRM, HapticRules.feel("success"));
        assertEquals(Feel.LONG_PRESS, HapticRules.feel("warning"));
        assertEquals(Feel.REJECT, HapticRules.feel("error"));
        assertEquals(Feel.URGENT, HapticRules.feel(" Urgent "));
        // The bridge's older kinds keep working; anything unknown is a tap.
        assertEquals(Feel.CONFIRM, HapticRules.feel("done"));
        assertEquals(Feel.LONG_PRESS, HapticRules.feel("press"));
        assertEquals(Feel.TAP, HapticRules.feel("boom"));
        assertEquals(Feel.TAP, HapticRules.feel(null));
    }

    @Test
    public void urgentStaysQuietWhenThePhoneIs() {
        assertEquals(Feel.URGENT, HapticRules.allowed(Feel.URGENT, false, false));
        assertEquals(Feel.REJECT, HapticRules.allowed(Feel.URGENT, true, false));
        assertEquals(Feel.REJECT, HapticRules.allowed(Feel.URGENT, false, true));
        // The others are touch feedback, which the phone's own setting governs.
        assertEquals(Feel.TAP, HapticRules.allowed(Feel.TAP, true, true));
        assertEquals(HapticRules.URGENT_TIMINGS.length, HapticRules.URGENT_AMPLITUDES.length);
    }

    @Test
    public void thePageGetsEveryTokenOnTheBridge() {
        String script = HapticRules.script();
        assertTrue(script.startsWith("(function(){if(window.SXHaptics)return;"));
        assertTrue(script.contains("method:\"haptic\""));
        for (String token : HapticRules.TOKENS) {
            assertTrue(token, script.contains(token + ":function(){play(\"" + token + "\")}"));
        }
        assertTrue(script.contains("tokens:Object.freeze([\"tap\",\"success\",\"warning\",\"error\",\"urgent\"])"));
    }
}
