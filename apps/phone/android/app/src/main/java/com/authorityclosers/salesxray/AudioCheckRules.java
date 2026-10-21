package com.authorityclosers.salesxray;

import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Is a call recording worth analysing? (Redmi audit 22 Oct, #53 and #60: on
 * the Redmi 13 the phone saved near-silent recordings, about -60 dB, and the
 * analysis came back "no intelligible dialogue".) Works on the loudness of
 * short frames that AudioLevels measures on the phone, so nothing is sent to
 * find out. Pure rules, tested on the JVM.
 *
 * Measured on the Redmi 13 that night: silent recordings averaged -57 to -67
 * dBFS with peaks of -23 to -52; normal ones -30 to -36 with peaks near -8.
 */
final class AudioCheckRules {

    /** Each frame is this long. */
    static final double FRAME_SECONDS = 0.1;
    /** A frame this loud holds someone's voice. */
    static final double SPEECH_DB = -42;
    /** Below this a frame is dead air: no voice and no line noise. */
    static final double DEAD_DB = -56;
    /** Less voice than this in the whole recording: it is silent. */
    static final double SILENT_SPEECH_SECONDS = 1.5;
    /** One-sided: long enough to judge, little voice, and mostly dead air between. */
    static final double ONE_SIDED_MIN_SECONDS = 40;
    static final double ONE_SIDED_MAX_SPEECH = 0.35;
    static final double ONE_SIDED_MIN_DEAD = 0.45;

    /** The test call: the other person talks first while the seller stays quiet. */
    static final double TEST_THEM_FROM = 1;
    static final double TEST_THEM_TO = 9;
    static final double TEST_YOU_FROM = 11;
    static final double TEST_HEARD_SECONDS = 1.5;

    private AudioCheckRules() {}

    /**
     * "ok", "silent", "one_sided" (maybe only one voice: a warning, not a
     * block) or "unknown" (too little audio to tell), with the numbers behind it.
     */
    static JSONObject verdict(double[] frames) {
        JSONObject result = new JSONObject();
        int count = frames == null ? 0 : frames.length;
        double measured = count * FRAME_SECONDS;
        int speech = 0;
        int dead = 0;
        for (int i = 0; i < count; i++) {
            if (frames[i] >= SPEECH_DB) speech++;
            else if (frames[i] < DEAD_DB) dead++;
        }
        double speechSeconds = speech * FRAME_SECONDS;
        String verdict;
        if (count == 0 || measured < 3) verdict = "unknown";
        else if (speechSeconds < SILENT_SPEECH_SECONDS) verdict = "silent";
        else if (
            measured >= ONE_SIDED_MIN_SECONDS &&
            (double) speech / count < ONE_SIDED_MAX_SPEECH &&
            (double) dead / count >= ONE_SIDED_MIN_DEAD
        ) verdict = "one_sided";
        else verdict = "ok";
        try {
            result.put("verdict", verdict);
            result.put("measuredSeconds", round(measured));
            result.put("speechSeconds", round(speechSeconds));
            result.put("deadShare", count == 0 ? 0 : round((double) dead / count));
            result.put("typicalDb", round(percentile(frames, 0.5)));
            result.put("loudDb", round(percentile(frames, 0.95)));
        } catch (JSONException ignored) {
            // Keys and values here are always valid.
        }
        return result;
    }

    /**
     * The setup test call (#60): "both" when the other person was heard in the
     * first seconds and the seller after; "only_you" when the other side is
     * missing (the Redmi 13's fault); "only_them"; "silent"; "unknown" when the
     * recording is too short to judge.
     */
    static String testCall(double[] frames) {
        int count = frames == null ? 0 : frames.length;
        if (count * FRAME_SECONDS < TEST_YOU_FROM + 2) return "unknown";
        double them = speech(frames, TEST_THEM_FROM, TEST_THEM_TO);
        double you = speech(frames, TEST_YOU_FROM, count * FRAME_SECONDS);
        boolean heardThem = them >= TEST_HEARD_SECONDS;
        boolean heardYou = you >= TEST_HEARD_SECONDS;
        if (heardThem && heardYou) return "both";
        if (heardYou) return "only_you";
        if (heardThem) return "only_them";
        return "silent";
    }

    private static double speech(double[] frames, double fromSeconds, double toSeconds) {
        int from = (int) Math.max(0, Math.round(fromSeconds / FRAME_SECONDS));
        int to = (int) Math.min(frames.length, Math.round(toSeconds / FRAME_SECONDS));
        int speech = 0;
        for (int i = from; i < to; i++) if (frames[i] >= SPEECH_DB) speech++;
        return speech * FRAME_SECONDS;
    }

    /** dBFS of a frame from its RMS (0..1); silence is -100. */
    static double decibels(double rms) {
        return rms <= 0.00001 ? -100 : Math.max(-100, 20 * Math.log10(rms));
    }

    private static double percentile(double[] frames, double share) {
        if (frames == null || frames.length == 0) return -100;
        double[] sorted = frames.clone();
        Arrays.sort(sorted);
        int index = (int) Math.min(sorted.length - 1, Math.max(0, Math.round(share * (sorted.length - 1))));
        return sorted[index];
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
