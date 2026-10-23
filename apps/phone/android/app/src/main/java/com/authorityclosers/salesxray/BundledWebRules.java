package com.authorityclosers.salesxray;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Set;

/**
 * Which file of the bundled web build answers a request (BundledWeb). Pure
 * rules, so they are tested on the JVM.
 *
 * The bundle is Sales X-Ray's static export (trailing slashes: /leads/ is
 * leads/index.html, and a screen change fetches leads/index.txt). Anything it
 * doesn't have goes to the server. So do the pages that need the server to
 * read their address (sign-in completion, invites, provider review), the API,
 * and a screen change asked in the live site's own way (an RSC request for a
 * path without .txt), so a page from the server never gets a bundled screen.
 */
final class BundledWebRules {

    /** Never answered from the bundle. */
    static final String[] NETWORK_ONLY = { "/v1/", "/auth/", "/organisation/invites", "/provider-review", "/review-fixture", "/.well-known/" };

    private BundledWebRules() {}

    /** The bundled file for this path, or null to ask the server. */
    static String resolve(String rawPath, boolean rscRequest, Set<String> files) {
        String path = decode(rawPath == null || rawPath.isEmpty() ? "/" : rawPath);
        if (path.contains("..")) return null;
        for (String prefix : NETWORK_ONLY) {
            if (path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - (prefix.endsWith("/") ? 1 : 0)))) return null;
        }
        String file = path.substring(1);
        if (rscRequest) return file.endsWith(".txt") && files.contains(file) ? file : null;
        if (!file.isEmpty() && files.contains(file)) return file;
        if (file.isEmpty() || file.endsWith("/")) {
            String index = file + "index.html";
            return files.contains(index) ? index : null;
        }
        if (files.contains(file + "/index.html")) return file + "/index.html";
        if (files.contains(file + ".html")) return file + ".html";
        return null;
    }

    static String mime(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        switch (ext) {
            case "html":
                return "text/html";
            case "js":
            case "mjs":
                return "application/javascript";
            case "css":
                return "text/css";
            case "json":
                return "application/json";
            case "txt":
                return "text/x-component";
            case "svg":
                return "image/svg+xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "ico":
                return "image/x-icon";
            case "woff2":
                return "font/woff2";
            case "woff":
                return "font/woff";
            case "ttf":
                return "font/ttf";
            case "webmanifest":
                return "application/manifest+json";
            case "mp3":
                return "audio/mpeg";
            case "mp4":
                return "video/mp4";
            case "wasm":
                return "application/wasm";
            default:
                return "application/octet-stream";
        }
    }

    /** Text types are sent with a charset. */
    static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.equals("application/javascript") || mime.equals("application/json") || mime.endsWith("+json") || mime.equals("image/svg+xml");
    }

    /** True when a bundle of this age may still be used (older ones give way to the live site). */
    static boolean fresh(long builtAtMs, long nowMs, long maxAgeMs) {
        return builtAtMs > 0 && nowMs - builtAtMs <= maxAgeMs && nowMs >= builtAtMs - 86_400_000L;
    }

    private static String decode(String path) {
        try {
            return URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException error) {
            return path;
        }
    }
}
