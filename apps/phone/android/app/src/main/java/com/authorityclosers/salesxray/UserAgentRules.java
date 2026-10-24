package com.authorityclosers.salesxray;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The app's user-agent token, "SalesXrayApp/<version>", carries the installed build's own version
 * (24 Oct 2026: telemetry read "0.3" from 1.0.24 and 1.0.25, because capacitor.config.json's token
 * was fixed). The web and the server only look for "SalesXrayApp/<digit>"; telemetry reads the rest.
 * Plain Java, for JVM tests; AppUserAgent reads Android.
 */
final class UserAgentRules {

    static final String NAME = "SalesXrayApp/";
    /** What capacitor.config.json says, kept when the version can't be read. */
    static final String FALLBACK = NAME + "0.3";
    /** The web's telemetry keeps 31 characters of the version. */
    private static final int MAX_VERSION = 31;

    private UserAgentRules() {}

    /** "SalesXrayApp/1.0.26" from Google Play, "SalesXrayApp/0.3.120" from the download link. */
    static String token(String versionName) {
        StringBuilder version = new StringBuilder();
        if (versionName != null) {
            for (char c : versionName.trim().toCharArray()) {
                if (version.length() == MAX_VERSION) break;
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == '-') {
                    version.append(c);
                }
            }
        }
        // The token must start with a digit, or the web would not know it is the app.
        if (version.length() == 0 || version.charAt(0) < '0' || version.charAt(0) > '9') return FALLBACK;
        return NAME + version;
    }

    /** Capacitor's config with this build's token in place of the file's fixed one. */
    static String config(String json, String token) throws JSONException {
        JSONObject config = new JSONObject(json);
        config.put("appendUserAgent", token);
        // Capacitor prefers android.appendUserAgent, so it must not keep an old one.
        JSONObject android = config.optJSONObject("android");
        if (android != null) android.remove("appendUserAgent");
        return config.toString();
    }
}
