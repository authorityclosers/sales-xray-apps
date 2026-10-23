package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** The recordings folder the seller picks on a Play build (S8, 23 Oct 2026). */
public class RecordingFolderRulesTest {

    @Test
    public void pathsOnThePhonesOwnStorage() {
        assertEquals("MIUI/sound_recorder/call_rec", RecordingFolderRules.path("primary:MIUI/sound_recorder/call_rec"));
        assertEquals("Recordings/Call", RecordingFolderRules.path("primary:Recordings/Call/"));
        assertEquals("1A2B-3C4D:Recordings", RecordingFolderRules.path("1A2B-3C4D:Recordings"));
        assertEquals("", RecordingFolderRules.path(null));
    }

    @Test
    public void aPickedFolderCoversItsOwnAndNearbyRecorderFolders() {
        assertTrue(RecordingFolderRules.covers("MIUI/sound_recorder/call_rec", "MIUI/sound_recorder/call_rec"));
        assertTrue(RecordingFolderRules.covers("miui/Sound_Recorder", "MIUI/sound_recorder/call_rec"));
        assertTrue(RecordingFolderRules.covers("MIUI", "MIUI/sound_recorder/call_rec"));
        assertFalse(RecordingFolderRules.covers("MIUI/sound_recorder/call_rec", "MIUI/sound_recorder"));
        assertFalse(RecordingFolderRules.covers("Music", "MIUI/sound_recorder/call_rec"));
        assertFalse(RecordingFolderRules.covers("MIU", "MIUI/sound_recorder/call_rec"));
        assertFalse(RecordingFolderRules.covers("", "MIUI/sound_recorder/call_rec"));
        assertTrue(RecordingFolderRules.coversAny(Arrays.asList("Download", "MIUI/sound_recorder/call_rec"), RecorderRules.XIAOMI));
        assertFalse(RecordingFolderRules.coversAny(Collections.emptyList(), RecorderRules.XIAOMI));
    }

    @Test
    public void onlyDocumentsInsideThePickedFolderOpen() {
        String tree = "primary:MIUI/sound_recorder/call_rec";
        assertTrue(RecordingFolderRules.inside(tree, tree + "/Ravi Sharma(9876543210)_20261023101530.mp3"));
        assertTrue(RecordingFolderRules.inside("primary:MIUI", "primary:MIUI/sound_recorder/call_rec/a.mp3"));
        assertFalse(RecordingFolderRules.inside("primary:MIUI", "primary:MIUI/a/b/c/d.mp3"));
        assertFalse(RecordingFolderRules.inside(tree, tree));
        assertFalse(RecordingFolderRules.inside(tree, "primary:MIUI/sound_recorder/call_rec_other/a.mp3"));
        assertFalse(RecordingFolderRules.inside(tree, tree + "/../../../Download/secret.pdf"));
        assertFalse(RecordingFolderRules.inside(tree, tree + "//a.mp3"));
        assertFalse(RecordingFolderRules.inside("", "primary:a.mp3"));
    }

    @Test
    public void xiaomiNeedsNothingMoreOnceItsFolderIsPicked() {
        RecorderRules.Profile miui = RecorderRules.profile("com.android.contacts", "xiaomi");
        assertTrue(RecorderRules.needsAllFiles(miui, 36, false, Collections.emptyList()));
        assertFalse(RecorderRules.needsAllFiles(miui, 36, false, Collections.singletonList("MIUI/sound_recorder/call_rec")));
        assertTrue(RecorderRules.needsAllFiles(miui, 36, false, Collections.singletonList("Download")));
        assertFalse(RecorderRules.needsAllFiles(miui, 36, true, Collections.emptyList()));
    }
}
