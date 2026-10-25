package com.authorityclosers.salesxray;

import java.util.Locale;

/**
 * Alerts by urgency (owner, 25 Oct 2026): a push that says urgency "urgent" (a nudge back to work, or an
 * urgent alert from Lead OS) goes to the "Urgent work" channel with its own sound; the rest stay on their
 * normal channels. Do-not-disturb always applies. Plain Java, for JVM tests; LeadAlerts posts them.
 */
final class AlertRules {

    static final String URGENT = "urgent";
    /** Leads with the urgent card on top (the words' url says the same once they arrive; Lead OS, 25 Oct 2026). */
    static final String NUDGE_PATH = "/leads?nudge=1";

    private AlertRules() {}

    /** Urgent when the push or the alert's words from Sales X-Ray say so. */
    static boolean urgent(String pushUrgency, String wordsUrgency) {
        return is(pushUrgency) || is(wordsUrgency);
    }

    private static boolean is(String urgency) {
        return urgency != null && URGENT.equals(urgency.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Where the words for an alert come from: the lead's own, a nudge's, or Lead Desk's. Only "nudge" goes
     * in the address: the alert route refuses a query it doesn't know (Lead OS, 25 Oct 2026).
     */
    static String wordsPath(boolean one, String leadId, String kind) {
        if (one) return "/v1/lead-desk/leads/" + leadId + "/alert";
        if ("nudge".equals(kind)) return "/v1/lead-desk/alert?kind=nudge";
        return "/v1/lead-desk/alert";
    }

    /** Offline words for a nudge back to work (Sales X-Ray's own words win when they arrive). */
    static String[] nudgeWords() {
        return new String[] {"Back to your leads", "Your leads are waiting. Tap to see who to call next."};
    }
}
