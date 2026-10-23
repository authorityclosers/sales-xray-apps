package com.authorityclosers.salesxray;

import java.util.regex.Pattern;

/** Plain rules for gold review alerts: the words and the page an alert opens. */
final class ReviewAlertRules {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private ReviewAlertRules() {}

    /** One new Report opens straight to it; several open the review list. */
    static boolean one(String kind, String assignmentId) {
        return !"many".equals(kind) && assignmentId != null && ID.matcher(assignmentId).matches();
    }

    static String path(String kind, String assignmentId) {
        return one(kind, assignmentId) ? "/review#call=" + assignmentId : "/review";
    }

    /** Only the review pages, never another page or site. */
    static boolean isPath(String path) {
        return (
            path != null &&
            (path.equals("/review") || path.startsWith("/review#") || path.startsWith("/review/") || path.startsWith("/review?"))
        );
    }

    static String title(int waiting) {
        return waiting > 1 ? waiting + " Reports to review" : "New Report to review";
    }

    static String body(boolean one, int waiting) {
        if (!one) return "New ones are ready. Tap to see them in Sales X-Ray.";
        return waiting > 1 ? "A new one is ready. Tap to open it in Sales X-Ray." : "Tap to open it in Sales X-Ray.";
    }
}
