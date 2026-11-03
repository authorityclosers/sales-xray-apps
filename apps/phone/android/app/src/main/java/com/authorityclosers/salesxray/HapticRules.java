package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * One haptics engine for the whole app (owner, 25 Oct 2026: alerts by urgency, with haptics). The page
 * gets window.SXHaptics with its tokens: tap, success, warning, error, and toggleOn, toggleOff and
 * longPress for switches and held rows (native feel, 3 Nov 2026), on Android's own touch feedback (the
 * phone's touch-feedback setting decides); and urgent, three firm pulses that only play when the phone
 * may buzz. Plain Java, for JVM tests; Haptics plays them.
 */
final class HapticRules {

    static final String[] TOKENS = {"tap", "success", "warning", "error", "urgent", "toggleOn", "toggleOff", "longPress"};

    /** What the phone does for a token. */
    enum Feel {
        TAP,
        CONFIRM,
        LONG_PRESS,
        REJECT,
        URGENT,
        TOGGLE_ON,
        TOGGLE_OFF,
    }

    /** Urgent: three firm pulses (ms off, on, off, on, off, on) at full strength. */
    static final long[] URGENT_TIMINGS = {0, 90, 70, 90, 70, 220};
    static final int[] URGENT_AMPLITUDES = {0, 255, 0, 255, 0, 255};

    private HapticRules() {}

    /** A token in any case or spelling (toggleOn, toggle-on), or one of the bridge's older kinds (press, done); anything else is a tap. */
    static Feel feel(String kind) {
        switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "")) {
            case "success":
            case "done":
                return Feel.CONFIRM;
            case "warning":
            case "press":
            case "longpress":
                return Feel.LONG_PRESS;
            case "toggleon":
                return Feel.TOGGLE_ON;
            case "toggleoff":
                return Feel.TOGGLE_OFF;
            case "error":
                return Feel.REJECT;
            case "urgent":
                return Feel.URGENT;
            default:
                return Feel.TAP;
        }
    }

    /** Silent or do-not-disturb: urgent becomes the error feel, which the touch-feedback setting still governs. */
    static Feel allowed(Feel feel, boolean silent, boolean doNotDisturb) {
        return feel == Feel.URGENT && (silent || doNotDisturb) ? Feel.REJECT : feel;
    }

    /** window.SXHaptics, before the page's own scripts: each token posts "haptic" to the SXPhone bridge. */
    static String script() {
        StringBuilder tokens = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        for (String token : TOKENS) {
            if (tokens.length() > 0) tokens.append(',');
            tokens.append('"').append(token).append('"');
            calls.append(token).append(":function(){play(\"").append(token).append("\")},");
        }
        return "(function(){if(window.SXHaptics)return;"
            + "function play(kind){try{if(window.SXPhone)window.SXPhone.postMessage(JSON.stringify("
            + "{id:\"sxh-\"+Date.now(),method:\"haptic\",args:{kind:String(kind)}}))}catch(e){}}"
            + "window.SXHaptics=Object.freeze({" + calls + "play:play,tokens:Object.freeze([" + tokens + "])})})();";
    }
}
