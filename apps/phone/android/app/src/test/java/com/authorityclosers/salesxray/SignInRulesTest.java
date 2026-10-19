package com.authorityclosers.salesxray;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The app's own sign-in: which starts it opens in the phone's browser. */
public class SignInRulesTest {

    @Test
    public void opensGoogleFacebookAndAppleStartsOnly() {
        assertTrue(SignInRules.isStart("/v1/auth/google/start"));
        assertTrue(SignInRules.isStart("/v1/auth/facebook/start"));
        assertTrue(SignInRules.isStart("/v1/auth/apple/start"));
        assertFalse(SignInRules.isStart("/v1/auth/twitter/start"));
        assertFalse(SignInRules.isStart("/v1/auth/google/callback"));
        assertFalse(SignInRules.isStart("/v1/auth/apple/callback"));
        assertFalse(SignInRules.isStart("/v1/auth/google/app-exchange"));
        assertFalse(SignInRules.isStart("/v1/auth/google/start/"));
        assertFalse(SignInRules.isStart("/x/v1/auth/google/start"));
        assertFalse(SignInRules.isStart("/v1/lead-desk/meta/connect"));
        assertFalse(SignInRules.isStart(""));
        assertFalse(SignInRules.isStart(null));
    }
}
