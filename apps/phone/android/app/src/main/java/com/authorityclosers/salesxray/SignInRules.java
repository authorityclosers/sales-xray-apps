package com.authorityclosers.salesxray;

import java.util.regex.Pattern;

/**
 * Plain rules for the app's own sign-in (GoogleSignIn): which Sales Xray sign-in
 * starts open in the phone's browser instead of a web view. Google, Facebook and
 * Apple all refuse or break sign-in inside web views, and all three come back to
 * the app the same way (com.authorityclosers.salesxray:/auth/return).
 */
final class SignInRules {

    private static final Pattern START = Pattern.compile("/v1/auth/(?:google|facebook|apple)/start");

    private SignInRules() {}

    /** True for exactly a Sales Xray sign-in start path, nothing else. */
    static boolean isStart(String path) {
        return path != null && START.matcher(path).matches();
    }
}
