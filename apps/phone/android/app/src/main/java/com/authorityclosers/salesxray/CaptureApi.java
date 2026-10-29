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
        Failure(int code) { super("capture_http_" + code); this.code = code; }
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
            String cookies = CookieManager.getInstance().getCookie(base);
            if (cookies == null || cookies.isEmpty()) throw new Failure(401);
            SessionCookies.attach(c, cookies);
            if (key != null) c.setRequestProperty("Idempotency-Key", key);
            if (identity != null) c.setRequestProperty("X-Auto-Capture-Identity", identity);
            if (standing) c.setRequestProperty("X-Upload-Consent", "standing");
            if (sha != null) {
                c.setRequestProperty("X-Source-Sha256", sha);
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
            if (status < 200 || status >= 300) throw new Failure(status);
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
    JSONObject book() throws Exception {
        JSONObject result = json("GET", "/v1/lead-desk/phone-book", null, null, false);
        if (identity == null) identity = CaptureRules.identity(result);
        return result;
    }
    JSONObject policy() throws Exception { return json("GET", "/v1/conversation/acquisition/upload-policy", null, null, false); }
    JSONObject consent() throws Exception { return json("GET", "/v1/sales-xray/call-ai/auto-analysis", null, null, false); }
}
