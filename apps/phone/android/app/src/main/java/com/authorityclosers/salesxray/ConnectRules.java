package com.authorityclosers.salesxray;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Plain rules for Connect (Facebook &amp; Instagram leads, Google Ads, WhatsApp, Messenger and
 * Instagram conversations) from the app:
 * which sign-in addresses the app opens in the phone's browser, and which page a
 * return opens. See ConnectFlow.
 */
final class ConnectRules {

    static final String RETURN_PATH = "/connect/return";

    /** Facebook's and Google's sign-in: the only places openConnect opens. */
    static final Set<String> SIGN_IN_HOSTS = new HashSet<>(
        Arrays.asList("www.facebook.com", "m.facebook.com", "web.facebook.com", "facebook.com", "accounts.google.com")
    );

    /** The page that finishes each Connect (the web's app-connect.ts reads the fragment). */
    private static final Map<String, String> PAGES = new HashMap<>();

    static {
        PAGES.put("meta", "/leads?tab=sources");
        PAGES.put("google", "/leads?tab=sources");
        PAGES.put("whatsapp", "/organisation?tab=integrations");
        PAGES.put("messaging", "/organisation?tab=integrations");
    }

    private static final Pattern HANDOFF = Pattern.compile("[A-Za-z0-9_-]{20,8192}");
    private static final Pattern RESULT = Pattern.compile("[a-z_]{1,32}");

    private ConnectRules() {}

    /** True for an https Facebook or Google sign-in address, nothing else. */
    static boolean opens(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return (
                "https".equals(uri.getScheme()) &&
                host != null &&
                SIGN_IN_HOSTS.contains(host.toLowerCase(Locale.ROOT)) &&
                uri.getRawUserInfo() == null &&
                (uri.getPort() == -1 || uri.getPort() == 443)
            );
        } catch (URISyntaxException error) {
            return false;
        }
    }

    /** "https://" and one of Sales Xray's own hosts, and nothing more. */
    static boolean isSalesXray(String origin, Set<String> hosts) {
        if (origin == null || !origin.startsWith("https://")) return false;
        return hosts.contains(origin.substring("https://".length()));
    }

    /** The origin of a Sales Xray page address, or null for any other site. */
    static String originOf(String pageUrl, Set<String> hosts) {
        if (pageUrl == null) return null;
        try {
            URI uri = new URI(pageUrl);
            String origin = "https://" + uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getPort() == -1 && isSalesXray(origin, hosts) ? origin : null;
        } catch (URISyntaxException error) {
            return null;
        }
    }

    /**
     * The page to open for a return from the browser: the Connect's own page on the
     * site that started it, with the sealed hand-off (or how it ended) in the fragment.
     * Null when the return is not one.
     */
    static String page(String origin, String encodedFragment, Set<String> hosts) {
        if (!isSalesXray(origin, hosts)) return null;
        Map<String, String> parts = fragment(encodedFragment);
        String provider = parts.get("provider");
        String page = provider == null ? null : PAGES.get(provider);
        if (page == null) return null;
        String handoff = parts.get("handoff");
        String result = parts.get("result");
        String tail = handoff != null && HANDOFF.matcher(handoff).matches()
            ? "handoff=" + handoff
            : "result=" + (result != null && RESULT.matcher(result).matches() ? result : "failed");
        return origin + page + "#connect=" + provider + "&" + tail;
    }

    static Map<String, String> fragment(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null) return values;
        for (String pair : encoded.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            try {
                values.put(URLDecoder.decode(pair.substring(0, split), "UTF-8"), URLDecoder.decode(pair.substring(split + 1), "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // A malformed pair is skipped; a missing hand-off reads as a failed Connect.
            }
        }
        return values;
    }
}
