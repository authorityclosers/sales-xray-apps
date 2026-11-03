package com.authorityclosers.salesxray;

/**
 * Who handles Back (predictive back; owner via the orchestrator, 3 Nov 2026: the app should feel native). From Android 12
 * the system keeps the app ready when Back leaves its first screen, and from Android 13 it shows its own back-to-home
 * preview, but only when no handler is listening; an app targeting Android 16 also no longer gets onBackPressed. So the
 * app listens only while the page can go back: the page says how deep it is over the bridge ("backDepth", 0 at a tab
 * root), and a page that hasn't said goes by the WebView's own history. Before Android 12, Back on the first screen
 * would close it, so the app always listens there and sends itself to the background instead. Plain Java, for JVM
 * tests; WebBack applies it.
 */
final class BackRules {

    /** Android 12 (API 31): Back on a task's first screen moves the task to the background instead of closing it. */
    static final int SYSTEM_KEEPS_APP = 31;
    /** The page hasn't said how deep it is. */
    static final int UNKNOWN = -1;

    private BackRules() {}

    /** True while the app's own handler takes Back; false lets Android play its back-to-home. */
    static boolean appHandles(int sdk, int depth, boolean canGoBack) {
        if (sdk < SYSTEM_KEEPS_APP) return true;
        return depth == UNKNOWN ? canGoBack : depth > 0;
    }

    /** The depth the page reported; a missing or negative one is unknown. */
    static int depth(int reported) {
        return reported < 0 ? UNKNOWN : reported;
    }
}
