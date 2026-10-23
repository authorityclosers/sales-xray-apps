package com.authorityclosers.salesxray;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** "Open settings" instead of a dead button ("Set up this phone", 24 Oct 2026). */
public class PermissionRulesTest {

    @Test
    public void blockedOnlyAfterAskingWhenAndroidStopsExplaining() {
        // Never asked: Android has nothing to explain yet; the dialog will show.
        assertFalse(PermissionRules.blocked(false, false, false));
        // Denied once: Android explains, and the dialog shows again.
        assertFalse(PermissionRules.blocked(false, true, true));
        // Denied twice, or "Don't ask again": the dialog won't show.
        assertTrue(PermissionRules.blocked(false, true, false));
        // Granted is never blocked.
        assertFalse(PermissionRules.blocked(true, true, false));
    }
}
