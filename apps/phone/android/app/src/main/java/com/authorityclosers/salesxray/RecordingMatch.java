package com.authorityclosers.salesxray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Which of the phone's recordings belongs to a call from the call history
 * (owner, 22 Oct: a dialler call with a lead is logged with its recording).
 * Pure rules, so they are tested on the JVM.
 *
 * A wrong match would put someone's private call on a lead, so it is strict:
 * - a file named after another phone number never matches;
 * - a file named after the lead's number matches when its own clock (the
 *   time in its name, or else when it was saved) fits the call;
 * - a file named after a contact matches only when the time in its name and
 *   the time it was saved both fit;
 * - a file with neither matches only when it was saved as the call ended and
 *   no other file fits.
 * A recording already used for another call is never used again.
 */
final class RecordingMatch {

    /** The recording starts when the call is answered: up to this long after the call-history time. */
    static final long STAMP_BEFORE_MS = 30_000;
    static final long STAMP_AFTER_MS = 90_000;
    /** The file is saved when the call ends; slow phones take a while. */
    static final long SAVED_BEFORE_MS = 5_000;
    static final long SAVED_AFTER_MS = 180_000;
    /** A file with no number or time in its name: saved right as the call ended. */
    static final long PLAIN_BEFORE_MS = 20_000;
    static final long PLAIN_AFTER_MS = 60_000;

    static final int NONE = 0;
    static final int PLAIN = 1;
    static final int CONTACT = 2;
    static final int NUMBER = 3;

    // 20261022021815, 20261022_021815 or 2026-10-22 02-18-15 (MIUI, OnePlus, Vivo).
    private static final Pattern LONG_STAMP = Pattern.compile(
        "(?<!\\d)(20\\d\\d)[-_]?(0[1-9]|1[0-2])[-_]?(0[1-9]|[12]\\d|3[01])[-_ T]?([01]\\d|2[0-3])[-_.:]?([0-5]\\d)[-_.:]?([0-5]\\d)(?!\\d)"
    );
    // 241005_101530 (Samsung).
    private static final Pattern SHORT_STAMP = Pattern.compile(
        "(?<!\\d)(\\d\\d)(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])[-_]([01]\\d|2[0-3])([0-5]\\d)([0-5]\\d)(?!\\d)"
    );
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private RecordingMatch() {}

    /** What a recording's file name says: the time in it (or 0) and the phone numbers in it. */
    static final class Name {

        final long stampMs;
        final List<String> numbers;

        Name(long stampMs, List<String> numbers) {
            this.stampMs = stampMs;
            this.numbers = numbers;
        }
    }

    static Name read(String fileName, TimeZone zone) {
        String base = fileName == null ? "" : fileName.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
        long stamp = 0;
        Matcher found = LONG_STAMP.matcher(base);
        if (found.find()) {
            stamp = clock(zone, Integer.parseInt(found.group(1)), found);
            base = base.substring(0, found.start()) + " " + base.substring(found.end());
        } else {
            found = SHORT_STAMP.matcher(base);
            if (found.find()) {
                stamp = clock(zone, 2000 + Integer.parseInt(found.group(1)), found);
                base = base.substring(0, found.start()) + " " + base.substring(found.end());
            }
        }
        List<String> numbers = new ArrayList<>();
        Matcher digits = DIGITS.matcher(base);
        while (digits.find()) if (digits.group().length() >= 7) numbers.add(digits.group());
        return new Name(stamp, numbers);
    }

    private static long clock(TimeZone zone, int year, Matcher found) {
        Calendar calendar = Calendar.getInstance(zone, Locale.ROOT);
        calendar.clear();
        calendar.set(
            year,
            Integer.parseInt(found.group(2)) - 1,
            Integer.parseInt(found.group(3)),
            Integer.parseInt(found.group(4)),
            Integer.parseInt(found.group(5)),
            Integer.parseInt(found.group(6))
        );
        return calendar.getTimeInMillis();
    }

    /** True when a number in a file name is this lead's (the last ten digits, or all of a shorter one). */
    static boolean sameNumber(String inName, String key) {
        if (inName == null || key == null || key.length() < 7) return false;
        int n = Math.min(10, Math.min(inName.length(), key.length()));
        if (n < 7) return false;
        return inName.substring(inName.length() - n).equals(key.substring(key.length() - n));
    }

    /**
     * How well one recording fits the call: NONE, PLAIN, CONTACT or NUMBER.
     * {@code key} is the lead's number (last ten digits); times are in ms.
     */
    static int fit(JSONObject item, String key, long startedMs, long seconds, TimeZone zone) {
        if (item == null || !item.optBoolean("supported", true)) return NONE;
        long endedMs = startedMs + Math.max(0, seconds) * 1000L;
        long saved = item.optLong("addedMs");
        // A known length that is far from the talk time is another call.
        long length = item.optLong("durationMs");
        if (length > 0 && Math.abs(length / 1000 - seconds) > Math.max(15, seconds / 4)) return NONE;
        Name name = read(item.optString("name"), zone);
        boolean stampFits = name.stampMs > 0 && name.stampMs >= startedMs - STAMP_BEFORE_MS && name.stampMs <= startedMs + STAMP_AFTER_MS;
        boolean savedFits = saved >= startedMs - SAVED_BEFORE_MS && saved <= endedMs + SAVED_AFTER_MS;
        boolean ours = false;
        for (String number : name.numbers) {
            if (sameNumber(number, key)) ours = true;
            else if (number.length() >= 10) return NONE;
        }
        if (ours) {
            if (name.stampMs > 0) return stampFits && savedFits ? NUMBER : NONE;
            return savedFits ? NUMBER : NONE;
        }
        if (name.stampMs > 0) return stampFits && savedFits ? CONTACT : NONE;
        boolean plainFits = saved >= endedMs - PLAIN_BEFORE_MS && saved <= endedMs + PLAIN_AFTER_MS;
        return plainFits ? PLAIN : NONE;
    }

    /**
     * The recording of this call among {@code items}, or null. Ones in
     * {@code used} belong to other calls. Two plain files that both fit are
     * too close to tell apart: neither is used.
     */
    static JSONObject best(List<JSONObject> items, String key, long startedMs, long seconds, Set<String> used, TimeZone zone) {
        long endedMs = startedMs + Math.max(0, seconds) * 1000L;
        JSONObject chosen = null;
        int chosenFit = NONE;
        int plain = 0;
        for (JSONObject item : items) {
            if (used != null && used.contains(item.optString("source"))) continue;
            int fit = fit(item, key, startedMs, seconds, zone);
            if (fit == NONE) continue;
            if (fit == PLAIN) plain++;
            boolean better = fit > chosenFit ||
                (fit == chosenFit && chosen != null &&
                    Math.abs(item.optLong("addedMs") - endedMs) < Math.abs(chosen.optLong("addedMs") - endedMs));
            if (better) {
                chosen = item;
                chosenFit = fit;
            }
        }
        if (chosenFit == PLAIN && plain > 1) return null;
        return chosen;
    }
}
