package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The page the seller was on reopens after Android closed the app in the background (native feel, 3 Nov 2026). */
public class PageStateTest {

    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("salesxray.authorityclosers.com", "app.salesxray.in"));

    @Test
    public void ourPagesReopenWithTheirQuery() {
        assertEquals("/leads?lead=2fdc4146", PageState.path("https://salesxray.authorityclosers.com/leads?lead=2fdc4146#n=1", HOSTS));
        assertEquals("/analysis/calls", PageState.path("https://app.salesxray.in/analysis/calls", HOSTS));
        assertEquals("/dashboard/", PageState.path("https://salesxray.authorityclosers.com/dashboard/", HOSTS));
    }

    @Test
    public void anythingElseOpensHome() {
        assertNull(PageState.path(null, HOSTS));
        assertNull(PageState.path("not a url", HOSTS));
        assertNull(PageState.path("http://salesxray.authorityclosers.com/leads", HOSTS));
        assertNull(PageState.path("https://accounts.google.com/o/oauth2", HOSTS));
        assertNull(PageState.path("https://localhost/offline.html", HOSTS));
        assertNull(PageState.path("https://salesxray.authorityclosers.com/", HOSTS));
        assertNull(PageState.path("https://salesxray.authorityclosers.com", HOSTS));
        // Sign-in and its hand-offs decide where they go themselves.
        assertNull(PageState.path("https://salesxray.authorityclosers.com/login?next=/leads", HOSTS));
        assertNull(PageState.path("https://salesxray.authorityclosers.com/auth/complete", HOSTS));
        assertNull(PageState.path("https://salesxray.authorityclosers.com/connect/meta", HOSTS));
    }
}
