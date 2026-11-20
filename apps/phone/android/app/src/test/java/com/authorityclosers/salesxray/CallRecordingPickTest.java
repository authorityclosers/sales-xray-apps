package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The recording of a call placed from the app (CallTracker). An Oppo CPH2835 (19 and 20 Nov 2026)
 * sent the previous call's file for the next call: the same 698480-byte file for two calls 14
 * minutes apart, and the same 982574-byte file for two calls 15 minutes apart.
 */
public class CallRecordingPickTest {

    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    /** 20 Nov 2026 11:38:00 IST. */
    private static final long START = 1_795_154_880_000L;
    private static final String LEAD = "+91 98765 43210";
    private static final CallRecordingPick.Lengths KNOWN = item -> item.optLong("durationMs");

    private static JSONObject file(String name, long addedMs, long durationMs, long size) throws Exception {
        return new JSONObject()
            .put("source", "tree:content://oppo/" + name)
            .put("name", name)
            .put("addedMs", addedMs)
            .put("durationMs", durationMs)
            .put("size", size)
            .put("supported", true)
            .put("captured", "dialer");
    }

    private static CallRecordingPick.Call call(long startedMs, long endedMs, long talkSeconds) {
        return new CallRecordingPick.Call(CallRecordingPick.key("lead-1", startedMs), startedMs, endedMs, talkSeconds, LEAD, "Ravi Sharma");
    }

    private static CallRecordingPick.Result pick(List<JSONObject> items, CallRecordingPick.Call call, String used) {
        return CallRecordingPick.pick(items, call, used, KNOWN, IST);
    }

    @Test
    public void anEarlierCallsFileIsNeverTaken() throws Exception {
        // The earlier call's file, saved 14 minutes before this call: the old picker's fallback took it.
        JSONObject earlier = file("Recording_001.m4a", START - 14 * 60_000L, 0, 698_480);
        CallRecordingPick.Result result = pick(Collections.singletonList(earlier), call(START, START + 120_000, -1), "");
        assertNull(result.recording);
        assertEquals(CallRecordingPick.STALE, result.reason);
        // The old window let in a file saved up to 5 s before the call; 2 s of clock slack is all there is now.
        JSONObject justBefore = file("Recording_002.m4a", START - 4_000, 0, 1000);
        assertNull(pick(Collections.singletonList(justBefore), call(START, START + 120_000, -1), "").recording);
        JSONObject skewed = file("Recording_003.m4a", START - 1_000, 0, 1000);
        assertSame(skewed, pick(Collections.singletonList(skewed), call(START, START + 120_000, -1), "").recording);
    }

    @Test
    public void aClockInTheNameFromBeforeTheCallIsAnEarlierCall() throws Exception {
        // Saved "now" (the index touched it) but its name says 11:23:58, a quarter of an hour before.
        JSONObject renamed = file("Ravi Sharma_20261120112358.m4a", START + 60_000, 0, 5000);
        CallRecordingPick.Result result = pick(Collections.singletonList(renamed), call(START, START + 120_000, -1), "");
        assertNull(result.recording);
        assertEquals(CallRecordingPick.STALE, result.reason);
    }

    @Test
    public void aLateFileIsWaitedForAndTakenOnceItStopsGrowing() throws Exception {
        JSONObject earlier = file("Recording_001.m4a", START - 14 * 60_000L, 0, 698_480);
        long ended = START + 120_000;
        long[] now = { ended };
        int[] looks = { 0 };
        JSONObject growing = file("Recording_002.m4a", ended + 9_000, 0, 200_000);
        JSONObject done = file("Recording_002.m4a", ended + 9_000, 0, 412_000);
        CallRecordingPick.Scan scan = () -> {
            looks[0]++;
            // Oppo writes the new file late: nothing for 9 s, then it grows once and stays.
            if (now[0] < ended + 9_000) return Collections.singletonList(earlier);
            if (now[0] < ended + 12_000) return Arrays.asList(growing, earlier);
            return Arrays.asList(done, earlier);
        };
        CallRecordingPick.Result result = CallRecordingPick.await(
            scan, call(START, ended, -1), "", KNOWN, IST, () -> now[0], ms -> now[0] += ms, 60_000, 3_000);
        assertEquals(CallRecordingPick.PICKED, result.reason);
        assertEquals(412_000, result.recording.optLong("size"));
        assertEquals(ended + 15_000, now[0]);
        assertTrue(looks[0] >= 5);
    }

    @Test
    public void nothingFitsBeforeTheDeadlineSoNothingIsSent() throws Exception {
        JSONObject earlier = file("Recording_001.m4a", START - 14 * 60_000L, 0, 698_480);
        long[] now = { START + 120_000 };
        CallRecordingPick.Result result = CallRecordingPick.await(
            () -> Collections.singletonList(earlier), call(START, START + 120_000, -1), "", KNOWN, IST,
            () -> now[0], ms -> now[0] += ms, 60_000, 3_000);
        assertNull(result.recording);
        assertEquals(CallRecordingPick.STALE, result.reason);
        assertEquals(START + 180_000, now[0]);
    }

    @Test
    public void theSameCallLookingAgainGetsItsOwnFile() throws Exception {
        CallRecordingPick.Call call = call(START, START + 120_000, -1);
        JSONObject mine = file("Recording_002.m4a", START + 121_000, 0, 412_000);
        String used = CallRecordingPick.remember("", call.key, mine, CallRecordingPick.USED_MAX);
        assertSame(mine, pick(Collections.singletonList(mine), call, used).recording);
    }

    @Test
    public void twoCallsGetTwoFilesAndAFileIsNeverSentTwice() throws Exception {
        long firstStart = START - 14 * 60_000L;
        CallRecordingPick.Call first = call(firstStart, firstStart + 100_000, -1);
        JSONObject a = file("Recording_001.m4a", firstStart + 101_000, 0, 698_480);
        CallRecordingPick.Result one = pick(Collections.singletonList(a), first, "");
        assertSame(a, one.recording);
        String used = CallRecordingPick.remember("", first.key, a, CallRecordingPick.USED_MAX);

        // The phone touches the first call's file during the second call (the media index re-reads it):
        // its time now passes, but it was already given to the first call.
        JSONObject touched = file("Recording_001.m4a", START + 30_000, 0, 698_480);
        touched.put("source", "media:content://media/external/audio/media/77");
        CallRecordingPick.Call second = call(START, START + 120_000, -1);
        CallRecordingPick.Result alone = pick(Collections.singletonList(touched), second, used);
        assertNull(alone.recording);
        assertEquals(CallRecordingPick.USED, alone.reason);

        JSONObject b = file("Recording_002.m4a", START + 121_000, 0, 412_000);
        CallRecordingPick.Result two = pick(Arrays.asList(b, touched), second, used);
        assertSame(b, two.recording);
        used = CallRecordingPick.remember(used, second.key, b, CallRecordingPick.USED_MAX);
        assertTrue(CallRecordingPick.usedElsewhere(used, first.key, b));
        assertTrue(CallRecordingPick.usedElsewhere(used, second.key, a));
        assertFalse(CallRecordingPick.usedElsewhere(used, second.key, b));
    }

    @Test
    public void filesWithoutALengthAreJudgedByTimeAlone() throws Exception {
        // A picked folder (Android's folder grant) gives no length, and reading it failed: 0.
        JSONObject noLength = file("Recording_002.m4a", START + 121_000, 0, 412_000);
        assertSame(noLength, CallRecordingPick.pick(Collections.singletonList(noLength), call(START, START + 120_000, 95), "", item -> 0, IST).recording);
        assertSame(noLength, CallRecordingPick.pick(Collections.singletonList(noLength), call(START, START + 120_000, -1), "", null, IST).recording);
    }

    @Test
    public void aLengthFarFromTheTalkTimeIsAnotherCall() throws Exception {
        JSONObject fiveMinutes = file("Recording_001.m4a", START + 121_000, 300_000, 4_000_000);
        CallRecordingPick.Result result = pick(Collections.singletonList(fiveMinutes), call(START, START + 120_000, 95), "");
        assertNull(result.recording);
        assertEquals(CallRecordingPick.OTHER, result.reason);
        // No talk time: a recording longer than the whole call is another call's.
        assertNull(pick(Collections.singletonList(fiveMinutes), call(START, START + 120_000, -1), "").recording);
        // Within 20 %: this call's.
        JSONObject near = file("Recording_002.m4a", START + 121_000, 104_000, 1_400_000);
        assertSame(near, pick(Collections.singletonList(near), call(START, START + 120_000, 95), "").recording);
        // A short call gets 5 s either way.
        JSONObject shortOne = file("Recording_003.m4a", START + 21_000, 14_000, 100_000);
        assertSame(shortOne, pick(Collections.singletonList(shortOne), call(START, START + 20_000, 10), "").recording);
        JSONObject tooLong = file("Recording_004.m4a", START + 21_000, 16_000, 120_000);
        assertNull(pick(Collections.singletonList(tooLong), call(START, START + 20_000, 10), "").recording);
    }

    @Test
    public void anotherNumberInTheNameIsAnotherCall() throws Exception {
        JSONObject other = file("Call(+919812345678)_x.m4a", START + 121_000, 0, 1000);
        assertEquals(CallRecordingPick.OTHER, pick(Collections.singletonList(other), call(START, START + 120_000, -1), "").reason);
        // An epoch clock in the name is not a phone number.
        JSONObject epoch = file("call_" + (START + 1_000) + ".m4a", START + 121_000, 0, 1000);
        assertSame(epoch, pick(Collections.singletonList(epoch), call(START, START + 120_000, -1), "").recording);
    }

    @Test
    public void aFileNamedAfterTheLeadWinsOverANewerPlainOne() throws Exception {
        JSONObject plain = file("Recording_009.m4a", START + 125_000, 0, 1000);
        JSONObject named = file("Ravi Sharma 9876543210.m4a", START + 121_000, 0, 2000);
        assertSame(named, pick(Arrays.asList(plain, named), call(START, START + 120_000, -1), "").recording);
    }

    @Test
    public void twoFilesThatFitEquallyAreNeitherSent() throws Exception {
        JSONObject x = file("Recording_010.m4a", START + 121_000, 0, 1000);
        JSONObject y = file("Recording_011.m4a", START + 122_000, 0, 2000);
        CallRecordingPick.Result result = pick(Arrays.asList(y, x), call(START, START + 120_000, -1), "");
        assertNull(result.recording);
        assertEquals(CallRecordingPick.AMBIGUOUS, result.reason);
        // The talk time tells them apart when their lengths are clearly different.
        JSONObject near = file("Recording_012.m4a", START + 121_000, 94_000, 1000);
        JSONObject far = file("Recording_013.m4a", START + 122_000, 80_000, 2000);
        assertSame(near, pick(Arrays.asList(far, near), call(START, START + 120_000, 95), "").recording);
        // Sales X-Ray's own recorder and the dialer both have the call: the dialer's is sent.
        JSONObject own = file("sx-call-1.m4a", START + 121_000, 0, 1000).put("captured", "accessibility");
        assertSame(x, pick(Arrays.asList(own, x), call(START, START + 120_000, -1), "").recording);
    }

    @Test
    public void theUsedHistoryKeepsTheNewest200() throws Exception {
        String used = "";
        List<JSONObject> files = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            JSONObject f = file("Recording_" + i + ".m4a", START, 0, 1000 + i);
            files.add(f);
            used = CallRecordingPick.remember(used, "lead:" + i, f, CallRecordingPick.USED_MAX);
        }
        assertEquals(200, used.split("\n").length);
        assertFalse(CallRecordingPick.usedElsewhere(used, "other", files.get(0)));
        assertTrue(CallRecordingPick.usedElsewhere(used, "other", files.get(204)));
        // Remembering the same file for the same call again adds no line.
        assertEquals(used, CallRecordingPick.remember(used, "lead:204", files.get(204), CallRecordingPick.USED_MAX));
    }
}
