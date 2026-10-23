package com.authorityclosers.salesxray;

/**
 * The phone's sound state for the page (owner, 23 Oct 2026: sounds and haptics that respect
 * silent mode and do-not-disturb). The app writes it on the page's root element:
 * data-sx-ringer="normal|vibrate|silent", and data-sx-dnd="on" while do-not-disturb filters
 * notifications. The page plays sounds only when the ringer is normal and do-not-disturb is
 * off. Plain Java, for JVM tests.
 */
final class DeviceSoundRules {

    // AudioManager.RINGER_MODE_* and NotificationManager.INTERRUPTION_FILTER_*.
    static final int RINGER_SILENT = 0;
    static final int RINGER_VIBRATE = 1;
    static final int FILTER_UNKNOWN = 0;
    static final int FILTER_ALL = 1;

    private DeviceSoundRules() {}

    static String ringer(int mode) {
        if (mode == RINGER_SILENT) return "silent";
        if (mode == RINGER_VIBRATE) return "vibrate";
        return "normal";
    }

    /** Priority only, alarms only or total silence: do-not-disturb is on. */
    static boolean dnd(int filter) {
        return filter != FILTER_ALL && filter != FILTER_UNKNOWN;
    }

    /** The page script that writes the state and tells the page it changed. */
    static String script(String ringer, boolean dnd) {
        String mode = "silent".equals(ringer) || "vibrate".equals(ringer) ? ringer : "normal";
        return "(function(){var e=document.documentElement;if(!e)return;"
            + "e.setAttribute('data-sx-ringer','" + mode + "');"
            + (dnd ? "e.setAttribute('data-sx-dnd','on');" : "e.removeAttribute('data-sx-dnd');")
            + "try{document.dispatchEvent(new CustomEvent('sx-device-state'));}catch(x){}})();";
    }
}
