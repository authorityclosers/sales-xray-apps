package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Live web updates on the phone (LiveWebRules has the rules and why). On start
 * and on coming back to the app it reads the site's signed manifest, downloads
 * a newer web bundle in the background and unpacks it to the app's own
 * storage; the next start uses it. The first start of a new bundle waits for
 * the page's ready signal (SXPhone "webReady"); without it in time, the app
 * goes back and opens Home again from what it used before.
 *
 * Off unless assets/live-web.json pins a public key for this site: no build
 * ships one until the owner turns live updates on.
 */
final class LiveWeb {

    /** Told which web app to use now, after a bundle failed to start. */
    interface Fallback {
        void use(BundledWeb web);
    }

    private static final String TAG = "SXLiveWeb";
    private static final String CONFIG = "live-web.json";
    /** Coming back to the app checks again at most this often; a start always checks. */
    private static final long RESUME_CHECK_MS = 5 * 60 * 1000L;

    private final Context context;
    private final String site;
    private final String host;
    private final String manifestUrl;
    private final List<String> keys;
    private final BundledWeb builtIn;
    private final File root;
    private final File bundles;
    private final File stateFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LiveWebRules.State state;
    /** The downloaded bundle this session uses, or null for the app's own copy. */
    private LiveWebRules.Held serving;
    private Fallback fallback;
    private Runnable timeout;
    private boolean waitingAfterPause;
    private volatile boolean checking;
    private volatile long lastCheckMs;

    private LiveWeb(Context context, String site, String host, String manifestUrl, List<String> keys, BundledWeb builtIn) {
        this.context = context.getApplicationContext();
        this.site = site;
        this.host = host;
        this.manifestUrl = manifestUrl;
        this.keys = keys;
        this.builtIn = builtIn;
        this.root = new File(this.context.getFilesDir(), "live-web");
        this.bundles = new File(root, "bundles");
        this.stateFile = new File(root, "state.json");
        this.state = LiveWebRules.State.parse(readState());
    }

    /** Live updates for the site at {@code serverUrl}, as assets/live-web.json sets them for it. */
    static LiveWeb create(Context context, String serverUrl, BundledWeb builtIn) {
        String host = serverUrl == null ? "" : String.valueOf(Uri.parse(serverUrl).getHost());
        String manifest = null;
        List<String> keys = new ArrayList<>();
        try (InputStream stream = context.getAssets().open(CONFIG)) {
            JSONObject site = new JSONObject(LiveWebRules.read(stream, 64 * 1024)).optJSONObject("sites");
            JSONObject entry = site == null ? null : site.optJSONObject(host);
            if (entry != null) {
                manifest = entry.optString("manifest", null);
                JSONArray list = entry.optJSONArray("keys");
                for (int i = 0; list != null && i < list.length(); i++) if (!list.optString(i).isEmpty()) keys.add(list.optString(i));
            }
        } catch (IOException | JSONException error) {
            // No settings: live updates stay off.
        }
        if (manifest != null && !allowedManifest(context, manifest, host)) manifest = null;
        return new LiveWeb(context, serverUrl, host, manifest, keys, builtIn);
    }

    /** The site's own address over https; a test build may also read the emulator's host machine (CI). */
    private static boolean allowedManifest(Context context, String manifest, String host) {
        Uri uri = Uri.parse(manifest);
        if ("https".equals(uri.getScheme()) && host.equals(uri.getHost())) return true;
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable && "http".equals(uri.getScheme()) && ("10.0.2.2".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }

    boolean enabled() {
        return manifestUrl != null && !keys.isEmpty() && !host.isEmpty();
    }

    /** The web app this start uses. Never waits for the network, so the app starts the same offline. */
    BundledWeb choose() {
        if (!enabled()) return builtIn;
        synchronized (lock) {
            LiveWebRules.Launch launch = LiveWebRules.launch(state, builtIn.builtIn(), presentIds(), System.currentTimeMillis());
            BundledWeb web = launch.builtIn() ? null : BundledWeb.fromFolder(context, folder(launch.bundle), site, launch.bundle);
            if (!launch.builtIn() && web == null) {
                // Its folder can't be read: as good as a failed start.
                launch = LiveWebRules.failed(state, launch.bundle.sha256);
                web = launch.builtIn() ? null : BundledWeb.fromFolder(context, folder(launch.bundle), site, launch.bundle);
                if (web == null) launch = new LiveWebRules.Launch(null, false);
            }
            serving = launch.bundle;
            // A copy the app won't serve pages from (the seller chose the live website, or it is too old) proves nothing.
            if (web != null && !web.on()) state.boot = null;
            save();
            cleanUpSoon();
            if (launch.builtIn()) {
                if (builtIn.sha().equals(state.builtInSha)) builtIn.confirmed(state.builtInConfirmedAtMs);
                Log.i(TAG, "start: the app's own web copy " + shortSha(builtIn.sha()));
                return builtIn;
            }
            Log.i(TAG, "start: " + (launch.trial ? "trying" : "using") + " web update " + launch.bundle.version + " (" + launch.bundle.id() + ")");
            return web;
        }
    }

    /** {@code web}'s first page is loading with the app in front: a downloaded bundle must say it is up in time. */
    void watch(BundledWeb web, Fallback fallback) {
        synchronized (lock) {
            if (!web.update() || serving == null || state.boot == null) return;
        }
        this.fallback = fallback;
        arm();
    }

    /** The page said it is up (SXPhone "webReady"). Main thread. */
    void ready() {
        disarm();
        waitingAfterPause = false;
        synchronized (lock) {
            if (serving == null || state.boot == null) return;
            LiveWebRules.ready(state, serving.sha256);
            save();
            cleanUpSoon();
            Log.i(TAG, "web update " + serving.version + " (" + serving.id() + ") is up");
        }
    }

    /** The app left the screen. Main thread. */
    void paused() {
        if (timeout == null) return;
        disarm();
        waitingAfterPause = true;
        synchronized (lock) {
            LiveWebRules.interrupted(state);
            save();
        }
    }

    /** The app is back on screen. Main thread. */
    void resumed() {
        if (waitingAfterPause) {
            waitingAfterPause = false;
            synchronized (lock) {
                LiveWebRules.resumed(state);
                save();
            }
            arm();
        }
        check(false);
    }

    private void arm() {
        disarm();
        timeout = this::timedOut;
        main.postDelayed(timeout, LiveWebRules.READY_TIMEOUT_MS);
    }

    private void disarm() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }

    private void timedOut() {
        timeout = null;
        BundledWeb next;
        synchronized (lock) {
            if (serving == null || state.boot == null) return;
            LiveWebRules.Held failed = serving;
            LiveWebRules.Launch launch = LiveWebRules.failed(state, failed.sha256);
            next = launch.builtIn() ? null : BundledWeb.fromFolder(context, folder(launch.bundle), site, launch.bundle);
            if (next == null) {
                launch = new LiveWebRules.Launch(null, false);
                state.boot = null;
                next = builtIn;
            }
            serving = launch.bundle;
            state.lastResult = "rolled_back";
            save();
            cleanUpSoon();
            Log.w(
                TAG,
                "web update " + failed.version + " (" + failed.id() + ") did not start in " + (LiveWebRules.READY_TIMEOUT_MS / 1000) +
                " s: rolled back to " + (launch.builtIn() ? "the app's own web copy " + shortSha(builtIn.sha()) : "web update " + launch.bundle.version + " (" + launch.bundle.id() + ")")
            );
        }
        Fallback listener = fallback;
        if (listener != null) listener.use(next);
    }

    /** Reads the manifest in the background, on start ({@code start}) or on coming back (at most every 5 minutes). */
    void check(boolean start) {
        if (!enabled() || checking) return;
        long now = System.currentTimeMillis();
        if (!start && now - lastCheckMs < RESUME_CHECK_MS) return;
        checking = true;
        lastCheckMs = now;
        io.execute(() -> {
            try {
                runCheck();
            } finally {
                checking = false;
            }
        });
    }

    private void runCheck() {
        LiveWebRules.Manifest manifest;
        try {
            manifest = LiveWebRules.open(fetchText(manifestUrl), keys, host);
        } catch (IOException error) {
            // Offline, or the site is busy: the app keeps what it has and asks again later.
            result("unreachable", "manifest unreachable (" + error.getClass().getSimpleName() + ")");
            return;
        } catch (LiveWebRules.Refused refused) {
            result("refused_" + refused.reason, "manifest refused: " + refused.reason);
            return;
        }
        LiveWebRules.Decision decision;
        synchronized (lock) {
            decision = LiveWebRules.decide(state, manifest, builtIn.builtIn(), LiveWebRules.NATIVE_CONTRACT, System.currentTimeMillis());
            if (decision == LiveWebRules.Decision.CARRIED && builtIn.sha().equals(state.builtInSha)) builtIn.confirmed(state.builtInConfirmedAtMs);
            save();
        }
        if (decision != LiveWebRules.Decision.DOWNLOAD) {
            result(decision.name().toLowerCase(java.util.Locale.ROOT), "web " + manifest.version + ": " + decision.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        String id = manifest.sha256.substring(0, 16);
        File zip = new File(root, "download.zip");
        File part = new File(bundles, id + ".part");
        try {
            download(resolve(manifestUrl, manifest.bundlePath()), zip, manifest.bytes);
            if (!LiveWebRules.sha256(zip).equals(manifest.sha256)) throw new LiveWebRules.Refused("hash");
            deleteTree(part);
            if (!part.mkdirs()) throw new IOException("mkdirs " + part);
            Set<String> files;
            try (InputStream in = new FileInputStream(zip)) {
                files = LiveWebRules.unpack(in, part);
            }
            String webJson;
            try (InputStream in = new FileInputStream(new File(part, "sx-web.json"))) {
                webJson = LiveWebRules.read(in, 4 * 1024 * 1024);
            }
            LiveWebRules.checkUnpacked(files, webJson, manifest);
            File done = new File(bundles, id);
            deleteTree(done);
            if (!part.renameTo(done)) throw new IOException("rename " + part);
            synchronized (lock) {
                LiveWebRules.downloaded(state, manifest, System.currentTimeMillis());
                save();
            }
            result("downloaded", "web update " + manifest.version + " (" + id + ") downloaded: used from the next start");
        } catch (LiveWebRules.Refused refused) {
            // The same bytes would be refused again: never fetched again.
            synchronized (lock) {
                state.markBad(manifest.sha256);
                save();
            }
            result("refused_" + refused.reason, "web update " + manifest.version + " refused: " + refused.reason);
        } catch (IOException error) {
            result("download_failed", "web update " + manifest.version + " download failed (" + error.getClass().getSimpleName() + "); tries again later");
        } finally {
            if (!zip.delete() && zip.exists()) Log.w(TAG, "could not delete " + zip.getName());
            deleteTree(part);
        }
    }

    private void result(String code, String words) {
        synchronized (lock) {
            state.lastResult = code;
            save();
        }
        Log.i(TAG, words);
    }

    /** What the page and support see (SXPhone "webShell"). */
    JSONObject status() {
        JSONObject status = new JSONObject();
        try {
            status.put("enabled", enabled());
            status.put("nativeContract", LiveWebRules.NATIVE_CONTRACT);
            synchronized (lock) {
                status.put("serving", serving == null ? "app" : serving.version);
                if (state.active != null) status.put("active", summary(state.active));
                if (state.pending != null) status.put("pending", summary(state.pending));
                if (state.previous != null) status.put("previous", summary(state.previous));
                status.put("refused", state.bad.size());
                status.put("checkedAtMs", state.checkedAtMs);
                status.put("lastResult", state.lastResult);
            }
        } catch (JSONException ignored) {}
        return status;
    }

    private static JSONObject summary(LiveWebRules.Held held) throws JSONException {
        return new JSONObject().put("version", held.version).put("id", held.id()).put("sha", held.sha);
    }

    // ---- Storage ----

    private File folder(LiveWebRules.Held held) {
        return new File(bundles, held.id());
    }

    private Set<String> presentIds() {
        Set<String> ids = new HashSet<>();
        File[] list = bundles.listFiles();
        if (list == null) return ids;
        for (File file : list) {
            if (file.isDirectory() && !file.getName().endsWith(".part") && new File(file, "sx-web.json").isFile()) ids.add(file.getName());
        }
        return ids;
    }

    /**
     * Folders no longer needed go: only the bundle in use, the one before it and one waiting stay. On the
     * background thread, after any download already queued there, so a bundle just unpacked is always kept.
     */
    private void cleanUpSoon() {
        io.execute(() -> {
            Set<String> keep;
            synchronized (lock) {
                keep = state.kept();
                if (serving != null) keep.add(serving.id());
            }
            deleteExcept(keep);
        });
    }

    private void deleteExcept(Set<String> keep) {
        File[] list = bundles.listFiles();
        if (list == null) return;
        for (File file : list) {
            if (!keep.contains(file.getName()) && !file.getName().endsWith(".part")) deleteTree(file);
        }
    }

    private String readState() {
        try (InputStream in = new FileInputStream(stateFile)) {
            return LiveWebRules.read(in, 256 * 1024);
        } catch (IOException error) {
            return "";
        }
    }

    /** Written whole to a temporary file, then moved over the old one. */
    private void save() {
        File temp = new File(root, "state.json.tmp");
        try {
            if (!root.isDirectory() && !root.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(state.serialize().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(stateFile)) Log.w(TAG, "could not save state");
        } catch (IOException error) {
            Log.w(TAG, "could not save state: " + error.getClass().getSimpleName());
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) Log.w(TAG, "could not delete " + file.getName());
    }

    // ---- Network ----

    private String userAgent() {
        try {
            return "SalesXrayApp/" + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName + " live-web";
        } catch (Exception error) {
            return "SalesXrayApp live-web";
        }
    }

    private HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", userAgent());
        connection.setRequestProperty("Cache-Control", "no-cache");
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection;
    }

    private String fetchText(String address) throws IOException {
        HttpURLConnection connection = open(address);
        try (InputStream in = connection.getInputStream()) {
            return LiveWebRules.read(in, LiveWebRules.MAX_MANIFEST_BYTES);
        } finally {
            connection.disconnect();
        }
    }

    /** Exactly {@code bytes} bytes, or an error (a short or long answer is retried later). */
    private void download(String address, File target, long bytes) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("mkdirs " + root);
        HttpURLConnection connection = open(address);
        long total = 0;
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                total += count;
                if (total > bytes) throw new IOException("longer than the manifest says");
                out.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        if (total != bytes) throw new IOException("shorter than the manifest says");
    }

    private static String resolve(String manifestUrl, String path) {
        return manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1) + path;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 9 ? String.valueOf(sha) : sha.substring(0, 9);
    }
}
