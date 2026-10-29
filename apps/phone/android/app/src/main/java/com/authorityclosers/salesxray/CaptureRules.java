package com.authorityclosers.salesxray;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.TimeZone;
import org.json.JSONObject;

/** Automatic capture accepts only explicit, unambiguous numbers and valid clocks. */
final class CaptureRules {
    static final long MAX_BYTES = 100_000_000L;
    static final long UNDO_MS = 60_000L;
    static final long KEEP_MS = 3L * 24 * 60 * 60 * 1000;

    static String identity(JSONObject book) {
        if (book == null || !book.optBoolean("organisation_workspace") ||
            book.optString("tenant_id").isEmpty() || book.optString("person_id").isEmpty()) return "";
        return book.optString("tenant_id") + ":" + book.optString("person_id");
    }

    static boolean consent(JSONObject consent, String policy) {
        return consent != null && "on".equals(consent.optString("state")) &&
            consent.optBoolean("can_start") && policy != null && policy.length() == 64 &&
            policy.equals(consent.optString("terms_sha256"));
    }

    static boolean samePhone(String raw, String lead) {
        String a = raw.replaceAll("[^0-9]", "");
        String b = lead.replaceAll("[^0-9]", "");
        if (a.startsWith("00")) a = a.substring(2);
        if (b.startsWith("00")) b = b.substring(2);
        if (a.equals(b)) return a.length() >= 10;
        // Indian national/trunk spellings are explicit; foreign country codes never collapse to a suffix.
        if (a.length() == 11 && a.startsWith("0")) a = a.substring(1);
        return a.length() == 10 && b.length() == 12 && b.startsWith("91") && b.substring(2).equals(a);
    }

    static String number(String name, long saved, long now, long since, TimeZone zone, JSONObject book) {
        RecordingMatch.Name parsed = RecordingMatch.read(name, zone);
        if (parsed.stampMs <= 0 || parsed.stampMs < since || parsed.stampMs > now ||
            now - parsed.stampMs > KEEP_MS || saved < parsed.stampMs ||
            saved > parsed.stampMs + 4 * 60 * 60 * 1000L + 180_000 || now - saved < UNDO_MS) return null;
        String matched = null;
        org.json.JSONArray numbers = book.optJSONArray("numbers");
        for (String raw : parsed.numbers) {
            // A short suffix must never authorize a longer phone number.
            String key = CallLogRules.key(raw);
            if (key.length() < 10) return null;
            String found = null;
            for (int i = 0; numbers != null && i < numbers.length(); i++) {
                String lead = numbers.optString(i);
                if (samePhone(raw, lead)) {
                    if (found != null && !found.equals(lead)) return null;
                    found = lead;
                }
            }
            if (found == null || (matched != null && !matched.equals(found))) return null;
            matched = found;
        }
        return matched;
    }

    static String id(String scope, String source, long stamp) {
        return UUID.nameUUIDFromBytes(("sx-auto:" + scope + ":" + source + ":" + stamp)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
