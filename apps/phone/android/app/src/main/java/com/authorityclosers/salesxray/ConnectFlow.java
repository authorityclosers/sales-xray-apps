package com.authorityclosers.salesxray;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;

/**
 * Connect (Facebook &amp; Instagram, Google Ads, WhatsApp) from the app. Facebook and
 * Google refuse sign-in inside web views, so the page asks the app to open the
 * sign-in in the phone's browser (a Custom Tab). Sales Xray's callback there sends
 * the browser to com.authorityclosers.salesxray:/connect/return with a sealed
 * hand-off; this opens the Connect's page again on the site that started it, and
 * the page finishes the Connect on its own session (the web's app-connect.ts).
 */
final class ConnectFlow {

    private static final String PREFS = "sx.connect";
    /** The Sales Xray site whose page asked: the return opens there. */
    private static final String KEY_ORIGIN = "origin";

    private final AppCompatActivity activity;
    private final WebView main;

    ConnectFlow(AppCompatActivity activity, WebView main) {
        this.activity = activity;
        this.main = main;
    }

    /** Opens a Facebook or Google sign-in the Sales Xray page asked for, in the phone's browser. */
    boolean open(String url, String pageUrl) {
        String origin = ConnectRules.originOf(pageUrl, PhoneBridge.HOSTS);
        if (origin == null || !ConnectRules.opens(url)) return false;
        prefs().edit().putString(KEY_ORIGIN, origin).apply();
        Uri address = Uri.parse(url);
        CustomTabsIntent tab = new CustomTabsIntent.Builder().setShowTitle(true).build();
        // A browser, never the Facebook app: it would take facebook.com links for itself.
        String browser = CustomTabsClient.getPackageName(activity, null);
        if (browser != null) tab.intent.setPackage(browser);
        try {
            tab.launchUrl(activity, address);
            return true;
        } catch (ActivityNotFoundException error) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, address).addCategory(Intent.CATEGORY_BROWSABLE));
                return true;
            } catch (ActivityNotFoundException ignored) {
                prefs().edit().clear().apply();
                return false;
            }
        }
    }

    /** Takes the hand-off the browser brought back, if this intent carries one. */
    void take(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null || !activity.getPackageName().equals(data.getScheme()) || !ConnectRules.RETURN_PATH.equals(data.getPath())) return;
        // Handle a return once, even if the activity is recreated with this intent.
        intent.setData(null);
        SharedPreferences prefs = prefs();
        String origin = prefs.getString(KEY_ORIGIN, null);
        prefs.edit().clear().apply();
        // A late return still opens the page: it tells the person to connect again.
        if (origin == null) return;
        String page = ConnectRules.page(origin, data.getEncodedFragment(), PhoneBridge.HOSTS);
        if (page != null) main.post(() -> main.loadUrl(page));
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
