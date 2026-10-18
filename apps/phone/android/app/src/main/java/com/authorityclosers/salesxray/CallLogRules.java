package com.authorityclosers.salesxray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The plain rules of call logging (UX study item 5), kept free of Android so
 * they are tested on their own: which call-history rows are work calls with a
 * lead, and which were already placed from the app.
 */
final class CallLogRules {

    // android.provider.CallLog.Calls types.
    static final int INCOMING = 1;
    static final int OUTGOING = 2;
    static final int MISSED = 3;
    static final int REJECTED = 5;
    static final int BLOCKED = 6;

    /** A call placed from the app is the call-history row dialled within this window of it. */
    static final long IN_APP_BEFORE_MS = 15_000;
    static final long IN_APP_AFTER_MS = 120_000;
    static final long IN_APP_KEEP_MS = 24L * 60 * 60 * 1000;

    private CallLogRules() {}

    /** The way the call went, or null for rows that are not a call with someone (voicemail). */
    static String direction(int type) {
        switch (type) {
            case OUTGOING:
                return "outgoing";
            case INCOMING:
                return "incoming";
            case MISSED:
                return "missed";
            case REJECTED:
                return "rejected";
            case BLOCKED:
                return "blocked";
            default:
                return null;
        }
    }

    /**
     * Why a call placed from the app never connected (UX study item 6): the
     * after-call sheet pre-fills it. No SIM, no service or airplane mode is "no
     * network"; a declined or blocked row is "they cut the call"; a call that
     * rang its full time is "no answer"; anything else the seller answers.
     */
    static String missedResult(int type, boolean offline, boolean rangOut) {
        if (offline) return "no_network";
        if (type == REJECTED || type == BLOCKED) return "rejected";
        return rangOut ? "no_answer" : "unknown";
    }

    static String digits(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /** The last ten digits: +91, 0 and 91 prefixes don't matter. */
    static String key(String raw) {
        String digits = digits(raw);
        return digits.length() <= 10 ? digits : digits.substring(digits.length() - 10);
    }

    /**
     * True when the row is on the work SIM. An empty work SIM means every SIM; a
     * row that names no SIM is kept (it can only be a lead's number anyway).
     */
    static boolean onWorkSim(String account, String sim, String alt) {
        if (sim == null || sim.isEmpty() || account == null || account.isEmpty()) return true;
        return account.equals(sim) || (alt != null && !alt.isEmpty() && account.equals(alt));
    }

    /** One remembered in-app call, as a line: "<key>,<started ms>". */
    static String inAppLine(String number, long startedMs) {
        return key(number) + "," + startedMs;
    }

    /** Keeps the remembered in-app calls of the last day, newest 50, plus the new one. */
    static String keepInApp(String stored, String line, long now) {
        StringBuilder kept = new StringBuilder();
        String[] lines = stored == null || stored.isEmpty() ? new String[0] : stored.split("\n");
        for (int i = Math.max(0, lines.length - 49); i < lines.length; i++) {
            long started = startedOf(lines[i]);
            if (started <= 0 || now - started > IN_APP_KEEP_MS) continue;
            kept.append(lines[i]).append('\n');
        }
        return kept.append(line).toString();
    }

    /** True when this outgoing row is a call the seller placed from a lead in the app. */
    static boolean placedInApp(String stored, String key, long dateMs) {
        if (stored == null || key.isEmpty()) return false;
        for (String line : stored.split("\n")) {
            int comma = line.indexOf(',');
            if (comma <= 0 || !line.substring(0, comma).equals(key)) continue;
            long started = startedOf(line);
            if (dateMs >= started - IN_APP_BEFORE_MS && dateMs <= started + IN_APP_AFTER_MS) return true;
        }
        return false;
    }

    private static long startedOf(String line) {
        int comma = line.indexOf(',');
        if (comma <= 0) return -1;
        try {
            return Long.parseLong(line.substring(comma + 1).trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** ISO 8601 in UTC, as Sales Xray reads times. */
    static String iso(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(ms));
    }
}
