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
}
