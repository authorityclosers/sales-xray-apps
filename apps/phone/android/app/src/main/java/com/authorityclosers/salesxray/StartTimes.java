package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Where a cold start's time goes (owner bug #67, 30 Oct 2026): an "SXStart" logcat line per step, with the
 * milliseconds since Android started the process and since the step before on the same thread.
 */
final class StartTimes {

    private static final String TAG = "SXStart";
    private static long lastMain = -1;
    /** Start-up variants a debuggable build reads from files/start-mode, for phone A/B runs. */
    private static volatile Set<String> mode = Collections.emptySet();

    private StartTimes() {}

    static void mark(String step) {
        long now = SystemClock.uptimeMillis();
        long since = now - Process.getStartUptimeMillis();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.i(TAG, step + " at " + since + " ms (+" + (lastMain < 0 ? since : now - lastMain) + ")");
            lastMain = now;
        } else {
            Log.i(TAG, step + " at " + since + " ms [" + Thread.currentThread().getName() + "]");
        }
    }

    static boolean on(String variant) {
        return mode.contains(variant);
    }

    /** Once, from the Application: a debuggable build's variants ("webview reads early"), none in a store build. */
    static void readMode(Context context) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        File file = new File(context.getFilesDir(), "start-mode");
        if (!file.isFile()) return;
        try (InputStream stream = new FileInputStream(file)) {
            byte[] bytes = new byte[256];
            int n = stream.read(bytes);
            String text = n > 0 ? new String(bytes, 0, n, StandardCharsets.UTF_8).trim() : "";
            mode = new HashSet<>(Arrays.asList(text.split("\\s+")));
            Log.i(TAG, "mode " + mode);
        } catch (IOException ignored) {}
    }
}
