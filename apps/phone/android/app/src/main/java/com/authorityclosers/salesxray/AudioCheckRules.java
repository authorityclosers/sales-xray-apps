package com.authorityclosers.salesxray;

import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Is anyone heard in a call recording? (Redmi audit 22 Oct, #53: recordings
 * of calls with nobody speaking on the line came back "no intelligible
 * dialogue".) Works on the loudness of short frames that AudioLevels
 * measures on the phone, so a silent recording is flagged before anything is
 * sent. Pure rules, tested on the JVM.
 *
 * Measured on the Redmi 13 that night: silent recordings averaged -57 to -67
 * dBFS with peaks of -23 to -52; normal ones -23 to -36 with peaks near -6.
 */
final class AudioCheckRules {

    /** Each frame is this long. */
    static final double FRAME_SECONDS = 0.1;
    /** A frame this loud holds someone's voice. */
    static final double SPEECH_DB = -42;
    /** Less voice than this in the whole recording: it is silent. */
    static final double SILENT_SPEECH_SECONDS = 1.5;

    private AudioCheckRules() {}

    /** "ok", "silent" or "unknown" (too little audio to tell), with the numbers behind it. */
    static JSONObject verdict(double[] frames) {
        JSONObject result = new JSONObject();
        int count = frames == null ? 0 : frames.length;
        double measured = count * FRAME_SECONDS;
        int speech = 0;
        for (int i = 0; i < count; i++) if (frames[i] >= SPEECH_DB) speech++;
        double speechSeconds = speech * FRAME_SECONDS;
        String verdict;
        if (count == 0 || measured < 3) verdict = "unknown";
        else if (speechSeconds < SILENT_SPEECH_SECONDS) verdict = "silent";
        else verdict = "ok";
        try {
            result.put("verdict", verdict);
            result.put("measuredSeconds", round(measured));
            result.put("speechSeconds", round(speechSeconds));
            result.put("typicalDb", round(percentile(frames, 0.5)));
            result.put("loudDb", round(percentile(frames, 0.95)));
        } catch (JSONException ignored) {
            // Keys and values here are always valid.
        }
        return result;
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
