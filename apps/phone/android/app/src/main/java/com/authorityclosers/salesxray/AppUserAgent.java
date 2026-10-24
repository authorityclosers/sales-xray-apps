package com.authorityclosers.salesxray;

import android.content.Context;
import android.util.Log;
import com.getcapacitor.CapConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The installed build's "SalesXrayApp/<version>" on the page (through Capacitor's config, before the
 * WebView exists) and on the app's own calls (lead alerts, the call log). See UserAgentRules.
 */
final class AppUserAgent {

    private static final String TAG = "SXUserAgent";
    private static final String CONFIG = "capacitor.config.json";
    private static volatile String current = UserAgentRules.FALLBACK;

    private AppUserAgent() {}

    /** Once, from the Application, before any screen, alert or job runs. */
    static void remember(Context context) {
        try {
            current = UserAgentRules.token(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Exception e) {
            current = UserAgentRules.FALLBACK;
        }
    }

    /** For the app's own calls: "SalesXrayApp/1.0.26". */
    static String current() {
        return current;
    }

    /**
     * Capacitor's config from the app's files, with this build's token; null (Capacitor reads its own,
     * with the fixed token) when that fails. Rewritten only when the build changes.
     */
    static CapConfig capConfig(Context context) {
        try {
            String json = UserAgentRules.config(asset(context), current);
            File dir = new File(context.getNoBackupFilesDir(), "capacitor");
            File file = new File(dir, CONFIG);
            if (!file.exists() || !json.equals(read(file))) {
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            return CapConfig.loadFromFile(context, dir.getPath());
        } catch (Exception e) {
            Log.w(TAG, "app version not in the user agent: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String asset(Context context) throws IOException {
        try (InputStream stream = context.getAssets().open(CONFIG)) {
            return text(stream);
        }
    }

    private static String read(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) {
            return text(stream);
        }
    }

    private static String text(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}
