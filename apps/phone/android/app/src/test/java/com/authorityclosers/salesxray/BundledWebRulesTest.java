package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Which bundled file answers a request (S9, owner 23 Oct 2026: a full app, not a website loading). */
public class BundledWebRulesTest {

    private static final Set<String> FILES = new HashSet<>(
        Arrays.asList(
            "index.html",
            "index.txt",
            "dashboard/index.html",
            "dashboard/index.txt",
            "leads/index.html",
            "leads/index.txt",
            "settings/[section]/index.html",
            "settings/team/index.html",
            "auth/complete/index.html",
            "organisation/invites/index.html",
            "_next/static/chunks/app/leads/page-abc123.js",
            "_next/static/css/1f2e.css",
            "favicon.ico",
            "404.html"
        )
    );

    @Test
    public void pagesComeFromTheirIndexFiles() {
        assertEquals("index.html", BundledWebRules.resolve("/", false, FILES));
        assertEquals("index.html", BundledWebRules.resolve("", false, FILES));
        assertEquals("dashboard/index.html", BundledWebRules.resolve("/dashboard/", false, FILES));
        assertEquals("leads/index.html", BundledWebRules.resolve("/leads", false, FILES));
        assertEquals("settings/team/index.html", BundledWebRules.resolve("/settings/team/", false, FILES));
    }

    @Test
    public void scriptsStylesAndIconsComeFromTheBundle() {
        assertEquals("_next/static/chunks/app/leads/page-abc123.js", BundledWebRules.resolve("/_next/static/chunks/app/leads/page-abc123.js", false, FILES));
        assertEquals("_next/static/css/1f2e.css", BundledWebRules.resolve("/_next/static/css/1f2e.css", false, FILES));
        assertEquals("favicon.ico", BundledWebRules.resolve("/favicon.ico", false, FILES));
        // Escaped brackets in a chunk or page path still find the file.
        assertEquals("settings/[section]/index.html", BundledWebRules.resolve("/settings/%5Bsection%5D/", false, FILES));
    }

    @Test
    public void screenChangesUseTheExportsTxtFilesOnly() {
        assertEquals("leads/index.txt", BundledWebRules.resolve("/leads/index.txt", true, FILES));
        // The live site asks for the path itself: that page came from the server, so the server answers.
        assertNull(BundledWebRules.resolve("/leads", true, FILES));
        assertNull(BundledWebRules.resolve("/chats/index.txt", true, FILES));
    }

    @Test
    public void theApiSignInInvitesAndUnknownPagesGoToTheServer() {
        assertNull(BundledWebRules.resolve("/v1/lead-desk/leads", false, FILES));
        assertNull(BundledWebRules.resolve("/v1", false, FILES));
        assertNull(BundledWebRules.resolve("/auth/complete/", false, FILES));
        assertNull(BundledWebRules.resolve("/organisation/invites/", false, FILES));
        assertNull(BundledWebRules.resolve("/analysis/calls/3f0c6a54-0000-4000-8000-000000000001/", false, FILES));
        assertNull(BundledWebRules.resolve("/review-fixture/leads/", false, FILES));
        assertNull(BundledWebRules.resolve("/../secrets", false, FILES));
    }

    @Test
    public void typesAndFreshness() {
        assertEquals("text/html", BundledWebRules.mime("leads/index.html"));
        assertEquals("application/javascript", BundledWebRules.mime("_next/static/chunks/a.js"));
        assertEquals("text/x-component", BundledWebRules.mime("leads/index.txt"));
        assertEquals("font/woff2", BundledWebRules.mime("_next/static/media/inter.woff2"));
        assertTrue(BundledWebRules.isText("application/javascript"));
        assertFalse(BundledWebRules.isText("image/png"));
        long day = 86_400_000L;
        long now = 1_800_000_000_000L;
        assertTrue(BundledWebRules.fresh(now - 3 * day, now, 14 * day));
        assertFalse(BundledWebRules.fresh(now - 15 * day, now, 14 * day));
        assertFalse(BundledWebRules.fresh(0, now, 14 * day));
        // A clock far behind the build (a phone set to the past) doesn't count as fresh.
        assertFalse(BundledWebRules.fresh(now + 3 * day, now, 14 * day));
    }
}
