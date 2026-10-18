package com.authorityclosers.salesxray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The plain rules of call logging (UX study item 5), kept free of Android so
 * they are tested on their own (org.json is a plain library in the tests):
 * which call-history rows are work calls with a lead, which were already
 * placed from the app, and which unknown numbers to offer "Save as lead?".
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

    /**
     * The phone's family for the setup steps (UX study item 7), from Android's
     * maker and brand: Redmi and POCO are Xiaomi, iQOO is vivo.
     */
    static String family(String manufacturer, String brand) {
        String name = ((manufacturer == null ? "" : manufacturer) + " " + (brand == null ? "" : brand)).toLowerCase(Locale.ROOT);
        if (name.contains("xiaomi") || name.contains("redmi") || name.contains("poco")) return "xiaomi";
        if (name.contains("samsung")) return "samsung";
        if (name.contains("vivo") || name.contains("iqoo")) return "vivo";
        if (name.contains("realme")) return "realme";
        if (name.contains("oppo")) return "oppo";
        if (name.contains("oneplus")) return "oneplus";
        if (name.contains("huawei") || name.contains("honor")) return "huawei";
        return "other";
    }

    /** Brands that stop apps in the background unless "Autostart" is allowed for them. */
    static boolean hasAutostart(String family) {
        switch (family) {
            case "xiaomi":
            case "vivo":
            case "oppo":
            case "realme":
            case "oneplus":
            case "huawei":
                return true;
            default:
                return false;
        }
    }

    /** India time is UTC+5:30 all year. */
    private static final long IST_OFFSET_MS = (5 * 60 + 30) * 60_000L;

    /**
     * True when the call started inside the team's work hours (India time; days
     * 0 Monday to 6 Sunday, minutes from midnight). No days means no work
     * hours are set: every call counts. Outside them a call stays on the phone.
     */
    static boolean inWorkHours(long dateMs, int[] days, int startMinute, int endMinute) {
        if (days == null || days.length == 0) return true;
        long local = dateMs + IST_OFFSET_MS;
        long day = Math.floorDiv(local, 86_400_000L);
        int weekday = (int) Math.floorMod(day + 3, 7L); // 1 Jan 1970 was a Thursday
        int minute = (int) (Math.floorMod(local, 86_400_000L) / 60_000L);
        boolean workDay = false;
        for (int value : days) if (value == weekday) workDay = true;
        return workDay && minute >= startMinute && minute < endMinute;
    }

    /** "09:30" as minutes from midnight, or -1. */
    static int minutes(String clock) {
        if (clock == null || !clock.matches("\\d{2}:\\d{2}")) return -1;
        return Integer.parseInt(clock.substring(0, 2)) * 60 + Integer.parseInt(clock.substring(3));
    }

    /**
     * Worth asking "Save as lead?": a real phone number (not a short service
     * code), a call that connected or that they made and nobody took, and not
     * a number the seller marked "Not a lead".
     */
    static boolean worthSaving(String key, String direction, long seconds, String ignored) {
        if (key == null || key.length() < 8 || isIgnored(ignored, key)) return false;
        if ("missed".equals(direction)) return true;
        return ("outgoing".equals(direction) || "incoming".equals(direction)) && seconds > 0;
    }

    static boolean isIgnored(String ignored, String key) {
        return ignored != null && ("\n" + ignored + "\n").contains("\n" + key + "\n");
    }

    /** Adds a number to "Not a lead", keeping the newest {@code max}. */
    static String addIgnored(String ignored, String key, int max) {
        if (isIgnored(ignored, key)) return ignored;
        String all = ignored == null || ignored.isEmpty() ? key : ignored + "\n" + key;
        String[] lines = all.split("\n");
        int from = Math.max(0, lines.length - max);
        StringBuilder kept = new StringBuilder();
        for (int i = from; i < lines.length; i++) kept.append(i > from ? "\n" : "").append(lines[i]);
        return kept.toString();
    }

    /**
     * The unknown work numbers, newest first: adds this call (when {@code key}
     * is given) to its number, with a count of calls, drops numbers last seen
     * longer ago than {@code keepMs}, and keeps the newest {@code max}.
     */
    static JSONArray keepUnknown(
        JSONArray list,
        String number,
        String key,
        long dateMs,
        String direction,
        long seconds,
        long now,
        long keepMs,
        int max
    ) {
        List<JSONObject> items = new ArrayList<>();
        boolean merged = key == null;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || now - item.optLong("lastMs") > keepMs) continue;
            if (!merged && key.equals(item.optString("key"))) {
                merged = true;
                try {
                    item.put("count", item.optInt("count", 1) + 1);
                    if (dateMs >= item.optLong("lastMs")) {
                        item.put("lastMs", dateMs).put("direction", direction).put("seconds", seconds).put("number", number);
                    }
                } catch (JSONException ignored) {
                    // Keys and values here are always valid.
                }
            }
            items.add(item);
        }
        if (!merged && now - dateMs <= keepMs) {
            try {
                items.add(
                    new JSONObject()
                        .put("number", number)
                        .put("key", key)
                        .put("lastMs", dateMs)
                        .put("direction", direction)
                        .put("seconds", seconds)
                        .put("count", 1)
                );
            } catch (JSONException ignored) {
                // Keys and values here are always valid.
            }
        }
        items.sort((a, b) -> Long.compare(b.optLong("lastMs"), a.optLong("lastMs")));
        JSONArray kept = new JSONArray();
        for (int i = 0; i < Math.min(max, items.size()); i++) kept.put(items.get(i));
        return kept;
    }

    static JSONArray dropUnknown(JSONArray list, String key) {
        JSONArray kept = new JSONArray();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && !item.optString("key").equals(key)) kept.put(item);
        }
        return kept;
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
