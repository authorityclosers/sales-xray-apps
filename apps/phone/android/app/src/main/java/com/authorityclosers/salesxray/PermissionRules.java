package com.authorityclosers.salesxray;

/** When a permission is blocked for good (PermissionAsks). Plain Java, for JVM tests. */
final class PermissionRules {

    private PermissionRules() {}

    /**
     * Not granted, asked before, and Android no longer wants to explain it: the dialog won't show
     * again. Before the first ask, Android also says "no explanation", which isn't blocked.
     */
    static boolean blocked(boolean granted, boolean asked, boolean rationale) {
        return !granted && asked && !rationale;
    }

    static final String BATTERY_ASK = "ask";
    static final String BATTERY_APP_INFO = "appInfo";
    static final String BATTERY_LIST = "list";

    /**
     * Which screen "Open battery settings" starts. Android's "Allow in the background?" question needs
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which the Play build leaves out: without it the question
     * opens and closes before it shows, and nothing seems to happen (OnePlus, Android 16, bug 147).
     * HyperOS answers it with its own Battery saver list either way. Otherwise App info, where Battery
     * (App battery usage) holds Unrestricted, from Android 12; the battery optimisation list before it.
     */
    static String batteryScreen(boolean canAsk, String family, int sdk) {
        if (canAsk || "xiaomi".equals(family)) return BATTERY_ASK;
        return sdk >= 31 ? BATTERY_APP_INFO : BATTERY_LIST;
    }
}
