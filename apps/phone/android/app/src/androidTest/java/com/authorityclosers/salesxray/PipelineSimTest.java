package com.authorityclosers.salesxray;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.CookieManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Sets the staging app up as a seller would, for the pipeline simulator (scripts/pipeline-sim.sh):
 * the QA seller's session in the WebView, the recordings folder picked through Android's own picker, and
 * automatic uploads on. Runs only with -e sim 1; the simulator then calls, records and watches the upload.
 */
@RunWith(AndroidJUnit4.class)
public class PipelineSimTest {
    @Test public void setUpLikeASeller() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("1".equals(args.getString("sim")));
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String base = LeadAlerts.serverUrl(c);
        assertNotNull("no server for this build", base);
        String[] cookies = new String(Base64.decode(args.getString("cookies", ""), Base64.DEFAULT), StandardCharsets.UTF_8).split("\n");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager manager = CookieManager.getInstance();
            for (String cookie : cookies) if (!cookie.trim().isEmpty()) manager.setCookie(base, cookie);
            manager.flush();
        });
        JSONObject book = new CaptureApi(c).book();
        assertFalse("the QA seller is not in an organisation workspace", CaptureRules.identity(book).isEmpty());

        // The folder: the app's own picker, answered in Android's documents app.
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            boolean picked = !RecordingFolder.trees(c).isEmpty();
            for (int attempt = 0; attempt < 6 && !picked; attempt++) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(PhoneBridge::pickActiveRecordingsFolder);
                UiObject2 use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000);
                if (use == null) { SystemClock.sleep(3_000); continue; }
                use.click();
                UiObject2 allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000);
                if (allow != null) allow.click();
                for (int wait = 0; wait < 20 && RecordingFolder.trees(c).isEmpty(); wait++) SystemClock.sleep(500);
                picked = !RecordingFolder.trees(c).isEmpty();
            }
            assertTrue("the recordings folder was not granted", picked);
        }
        // Uploads on, as after the disclosure's Turn on (the server's standing consent is checked again on each look).
        AutoCapture.configure(c, book, true, false);
        assertTrue(AutoCapture.enabled(c, new CaptureApi(c).book()));
    }
}
