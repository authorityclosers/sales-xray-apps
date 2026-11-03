package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void openBatterySettingsNeverOpensAQuestionThatClosesAtOnce() {
        // The direct build holds the permission: Android's own question.
        assertEquals(PermissionRules.BATTERY_ASK, PermissionRules.batteryScreen(true, "oneplus", 36));
        // HyperOS shows its Battery saver list for the question, permission or not.
        assertEquals(PermissionRules.BATTERY_ASK, PermissionRules.batteryScreen(false, "xiaomi", 35));
        // The Play build elsewhere (bug 147, OnePlus on Android 16): App info, one tap from Battery.
        assertEquals(PermissionRules.BATTERY_APP_INFO, PermissionRules.batteryScreen(false, "oneplus", 36));
        assertEquals(PermissionRules.BATTERY_APP_INFO, PermissionRules.batteryScreen(false, "other", 31));
        // Before Android 12, App info has no battery switch: the optimisation list.
        assertEquals(PermissionRules.BATTERY_LIST, PermissionRules.batteryScreen(false, "samsung", 30));
    }
}
