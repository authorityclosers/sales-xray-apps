package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;

/** Silent and one-sided call recordings, told apart on the phone (Redmi audit #53, #60). */
public class AudioCheckRulesTest {

    /** {@code seconds} of 0.1 s frames at {@code db}. */
    private static double[] flat(double seconds, double db) {
        double[] frames = new double[(int) Math.round(seconds / AudioCheckRules.FRAME_SECONDS)];
        Arrays.fill(frames, db);
        return frames;
    }

    private static double[] join(double[]... parts) {
        int size = 0;
        for (double[] part : parts) size += part.length;
        double[] all = new double[size];
        int at = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
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
    public void aNormalConversationIsOk() {
        // Both people heard: voices near -30 dB, line noise near -48 between them.
        JSONObject check = AudioCheckRules.verdict(talk(120, -30, -48));
        assertEquals("ok", check.optString("verdict"));
    }

    @Test
    public void onlyTheSellersVoiceWithDeadAirBetweenIsOneSided() {
        // The seller speaks a quarter of the time; the customer's turns are dead air.
        double[] frames = join(flat(15, -28), flat(45, -63), flat(10, -28), flat(30, -64));
        assertEquals("one_sided", AudioCheckRules.verdict(frames).optString("verdict"));
        // A short call can't be judged that way.
        assertEquals("ok", AudioCheckRules.verdict(join(flat(5, -28), flat(20, -63))).optString("verdict"));
    }

    @Test
    public void tooLittleAudioIsUnknown() {
        assertEquals("unknown", AudioCheckRules.verdict(new double[0]).optString("verdict"));
        assertEquals("unknown", AudioCheckRules.verdict(null).optString("verdict"));
        assertEquals("unknown", AudioCheckRules.verdict(flat(2, -20)).optString("verdict"));
    }

    @Test
    public void theTestCallTellsWhichSideIsMissing() {
        // They count to ten while the seller is quiet, then the seller talks.
        double[] both = join(flat(1, -60), talk(9, -32, -50), flat(1, -50), talk(8, -25, -50));
        assertEquals("both", AudioCheckRules.testCall(both));
        // The Redmi 13: the other side comes out at -60.
        double[] onlyYou = join(flat(10, -61), flat(1, -61), talk(8, -25, -61));
        assertEquals("only_you", AudioCheckRules.testCall(onlyYou));
        double[] onlyThem = join(flat(1, -60), talk(9, -32, -50), flat(9, -62));
        assertEquals("only_them", AudioCheckRules.testCall(onlyThem));
        assertEquals("silent", AudioCheckRules.testCall(flat(20, -62)));
        assertEquals("unknown", AudioCheckRules.testCall(flat(8, -30)));
    }

    @Test
    public void decibelsOfAFrame() {
        assertEquals(0.0, AudioCheckRules.decibels(1.0), 0.001);
        assertEquals(-20.0, AudioCheckRules.decibels(0.1), 0.001);
        assertEquals(-100.0, AudioCheckRules.decibels(0), 0.001);
    }
}
