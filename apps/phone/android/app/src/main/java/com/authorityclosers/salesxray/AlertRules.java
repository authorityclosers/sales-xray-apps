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

    /** The Reminders channel (owner, 22 Nov 2026): a follow-up or a task that is due now. */
    static final String CHANNEL_REMINDERS = "reminders";
    static final String CHANNEL_URGENT = "urgent_work";
    static final String CHANNEL_LEADS = "lead_alerts";

    /**
     * Reminders buzz hard and long, so a seller across the room hears and feels one (owner, 22 Nov 2026:
     * "a proper vibrating, attention-grabbing notification"): ms off, on, off, on, off, on, off, on.
     */
    static final long[] REMINDER_TIMINGS = {0, 500, 200, 500, 200, 500, 400, 900};

    /** A reminder: the push says so (Sales X-Ray 1.95+), or it is the one lead whose call is due now. */
    static boolean reminder(String pushReminder, String kind) {
        return "1".equals(pushReminder == null ? null : pushReminder.trim()) || "due".equals(kind);
    }

    /** Which channel an alert goes on: urgent wins, then a reminder, else lead alerts. */
    static String channelFor(boolean urgent, boolean reminder) {
        if (urgent) return CHANNEL_URGENT;
        return reminder ? CHANNEL_REMINDERS : CHANNEL_LEADS;
    }

    /**
     * Ask once to let Sales X-Ray run unrestricted, so a reminder pushed while the phone sleeps still rings on
     * time: only once alerts are on, only while the phone still restricts the app, never twice.
     */
    static boolean askBattery(boolean alertsOn, boolean unrestricted, boolean askedBefore, int sdk) {
        return alertsOn && !unrestricted && !askedBefore && sdk >= 23;
    }

    /** Offline words for a nudge back to work (Sales X-Ray's own words win when they arrive). */
    static String[] nudgeWords() {
        return new String[] {"Back to your leads", "Your leads are waiting. Tap to see who to call next."};
    }
}
