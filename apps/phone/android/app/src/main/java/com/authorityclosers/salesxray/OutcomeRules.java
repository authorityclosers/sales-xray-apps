package com.authorityclosers.salesxray;

/**
 * Filling in the outcome of call attempts from what the phone kept (owner via the orchestrator,
 * 2 Nov 2026: 35 of a tester's 36 attempts in a day had none): how far back, how often, and the
 * seller's one line. Plain Java, for JVM tests; OutcomeBackfill and CallLogSync read the phone.
 */
final class OutcomeRules {

    /** How far back recordings and a first call-history read fill outcomes (the server's OUTCOME_FILL_DAYS). */
    static final long FILL_MS = 30L * 24 * 60 * 60 * 1000;
    /** Call history older than this is never a call of its own (the server's PHONE_LOG_DAYS). */
    static final long LOG_MS = 7L * 24 * 60 * 60 * 1000;
    /** The recordings are read at most this often. */
    static final long LOOK_EVERY_MS = 60L * 60 * 1000;
    /** Each read goes back this far before the last one: a dial is written only when the seller comes back. */
    static final long OVERLAP_MS = 6L * 60 * 60 * 1000;
    /** Recordings per request (the server takes at most 200). */
    static final int BATCH = 200;

    private OutcomeRules() {}

    static boolean due(long lastLookMs, long now) {
        return lastLookMs <= 0 || now < lastLookMs || now - lastLookMs >= LOOK_EVERY_MS;
    }

    /** Where a read of the recordings starts: 30 days back the first time, then a little before the last read. */
    static long from(long throughMs, long now) {
        long oldest = now - FILL_MS;
        return throughMs <= 0 ? oldest : Math.max(oldest, Math.min(now, throughMs) - OVERLAP_MS);
    }

    /** A first call-history read's row: past a week, only a call out can be the outcome of a dial. */
    static boolean backfillRow(String direction, long dateMs, long now) {
        return now - dateMs <= LOG_MS || "outgoing".equals(direction);
    }

    /** The seller's one line, or null when nothing was filled in. */
    static String filledLine(int filled) {
        if (filled <= 0) return null;
        return "Filled in " + filled + (filled == 1 ? " call outcome" : " call outcomes") + " from your phone";
    }
}
