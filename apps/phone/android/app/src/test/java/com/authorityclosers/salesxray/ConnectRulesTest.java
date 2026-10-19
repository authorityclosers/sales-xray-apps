package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Connect from the app: what opens in the phone's browser, and the page a return opens. */
public class ConnectRulesTest {

    private static final Set<String> HOSTS = new HashSet<>(
        Arrays.asList("salesxray.authorityclosers.com", "salesxray-dev.authorityclosers.com")
    );
    private static final String PROD = "https://salesxray.authorityclosers.com";
    private static final String HANDOFF = "AbC-_0123456789abcdefghijklmnopqrstuvwxyz";

    @Test
    public void opensOnlyFacebookAndGoogleSignIn() {
        assertTrue(ConnectRules.opens("https://www.facebook.com/v23.0/dialog/oauth?client_id=1&state=s"));
        assertTrue(ConnectRules.opens("https://m.facebook.com/v23.0/dialog/oauth"));
        assertTrue(ConnectRules.opens("https://accounts.google.com/o/oauth2/v2/auth?scope=x"));
        assertTrue(ConnectRules.opens("https://WWW.FACEBOOK.COM/dialog/oauth"));
        assertFalse(ConnectRules.opens("http://www.facebook.com/dialog/oauth"));
        assertFalse(ConnectRules.opens("https://www.facebook.com.example.com/dialog/oauth"));
        assertFalse(ConnectRules.opens("https://evil.example/https://www.facebook.com/"));
        assertFalse(ConnectRules.opens("https://user@www.facebook.com/dialog/oauth"));
        assertFalse(ConnectRules.opens("https://www.facebook.com:8443/dialog/oauth"));
        assertFalse(ConnectRules.opens("intent://www.facebook.com/#Intent;end"));
        assertFalse(ConnectRules.opens("javascript:alert(1)"));
        assertFalse(ConnectRules.opens("https://salesxray.authorityclosers.com/leads"));
        assertFalse(ConnectRules.opens("not a url"));
        assertFalse(ConnectRules.opens(null));
    }

    @Test
    public void theOriginIsOneOfSalesXraysOwnSites() {
        assertEquals(PROD, ConnectRules.originOf(PROD + "/leads?tab=sources", HOSTS));
        assertEquals("https://salesxray-dev.authorityclosers.com", ConnectRules.originOf("https://salesxray-dev.authorityclosers.com/organisation", HOSTS));
        assertNull(ConnectRules.originOf("https://example.com/leads", HOSTS));
        assertNull(ConnectRules.originOf("http://salesxray.authorityclosers.com/leads", HOSTS));
        assertNull(ConnectRules.originOf("https://salesxray.authorityclosers.com:444/leads", HOSTS));
        assertNull(ConnectRules.originOf(null, HOSTS));
        assertFalse(ConnectRules.isSalesXray("https://salesxray.authorityclosers.com/x", HOSTS));
        assertFalse(ConnectRules.isSalesXray("https://salesxray.authorityclosers.com.example", HOSTS));
    }

    @Test
    public void aReturnOpensItsConnectsOwnPageWithTheHandOff() {
        assertEquals(
            PROD + "/leads?tab=sources#connect=meta&handoff=" + HANDOFF,
            ConnectRules.page(PROD, "provider=meta&path=/leads%3Ftab%3Dsources&handoff=" + HANDOFF, HOSTS)
        );
        assertEquals(
            PROD + "/leads?tab=sources#connect=google&handoff=" + HANDOFF,
            ConnectRules.page(PROD, "provider=google&handoff=" + HANDOFF, HOSTS)
        );
        assertEquals(
            PROD + "/organisation?tab=integrations#connect=whatsapp&handoff=" + HANDOFF,
            ConnectRules.page(PROD, "provider=whatsapp&handoff=" + HANDOFF, HOSTS)
        );
        // Messenger and Instagram conversations connect in Settings → Integrations too.
        assertEquals(
            PROD + "/organisation?tab=integrations#connect=messaging&handoff=" + HANDOFF,
            ConnectRules.page(PROD, "provider=messaging&handoff=" + HANDOFF, HOSTS)
        );
    }

    @Test
    public void thePagePathComesFromTheAppNotTheReturn() {
        assertEquals(
            PROD + "/leads?tab=sources#connect=meta&handoff=" + HANDOFF,
            ConnectRules.page(PROD, "provider=meta&path=//evil.example/x&handoff=" + HANDOFF, HOSTS)
        );
    }

    @Test
    public void aReturnWithoutAHandOffSaysHowItEnded() {
        assertEquals(PROD + "/leads?tab=sources#connect=meta&result=cancelled", ConnectRules.page(PROD, "provider=meta&result=cancelled", HOSTS));
        assertEquals(PROD + "/leads?tab=sources#connect=google&result=failed", ConnectRules.page(PROD, "provider=google&result=<b>", HOSTS));
        assertEquals(PROD + "/leads?tab=sources#connect=google&result=failed", ConnectRules.page(PROD, "provider=google&handoff=short", HOSTS));
        assertEquals(PROD + "/leads?tab=sources#connect=meta&result=failed", ConnectRules.page(PROD, "provider=meta&handoff=a%26b" + HANDOFF, HOSTS));
    }

    @Test
    public void anythingElseOpensNothing() {
        assertNull(ConnectRules.page(PROD, "provider=other&handoff=" + HANDOFF, HOSTS));
        assertNull(ConnectRules.page(PROD, "handoff=" + HANDOFF, HOSTS));
        assertNull(ConnectRules.page(PROD, null, HOSTS));
        assertNull(ConnectRules.page("https://example.com", "provider=meta&handoff=" + HANDOFF, HOSTS));
        assertNull(ConnectRules.page(null, "provider=meta&handoff=" + HANDOFF, HOSTS));
    }
}
