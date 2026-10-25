package com.authorityclosers.salesxray;

/**
 * When a permission is blocked for good (PermissionAsks). Plain Java, for JVM tests.
 *
 * Android says "no explanation" both before the first ask and once it won't ask again, and also after
 * its permissions were reset (adb, or Android removing an unused app's permissions). So "asked before"
 * is not enough (25 Oct 2026: setup said "Android won't ask again" when Android would ask). The app
 * remembers what Android answered instead: blocked only after a request came back denied at once (no
 * question was shown) or a second denial, and Android explaining again always wins.
 */
final class PermissionRules {

    /** A denial this soon after the request means Android showed no question. */
    static final long INSTANT_MS = 400;

    /** What the app remembers about one permission from Android's answers. */
    static final class Memory {

        final boolean deniedOnce;
        final boolean deniedForGood;

        Memory(boolean deniedOnce, boolean deniedForGood) {
            this.deniedOnce = deniedOnce;
            this.deniedForGood = deniedForGood;
        }
    }

    static final Memory NOTHING = new Memory(false, false);

    private PermissionRules() {}

    /**
     * After Android's answer to a request made `elapsedMs` before. Denied with an explanation: asked
     * again next time. Denied without one: for good when it came at once or after an earlier denial;
     * otherwise the seller only dismissed the question (Android 11+ asks again).
     */
    static Memory after(Memory before, boolean granted, boolean rationale, long elapsedMs) {
        if (granted) return NOTHING;
        if (rationale) return new Memory(true, false);
        boolean forGood = before.deniedOnce || before.deniedForGood || (elapsedMs >= 0 && elapsedMs < INSTANT_MS);
        return new Memory(before.deniedOnce, forGood);
    }

    /** Not granted, Android answered "never" before, and Android has nothing to explain now. */
    static boolean blocked(boolean granted, boolean deniedForGood, boolean rationale) {
        return !granted && deniedForGood && !rationale;
    }
}
