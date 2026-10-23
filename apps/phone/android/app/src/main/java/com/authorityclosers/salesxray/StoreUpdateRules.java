package com.authorityclosers.salesxray;

/**
 * When the Play build offers a new version (Google Play in-app updates, owner
 * 23 Oct 2026). Plain Java, for JVM tests; StoreUpdates (src/play) asks Play.
 */
final class StoreUpdateRules {

    /** "No thanks" holds for three days per version, so testers aren't asked at every start. */
    static final long ASK_AGAIN_MS = 3L * 24 * 60 * 60 * 1000;

    private StoreUpdateRules() {}

    static boolean ask(boolean available, boolean flexibleAllowed, int availableVersion, int declinedVersion, long declinedAtMs, long nowMs) {
        if (!available || !flexibleAllowed) return false;
        boolean declinedRecently = availableVersion == declinedVersion && nowMs >= declinedAtMs && nowMs - declinedAtMs < ASK_AGAIN_MS;
        return !declinedRecently;
    }
}
