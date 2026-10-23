package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sales X-Ray's web app inside the app (owner, 23 Oct 2026: "I need a full
 * app now, not like a website loading thing"). The app's build puts the web
 * app's static export in assets/web (scripts/build-web-shell.sh, with a list
 * of its files in web/sx-web.json). The app keeps opening Sales X-Ray's own
 * address, but pages, screens, scripts and styles are answered from the
 * phone, so it starts and moves between screens without waiting for the
 * network. The API (/v1), sign-in and anything the bundle doesn't have go to
 * the server as before: same address, same cookies.
 *
 * A bundle older than two weeks, one built for another address, or the
 * seller's own "use the live website" choice turns it off.
 */
final class BundledWeb {

    static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;
    private static final String DIR = "web/";
    private static final String MANIFEST = "web/sx-web.json";
    private static final String PREFS = "sx_web_shell";
    private static final String LIVE = "live";

    private final Context context;
    private final Set<String> files;
    private final String host;
    private final String serverHost;
    private final String sha;
    private final String ref;
    private final long builtAtMs;

    private BundledWeb(Context context, Set<String> files, String host, String serverHost, String sha, String ref, long builtAtMs) {
        this.context = context.getApplicationContext();
        this.files = files;
        this.host = host;
        this.serverHost = serverHost;
        this.sha = sha;
        this.ref = ref;
        this.builtAtMs = builtAtMs;
    }

    /** The bundle this app carries, for the site at {@code serverUrl} (an empty one when there is none). */
    static BundledWeb load(Context context, String serverUrl) {
        String serverHost = serverUrl == null ? "" : String.valueOf(Uri.parse(serverUrl).getHost());
        try (InputStream stream = context.getAssets().open(MANIFEST)) {
            JSONObject manifest = new JSONObject(read(stream));
            JSONArray list = manifest.optJSONArray("files");
            Set<String> files = new HashSet<>();
            for (int i = 0; list != null && i < list.length(); i++) files.add(list.optString(i));
            return new BundledWeb(
                context,
                files,
                manifest.optString("host"),
                serverHost,
                manifest.optString("sha"),
                manifest.optString("ref"),
                manifest.optLong("builtAtMs")
            );
        } catch (IOException | JSONException error) {
            return new BundledWeb(context, Collections.emptySet(), "", serverHost, "", "", 0);
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean available() {
        return !files.isEmpty() && host.equals(serverHost);
    }

    boolean fresh() {
        return BundledWebRules.fresh(builtAtMs, System.currentTimeMillis(), MAX_AGE_MS);
    }

    /** Pages come from the phone. */
    boolean on() {
        return available() && fresh() && !prefs().getBoolean(LIVE, false);
    }

    /** The seller's choice: the live website (true) or the app's own copy. */
    void setLive(boolean live) {
        prefs().edit().putBoolean(LIVE, live).apply();
    }

    /** The bundled answer to this request, or null for the server. */
    WebResourceResponse serve(WebResourceRequest request) {
        if (!on() || !"GET".equalsIgnoreCase(request.getMethod())) return null;
        Uri url = request.getUrl();
        if (!"https".equals(url.getScheme()) || !host.equals(url.getHost())) return null;
        Map<String, String> headers = request.getRequestHeaders();
        boolean rsc = "1".equals(headerOf(headers, "RSC")) || url.getQueryParameter("_rsc") != null;
        String file = BundledWebRules.resolve(url.getEncodedPath(), rsc, files);
        if (file == null) return null;
        try {
            InputStream stream = context.getAssets().open(DIR + file);
            String mime = BundledWebRules.mime(file);
            Map<String, String> answer = new HashMap<>();
            answer.put("Cache-Control", file.startsWith("_next/static/") ? "public, max-age=31536000, immutable" : "no-cache");
            answer.put("X-SX-Shell", sha);
            return new WebResourceResponse(mime, BundledWebRules.isText(mime) ? "utf-8" : null, 200, "OK", answer, stream);
        } catch (IOException error) {
            return null;
        }
    }

    JSONObject status() {
        JSONObject status = new JSONObject();
        try {
            status.put("available", available());
            status.put("on", on());
            status.put("fresh", fresh());
            status.put("live", prefs().getBoolean(LIVE, false));
            status.put("sha", sha);
            status.put("ref", ref);
            status.put("builtAtMs", builtAtMs);
            status.put("files", files.size());
        } catch (JSONException ignored) {}
        return status;
    }

    private static String headerOf(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) > 0) out.write(buffer, 0, count);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
