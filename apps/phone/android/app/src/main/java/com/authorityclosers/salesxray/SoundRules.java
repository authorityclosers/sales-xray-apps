package com.authorityclosers.salesxray;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The sound picked for each moment (owner's round-2 board 4, 20 Nov 2026; brand kit sound/SOUND-PICKER.md).
 * Sales X-Ray keeps the picks (Settings › Sounds & alerts, /v1/lead-desk/sounds): a library sound id such as
 * "glass-v2.mention", "none" or "system" (the phone's own sound). Here: which moment a push sounds as, the
 * res/raw file of a sound, and the notification channel of a (moment, sound) pair, since Android never lets an
 * app change a channel's sound after it is made (one channel per pair, the old one deleted). Plain Java, for
 * JVM tests; SoundChannels makes the channels.
 */
final class SoundRules {

    static final String INCOMING_CALL = "sx.incoming_call";
    static final String NEW_LEAD = "sx.new_lead";
    static final String NEW_MESSAGE = "sx.new_message";
    static final String FOLLOW_UP_DUE = "sx.follow_up_due";
    static final String REPORT_READY = "sx.report_ready";
    static final String UPLOAD_FAILED = "sx.upload_failed";
    static final String NONE = "none";
    static final String SYSTEM = "system";

    /** The library's moments, their kinds, defaults and names (library.json events[]). */
    static final String[][] EVENTS = {
        {INCOMING_CALL, "ringtone", "glass-v3.ringtone", "Incoming call"},
        {NEW_LEAD, "mention", "glass-v2.mention", "New lead given to me"},
        {NEW_MESSAGE, "message", "glass-v2.message", "New message / update"},
        {FOLLOW_UP_DUE, "reminder", "glass-v2.reminder", "Follow-up due"},
        {REPORT_READY, "success", "glass-v1.success", "Call report ready"},
        {UPLOAD_FAILED, "error", "glass-v3.error", "Recording/upload problem"},
    };
    static final String[] PACKS = {
        "glass-v1", "glass-v2", "glass-v3", "warm-v1", "warm-v2", "warm-v3", "pulse-v1", "pulse-v2", "pulse-v3", "A", "B", "C",
    };
    private static final Pattern SOUND = Pattern.compile("^([A-Za-z0-9-]{1,16})\\.([a-z]{1,16})$");

    private SoundRules() {}

    private static String[] event(String id) {
        for (String[] event : EVENTS) if (event[0].equals(id)) return event;
        return null;
    }

    static boolean known(String event) {
        return event(event) != null;
    }

    static String defaultFor(String event) {
        String[] found = event(event);
        return found == null ? SYSTEM : found[2];
    }

    static String label(String event) {
        String[] found = event(event);
        return found == null ? "Alerts" : found[3];
    }

    /**
     * The moment a Lead Desk push sounds as: a new lead (or the phone's test) as "New lead given to me", a call or
     * follow-up that is due (one, many, the day's sweep, a task) as "Follow-up due", the team's and owners' alerts
     * as "New message / update". Nudges and urgent alerts keep their own Urgent work channel (AlertRules).
     */
    static String eventForPush(String kind) {
        if (kind == null) return NEW_MESSAGE;
        switch (kind) {
            case "new":
            case "test":
                return NEW_LEAD;
            case "due":
            case "many":
            case "sweep":
            case "task":
            case "late":
                return FOLLOW_UP_DUE;
            default:
                return NEW_MESSAGE;
        }
    }

    private static boolean isPack(String pack) {
        for (String known : PACKS) if (known.equals(pack)) return true;
        return false;
    }

    /** Whether this choice may play for this moment: none, the phone's own, or a library sound of its kind. */
    static boolean valid(String event, String choice) {
        String[] found = event(event);
        if (found == null || choice == null) return false;
        if (NONE.equals(choice) || SYSTEM.equals(choice)) return true;
        java.util.regex.Matcher match = SOUND.matcher(choice);
        return match.matches() && isPack(match.group(1)) && found[1].equals(match.group(2));
    }

    /** The pick when it is one this moment can play, else the library's default. */
    static String resolve(String event, String picked) {
        return valid(event, picked) ? picked : defaultFor(event);
    }

    /** A library sound's res/raw name: "glass-v2.mention" is sx_glass_v2_mention, "A.ringtone" sx_a_ringtone. */
    static String rawName(String sound) {
        java.util.regex.Matcher match = SOUND.matcher(sound == null ? "" : sound);
        if (!match.matches() || !isPack(match.group(1))) return null;
        return "sx_" + safe(match.group(1)) + "_" + match.group(2);
    }

    private static String safe(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    /** All of a moment's channels start with this, so the stale ones can be found and deleted. */
    static String channelPrefix(String event) {
        return "snd_" + safe(event.startsWith("sx.") ? event.substring(3) : event) + "_";
    }

    /** One channel per (moment, sound): snd_new_lead_glass_v2_mention, snd_new_lead_none, snd_new_lead_system. */
    static String channelId(String event, String choice) {
        return channelPrefix(event) + safe(resolve(event, choice));
    }
}
