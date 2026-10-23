package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The phone's sound state on the page (sounds and haptics, owner 23 Oct 2026). */
public class DeviceSoundRulesTest {

    @Test
    public void ringerModes() {
        assertEquals("silent", DeviceSoundRules.ringer(0));
        assertEquals("vibrate", DeviceSoundRules.ringer(1));
        assertEquals("normal", DeviceSoundRules.ringer(2));
        assertEquals("normal", DeviceSoundRules.ringer(7));
    }

    @Test
    public void doNotDisturbIsAnyFilterButAll() {
        assertFalse(DeviceSoundRules.dnd(1));
        assertFalse(DeviceSoundRules.dnd(0));
        assertTrue(DeviceSoundRules.dnd(2));
        assertTrue(DeviceSoundRules.dnd(3));
        assertTrue(DeviceSoundRules.dnd(4));
    }

    @Test
    public void theScriptWritesOnlyKnownValues() {
        String on = DeviceSoundRules.script("vibrate", true);
        assertTrue(on.contains("setAttribute('data-sx-ringer','vibrate')"));
        assertTrue(on.contains("setAttribute('data-sx-dnd','on')"));
        String off = DeviceSoundRules.script("normal", false);
        assertTrue(off.contains("removeAttribute('data-sx-dnd')"));
        // Anything else is "normal": nothing from outside reaches the script.
        assertTrue(DeviceSoundRules.script("');alert(1);//", false).contains("'data-sx-ringer','normal'"));
        assertTrue(off.contains("sx-device-state"));
    }
}
