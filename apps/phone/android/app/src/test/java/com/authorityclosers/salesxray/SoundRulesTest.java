package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The picked sound per moment (round-2 board 4, 20 Nov 2026). */
public class SoundRulesTest {

    @Test
    public void eachPushSoundsAsItsMoment() {
        assertEquals(SoundRules.NEW_LEAD, SoundRules.eventForPush("new"));
        assertEquals(SoundRules.NEW_LEAD, SoundRules.eventForPush("test"));
        assertEquals(SoundRules.FOLLOW_UP_DUE, SoundRules.eventForPush("due"));
        assertEquals(SoundRules.FOLLOW_UP_DUE, SoundRules.eventForPush("many"));
        assertEquals(SoundRules.NEW_MESSAGE, SoundRules.eventForPush("owner"));
        assertEquals(SoundRules.NEW_MESSAGE, SoundRules.eventForPush(null));
    }

    @Test
    public void onlyASoundOfTheMomentsKindNoneOrThePhonesOwn() {
        assertTrue(SoundRules.valid(SoundRules.NEW_LEAD, "warm-v1.mention"));
        assertTrue(SoundRules.valid(SoundRules.INCOMING_CALL, "A.ringtone"));
        assertTrue(SoundRules.valid(SoundRules.NEW_LEAD, "none"));
        assertTrue(SoundRules.valid(SoundRules.NEW_LEAD, "system"));
        assertFalse(SoundRules.valid(SoundRules.NEW_LEAD, "warm-v1.ringtone"));
        assertFalse(SoundRules.valid(SoundRules.NEW_LEAD, "nope-v9.mention"));
        assertFalse(SoundRules.valid("pulse.mention", "glass-v1.mention"));
        assertEquals("glass-v2.mention", SoundRules.resolve(SoundRules.NEW_LEAD, "A.error"));
        assertEquals("glass-v3.ringtone", SoundRules.resolve(SoundRules.INCOMING_CALL, null));
    }

    @Test
    public void rawNamesAndOneChannelPerMomentAndSound() {
        assertEquals("sx_glass_v2_mention", SoundRules.rawName("glass-v2.mention"));
        assertEquals("sx_a_ringtone", SoundRules.rawName("A.ringtone"));
        assertNull(SoundRules.rawName("none"));
        assertNull(SoundRules.rawName("../x.mention"));
        assertEquals("snd_new_lead_glass_v2_mention", SoundRules.channelId(SoundRules.NEW_LEAD, "glass-v2.mention"));
        assertEquals("snd_new_lead_none", SoundRules.channelId(SoundRules.NEW_LEAD, "none"));
        // A pick it can't play is the library's default, on that default's channel.
        assertEquals("snd_follow_up_due_glass_v2_reminder", SoundRules.channelId(SoundRules.FOLLOW_UP_DUE, "A.error"));
        assertTrue(SoundRules.channelId(SoundRules.NEW_LEAD, "system").startsWith(SoundRules.channelPrefix(SoundRules.NEW_LEAD)));
        assertFalse(SoundRules.channelId(SoundRules.NEW_MESSAGE, "system").startsWith(SoundRules.channelPrefix(SoundRules.NEW_LEAD)));
    }
}
