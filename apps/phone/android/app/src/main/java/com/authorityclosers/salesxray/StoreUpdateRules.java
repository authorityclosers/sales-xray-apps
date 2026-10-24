package com.authorityclosers.salesxray;

/**
 * When the Play build offers a new version (Google Play in-app updates, owner 23 Oct 2026; priorities,
 * owner 25 Oct 2026). Plain Java, for JVM tests; StoreUpdates (src/play) asks Play.
 */
final class StoreUpdateRules {

    /** What the app does about a version Play has. */
    enum Offer {
        /** Nothing to offer, or "Later" still holds. */
        NONE,
        /** "Update available" in the middle of the screen, with Later; it downloads while the seller works. */
        PROMPT,
        /** A release marked high priority: Play's full-screen update, no Later. */
        IMMEDIATE,
    }

    /** "Later" holds for three days per version, so testers aren't asked at every start. */
    static final long ASK_AGAIN_MS = 3L * 24 * 60 * 60 * 1000;
    /** Play's in-app update priority (0-5, set per release: play.yml update_priority) that forces the update. */
    static final int IMMEDIATE_PRIORITY = 4;

    private StoreUpdateRules() {}

    static Offer offer(
        boolean available,
        int priority,
        boolean flexibleAllowed,
        boolean immediateAllowed,
        int availableVersion,
        int declinedVersion,
        long declinedAtMs,
        long nowMs
    ) {
        if (!available) return Offer.NONE;
        // A major fix must be taken: "Later" never holds it back.
        if (priority >= IMMEDIATE_PRIORITY && immediateAllowed) return Offer.IMMEDIATE;
        if (!flexibleAllowed) return Offer.NONE;
        boolean declinedRecently = availableVersion == declinedVersion && nowMs >= declinedAtMs && nowMs - declinedAtMs < ASK_AGAIN_MS;
        return declinedRecently ? Offer.NONE : Offer.PROMPT;
    }
}
