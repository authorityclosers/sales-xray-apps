package com.authorityclosers.salesxray;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The page sits clear of the status bar, the cut-out and the navigation bar (owner's
 * phone test, 19 Oct: the top of screens was under the punch-hole). Run on an emulator
 * with a cut-out overlay switched on (scripts/emulator-call-recorder.sh).
 */
@RunWith(AndroidJUnit4.class)
public class SafeAreaTest {

    @Test
    public void thePageStaysClearOfTheBarsAndTheCutOut() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(3000);
            AtomicReference<String> problem = new AtomicReference<>();
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                WindowInsetsCompat root = ViewCompat.getRootWindowInsets(decor);
                assertNotNull(root);
                Insets need = root.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets cutout = root.getInsets(WindowInsetsCompat.Type.displayCutout());
                WebView web = activity.getBridge().getWebView();
                int[] at = new int[2];
                web.getLocationOnScreen(at);
                int bottom = at[1] + web.getHeight();
                int screen = decor.getHeight();
                Log.i("SXTEST", "safe area need=" + need + " cutout=" + cutout + " web top=" + at[1] + " bottom=" + bottom + " screen=" + screen);
                if (need.top == 0) problem.set("no top inset reported");
                else if (at[1] < need.top) problem.set("page top " + at[1] + " under the bars/cut-out " + need.top);
                else if (bottom > screen - need.bottom) problem.set("page bottom " + bottom + " under the navigation bar " + (screen - need.bottom));
                else if (at[0] < need.left || at[0] + web.getWidth() > decor.getWidth() - need.right) problem.set("page under a side inset");
            });
            assertTrue(String.valueOf(problem.get()), problem.get() == null);
        }
    }
}
