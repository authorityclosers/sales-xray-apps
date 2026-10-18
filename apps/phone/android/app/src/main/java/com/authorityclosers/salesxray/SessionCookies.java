package com.authorityclosers.salesxray;

import java.net.CookieHandler;
import java.net.HttpURLConnection;

/**
 * Puts the seller's session on a request from the app's own code exactly once.
 * Once the web view is up, Capacitor sets a default CookieHandler that copies
 * the web view's cookies onto every HttpURLConnection by itself; a Cookie header
 * set here as well sends the session twice, and Sales Xray turns a duplicated
 * session away as signed out (401). Without that handler (the app's screen was
 * never opened in this process) the header is set here.
 */
final class SessionCookies {

    private SessionCookies() {}

    static void attach(HttpURLConnection connection, String cookies) {
        if (CookieHandler.getDefault() != null) return;
        if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
    }
}
