package com.authorityclosers.salesxray;

import android.os.Build;
import android.webkit.WebView;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

/**
 * Back through the page's history (it closes sheets and dialogs first); on a tab root, Android's own back-to-home,
 * so the app goes to the background and stays ready (BackRules). Capacitor's own handler did nothing on the first page.
 */
final class WebBack {

    private final WebView page;
    private final OnBackPressedCallback handler;
    private int depth = BackRules.UNKNOWN;

    WebBack(ComponentActivity activity, WebView page) {
        this.page = page;
        handler = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (page.canGoBack()) page.goBack();
                else activity.moveTaskToBack(true);
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, handler);
        update();
    }

    /** The WebView's history changed: a new page, or the page's own pushState. Main thread. */
    void historyChanged() {
        update();
    }

    /** A new document: it hasn't said how deep it is yet. Main thread. */
    void pageStarted() {
        depth = BackRules.UNKNOWN;
        update();
    }

    /** The page's router depth from the bridge. Main thread. */
    void setDepth(int reported) {
        depth = BackRules.depth(reported);
        update();
    }

    private void update() {
        handler.setEnabled(BackRules.appHandles(Build.VERSION.SDK_INT, depth, page.canGoBack()));
    }
}
