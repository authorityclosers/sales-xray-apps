package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** The installed build's version in "SalesXrayApp/<version>" (24 Oct 2026: telemetry read "0.3" from 1.0.25). */
public class UserAgentRulesTest {

    @Test
    public void theTokenCarriesTheBuildsVersion() {
        assertEquals("SalesXrayApp/1.0.26", UserAgentRules.token("1.0.26"));
        assertEquals("SalesXrayApp/0.3.120", UserAgentRules.token("0.3.120"));
        assertEquals("SalesXrayApp/0.3.120-dev", UserAgentRules.token("0.3.120-dev"));
        // Nothing that would break the header or the web's reading of it.
        assertEquals("SalesXrayApp/1.0.26beta", UserAgentRules.token(" 1.0.26 (beta)"));
        assertEquals(31, UserAgentRules.token("1." + "9".repeat(60)).length() - UserAgentRules.NAME.length());
    }

    @Test
    public void withoutAVersionTheFixedTokenStays() {
        // The web knows the app by "SalesXrayApp/<digit>".
        assertEquals(UserAgentRules.FALLBACK, UserAgentRules.token(null));
        assertEquals(UserAgentRules.FALLBACK, UserAgentRules.token(""));
        assertEquals(UserAgentRules.FALLBACK, UserAgentRules.token("dev"));
    }

    @Test
    public void capacitorsConfigGetsTheTokenAndKeepsTheRest() throws Exception {
        String file = "{\"appId\":\"com.authorityclosers.salesxray\",\"appendUserAgent\":\"SalesXrayApp/0.3\","
            + "\"server\":{\"url\":\"https://salesxray.authorityclosers.com\",\"errorPath\":\"offline.html\"},"
            + "\"android\":{\"allowMixedContent\":false,\"appendUserAgent\":\"SalesXrayApp/0.2\"},"
            + "\"plugins\":{\"SystemBars\":{\"insetsHandling\":\"disable\"}}}";
        JSONObject config = new JSONObject(UserAgentRules.config(file, "SalesXrayApp/1.0.26"));
        assertEquals("SalesXrayApp/1.0.26", config.getString("appendUserAgent"));
        // Capacitor would prefer android.appendUserAgent over the top-level one.
        assertFalse(config.getJSONObject("android").has("appendUserAgent"));
        assertFalse(config.getJSONObject("android").getBoolean("allowMixedContent"));
        assertEquals("https://salesxray.authorityclosers.com", config.getJSONObject("server").getString("url"));
        assertEquals("offline.html", config.getJSONObject("server").getString("errorPath"));
        assertTrue(config.getJSONObject("plugins").has("SystemBars"));
    }
}
