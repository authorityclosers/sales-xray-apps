package com.authorityclosers.salesxray;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.*;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public class CaptureRulesTest {
    final TimeZone zone = TimeZone.getTimeZone("UTC");
    final long stamp = 1793275200000L; // 2026-10-29 12:00 UTC
    JSONObject book() throws Exception {
        return new JSONObject().put("organisation_workspace", true).put("tenant_id", "fictional-workspace")
            .put("person_id", "fictional-seller").put("numbers", new JSONArray().put("+12025550123"));
    }
    @Test public void oemPatternsRequireNumberAndClock() throws Exception {
        for (String name : new String[] {
            "Call recording 12025550123_261029120000.mp3", // MIUI compact
            "Call recording 12025550123_261029_120000.m4a", // Samsung
            "12025550123_20261029120000.amr", // Oxygen
            "Call_12025550123_2026-10-29_12-00-00.m4a", // realme/OPlus
        }) {
            assertEquals(name, stamp, RecordingMatch.read(name, zone).stampMs);
            assertEquals(name, "+12025550123", CaptureRules.number(name, stamp + 20000, stamp + 120000, stamp - 1, zone, book()));
        }
    }
    @Test public void personalUnknownAndNameOnlyCallsNeverMatch() throws Exception {
        for (String name : new String[] {"12025550124_261029120000.mp3", "Alex Example_261029120000.mp3", "12025550123.mp3", "12025550123_12025550124_261029120000.mp3"})
            assertNull(CaptureRules.number(name, stamp + 20000, stamp + 120000, stamp - 1, zone, book()));
        assertNull(CaptureRules.number("12025550123_261029120000.mp3", stamp + 20000, stamp + 120000, stamp + 1, zone, book()));
    }
    @Test public void invalidDatesAndStillWritingFilesAreIgnored() throws Exception {
        assertEquals(0, RecordingMatch.read("12025550123_260230120000.mp3", zone).stampMs);
        assertNull(CaptureRules.number("12025550123_261029120000.mp3", stamp + 20000, stamp + 30000, stamp - 1, zone, book()));
    }
    @Test public void consentFailsClosed() throws Exception {
        String hash = String.join("", java.util.Collections.nCopies(64, "a"));
        assertFalse(CaptureRules.consent(null, hash));
        JSONObject on = new JSONObject().put("state", "on").put("can_start", true).put("terms_sha256", hash);
        assertTrue(CaptureRules.consent(on, hash));
        assertFalse(CaptureRules.consent(on, hash.replace('a', 'b')));
        assertFalse(CaptureRules.consent(on.put("can_start", false), hash));
        assertFalse(CaptureRules.consent(on.put("can_start", true).put("state", "off"), hash));
    }
    @Test public void retryUsesSameIdentityAndWorkspaceChangeUsesAnother() {
        assertEquals(CaptureRules.id("workspace:seller", "12025550123", stamp), CaptureRules.id("workspace:seller", "12025550123", stamp));
        assertNotEquals(CaptureRules.id("workspace:seller", "12025550123", stamp), CaptureRules.id("other:seller", "12025550123", stamp));
    }
    @Test public void internationalNumbersNeverMatchBySuffixAlone() {
        assertFalse(CaptureRules.samePhone("442025550123", "+12025550123"));
        assertTrue(CaptureRules.samePhone("0012025550123", "+12025550123"));
        assertFalse(CaptureRules.samePhone("2025550123", "+12025550123"));
    }
    @Test public void corporateProofIsRequired() throws Exception {
        assertEquals("", CaptureRules.identity(new JSONObject().put("person_id", "fictional-seller")));
        assertFalse(CaptureRules.identity(book()).isEmpty());
    }
    // Real-call test on the Redmi 13, 30 Oct 2026: each refusal below lost a call silently.
    static final String PROFILE_WORDS = "Complete your Sales Xray contact profile to continue.";
    static final String ALLOWANCE_WORDS = "Your current free call allowance has been used.";
    @Test public void profileRefusalHoldsInsteadOfTurningUploadsOff() {
        assertEquals(CaptureRules.PROFILE, CaptureRules.refusal(403, PROFILE_WORDS, false, null));
        assertEquals(CaptureRules.PROFILE, CaptureRules.refusal(403, PROFILE_WORDS, null, null));
        assertEquals(CaptureRules.PROFILE, CaptureRules.refusal(403, "", false, null));
        // Finished the profile in between: go again at once.
        assertEquals(CaptureRules.RETRY, CaptureRules.refusal(403, PROFILE_WORDS, true, null));
        assertEquals(CaptureRules.ACCESS, CaptureRules.refusal(403, "Choose a workspace you belong to.", true, null));
        assertFalse(CaptureRules.stops(CaptureRules.PROFILE));
        assertFalse(CaptureRules.stops(CaptureRules.ACCESS));
        assertTrue(CaptureRules.recoverable(CaptureRules.PROFILE));
    }
    @Test public void allowanceRefusalHoldsAndRetriesOnceMinutesExist() {
        assertEquals(CaptureRules.ALLOWANCE, CaptureRules.refusal(409, ALLOWANCE_WORDS, null, false));
        assertEquals(CaptureRules.ALLOWANCE, CaptureRules.refusal(409, ALLOWANCE_WORDS, null, null));
        assertEquals(CaptureRules.ALLOWANCE, CaptureRules.refusal(409, "", null, false));
        assertEquals(CaptureRules.RETRY, CaptureRules.refusal(409, ALLOWANCE_WORDS, null, true));
        assertFalse(CaptureRules.stops(CaptureRules.ALLOWANCE));
        long now = stamp;
        assertFalse(CaptureRules.release(CaptureRules.ALLOWANCE, true, null, false, now - 1, now));
        assertFalse(CaptureRules.release(CaptureRules.ALLOWANCE, true, null, null, now - 1, now));
        assertTrue(CaptureRules.release(CaptureRules.ALLOWANCE, true, null, true, now - 1, now));
        assertTrue(CaptureRules.release(CaptureRules.PROFILE, true, true, null, now - 1, now));
        assertFalse(CaptureRules.release(CaptureRules.PROFILE, true, false, null, now - 1, now));
    }
    @Test public void minutesLeftReadsTheAllowance() throws Exception {
        assertNull(CaptureRules.minutesLeft(null));
        assertNull(CaptureRules.minutesLeft(new JSONObject()));
        assertTrue(CaptureRules.minutesLeft(new JSONObject().put("available_seconds", 60)));
        assertFalse(CaptureRules.minutesLeft(new JSONObject().put("available_seconds", 0)));
        assertTrue(CaptureRules.minutesLeft(new JSONObject().put("available_seconds", 0).put("unlimited", true)));
    }
    @Test public void onlyConsentAndNoticesTurnUploadsOff() {
        assertEquals(CaptureRules.SIGNED_OUT, CaptureRules.refusal(401, "", null, null));
        assertEquals(CaptureRules.CONSENT, CaptureRules.refusal(409, "Automatic analysis isn't on for you here. Tap Analyse this call.", null, true));
        assertEquals(CaptureRules.WORKSPACE, CaptureRules.refusal(409, "This call belongs to another workspace or seller.", null, true));
        for (int code : new int[] {500, 502, 503, 429, 408}) assertEquals(CaptureRules.RETRY, CaptureRules.refusal(code, "", null, null));
        assertEquals(CaptureRules.REVIEW, CaptureRules.refusal(422, "Choose an audio or video file up to 100 MB.", null, null));
        assertEquals(CaptureRules.REVIEW, CaptureRules.refusal(409, "The file changed.", null, true));
        for (String reason : CaptureRules.PRIORITY)
            assertEquals(reason, reason.equals(CaptureRules.CONSENT) || reason.equals(CaptureRules.NOTICES), CaptureRules.stops(reason));
        assertFalse(CaptureRules.recoverable(CaptureRules.REVIEW));
        assertFalse(CaptureRules.recoverable(CaptureRules.EXPIRED));
        // Never into another workspace; access refusals at most hourly.
        assertFalse(CaptureRules.release(CaptureRules.SIGNED_OUT, false, null, null, 0, stamp));
        assertTrue(CaptureRules.release(CaptureRules.SIGNED_OUT, true, null, null, stamp, stamp));
        assertFalse(CaptureRules.release(CaptureRules.ACCESS, true, null, null, stamp - 60_000, stamp));
        assertTrue(CaptureRules.release(CaptureRules.ACCESS, true, null, null, stamp - CaptureRules.ACCESS_RETRY_MS, stamp));
        assertFalse(CaptureRules.release(CaptureRules.REVIEW, true, true, true, 0, stamp));
    }
    @Test public void turningOnAgainOffersTheOffPeriodButNeverBeforeIt() {
        long now = stamp;
        assertEquals(now, CaptureRules.resumeSince(now - 3_600_000, now, false));
        assertEquals(now - 3_600_000, CaptureRules.resumeSince(now - 3_600_000, now, true));
        // Never more than the 3-day upload window, never without a recorded turn-off.
        assertEquals(now - CaptureRules.KEEP_MS, CaptureRules.resumeSince(now - 10 * CaptureRules.KEEP_MS, now, true));
        assertEquals(now, CaptureRules.resumeSince(0, now, true));
        assertEquals(now, CaptureRules.resumeSince(now + 5, now, true));
    }
    @Test public void healthNamesTheMostUrgentReason() {
        Map<String, Integer> held = new HashMap<>();
        long now = stamp;
        assertEquals("ok", CaptureRules.health(true, null, null, held, 0, now));
        assertEquals(CaptureRules.OFF, CaptureRules.health(false, null, null, held, 0, now));
        assertEquals(CaptureRules.CONSENT, CaptureRules.health(false, CaptureRules.CONSENT, null, held, 0, now));
        assertEquals(CaptureRules.SIGNED_OUT, CaptureRules.health(false, CaptureRules.SIGNED_OUT, null, held, 0, now));
        held.put(CaptureRules.ALLOWANCE, 2);
        assertEquals(CaptureRules.ALLOWANCE, CaptureRules.health(true, null, null, held, 0, now));
        held.put(CaptureRules.PROFILE, 1);
        assertEquals(CaptureRules.PROFILE, CaptureRules.health(true, null, null, held, 0, now));
        assertEquals(CaptureRules.SIGNED_OUT, CaptureRules.health(true, null, CaptureRules.SIGNED_OUT, held, 0, now));
        held.clear();
        assertEquals(CaptureRules.NETWORK, CaptureRules.health(true, null, null, held, now - 60_000, now));
        assertEquals("ok", CaptureRules.health(true, null, null, held, now - CaptureRules.NETWORK_FRESH_MS, now));
        held.put(CaptureRules.OFF, 3);
        assertEquals("ok", CaptureRules.health(true, null, null, held, 0, now));
    }
    @Test public void noticesSayWhyAndOpenTheFix() {
        assertEquals("/settings/profile", CaptureRules.attention(CaptureRules.PROFILE, 1, false)[2]);
        assertTrue(CaptureRules.attention(CaptureRules.PROFILE, 1, false)[1].startsWith("1 lead call waits"));
        assertTrue(CaptureRules.attention(CaptureRules.ALLOWANCE, 3, false)[1].contains("3 lead calls wait"));
        assertTrue(CaptureRules.attention(CaptureRules.ALLOWANCE, 3, false)[1].contains("by themselves"));
        assertEquals("/settings/privacy#calls-setup", CaptureRules.attention(CaptureRules.NETWORK, 1, false)[2]);
        assertTrue(CaptureRules.attention(CaptureRules.SIGNED_OUT, 0, true)[1].contains("turn uploads on"));
        assertTrue(CaptureRules.attention(CaptureRules.SIGNED_OUT, 2, false)[1].contains("by themselves"));
        for (String reason : CaptureRules.PRIORITY) {
            String[] words = CaptureRules.attention(reason, 1, false);
            assertTrue(reason, words[2].startsWith("/leads") || words[2].startsWith("/settings"));
            assertFalse(reason, words[0].isEmpty() || words[1].isEmpty());
        }
        assertTrue(CaptureRules.notifyAgain(0, 0, 1, stamp));
        assertFalse(CaptureRules.notifyAgain(stamp - 60_000, 1, 1, stamp));
        assertTrue(CaptureRules.notifyAgain(stamp - 60_000, 1, 2, stamp));
        assertTrue(CaptureRules.notifyAgain(stamp - CaptureRules.RENOTIFY_MS, 1, 1, stamp));
    }
    @Test public void blockedBackgroundNetworkIsToldFromOffline() {
        assertEquals(CaptureRules.NET_OK, CaptureRules.netState(true, false, true));
        assertEquals(CaptureRules.NET_BLOCKED, CaptureRules.netState(true, true, false));
        assertEquals(CaptureRules.NET_BLOCKED, CaptureRules.netState(true, false, false));
        assertEquals(CaptureRules.NET_OFFLINE, CaptureRules.netState(false, false, false));
    }
}
