package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.authorityclosers.salesxray.MicRules.Answer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** The page's microphone, for voice notes only (owner, 26 Oct 2026: Play-safe). */
public class MicRulesTest {

    private static final Set<String> HOSTS = new HashSet<>(
        Arrays.asList("salesxray.authorityclosers.com", "salesxray-dev.authorityclosers.com")
    );
    private static final String[] AUDIO = { MicRules.AUDIO };
    private static final String PROD = "https://salesxray.authorityclosers.com/";

    @Test
    public void onlyOurOwnSitesMayAsk() {
        assertTrue(MicRules.ours(PROD, HOSTS));
        assertTrue(MicRules.ours("https://salesxray-dev.authorityclosers.com", HOSTS));
        assertFalse(MicRules.ours("http://salesxray.authorityclosers.com/", HOSTS));
        assertFalse(MicRules.ours("https://salesxray.authorityclosers.com:8443/", HOSTS));
        assertFalse(MicRules.ours("https://salesxray.authorityclosers.com.example.com/", HOSTS));
        assertFalse(MicRules.ours("https://user@salesxray.authorityclosers.com/", HOSTS));
        assertFalse(MicRules.ours("https://example.com/", HOSTS));
        assertFalse(MicRules.ours("null", HOSTS));
        assertFalse(MicRules.ours(null, HOSTS));
    }

    @Test
    public void onlyTheMicrophoneIsEverGiven() {
        assertTrue(MicRules.audioOnly(AUDIO));
        assertFalse(MicRules.audioOnly(new String[] { "android.webkit.resource.VIDEO_CAPTURE" }));
        assertFalse(MicRules.audioOnly(new String[] { MicRules.AUDIO, "android.webkit.resource.VIDEO_CAPTURE" }));
        assertFalse(MicRules.audioOnly(new String[] { "android.webkit.resource.PROTECTED_MEDIA_ID" }));
        assertFalse(MicRules.audioOnly(new String[0]));
        assertFalse(MicRules.audioOnly(null));
    }

    @Test
    public void androidIsAskedOnlyWhenItWouldAsk() {
        // declared, granted, blocked
        assertEquals(Answer.GRANT, MicRules.answer(PROD, AUDIO, HOSTS, true, true, false));
        assertEquals(Answer.ASK, MicRules.answer(PROD, AUDIO, HOSTS, true, false, false));
        // Denied twice: Android won't show its prompt, so the seller gets the app's settings.
        assertEquals(Answer.OPEN_SETTINGS, MicRules.answer(PROD, AUDIO, HOSTS, true, false, true));
        // A build without the microphone, another site, or the camera too: no, and nothing is asked.
        assertEquals(Answer.DENY, MicRules.answer(PROD, AUDIO, HOSTS, false, false, false));
        assertEquals(Answer.DENY, MicRules.answer("https://example.com/", AUDIO, HOSTS, true, true, false));
        assertEquals(Answer.DENY, MicRules.answer(PROD, new String[] { MicRules.AUDIO, "android.webkit.resource.VIDEO_CAPTURE" }, HOSTS, true, true, false));
    }

    @Test
    public void theRecordingStopsInTheBackground() {
        String script = MicRules.script();
        // Once per page; it remembers what getUserMedia and MediaRecorder make, and stops both.
        assertTrue(script.startsWith("(function(){if(window.__sxMicStop)return;"));
        assertTrue(script.contains("md.getUserMedia=function(c)"));
        assertTrue(script.contains("window.MediaRecorder=class extends MR"));
        assertTrue(script.contains("r.stop()"));
        assertTrue(script.contains("t.stop()"));
        assertEquals("window.__sxMicStop&&window.__sxMicStop()", MicRules.STOP);
        assertEquals("Sales X-Ray uses the microphone only while you record a voice note.", MicRules.RATIONALE);
    }
}
