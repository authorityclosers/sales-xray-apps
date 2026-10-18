package com.authorityclosers.salesxray;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** Where each phone's own dialer saves call recordings (owner, 19 Oct). */
public class RecorderRulesTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void googlesPhoneAppKeepsRecordingsPrivateOnEveryPhone() {
        for (String family : new String[] { "xiaomi", "samsung", "other" }) {
            RecorderRules.Profile profile = RecorderRules.profile("com.google.android.dialer", family);
            assertEquals("private", profile.kind);
            assertEquals(0, profile.folders.length);
        }
        // The connected Redmi: Pixel-based Android 13 with Google's Phone app.
        assertEquals("private", RecorderRules.recording(RecorderRules.profile("com.google.android.dialer", "xiaomi"), null, NOW, NOW));
    }

    @Test
    public void makerDialersPointAtTheirOwnFolders() {
        RecorderRules.Profile miui = RecorderRules.profile("com.android.contacts", "xiaomi");
        assertEquals("folder", miui.kind);
        assertEquals("MIUI/sound_recorder/call_rec", miui.folders[0]);
        assertEquals("button_auto_record_call", miui.settingKey);
        assertEquals("xiaomi", RecorderRules.profile("com.miui.voip", "xiaomi").maker);

        assertArrayEquals(RecorderRules.SAMSUNG, RecorderRules.profile("com.samsung.android.dialer", "samsung").folders);
        assertEquals("Recordings/Call", RecorderRules.SAMSUNG[0]);

        for (String app : new String[] { "com.oplus.dialer", "com.coloros.dialer", "com.oneplus.dialer" }) {
            assertEquals("oplus", RecorderRules.profile(app, "other").maker);
        }
        assertEquals("oplus", RecorderRules.profile("com.android.contacts", "realme").maker);
        assertTrue(Arrays.asList(RecorderRules.OPLUS).contains("Music/Recordings/Call Recordings"));

        assertEquals("vivo", RecorderRules.profile("com.android.dialer", "vivo").maker);
        assertEquals("huawei", RecorderRules.profile("com.hihonor.contacts", "huawei").maker);
        assertNull(RecorderRules.profile("com.samsung.android.dialer", "samsung").settingKey);
    }

    @Test
    public void anOtherDialerSearchesEveryKnownFolder() {
        RecorderRules.Profile other = RecorderRules.profile("com.truecaller", "xiaomi");
        assertEquals("unknown", other.kind);
        assertTrue(other.folders.length > 0);
        assertEquals("unknown", RecorderRules.profile(null, "other").kind);
        assertEquals("xiaomi", RecorderRules.profile(null, "xiaomi").maker);
    }

    @Test
    public void recordingIsOnWhenTheSwitchSaysSoOrARecentRecordingShowsIt() {
        RecorderRules.Profile miui = RecorderRules.profile("com.android.contacts", "xiaomi");
        assertEquals("on", RecorderRules.recording(miui, 1, 0, NOW));
        assertEquals("off", RecorderRules.recording(miui, 0, NOW - DAY, NOW));
        RecorderRules.Profile samsung = RecorderRules.profile("com.samsung.android.dialer", "samsung");
        assertEquals("on", RecorderRules.recording(samsung, null, NOW - 2 * DAY, NOW));
        assertEquals("unknown", RecorderRules.recording(samsung, null, NOW - 40 * DAY, NOW));
        assertEquals("unknown", RecorderRules.recording(samsung, null, 0, NOW));
    }

    @Test
    public void onlyMakerDialersAreOfferedAsTheSwitch() {
        assertTrue(RecorderRules.savesToFolder("com.android.contacts", "xiaomi"));
        assertFalse(RecorderRules.savesToFolder("com.google.android.dialer", "xiaomi"));
        assertFalse(RecorderRules.savesToFolder("com.truecaller", "other"));
    }

    @Test
    public void theScannerReadsEveryMakersFolders() {
        java.util.List<String> all = Arrays.asList(RecordingScanner.folders());
        for (String[] maker : new String[][] { RecorderRules.XIAOMI, RecorderRules.SAMSUNG, RecorderRules.OPLUS, RecorderRules.VIVO, RecorderRules.HUAWEI }) {
            assertTrue(all.containsAll(Arrays.asList(maker)));
        }
        assertEquals(all.size(), new java.util.HashSet<>(all).size());
    }
}
