package com.authorityclosers.salesxray;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;
import org.json.JSONObject;

/** Read-only QA navigation, enabled only in the side-by-side staging package. */
final class QaProbe {
    static final String PACKAGE = "com.authorityclosers.salesxray.staging";
    static final String ORIGIN = "https://salesxray-staging.authorityclosers.com";
    private long started;
    private boolean first = true;
    private String requested;

    boolean open(Activity activity, WebView web, Intent intent) {
        if (!PACKAGE.equals(activity.getPackageName()) || intent == null) return false;
        Uri uri = intent.getData();
        if (uri == null || !PACKAGE.equals(uri.getScheme()) || uri.getHost() != null) return false;
        String path = uri.getEncodedPath();
        if (!allowed(path) || uri.getEncodedFragment() != null) return false;
        requested = path + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery());
        started = first ? android.os.Process.getStartUptimeMillis() : SystemClock.uptimeMillis();
        first = false;
        web.loadUrl(ORIGIN + requested);
        return true;
    }

    static boolean allowed(String path) {
        if (path == null || path.contains("%") || path.contains("\\") || path.contains("..") || path.startsWith("//")) return false;
        for (String root : new String[]{"/dashboard", "/leads", "/analysis/calls", "/review", "/chats", "/organisation", "/settings"}) {
            if (path.equals(root) || path.startsWith(root + "/")) return true;
        }
        return false;
    }

    void committed(WebView web, String url) {
        if (requested == null || url == null || !url.startsWith(ORIGIN + "/")) return;
        // Chrome's buffered Paint Timing entry is the document's first contentful paint,
        // including redirects. Convert its time origin to our native navigation start.
        long elapsed = SystemClock.uptimeMillis() - started;
        String prefix = "SXQA " + JSONObject.quote(requested) + " ";
        web.evaluateJavascript("(()=>{if(window.__sxQaPaint)return;window.__sxQaPaint=true;"
            + "const offset=" + elapsed + "-performance.now();const emit=e=>console.info("
            + JSONObject.quote(prefix) + "+JSON.stringify({ms:Math.max(0,Math.round(offset+e.startTime)),url:location.origin+location.pathname,metric:'first-contentful-paint'}));"
            + "const e=performance.getEntriesByName('first-contentful-paint')[0];if(e)emit(e);"
            + "else{const o=new PerformanceObserver(l=>{const e=l.getEntries().find(x=>x.name==='first-contentful-paint');if(e){emit(e);o.disconnect();}});o.observe({type:'paint',buffered:true});}})()", null);
    }

    static void console(Activity activity, ConsoleMessage message) {
        if (!PACKAGE.equals(activity.getPackageName())) return;
        // QA APKs are release builds; explicitly retain console/bridge errors in logcat.
        if (message.message().startsWith("SXQA ")) Log.i("SXQA", message.message());
        else if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
            Log.e("SXQAConsole", message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
    }
}
