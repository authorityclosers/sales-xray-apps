package com.authorityclosers.salesxray;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Alerts by urgency (owner, 25 Oct 2026): a push that says urgency "urgent" (a nudge back to work, or an
 * urgent alert from Lead OS) goes to the "Urgent work" channel with its own sound; the rest stay on their
 * normal channels. Do-not-disturb always applies. Plain Java, for JVM tests; LeadAlerts posts them.
 */
final class AlertRules {

    static final String URGENT = "urgent";
    /** A push's kind, as Sales X-Ray sends it: lowercase letters and underscores. */
    private static final Pattern KIND = Pattern.compile("^[a-z_]{1,24}$");

    private AlertRules() {}

    /** Urgent when the push or the alert's words from Sales X-Ray say so. */
    static boolean urgent(String pushUrgency, String wordsUrgency) {
        return is(pushUrgency) || is(wordsUrgency);
    }

    private static boolean is(String urgency) {
        return urgency != null && URGENT.equals(urgency.trim().toLowerCase(Locale.ROOT));
    }

    /** Where the words for an alert come from: the lead's own, or Lead Desk's for its kind. */
    static String wordsPath(boolean one, String leadId, String kind) {
        if (one) return "/v1/lead-desk/leads/" + leadId + "/alert";
        if (kind != null && !"many".equals(kind) && KIND.matcher(kind).matches()) return "/v1/lead-desk/alert?kind=" + kind;
        return "/v1/lead-desk/alert";
    }

    /** Offline words for a nudge back to work (Sales X-Ray's own words win when they arrive). */
    static String[] nudgeWords() {
        return new String[] {"Back to your leads", "Your leads are waiting. Tap to see who to call next."};
    }
}
