package com.authorityclosers.salesxray;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where the seller was, kept when Android closes the app in the background (native feel, 3 Nov 2026: HyperOS and
 * OxygenOS stop the app often, and it then reopened on Home). The screen's saved state keeps the page's address;
 * the next screen opens that page instead of Home.
 *
 * WebViewCompat.saveState isn't used: its restoreState needs a WebView that has loaded nothing yet, and Capacitor's
 * bridge has already started loading the site before the screen can restore anything ("If it is called after this
 * WebView has had a chance to build state ... there may be undesirable side-effects", WebView.restoreState).
 *
 * After the page's renderer crashed, the app opens Home instead, so a page that crashes it can't do it again and
 * again. Plain Java, for JVM tests.
 */
final class PageState {

    /** The saved address in the screen's instance state. */
    static final String KEY = "sx.page";

    /** Sign-in and its hand-offs never reopen by themselves: the page decides where they go. */
    private static final Pattern NOT_REOPENED = Pattern.compile("^/(login|register|auth|connect|offline)(/|$|\\?)");

    private PageState() {}

    /** The path, query included, to reopen from a saved address on one of our sites; null opens Home. */
    static String path(String saved, Set<String> hosts) {
        if (saved == null) return null;
        URI address;
        try {
            address = new URI(saved);
        } catch (URISyntaxException error) {
            return null;
        }
        if (!"https".equals(address.getScheme()) || address.getHost() == null || !hosts.contains(address.getHost())) return null;
        String path = address.getRawPath();
        // The site's root only sends on to Home or sign-in.
        if (path == null || !path.startsWith("/") || path.equals("/")) return null;
        String query = address.getRawQuery();
        String reopen = query == null ? path : path + "?" + query;
        return NOT_REOPENED.matcher(reopen).find() ? null : reopen;
    }
}
