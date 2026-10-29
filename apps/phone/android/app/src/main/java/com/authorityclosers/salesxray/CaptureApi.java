package com.authorityclosers.salesxray;

import android.content.Context;
import android.webkit.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Same-origin requests on the existing WebView session. No redirect or stored credential. */
class CaptureApi {
    final String base;
    String identity;
    CaptureApi(Context context) throws IOException {
        base = LeadAlerts.serverUrl(context);
        if (base == null) throw new IOException("origin_unavailable");
    }
    static final class Failure extends IOException {
        final int code;
        /** The server's {"detail"} words, at most 300 characters (never audio or a credential). */
        final String detail;
        Failure(int code) { this(code, ""); }
        Failure(int code, String detail) { super("capture_http_" + code); this.code = code; this.detail = detail == null ? "" : detail; }
    }
    /** A call that waits on this phone, and why (CaptureRules). */
    static final class Hold extends Exception {
        final String reason;
        Hold(String reason) { super("capture_hold_" + reason); this.reason = reason; }
    }
    JSONObject json(String method, String path, JSONObject body, String key, boolean standing) throws Exception {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        return request(method, path, bytes == null ? null : new ByteArrayInputStream(bytes),
            bytes == null ? 0 : bytes.length, "application/json", key, standing, null, null);
    }
    JSONObject request(String method, String path, InputStream input, long size, String mime,
                       String key, boolean standing, String sha, String policy) throws Exception {
        if (!path.startsWith("/v1/") || path.contains("..")) throw new IOException("bad_path");
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000); c.setReadTimeout(90_000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Origin", base);
            c.setRequestProperty("User-Agent", AppUserAgent.current() + " (auto-capture)");
            String cookies = CookieManager.getInstance().getCookie(base);
            if (cookies == null || cookies.isEmpty()) throw new Failure(401);
            SessionCookies.attach(c, cookies);
            if (key != null) c.setRequestProperty("Idempotency-Key", key);
            if (identity != null) c.setRequestProperty("X-Auto-Capture-Identity", identity);
            if (standing) c.setRequestProperty("X-Upload-Consent", "standing");
            if (sha != null) {
                c.setRequestProperty("X-Source-Sha256", sha);
                c.setRequestProperty("X-Capture-Source", "android_dialer_pickup");
                c.setRequestProperty("X-Upload-Policy", policy);
            }
            if (input != null) {
                c.setRequestProperty("Content-Type", mime);
                c.setFixedLengthStreamingMode(size); c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    byte[] buffer = new byte[65536]; long total = 0; int n;
                    while ((n = input.read(buffer)) != -1) {
                        total += n;
                        if (total > size) throw new IOException("source_changed");
                        out.write(buffer, 0, n);
                    }
                    if (total != size) throw new IOException("source_changed");
                }
            }
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new Failure(status, detail(c));
            if (status == 204) return new JSONObject();
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 2 * 1024 * 1024) throw new IOException("response_too_large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { c.disconnect(); }
    }
    /** The refusal's {"detail"}; the edge's write-eligibility check answers with the API's own body. */
    private static String detail(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return "";
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1 && out.size() < 4096) out.write(buffer, 0, n);
            String words = new JSONObject(out.toString("UTF-8")).optString("detail", "");
            return words.length() > 300 ? words.substring(0, 300) : words;
        } catch (Exception unreadable) { return ""; }
    }
    JSONObject book() throws Exception {
        JSONObject result = json("GET", "/v1/lead-desk/phone-book", null, null, false);
        if (identity == null) identity = CaptureRules.identity(result);
        return result;
    }
    JSONObject policy() throws Exception { return json("GET", "/v1/conversation/acquisition/upload-policy", null, null, false); }
    JSONObject consent() throws Exception { return json("GET", "/v1/sales-xray/call-ai/auto-analysis", null, null, false); }
    /** The edge asks this before any audio: false while the seller's contact profile is incomplete. */
    boolean eligible() throws Exception {
        try { json("GET", "/v1/me/sales-xray-profile/write-eligibility", null, null, false); return true; }
        catch (Failure refused) { if (refused.code == 403) return false; throw refused; }
    }
    /** Call minutes left for this seller in the current workspace; null when the answer says nothing. */
    Boolean minutesLeft() throws Exception {
        return CaptureRules.minutesLeft(json("GET", "/v1/conversation/acquisition/session", null, null, false).optJSONObject("allowance"));
    }
}
