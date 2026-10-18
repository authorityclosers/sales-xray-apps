package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Call capture on a real phone (owner, 19 Oct). Run over adb:
 *   adb shell am instrument -w -e placed "MIUI/sound_recorder/call_rec/x.m4a,Recordings/Call/y.m4a" \
 *     com.authorityclosers.salesxray.dev.test/androidx.test.runner.AndroidJUnitRunner
 * "placed" names test recordings pushed into the makers' real folders beforehand.
 * Every result is also logged under the tag SXTEST for the test report.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceCaptureTest {

    private static final String TAG = "SXTEST";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = stream.read(buffer)) > 0) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    @Test
    public void knowsThisPhonesRecorder() throws Exception {
        JSONObject status = RecorderProfile.status(context());
        Log.i(TAG, "recorderStatus " + status);
        assertTrue(status.has("kind"));
        assertTrue(status.has("folders"));
        String dialer = status.optString("dialer");
        if (RecorderRules.GOOGLE_DIALER.equals(dialer)) {
            assertEquals("private", status.getString("kind"));
            assertEquals("private", status.getString("recording"));
        }
        JSONObject setup = PhoneSetup.status(context());
        Log.i(TAG, "setupStatus " + setup);
        assertNotNull(setup.optJSONObject("recorderProfile"));
    }

    @Test
    public void picksUpRecordingsPlacedInTheMakersRealFolders() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String placed = args.getString("placed", "");
        List<JSONObject> found = RecordingScanner.list(context(), 500);
        Log.i(TAG, "canReadFolders " + RecordingScanner.canReadFolders(context()) + " allFiles " + RecordingScanner.hasAllFilesAccess() + " listed " + found.size());
        for (String relative : placed.split(",")) {
            if (relative.trim().isEmpty()) continue;
            String name = relative.substring(relative.lastIndexOf('/') + 1);
            JSONObject hit = null;
            for (JSONObject item : found) if (name.equals(item.optString("name"))) hit = item;
            Log.i(TAG, "placed " + relative + " -> " + (hit == null ? "NOT FOUND" : hit.toString()));
            assertNotNull("not picked up: " + relative, hit);
            try (InputStream stream = RecordingScanner.open(context(), hit.getString("source"))) {
                byte[] bytes = readAll(stream);
                Log.i(TAG, "read " + relative + " bytes " + bytes.length);
                assertTrue(bytes.length > 1000);
            }
        }
    }

    @Test
    public void recordsAConversationInTheAppAndListsIt() throws Exception {
        Context context = context();
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            String outcome = AppRecorder.start(context, "device-test");
            Log.i(TAG, "recordStart " + outcome);
            assertEquals("started", outcome);
            Thread.sleep(6000);
            assertTrue(AppRecorder.state(context).getBoolean("recording"));
            AppRecorder.stop(context);
            JSONObject state = AppRecorder.state(context);
            for (int i = 0; i < 50 && (state.getBoolean("recording") || state.isNull("last")); i++) {
                Thread.sleep(100);
                state = AppRecorder.state(context);
            }
            Log.i(TAG, "recordState " + state);
            String source = state.getString("last");
            assertTrue(source.startsWith("app:"));
            JSONObject listed = null;
            for (JSONObject item : RecordingScanner.list(context, 500)) if (source.equals(item.optString("source"))) listed = item;
            Log.i(TAG, "listed " + listed);
            assertNotNull(listed);
            try (InputStream stream = RecordingScanner.open(context, source)) {
                byte[] bytes = readAll(stream);
                Log.i(TAG, "in-app recording bytes " + bytes.length);
                assertTrue(bytes.length > 4000);
                // An MPEG-4 audio file: "ftyp" at byte 4.
                assertEquals("ftyp", new String(bytes, 4, 4, "US-ASCII"));
                // Speech size: the audio is 32 kbps; Android's MPEG-4 writer also keeps a
                // fixed ~250 KB of header room whatever the length (Redmi, 19 Oct: 6 s = 282 KB,
                // stream 32 kbps), so an hour is about 14 MB.
                assertTrue("too big for speech: " + bytes.length, bytes.length < 400_000);
            }
        }
    }

    @Test
    public void theCallRecorderReadsNoScreenAndHearsOnlySalesXray() throws Exception {
        Context context = context();
        JSONObject status = CallRecorderService.status(context);
        Log.i(TAG, "callRecorder " + status);
        assertTrue("sideload build has the recorder", status.getBoolean("available"));
        assertTrue("off by default", !status.getBoolean("wanted"));
        android.view.accessibility.AccessibilityManager manager = context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        android.accessibilityservice.AccessibilityServiceInfo mine = null;
        for (android.accessibilityservice.AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(context.getPackageName())) mine = info;
        }
        assertNotNull(mine);
        Log.i(TAG, "accessibility capabilities " + mine.getCapabilities() + " packages " + java.util.Arrays.toString(mine.packageNames));
        assertEquals(0, mine.getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        assertNotNull(mine.packageNames);
        assertEquals(1, mine.packageNames.length);
        assertEquals(context.getPackageName(), mine.packageNames[0]);
        assertTrue(mine.loadDescription(context.getPackageManager()).contains("does not read your screen"));
    }

    /**
     * After a call (the emulator's `adb emu gsm call/accept/cancel`, or the Dev
     * build's simulated call on a phone without a SIM): the recorder made one
     * file, tagged "accessibility", that lists and reads back. Runs only when
     * the caller passes -e expectCall <phone|simulated>.
     */
    /** The newest recording the call recorder made whose name has {@code marker} ("" = a SIM call). */
    private JSONObject newestCallRecording(String marker) {
        for (JSONObject item : RecordingScanner.list(context(), 500)) {
            if (!"accessibility".equals(item.optString("captured"))) continue;
            String name = item.optString("name");
            boolean sim = !name.contains("(");
            if (marker.isEmpty() ? sim : name.contains(marker)) return item;
        }
        return null;
    }

    @Test
    public void aCallWasRecordedByTheRecorder() throws Exception {
        String expect = InstrumentationRegistry.getArguments().getString("expectCall");
        org.junit.Assume.assumeTrue("run with -e expectCall phone|simulated[,voip]", expect != null && (expect.contains("phone") || expect.contains("simulated")));
        Context context = context();
        JSONObject status = CallRecorderService.status(context);
        Log.i(TAG, "after call " + status);
        assertTrue(status.getBoolean("wanted"));
        assertTrue(status.getBoolean("enabled"));
        JSONObject listed = newestCallRecording(expect.contains("simulated") ? "(simulated)" : "");
        assertNotNull("no call recording listed", listed);
        String source = listed.getString("source");
        assertTrue(source.startsWith("app:" + CallRecorderService.PREFIX));
        Log.i(TAG, "call recording listed " + listed);
        assertNotNull(listed);
        assertEquals("accessibility", listed.getString("captured"));
        try (InputStream stream = RecordingScanner.open(context, source)) {
            byte[] bytes = readAll(stream);
            Log.i(TAG, "call recording bytes " + bytes.length);
            assertEquals("ftyp", new String(bytes, 4, 4, "US-ASCII"));
        }
    }

    /**
     * An internet call (WhatsApp, Meet, Telegram) puts the phone in "in
     * communication" mode. The Dev build's SimulateCallReceiver does the same
     * (voip-start, then voip-end) in the running app before this check: the
     * recorder made one "(internet call)" file tagged "accessibility".
     * Runs only with -e expectCall voip.
     */
    @Test
    public void anInternetCallIsRecorded() throws Exception {
        String expect = InstrumentationRegistry.getArguments().getString("expectCall");
        org.junit.Assume.assumeTrue("run with -e expectCall ...,voip", expect != null && expect.contains("voip"));
        Context context = context();
        JSONObject status = CallRecorderService.status(context);
        Log.i(TAG, "after internet call " + status);
        JSONObject listed = newestCallRecording("(internet call)");
        assertNotNull("no internet-call recording listed", listed);
        String source = listed.getString("source");
        Log.i(TAG, "internet call listed " + listed);
        assertNotNull(listed);
        assertEquals("accessibility", listed.getString("captured"));
        try (InputStream stream = RecordingScanner.open(context, source)) {
            assertEquals("ftyp", new String(readAll(stream), 4, 4, "US-ASCII"));
        }
    }

    @Test
    public void refusesToReadOutsideTheRecordingFolders() {
        for (String source : new String[] { "app:../../shared_prefs/sx_call_log.xml", "file:/sdcard/DCIM/x.jpg", "media:content://contacts/people/1" }) {
            try {
                RecordingScanner.open(context(), source).close();
                throw new AssertionError("opened " + source);
            } catch (java.io.IOException expected) {
                Log.i(TAG, "refused " + source);
            }
        }
    }
}
