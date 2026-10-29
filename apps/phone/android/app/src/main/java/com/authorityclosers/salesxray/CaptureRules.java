package com.authorityclosers.salesxray;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.TimeZone;
import org.json.JSONObject;

/** Automatic capture accepts only explicit, unambiguous numbers and valid clocks. */
final class CaptureRules {
    static final long MAX_BYTES = 100_000_000L;
    static final long UNDO_MS = 60_000L;
    static final long KEEP_MS = 3L * 24 * 60 * 60 * 1000;

    // Why a call waits on the phone (Redmi real-call test, 30 Oct 2026: each of these lost a call silently).
    // The watch retries the recoverable ones by itself once their cause is gone; the seller is told about all of them.
    static final String RETRY = "retry";
    static final String PROFILE = "profile";        // the edge's write-eligibility check: contact profile incomplete
    static final String ALLOWANCE = "allowance";    // no call minutes left in the workspace
    static final String SIGNED_OUT = "signed_out";  // no session on this phone
    static final String CONSENT = "consent";        // standing consent off or its terms changed: uploads turn off
    static final String NOTICES = "notices";        // upload notices blocked, so Undo can't be offered: uploads turn off
    static final String WORKSPACE = "workspace";    // the app is in another workspace than the call's
    static final String FOLDER = "folder";          // the recordings folder grant is gone
    static final String NETWORK = "network";        // the phone blocks the app's background network
    static final String ACCESS = "access";          // another refusal; tried again hourly
    static final String OFF = "off";                // the seller turned uploads off
    static final String REVIEW = "review";          // the server or the file says no: the seller uploads it by hand
    static final String EXPIRED = "expired";        // older than KEEP_MS
    // Highest first: what the capture-health card and the attention notice name.
    static final List<String> PRIORITY = Arrays.asList(
        CONSENT, NOTICES, SIGNED_OUT, PROFILE, ALLOWANCE, FOLDER, NETWORK, WORKSPACE, ACCESS, REVIEW, EXPIRED, OFF);
    static final long RENOTIFY_MS = 6L * 60 * 60 * 1000;
    static final long ACCESS_RETRY_MS = 60L * 60 * 1000;
    static final long NETWORK_FRESH_MS = 30L * 60 * 1000;

    static final String NET_OK = "ok";
    static final String NET_BLOCKED = "blocked";
    static final String NET_OFFLINE = "offline";

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

    /**
     * What a refused request means. The words are the server's {"detail"}; profileComplete and minutesLeft
     * are read back from write-eligibility and the allowance (null when that read failed too), so a
     * reworded message never changes the answer.
     */
    static String refusal(int code, String detail, Boolean profileComplete, Boolean minutesLeft) {
        if (code >= 500 || code == 429 || code == 408) return RETRY;
        String words = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (code == 401) return SIGNED_OUT;
        if (code == 403) {
            if (Boolean.FALSE.equals(profileComplete)) return PROFILE;
            if (profileComplete == null && words.contains("profile")) return PROFILE;
            // The profile is complete now: the refusal came just before the seller finished it.
            if (Boolean.TRUE.equals(profileComplete) && words.contains("profile")) return RETRY;
            return ACCESS;
        }
        if (code == 409) {
            if (words.contains("automatic analysis")) return CONSENT;
            if (words.contains("another workspace")) return WORKSPACE;
            // Minutes added since the refusal: try again at once.
            if (words.contains("allowance")) return Boolean.TRUE.equals(minutesLeft) ? RETRY : ALLOWANCE;
            if (words.isEmpty() && Boolean.FALSE.equals(minutesLeft)) return ALLOWANCE;
        }
        return REVIEW;
    }

    /** Minutes left in an allowance read ({"available_seconds", "unlimited"}); null when it says nothing. */
    static Boolean minutesLeft(JSONObject allowance) {
        if (allowance == null) return null;
        if (allowance.optBoolean("unlimited")) return true;
        if (!allowance.has("available_seconds") || allowance.isNull("available_seconds")) return null;
        return allowance.optLong("available_seconds") > 0;
    }

    /** These turn uploads off (the seller turns them on again); every other hold keeps them on. */
    static boolean stops(String reason) {
        return CONSENT.equals(reason) || NOTICES.equals(reason);
    }

    /** Held calls the watch tries again by itself. */
    static boolean recoverable(String reason) {
        return !REVIEW.equals(reason) && !EXPIRED.equals(reason) && PRIORITY.contains(reason);
    }

    /** Whether a held call goes back in the queue on this watch run (uploads are on and consent holds). */
    static boolean release(String reason, boolean sameIdentity, Boolean profileComplete, Boolean minutesLeft,
                           long heldAt, long now) {
        if (!recoverable(reason) || !sameIdentity) return false;
        if (PROFILE.equals(reason)) return Boolean.TRUE.equals(profileComplete);
        if (ALLOWANCE.equals(reason)) return Boolean.TRUE.equals(minutesLeft);
        if (ACCESS.equals(reason)) return now - heldAt >= ACCESS_RETRY_MS;
        // Signed in again, back in the workspace, consent on, folder re-picked, network back or uploads on:
        // the upload re-checks each of these itself before any byte leaves the phone.
        return true;
    }

    /** Where uploads start again when the seller turns them back on (never before the first turn-on). */
    static long resumeSince(long offAt, long now, boolean includeOffPeriod) {
        if (!includeOffPeriod || offAt <= 0 || offAt > now) return now;
        return Math.max(offAt, now - KEEP_MS);
    }

    /** The one state the capture-health card leads with. */
    static String health(boolean on, String stopped, String paused, Map<String, Integer> held,
                         long networkBlockedAt, long now) {
        // Off: why (consent, notices, signed out on this phone, or the seller's own switch).
        if (!on) return stopped == null || stopped.isEmpty() ? OFF : stopped;
        boolean network = networkBlockedAt > 0 && now - networkBlockedAt < NETWORK_FRESH_MS;
        for (String reason : PRIORITY) {
            if (reason.equals(paused)) return reason;
            Integer count = held.get(reason);
            if (count != null && count > 0 && !OFF.equals(reason)) return reason;
            if (NETWORK.equals(reason) && network) return reason;
        }
        return "ok";
    }

    /** A notice per reason at most every RENOTIFY_MS, and at once when the waiting count grows. */
    static boolean notifyAgain(long lastAt, int lastCount, int count, long now) {
        return lastAt <= 0 || now - lastAt >= RENOTIFY_MS || count > lastCount;
    }

    /** The attention notice: title, text and the page it opens. off: uploads are turned off now. */
    static String[] attention(String reason, int calls, boolean off) {
        if (off && SIGNED_OUT.equals(reason)) return new String[] {"Automatic call uploads are off", "You signed out on this phone. Sign in, then turn uploads on in Settings → Privacy → Calls.", "/settings/privacy#automatic-call-uploads"};
        String waiting = calls <= 0 ? "Your next lead calls wait on this phone." : calls == 1 ? "1 lead call waits on this phone." : calls + " lead calls wait on this phone.";
        switch (reason) {
            case PROFILE: return new String[] {"Add your name and phone to upload calls", waiting + " Complete your profile in Settings → Profile and they upload by themselves.", "/settings/profile"};
            case ALLOWANCE: return new String[] {"Call minutes used up", waiting + " They upload by themselves when your workspace has minutes again.", "/settings/privacy#calls-setup"};
            case SIGNED_OUT: return new String[] {"Sign in to upload your calls", waiting + " Open Sales X-Ray and sign in; they upload by themselves after.", "/leads"};
            case CONSENT: return new String[] {"Automatic call uploads are off", "Automatic analysis is off for you or its terms changed. Turn uploads on again in Settings → Privacy → Calls.", "/settings/privacy#automatic-call-uploads"};
            case NOTICES: return new String[] {"Automatic call uploads are off", "Upload notices are blocked, so Undo can't show. Allow them, then turn uploads on in Settings → Privacy → Calls.", "/settings/privacy#automatic-call-uploads"};
            case WORKSPACE: return new String[] {"Calls wait for your organisation", waiting + " Switch to your organisation workspace in Sales X-Ray to upload them.", "/leads"};
            case FOLDER: return new String[] {"Pick your recordings folder again", waiting + " Sales X-Ray can no longer read the recordings folder.", "/settings/privacy#calls-setup"};
            case NETWORK: return new String[] {"Your phone is holding back call uploads", waiting + " Set Sales X-Ray's battery use to No restrictions so it can upload in the background.", "/settings/privacy#calls-setup"};
            case EXPIRED: return new String[] {"Some calls were too old to upload", "Recordings older than 3 days are not uploaded by themselves. Open the lead to add one by hand.", "/leads"};
            default: return new String[] {"A call needs you", waiting + " Open the lead to upload its recording by hand.", "/leads"};
        }
    }

    /** ConnectivityManager's view for this app: Android 15+ reports a cached app's network as BLOCKED. */
    static String netState(boolean hasInfo, boolean blocked, boolean hasActive) {
        if (blocked || (hasInfo && !hasActive)) return NET_BLOCKED;
        return hasActive ? NET_OK : NET_OFFLINE;
    }
}
