package com.authorityclosers.salesxray;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.authorityclosers.salesxray.PermissionRules.Memory;
import org.junit.Test;

/** "Open settings" instead of a dead button, and never when Android would still ask (24 and 25 Oct 2026). */
public class PermissionRulesTest {

    private static final long SHOWN = 3000;
    private static final long AT_ONCE = 60;

    @Test
    public void blockedOnlyWhenAndroidSaidNeverAndExplainsNothing() {
        assertTrue(PermissionRules.blocked(false, true, false));
        // Never answered "never": Android will ask (a first ask, or a reset).
        assertFalse(PermissionRules.blocked(false, false, false));
        // Android explains again: its question shows.
        assertFalse(PermissionRules.blocked(false, true, true));
        assertFalse(PermissionRules.blocked(true, true, false));
    }

    @Test
    public void deniedTwiceIsForGood() {
        Memory once = PermissionRules.after(PermissionRules.NOTHING, false, true, SHOWN);
        assertTrue(once.deniedOnce);
        assertFalse(once.deniedForGood);
        Memory twice = PermissionRules.after(once, false, false, SHOWN);
        assertTrue(twice.deniedForGood);
    }

    @Test
    public void aNoAtOnceMeansNoQuestionWasShown() {
        // Blocked before this app version kept answers: the first tap finds out.
        assertTrue(PermissionRules.after(PermissionRules.NOTHING, false, false, AT_ONCE).deniedForGood);
    }

    @Test
    public void aDismissedQuestionOrAResetIsAskedAgain() {
        // Android 11+: tapping outside the question is no denial.
        Memory dismissed = PermissionRules.after(PermissionRules.NOTHING, false, false, SHOWN);
        assertFalse(dismissed.deniedForGood);
        // 25 Oct: the permissions were reset after a grant, so the app remembers nothing against them.
        Memory granted = PermissionRules.after(new Memory(true, true), true, false, SHOWN);
        assertFalse(granted.deniedOnce);
        assertFalse(granted.deniedForGood);
        assertFalse(PermissionRules.blocked(false, granted.deniedForGood, false));
        // No time recorded (asked before this version): not taken for an instant no.
        assertFalse(PermissionRules.after(PermissionRules.NOTHING, false, false, -1).deniedForGood);
    }
}
