package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google sign-in for the app. Google refuses sign-in inside web views, so the
 * Sales Xray sign-in start opens in a Chrome Custom Tab instead, with a
 * PKCE-style challenge only this app can answer. The server hands the result
 * back to com.authorityclosers.salesxray:/auth/return; the web view then redeems
 * the one-time code once and gets its own normal session.
 */
final class GoogleSignIn {

    static final String START_PATH = "/v1/auth/google/start";
    static final String RETURN_PATH = "/auth/return";
    static final String EXCHANGE_PATH = "/v1/auth/google/app-exchange";

    private static final String PREFS = "sx.google_sign_in";
    private static final String KEY_VERIFIER = "verifier";
    private static final String KEY_ORIGIN = "origin";
    private static final String KEY_STARTED = "started_at";
    // A sign-in that takes longer than this starts again from the app.
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    private static final Pattern RESULT = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern FLOW = Pattern.compile("[0-9a-fA-F-]{36}");

    private final AppCompatActivity activity;
    private final WebView webView;
    private final SecureRandom random = new SecureRandom();

    GoogleSignIn(AppCompatActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** Opens a Sales Xray Google sign-in start in a Custom Tab instead of the web view. */
    boolean intercept(Uri url) {
        if (url == null || !"https".equals(url.getScheme()) || !PhoneBridge.HOSTS.contains(url.getHost())) return false;
        if (!START_PATH.equals(url.getPath()) || url.getQueryParameter("app_challenge") != null) return false;
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String verifier = base64Url(secret);
        String challenge;
        try {
            challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            return false;
        }
        prefs()
            .edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_ORIGIN, "https://" + url.getHost())
            .putLong(KEY_STARTED, System.currentTimeMillis())
            .apply();
        Uri start = url.buildUpon().appendQueryParameter("app_challenge", challenge).build();
        new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, start);
        return true;
    }

    /** Takes the result the Custom Tab handed back, if this intent carries one. */
    void take(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null || !activity.getPackageName().equals(data.getScheme()) || !RETURN_PATH.equals(data.getPath())) return;
        // Handle a result once, even if the activity is recreated with this intent.
        intent.setData(null);
        SharedPreferences prefs = prefs();
        String verifier = prefs.getString(KEY_VERIFIER, null);
        String origin = prefs.getString(KEY_ORIGIN, null);
        long startedAt = prefs.getLong(KEY_STARTED, 0L);
        prefs.edit().clear().apply();
        if (verifier == null || origin == null || System.currentTimeMillis() - startedAt > MAX_AGE_MS) return;
        Map<String, String> fragment = fragment(data.getEncodedFragment());
        String code = fragment.get("code");
        if (code != null) {
            byte[] body = ("code=" + encode(code) + "&verifier=" + encode(verifier)).getBytes(StandardCharsets.US_ASCII);
            webView.post(() -> webView.postUrl(origin + EXCHANGE_PATH, body));
            return;
        }
        // Cancelled or refused: the web completion page explains what happened.
        String result = fragment.get("result");
        Uri.Builder complete = Uri.parse(origin + "/auth/complete").buildUpon();
        String flow = fragment.get("flow");
        if (flow != null && FLOW.matcher(flow).matches()) complete.appendQueryParameter("flow", flow);
        complete.appendQueryParameter("auth_result", result != null && RESULT.matcher(result).matches() ? result : "failed");
        String target = complete.build().toString();
        webView.post(() -> webView.loadUrl(target));
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String base64Url(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Map<String, String> fragment(String encoded) {
        Map<String, String> values = new HashMap<>();
        if (encoded == null) return values;
        for (String pair : encoded.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            try {
                values.put(
                    URLDecoder.decode(pair.substring(0, split), "UTF-8"),
                    URLDecoder.decode(pair.substring(split + 1), "UTF-8")
                );
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // A malformed pair is skipped; a missing code reads as a failed sign-in.
            }
        }
        return values;
    }
}
