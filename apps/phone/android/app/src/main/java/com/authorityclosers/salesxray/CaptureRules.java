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
    /**
     * A recording no longer written to for this long is finished (the dialler closed it when the call ended).
     * It was UNDO_MS, which added a minute before every upload's own minute of Undo (fast upload, 18 Nov 2026).
     */
    static final long SETTLE_MS = 15_000L;
    static final long KEEP_MS = 3L * 24 * 60 * 60 * 1000;
    /** Uploads use any network unless the seller picks Wi-Fi only (owner, bug 120, 3 Nov 2026: "everyone has 5G"). */
    static final boolean WIFI_ONLY_DEFAULT = false;
    /** Clock slack around the Undo minute: an upload job due within UNDO_MS plus this waits in the job itself. */
    static final long UNDO_SLACK_MS = 5_000L;

    /**
     * Whether an upload job started before its Undo minute is over waits that minute out in the job itself.
     * Redmi 13 (HyperOS, device test 25 Nov 2026): a call detected 55 s after hang-up said "Uploads in 60 seconds"
     * and was still waiting 33 minutes later; a second, delayed job is one more thing a phone can drop, so the
     * upload job starts at once and waits here. Longer waits (only a held call's old due time) go back to WorkManager.
     */
    static boolean waitInJob(long due, long now) {
        return due > now && due - now <= UNDO_MS + UNDO_SLACK_MS;
    }

    // What became of a recording between its match and its upload (sourceChange).
    static final String SOURCE_SAME = "same";
    static final String SOURCE_SETTLING = "settling";
    static final String SOURCE_FINISHED = "finished";
    static final String SOURCE_CHANGED = "changed";

    /**
     * The recording's size and last-written time at upload against those at its match. A dialler that is still
     * closing or tagging the file (MIUI writes after the call ends) is waited for, then the finished file goes:
     * it is the same recording of the same call (same media row or document), only complete. A file that went
     * back in time or is empty is another file: held for the seller (REVIEW), as before.
     */
    static String sourceChange(long matchedSize, long matchedSaved, long size, long saved, long now) {
        if (size == matchedSize && saved == matchedSaved) return SOURCE_SAME;
        if (size <= 0 || saved < matchedSaved) return SOURCE_CHANGED;
        if (now - saved < SETTLE_MS) return SOURCE_SETTLING;
        return SOURCE_FINISHED;
    }

    /** The call's own notice once it is held: what happened, in the attention notice's words. Null: no notice. */
    static String[] heldNotice(String reason, String leadName, boolean off) {
        if (OFF.equals(reason)) return null;
        String[] words = attention(reason, 1, off);
        return new String[] {"Call with " + leadName + " not uploaded yet", words[1]};
    }

    // Why a call waits on the phone (Redmi real-call test, 30 Oct 2026: each of these lost a call silently).
    // The watch retries the recoverable ones by itself once their cause is gone; the seller is told about all of them.
    static final String RETRY = "retry";
    static final String PROFILE = "profile";        // the edge's write-eligibility check: contact profile incomplete
    static final String ALLOWANCE = "allowance";    // no call minutes left in the workspace
    static final String SIGNED_OUT = "signed_out";  // no session on this phone
    static final String CONSENT = "consent";        // standing consent turned off (or AI Off): uploads turn off
    // The upload terms (or the consent words) changed since the seller agreed (the 17 Nov 2026 provider switch did
    // this to every phone at once, and each one turned its uploads off). Now calls wait on the phone, uploads stay
    // on, the seller is asked to agree again, and the waiting calls go by themselves once they do.
    static final String TERMS = "terms";
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
        CONSENT, TERMS, NOTICES, SIGNED_OUT, PROFILE, ALLOWANCE, FOLDER, NETWORK, WORKSPACE, ACCESS, REVIEW, EXPIRED, OFF);
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

    // Why the server made no call from a recording (POST /recording-call). Remembered: never posted again.
    static final String REFUSED_HOURS = "hours";
    static final String REFUSED_EARLY = "before_lead";
    static final String REFUSED_OTHER = "refused";

    /** A recording the server won't make a call from, by its {"detail"} words; null when a later scan may succeed. */
    static String refused(int code, String detail) {
        if (code != 404 && code != 409 && code != 422) return null;
        String words = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (words.contains("another workspace")) return null;
        if (words.contains("outside work hours")) return REFUSED_HOURS;
        if (words.contains("before this lead was added")) return REFUSED_EARLY;
        return REFUSED_OTHER;
    }

    /**
     * One quiet notice for a refusal the seller can act on or should know of ({title, text, action, page}); null stays
     * silent. Outside work hours: "Upload anyway" asks the server again under the same terms (Mac, 25 Nov 2026 19:20;
     * from v1.99.1 the server takes a lead call at any hour). Before the lead: the server keeps refusing, so the
     * notice opens Calls, where Find my old calls offers it with Analyse (v1.99.1).
     */
    static String[] refusedNotice(String reason, String number) {
        String title = "Call with " + (number == null || number.isEmpty() ? "a lead" : number) + " not uploaded";
        if (REFUSED_HOURS.equals(reason)) return new String[] { title, "Recorded outside work hours.", "Upload anyway", "/leads" };
        if (REFUSED_EARLY.equals(reason)) return new String[] { title, "Made before this lead was added.", null, "/analysis/calls" };
        return null;
    }

    /** Where a call's upload notice opens: its lead's sheet, as lead alerts open it (any stage), else Leads. */
    static String leadPath(String leadId) {
        if (leadId == null || !leadId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return "/leads";
        return "/leads#lead=" + leadId;
    }

    static boolean consent(JSONObject consent, String policy) {
        return consent != null && "on".equals(consent.optString("state")) &&
            consent.optBoolean("can_start") && policy != null && policy.length() == 64 &&
            policy.equals(consent.optString("terms_sha256"));
    }

    /**
     * Why the seller's standing consent doesn't cover an upload now: null when it does; TERMS when they are asked
     * again (the upload terms or the words changed since they agreed, or the answer could not be read), so calls
     * wait and nothing turns off; CONSENT when they turned it off or their AI level is Off, so uploads turn off.
     */
    static String consentGap(JSONObject consent, String policy) {
        if (consent(consent, policy)) return null;
        if (consent == null || policy == null || policy.length() != 64) return TERMS;
        String state = consent.optString("state");
        if ("ask".equals(state)) return TERMS;
        if ("on".equals(state) && consent.optBoolean("can_start")) return TERMS;
        return CONSENT;
    }

    static boolean samePhone(String raw, String lead) {
        String a = raw.replaceAll("[^0-9]", "");
        String b = lead.replaceAll("[^0-9]", "");
        if (a.startsWith("00")) a = a.substring(2);
        if (b.startsWith("00")) b = b.substring(2);
        if (a.equals(b)) return a.length() >= 10;
        // An Indian toll-free or shared-cost number (1800, 1860) is dialled and logged without +91, while
        // the lead may hold it with +91 (phone QA, 3 Nov 2026: Jio care's 1800 line never reached its lead).
        if (tollFree(a) && b.equals("91" + a) || tollFree(b) && a.equals("91" + b)) return true;
        // Indian national/trunk spellings are explicit; foreign country codes never collapse to a suffix.
        if (a.length() == 11 && a.startsWith("0")) a = a.substring(1);
        return a.length() == 10 && b.length() == 12 && b.startsWith("91") && b.substring(2).equals(a);
    }

    /** An Indian toll-free or shared-cost number's national digits (1800 or 1860, then 6 or 7 digits). */
    static boolean tollFree(String digits) {
        return (digits.length() == 10 || digits.length() == 11) && (digits.startsWith("1800") || digits.startsWith("1860"));
    }

    /** The number to dial: an Indian toll-free one without +91, which its line doesn't take; others as they are. */
    static String dialable(String number) {
        if (number == null) return "";
        String digits = number.replaceAll("[^0-9]", "");
        if (number.trim().startsWith("+") && digits.startsWith("91") && tollFree(digits.substring(2))) return digits.substring(2);
        return number;
    }

    /** AudioManager modes of a phone call or a VoIP call (MODE_IN_CALL 2, MODE_IN_COMMUNICATION 3, MODE_CALL_SCREENING 4). */
    static boolean inCall(int audioMode) { return audioMode == 2 || audioMode == 3 || audioMode == 4; }
    /** During a call a recording may still be open: only files still for the old full minute are taken then. */
    static boolean settled(long saved, long now, boolean inCall) { return now - saved >= (inCall ? UNDO_MS : SETTLE_MS); }
    static String number(String name, long saved, long now, long since, TimeZone zone, JSONObject book) {
        return number(name, saved, now, since, zone, book, KEEP_MS);
    }

    /** The lead number a recording's name holds, for a recording at most {@code maxAgeMs} old. */
    static String number(String name, long saved, long now, long since, TimeZone zone, JSONObject book, long maxAgeMs) {
        RecordingMatch.Name parsed = RecordingMatch.read(name, zone);
        if (parsed.stampMs <= 0 || parsed.stampMs < since || parsed.stampMs > now ||
            now - parsed.stampMs > maxAgeMs || saved < parsed.stampMs ||
            saved > parsed.stampMs + 4 * 60 * 60 * 1000L + 180_000 || now - saved < SETTLE_MS) return null;
        String matched = null;
        org.json.JSONArray numbers = book.optJSONArray("numbers");
        for (String raw : parsed.numbers) {
            String found = leadNumber(raw, numbers);
            if (found == null || (matched != null && !matched.equals(found))) return null;
            matched = found;
        }
        return matched;
    }

    /** The one lead number {@code raw} is; null for none, several, or a suffix too short to tell. */
    static String leadNumber(String raw, org.json.JSONArray numbers) {
        // A short suffix must never authorize a longer phone number.
        if (raw == null || CallLogRules.key(raw).length() < 10) return null;
        String found = null;
        for (int i = 0; numbers != null && i < numbers.length(); i++) {
            String lead = numbers.optString(i);
            if (samePhone(raw, lead)) {
                if (found != null && !found.equals(lead)) return null;
                found = lead;
            }
        }
        return found;
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
            // The server's standing-consent refusal: the terms changed or it was turned off. Calls wait; the next
            // scan reads the consent again and turns uploads off only if the seller turned it off.
            if (words.contains("automatic analysis")) return TERMS;
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
            case CONSENT: return new String[] {"Automatic call uploads are off", "Automatic analysis is off for you. Turn uploads on again in Settings → Privacy → Calls.", "/settings/privacy#automatic-call-uploads"};
            case TERMS: return new String[] {"Agree to the new upload terms", waiting + " Sales X-Ray's upload terms changed. Open Sales X-Ray and tap Agree; your calls then upload by themselves.", "/settings/privacy#automatic-call-uploads"};
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
