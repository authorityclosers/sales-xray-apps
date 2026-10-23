package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The app's own sign-in: what a page may ask for, and what it hears back. */
public class NativeSignInRulesTest {

    private static final String CLIENT = "136376378344-abcdefgh12345678abcdefgh12345678.apps.googleusercontent.com";
    private static final String NONCE = "Zq3_x-7Vb2kLm9Np0Qr4St6Uv8Wx1Yz5Ab3Cd7Ef9Gh";

    @Test
    public void googleNeedsOurWebClientAndTheTransactionNonce() {
        assertTrue(NativeSignInRules.googleRequest(CLIENT, NONCE));
        assertFalse(NativeSignInRules.googleRequest("evil.example.com", NONCE));
        assertFalse(NativeSignInRules.googleRequest(CLIENT + ".evil.example", NONCE));
        assertFalse(NativeSignInRules.googleRequest(CLIENT, "short"));
        assertFalse(NativeSignInRules.googleRequest(CLIENT, "has spaces in it, sixteen+"));
        assertFalse(NativeSignInRules.googleRequest(null, NONCE));
        assertFalse(NativeSignInRules.googleRequest(CLIENT, null));
    }

    @Test
    public void facebookNeedsANumericAppIdAndAClientToken() {
        assertTrue(NativeSignInRules.facebookRequest("1514054053884522", "0123456789abcdef0123456789abcdef"));
        assertFalse(NativeSignInRules.facebookRequest("15140540538845x2", "0123456789abcdef0123456789abcdef"));
        assertFalse(NativeSignInRules.facebookRequest("1514054053884522", ""));
        assertFalse(NativeSignInRules.facebookRequest("1514054053884522", "token|with|bars0000000"));
        assertFalse(NativeSignInRules.facebookRequest(null, "0123456789abcdef0123456789abcdef"));
    }

    @Test
    public void aClosedSheetIsCancelledAndASetupGoogleRefusesFallsBackToTheBrowser() {
        assertEquals("cancelled", NativeSignInRules.googleError("GetCredentialCancellationException"));
        assertEquals("unavailable", NativeSignInRules.googleError("NoCredentialException"));
        assertEquals("unavailable", NativeSignInRules.googleError("GetCredentialProviderConfigurationException"));
        assertEquals("unavailable", NativeSignInRules.googleError("GetCredentialUnsupportedException"));
        assertEquals("unavailable", NativeSignInRules.googleError("GetCredentialCustomException"));
        assertEquals("failed", NativeSignInRules.googleError("GetCredentialInterruptedException"));
        assertEquals("failed", NativeSignInRules.googleError("GetCredentialUnknownException"));
        assertEquals("failed", NativeSignInRules.googleError(null));
    }
}
