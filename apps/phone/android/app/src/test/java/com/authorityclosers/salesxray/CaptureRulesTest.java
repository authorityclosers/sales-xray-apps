package com.authorityclosers.salesxray;
import static org.junit.Assert.*;
import org.junit.Test;
import org.json.*;
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
    @Test public void corporateProofIsRequired() throws Exception {
        assertEquals("", CaptureRules.identity(new JSONObject().put("person_id", "fictional-seller")));
        assertFalse(CaptureRules.identity(book()).isEmpty());
    }
}
