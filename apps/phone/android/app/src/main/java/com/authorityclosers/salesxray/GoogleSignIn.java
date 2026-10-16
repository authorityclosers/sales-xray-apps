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
 * back to com.authorityclosers.salesxray:/auth/return. The sign-in window the
 * page opened (a hidden web view, see MainActivity) redeems the one-time code
 * once, lands on /auth/complete and tells the page, exactly as on the web.
 */
final class GoogleSignIn {

    static final String START_PATH = "/v1/auth/google/start";
    static final String RETURN_PATH = "/auth/return";
    static final String EXCHANGE_PATH = "/v1/auth/google/app-exchange";
    static final String COMPLETE_PATH = "/auth/complete";

    private static final String PREFS = "sx.google_sign_in";
    private static final String KEY_VERIFIER = "verifier";
    private static final String KEY_ORIGIN = "origin";
    private static final String KEY_STARTED = "started_at";
    // A sign-in that takes longer than this starts again from the app.
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;
    private static final Pattern RESULT = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern FLOW = Pattern.compile("[0-9a-fA-F-]{36}");

    private final AppCompatActivity activity;
    private final WebView main;
    private final SecureRandom random = new SecureRandom();
    /** The web view that started the sign-in: the page's sign-in window, or the app itself. */
    private WebView target;
    private String origin;
    private String flow;
    /** Google's sheet is open, and whether the app has been in the background since. */
    private boolean awaiting;
    private boolean leftApp;
    /** The app itself redeemed a code (its sign-in window was gone): go home after. */
    private boolean homeAfterComplete;

    GoogleSignIn(AppCompatActivity activity, WebView main) {
        this.activity = activity;
        this.main = main;
    }

    /** Pages a sign-in window loads itself; everything else leaves it. */
    static boolean staysInSignInWindow(Uri url) {
        return (
            url != null &&
            "https".equals(url.getScheme()) &&
            PhoneBridge.HOSTS.contains(url.getHost()) &&
            (COMPLETE_PATH.equals(url.getPath()) || EXCHANGE_PATH.equals(url.getPath()))
        );
    }

    /** Opens a Sales Xray Google sign-in start in a Custom Tab instead of a web view. */
    boolean intercept(Uri url, WebView source) {
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
        target = source;
        origin = "https://" + url.getHost();
        flow = flowOf(url.getQueryParameter("return_path"));
        awaiting = true;
        leftApp = false;
        prefs()
            .edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_ORIGIN, origin)
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
        awaiting = false;
        SharedPreferences prefs = prefs();
        String verifier = prefs.getString(KEY_VERIFIER, null);
        String savedOrigin = prefs.getString(KEY_ORIGIN, null);
        long startedAt = prefs.getLong(KEY_STARTED, 0L);
        prefs.edit().clear().apply();
        if (verifier == null || savedOrigin == null || System.currentTimeMillis() - startedAt > MAX_AGE_MS) return;
        origin = savedOrigin;
        Map<String, String> fragment = fragment(data.getEncodedFragment());
        String code = fragment.get("code");
        if (code != null) {
            byte[] body = ("code=" + encode(code) + "&verifier=" + encode(verifier)).getBytes(StandardCharsets.US_ASCII);
            WebView into = liveTarget();
            homeAfterComplete = into == main;
            into.post(() -> into.postUrl(savedOrigin + EXCHANGE_PATH, body));
            return;
        }
        String result = fragment.get("result");
        String returnedFlow = fragment.get("flow");
        if (returnedFlow != null && FLOW.matcher(returnedFlow).matches()) flow = returnedFlow;
        report(result != null && RESULT.matcher(result).matches() ? result : "failed");
    }

    void onStop() {
        if (awaiting) leftApp = true;
    }

    /** Back in the app with no result: the person closed Google's sheet. */
    void onResume() {
        if (!awaiting || !leftApp) return;
        awaiting = false;
        leftApp = false;
        prefs().edit().clear().apply();
        report("failed");
    }

    /** After the app itself redeemed a code, /auth/complete has no window to tell: go home. */
    void onPageFinished(WebView view, String url) {
        if (!homeAfterComplete || view != main || origin == null) return;
        if (!COMPLETE_PATH.equals(Uri.parse(url).getPath())) return;
        homeAfterComplete = false;
        String home = origin + "/";
        view.post(() -> view.loadUrl(home));
    }

    void signInWindowClosed(WebView window) {
        if (target == window) target = null;
    }

    /** Tells the page's sign-in window how Google ended; the page shows the message. */
    private void report(String result) {
        WebView window = target;
        if (window == null || window == main || origin == null) return;
        Uri.Builder complete = Uri.parse(origin + COMPLETE_PATH).buildUpon();
        if (flow != null) complete.appendQueryParameter("flow", flow);
        complete.appendQueryParameter("auth_result", result);
        String url = complete.build().toString();
        window.post(() -> window.loadUrl(url));
    }

    private WebView liveTarget() {
        WebView window = target;
        return window != null && window.getParent() != null ? window : main;
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String flowOf(String returnPath) {
        if (returnPath == null) return null;
        String value = Uri.parse("https://x" + returnPath).getQueryParameter("flow");
        return value != null && FLOW.matcher(value).matches() ? value : null;
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
