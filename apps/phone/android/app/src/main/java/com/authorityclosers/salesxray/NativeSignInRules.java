package com.authorityclosers.salesxray;

import java.util.regex.Pattern;

/**
 * Plain rules for the app's own sign-in (NativeSignIn): what a page may ask for, and
 * what the page is told when Android's Google sheet or Facebook's sign-in ends
 * without a token. "unavailable" sends the page back to the phone's browser, as
 * before, so a phone or an environment that can't sign in here is never stuck.
 */
final class NativeSignInRules {

    /** Our web client ID, which Android's account sheet uses as the server client ID. */
    private static final Pattern GOOGLE_CLIENT = Pattern.compile("[0-9]{4,20}-[0-9a-z]{8,64}\\.apps\\.googleusercontent\\.com");
    /** The sign-in transaction's nonce (base64url). */
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,256}");
    private static final Pattern FACEBOOK_APP = Pattern.compile("[0-9]{5,25}");
    private static final Pattern FACEBOOK_CLIENT_TOKEN = Pattern.compile("[0-9A-Za-z]{16,64}");

    private NativeSignInRules() {}

    static boolean googleRequest(String clientId, String nonce) {
        return clientId != null && nonce != null && GOOGLE_CLIENT.matcher(clientId).matches() && NONCE.matcher(nonce).matches();
    }

    static boolean facebookRequest(String appId, String clientToken) {
        return appId != null && clientToken != null && FACEBOOK_APP.matcher(appId).matches() && FACEBOOK_CLIENT_TOKEN.matcher(clientToken).matches();
    }

    /**
     * What a Credential Manager error means for the page, by the exception's class name.
     * Closing the sheet is "cancelled"; a phone without Google's sign-in, or a setup
     * Google refuses (the console's Android client), is "unavailable".
     */
    static String googleError(String exceptionName) {
        if (exceptionName == null) return "failed";
        switch (exceptionName) {
            case "GetCredentialCancellationException":
                return "cancelled";
            case "NoCredentialException":
            case "GetCredentialProviderConfigurationException":
            case "GetCredentialUnsupportedException":
            case "GetCredentialCustomException":
                return "unavailable";
            default:
                return "failed";
        }
    }
}
