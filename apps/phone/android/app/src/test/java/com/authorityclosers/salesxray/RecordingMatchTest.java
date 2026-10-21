package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;

/** Which phone recording belongs to a logged lead call (owner, 22 Oct: dialler calls get their recording). */
public class RecordingMatchTest {

    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    /** 22 Oct 2026 02:18:12 IST, the Redmi 13's 13 s call to the owner's OnePlus. */
    private static final long CALL = 1_792_615_692_000L;
    private static final String LEAD = "9665278779";

    private static JSONObject item(String name, long addedMs) throws Exception {
        return new JSONObject().put("source", "file:/sdcard/MIUI/sound_recorder/call_rec/" + name).put("name", name).put("addedMs", addedMs).put("supported", true);
    }

    @Test
    public void readsMiuisNumberAndClockFromTheFileName() {
        RecordingMatch.Name name = RecordingMatch.read("QA Test(00919665278779)_20261022021815.mp3", IST);
        assertEquals(CALL + 3_000, name.stampMs);
        assertEquals(Collections.singletonList("00919665278779"), name.numbers);
        RecordingMatch.Name samsung = RecordingMatch.read("Call recording Ravi Sharma_261022_021815.m4a", IST);
        assertEquals(CALL + 3_000, samsung.stampMs);
        assertEquals(0, samsung.numbers.size());
        assertEquals(0, RecordingMatch.read("Ravi.amr", IST).stampMs);
    }

    @Test
    public void theRedmiRecordingMatchesItsCall() throws Exception {
        JSONObject miui = item("QA Test(00919665278779)_20261022021815.mp3", CALL + 21_000);
        assertEquals(RecordingMatch.NUMBER, RecordingMatch.fit(miui, LEAD, CALL, 13, IST));
        assertSame(miui, RecordingMatch.best(Arrays.asList(miui), LEAD, CALL, 13, new HashSet<>(), IST));
    }

    @Test
    public void anotherNumberNeverMatchesEvenAtTheRightTime() throws Exception {
        JSONObject other = item("(00918999438781)_20261022021815.mp3", CALL + 21_000);
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(other, LEAD, CALL, 13, IST));
    }

    @Test
    public void theLeadsNumberAtAnotherTimeIsAnotherCall() throws Exception {
        // The same lead, called again an hour later.
        JSONObject later = item("(00919665278779)_20261022031815.mp3", CALL + 3_621_000);
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(later, LEAD, CALL, 13, IST));
        // Named after the number, no clock in the name: the time it was saved decides.
        assertEquals(RecordingMatch.NUMBER, RecordingMatch.fit(item("+919665278779.amr", CALL + 30_000), LEAD, CALL, 13, IST));
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(item("+919665278779.amr", CALL + 900_000), LEAD, CALL, 13, IST));
    }

    @Test
    public void aContactNamedFileNeedsBothClocksToFit() throws Exception {
        assertEquals(RecordingMatch.CONTACT, RecordingMatch.fit(item("Call recording Ravi_261022_021815.m4a", CALL + 20_000), LEAD, CALL, 13, IST));
        // Saved much later than the call ended: not this call.
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(item("Call recording Ravi_261022_021815.m4a", CALL + 600_000), LEAD, CALL, 13, IST));
    }

    @Test
    public void aPlainFileMatchesOnlyWhenSavedAsTheCallEndedAndAlone() throws Exception {
        JSONObject plain = item("Recording 7.m4a", CALL + 20_000);
        assertEquals(RecordingMatch.PLAIN, RecordingMatch.fit(plain, LEAD, CALL, 13, IST));
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(item("Recording 7.m4a", CALL + 200_000), LEAD, CALL, 13, IST));
        // Two plain files both fit: too close to tell, so neither.
        List<JSONObject> two = Arrays.asList(plain, item("Recording 8.m4a", CALL + 40_000));
        assertNull(RecordingMatch.best(two, LEAD, CALL, 13, new HashSet<>(), IST));
    }

    @Test
    public void theNumberedFileWinsAndAUsedOneIsSkipped() throws Exception {
        JSONObject plain = item("Recording 7.m4a", CALL + 20_000);
        JSONObject miui = item("(00919665278779)_20261022021815.mp3", CALL + 25_000);
        assertSame(miui, RecordingMatch.best(Arrays.asList(plain, miui), LEAD, CALL, 13, new HashSet<>(), IST));
        HashSet<String> used = new HashSet<>(Collections.singletonList(miui.getString("source")));
        assertSame(plain, RecordingMatch.best(Arrays.asList(plain, miui), LEAD, CALL, 13, used, IST));
    }

    @Test
    public void aKnownLengthFarFromTheTalkTimeIsAnotherCall() throws Exception {
        JSONObject longer = item("(00919665278779).mp3", CALL + 20_000).put("durationMs", 300_000);
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(longer, LEAD, CALL, 13, IST));
        assertEquals(RecordingMatch.NUMBER, RecordingMatch.fit(longer.put("durationMs", 14_000), LEAD, CALL, 13, IST));
    }

    @Test
    public void unsupportedFilesAreNotOffered() throws Exception {
        assertEquals(RecordingMatch.NONE, RecordingMatch.fit(item("(00919665278779).mp3", CALL + 20_000).put("supported", false), LEAD, CALL, 13, IST));
    }
}
