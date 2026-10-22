package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

/** Silent call recordings, told apart on the phone (Redmi audit #53). */
public class AudioCheckRulesTest {

    /** {@code seconds} of 0.1 s frames at {@code db}. */
    private static double[] flat(double seconds, double db) {
        double[] frames = new double[(int) Math.round(seconds / AudioCheckRules.FRAME_SECONDS)];
        Arrays.fill(frames, db);
        return frames;
    }

    /** Talk: voice at {@code voice} dB with short pauses at {@code pause} dB, a third of the time. */
    private static double[] talk(double seconds, double voice, double pause) {
        double[] frames = flat(seconds, voice);
        for (int i = 0; i < frames.length; i++) if (i % 3 == 2) frames[i] = pause;
        return frames;
    }

    @Test
    public void theRedmisNightRecordingsAreSilent() {
        // About -60 dB all through, with a click or two at -23.
        double[] frames = flat(134, -61);
        frames[40] = -23;
        frames[41] = -30;
        JSONObject check = AudioCheckRules.verdict(frames);
        assertEquals("silent", check.optString("verdict"));
        assertEquals(134.0, check.optDouble("measuredSeconds"), 0.01);
    }

    @Test
    public void aConversationIsOk() {
        // Voices near -30 dB, line noise near -48 between them.
        assertEquals("ok", AudioCheckRules.verdict(talk(120, -30, -48)).optString("verdict"));
        // The far end alone, like the 22 Oct call to an automated line (-23 dB mean).
        assertEquals("ok", AudioCheckRules.verdict(talk(25, -23, -45)).optString("verdict"));
    }

    @Test
    public void tooLittleAudioIsUnknown() {
        assertEquals("unknown", AudioCheckRules.verdict(new double[0]).optString("verdict"));
        assertEquals("unknown", AudioCheckRules.verdict(null).optString("verdict"));
        assertEquals("unknown", AudioCheckRules.verdict(flat(2, -20)).optString("verdict"));
    }

    @Test
    public void decibelsOfAFrame() {
        assertEquals(0.0, AudioCheckRules.decibels(1.0), 0.001);
        assertEquals(-20.0, AudioCheckRules.decibels(0.1), 0.001);
        assertEquals(-100.0, AudioCheckRules.decibels(0), 0.001);
    }
}
