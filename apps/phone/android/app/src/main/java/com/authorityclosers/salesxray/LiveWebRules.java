package com.authorityclosers.salesxray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Live web updates (owner, 28 Oct 2026: "Why aren't I getting the app update
 * on Play Store automatically ... I need new updated"). After each production
 * release Sales X-Ray publishes its web app's static export as a zip (the same
 * files build-web-shell.sh puts in the app) with a manifest signed by a key
 * only the release pipeline can use. The app checks the signature against keys
 * pinned in the app (assets/live-web.json), downloads the zip, checks its
 * SHA-256, unpacks it to the app's own storage and uses it from the next start.
 * A bundle that doesn't say it is up (the page's webReady) in time is dropped
 * and the app goes back: to the bundle before it, then to its own copy.
 *
 * Only what a web page shows or runs in the WebView may come this way (TYPES):
 * no native code, ever (Play's Device and Network Abuse policy, PLAY-STORE.md).
 *
 * Pure rules (no Android classes), so they are tested on the JVM (LiveWebRulesTest).
 */
final class LiveWebRules {

    /** The manifest's format; the signature covers these words first, so no other signature of ours passes for one. */
    static final String FORMAT = "sx-web-bundle/1";
    private static final byte[] CONTEXT = (FORMAT + "\n").getBytes(StandardCharsets.UTF_8);

    /**
     * What this build's native side offers the web app: SXPhone's methods, SXHaptics, the user agent. A web release
     * that needs more (a new bridge method, plugin or permission) says so in minNative and waits for the store build
     * that raises this number.
     */
    static final int NATIVE_CONTRACT = 1;

    /** How long a downloaded bundle has to say it is up, once its first page loads with the app in front. */
    static final long READY_TIMEOUT_MS = 15_000;
    /** A bundle that once worked is dropped after this many failed starts in a row; a new one after its first. */
    static final int MAX_FAILURES = 2;
    /** Without a manifest that confirms it, a copy older than this gives way to the live site (as BundledWeb). */
    static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000;

    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final long MAX_BUNDLE_BYTES = 64L * 1024 * 1024;
    static final long MAX_UNPACKED_BYTES = 192L * 1024 * 1024;
    static final int MAX_FILES = 20_000;

    /** Pages every bundle must carry (the same check as build-web-shell.sh), and its own file list. */
    static final String[] REQUIRED = { "index.html", "dashboard/index.html", "leads/index.html", "chats/index.html", "sx-web.json" };

    /**
     * The only file types a bundle may carry: pages, scripts, styles, data, pictures, fonts and media for the
     * WebView. Native code (.so, .dex, .jar, .apk, .oat), WebAssembly and anything unknown make the whole bundle
     * refused. The platform's packer (ac_platform/app_web) keeps the same list.
     */
    static final Set<String> TYPES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "html", "js", "mjs", "css", "json", "txt",
                "svg", "png", "jpg", "jpeg", "webp", "avif", "gif", "ico",
                "woff2", "woff", "ttf", "otf",
                "webmanifest", "xml", "csv", "pdf",
                "mp3", "mp4", "webm", "wav", "ogg", "m4a"
            )
        )
    );

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern HEX128 = Pattern.compile("^[0-9a-f]{128}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.+-]{0,39}$");

    private LiveWebRules() {}

    /** A manifest the app refused, and why (logged and shown in webShell status; never shown to sellers). */
    static final class Refused extends Exception {
        final String reason;

        Refused(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** A verified manifest: one bundle for one site. */
    static final class Manifest {
        final String host;
        final String version;
        final String sha;
        final String sha256;
        final long sequence;
        final long bytes;
        final int minNative;
        final long commitAtMs;
        final long builtAtMs;

        Manifest(String host, String version, String sha, String sha256, long sequence, long bytes, int minNative, long commitAtMs, long builtAtMs) {
            this.host = host;
            this.version = version;
            this.sha = sha;
            this.sha256 = sha256;
            this.sequence = sequence;
            this.bytes = bytes;
            this.minNative = minNative;
            this.commitAtMs = commitAtMs;
            this.builtAtMs = builtAtMs;
        }

        /** Where the zip is, beside the manifest: content-addressed, so a name always means the same bytes. */
        String bundlePath() {
            return "bundles/" + sha256 + ".zip";
        }
    }

    /**
     * Opens a signed manifest: {"format", "key", "payload", "signature"}, where payload is the manifest's own JSON
     * text and signature (hex) is Ed25519 over FORMAT + "\n" + payload, by one of {@code keys} (hex public keys).
     */
    static Manifest open(String envelopeText, List<String> keys, String host) throws Refused {
        if (keys == null || keys.isEmpty()) throw new Refused("no_key");
        if (envelopeText == null || envelopeText.length() > MAX_MANIFEST_BYTES) throw new Refused("format");
        JSONObject envelope;
        try {
            envelope = new JSONObject(envelopeText);
        } catch (JSONException error) {
            throw new Refused("format");
        }
        if (!FORMAT.equals(envelope.optString("format"))) throw new Refused("format");
        String key = envelope.optString("key").toLowerCase(Locale.ROOT);
        String signature = envelope.optString("signature").toLowerCase(Locale.ROOT);
        String payload = envelope.optString("payload");
        if (!HEX64.matcher(key).matches() || !HEX128.matcher(signature).matches() || payload.isEmpty()) throw new Refused("format");
        boolean pinned = false;
        for (String candidate : keys) pinned |= key.equals(candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT));
        if (!pinned) throw new Refused("unknown_key");
        byte[] text = payload.getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[CONTEXT.length + text.length];
        System.arraycopy(CONTEXT, 0, signed, 0, CONTEXT.length);
        System.arraycopy(text, 0, signed, CONTEXT.length, text.length);
        if (!Ed25519.verify(hex(key), signed, hex(signature))) throw new Refused("signature");
        // Only now, signed by us, is the payload read.
        JSONObject fields;
        try {
            fields = new JSONObject(payload);
        } catch (JSONException error) {
            throw new Refused("fields");
        }
        if (!host.equals(fields.optString("host"))) throw new Refused("host");
        String sha256 = fields.optString("sha256");
        String sha = fields.optString("sha");
        String version = fields.optString("version");
        long sequence = fields.optLong("sequence");
        long bytes = fields.optLong("bytes");
        int minNative = fields.optInt("minNative");
        long commitAtMs = fields.optLong("commitAtMs");
        long builtAtMs = fields.optLong("builtAtMs");
        if (
            !HEX64.matcher(sha256).matches() ||
            !HEX40.matcher(sha).matches() ||
            !VERSION.matcher(version).matches() ||
            sequence <= 0 ||
            bytes <= 0 ||
            bytes > MAX_BUNDLE_BYTES ||
            minNative < 1 ||
            commitAtMs <= 0 ||
            builtAtMs <= 0 ||
            !("bundles/" + sha256 + ".zip").equals(fields.optString("bundle"))
        ) {
            throw new Refused("fields");
        }
        return new Manifest(host, version, sha, sha256, sequence, bytes, minNative, commitAtMs, builtAtMs);
    }

    // ---- What the app keeps: the bundle in use, the one before it, and one waiting for the next start. ----

    /** A downloaded, unpacked bundle. */
    static final class Held {
        final String sha256;
        final String version;
        final String sha;
        final long sequence;
        final long commitAtMs;
        final long builtAtMs;
        /** The last time a manifest said this is still the current release (keeps it fresh past MAX_AGE_MS). */
        long confirmedAtMs;
        /** Failed starts in a row. */
        int failures;

        Held(String sha256, String version, String sha, long sequence, long commitAtMs, long builtAtMs, long confirmedAtMs, int failures) {
            this.sha256 = sha256;
            this.version = version;
            this.sha = sha;
            this.sequence = sequence;
            this.commitAtMs = commitAtMs;
            this.builtAtMs = builtAtMs;
            this.confirmedAtMs = confirmedAtMs;
            this.failures = failures;
        }

        static Held of(Manifest manifest, long now) {
            return new Held(manifest.sha256, manifest.version, manifest.sha, manifest.sequence, manifest.commitAtMs, manifest.builtAtMs, now, 0);
        }

        /** Short id: the folder name and what logs say. */
        String id() {
            return sha256.substring(0, 16);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put("sha256", sha256)
                .put("version", version)
                .put("sha", sha)
                .put("sequence", sequence)
                .put("commitAtMs", commitAtMs)
                .put("builtAtMs", builtAtMs)
                .put("confirmedAtMs", confirmedAtMs)
                .put("failures", failures);
        }

        static Held fromJson(JSONObject json) {
            if (json == null || !HEX64.matcher(json.optString("sha256")).matches()) return null;
            return new Held(
                json.optString("sha256"),
                json.optString("version"),
                json.optString("sha"),
                json.optLong("sequence"),
                json.optLong("commitAtMs"),
                json.optLong("builtAtMs"),
                json.optLong("confirmedAtMs"),
                json.optInt("failures")
            );
        }
    }

    /** The web app the app carries itself (assets/web, sx-web.json). */
    static final class BuiltIn {
        final String sha;
        final long commitAtMs;
        final long builtAtMs;

        BuiltIn(String sha, long commitAtMs, long builtAtMs) {
            this.sha = sha == null ? "" : sha;
            this.commitAtMs = commitAtMs;
            this.builtAtMs = builtAtMs;
        }

        /** Which code is newer: the commit's time where the copy says it (from 1.0.31), else when it was built. */
        long order() {
            return commitAtMs > 0 ? commitAtMs : builtAtMs;
        }
    }

    /** Everything the app remembers about live updates (filesDir/live-web/state.json). */
    static final class State {
        Held active;
        Held previous;
        Held pending;
        /** SHA-256s of bundles that failed to start: never downloaded or used again. */
        final List<String> bad = new ArrayList<>();
        /** The highest manifest sequence acted on: an older manifest played back is refused. */
        long sequence;
        /** The downloaded bundle the current start is waiting on, and whether the app left the screen before it said ready. */
        String boot;
        boolean bootInterrupted;
        /** The built-in copy, confirmed current by a manifest (same commit), and when. */
        String builtInSha = "";
        long builtInConfirmedAtMs;
        long checkedAtMs;
        String lastResult = "";

        static State parse(String text) {
            State state = new State();
            if (text == null || text.isEmpty()) return state;
            try {
                JSONObject json = new JSONObject(text);
                state.active = Held.fromJson(json.optJSONObject("active"));
                state.previous = Held.fromJson(json.optJSONObject("previous"));
                state.pending = Held.fromJson(json.optJSONObject("pending"));
                JSONArray bad = json.optJSONArray("bad");
                for (int i = 0; bad != null && i < bad.length(); i++) {
                    String sha256 = bad.optString(i);
                    if (HEX64.matcher(sha256).matches()) state.bad.add(sha256);
                }
                state.sequence = json.optLong("sequence");
                state.boot = json.optString("boot", null);
                if (state.boot != null && state.boot.isEmpty()) state.boot = null;
                state.bootInterrupted = json.optBoolean("bootInterrupted");
                state.builtInSha = json.optString("builtInSha");
                state.builtInConfirmedAtMs = json.optLong("builtInConfirmedAtMs");
                state.checkedAtMs = json.optLong("checkedAtMs");
                state.lastResult = json.optString("lastResult");
            } catch (JSONException error) {
                // A damaged file: start again from the app's own copy.
                return new State();
            }
            return state;
        }

        String serialize() {
            try {
                JSONObject json = new JSONObject();
                if (active != null) json.put("active", active.toJson());
                if (previous != null) json.put("previous", previous.toJson());
                if (pending != null) json.put("pending", pending.toJson());
                json.put("bad", new JSONArray(bad));
                json.put("sequence", sequence);
                if (boot != null) json.put("boot", boot);
                json.put("bootInterrupted", bootInterrupted);
                json.put("builtInSha", builtInSha);
                json.put("builtInConfirmedAtMs", builtInConfirmedAtMs);
                json.put("checkedAtMs", checkedAtMs);
                json.put("lastResult", lastResult);
                return json.toString();
            } catch (JSONException error) {
                return "{}";
            }
        }

        boolean isBad(String sha256) {
            return bad.contains(sha256);
        }

        void markBad(String sha256) {
            if (!bad.contains(sha256)) bad.add(sha256);
            while (bad.size() > 20) bad.remove(0);
        }

        /** The bundles whose folders stay on the phone. */
        Set<String> kept() {
            Set<String> ids = new HashSet<>();
            for (Held held : new Held[] { active, previous, pending }) if (held != null) ids.add(held.id());
            return ids;
        }
    }

    /** What a launch uses: a downloaded bundle (and whether it is on trial), or the app's own copy. */
    static final class Launch {
        final Held bundle;
        final boolean trial;

        Launch(Held bundle, boolean trial) {
            this.bundle = bundle;
            this.trial = trial;
        }

        boolean builtIn() {
            return bundle == null;
        }
    }

    /**
     * Which web app this start uses. Never touches the network, so the app starts the same offline.
     * {@code present} says whether a bundle's folder is still whole on the phone.
     */
    static Launch launch(State state, BuiltIn builtIn, Set<String> present, long now) {
        // The last start never said ready and never left the screen: it crashed or hung there.
        if (state.boot != null) {
            String failed = state.boot;
            boolean interrupted = state.bootInterrupted;
            state.boot = null;
            state.bootInterrupted = false;
            if (!interrupted) fail(state, failed);
        }
        // A Play or download-link update that carries newer code than what was downloaded wins.
        state.pending = usable(state, state.pending, builtIn, present);
        state.active = usable(state, state.active, builtIn, present);
        state.previous = usable(state, state.previous, builtIn, present);
        if (state.active == null && state.previous != null) {
            state.active = state.previous;
            state.previous = null;
        }
        Launch launch;
        if (state.pending != null) launch = new Launch(state.pending, true);
        else if (state.active != null) launch = new Launch(state.active, false);
        else launch = new Launch(null, false);
        if (!launch.builtIn()) {
            state.boot = launch.bundle.sha256;
            state.bootInterrupted = false;
        }
        return launch;
    }

    private static Held usable(State state, Held held, BuiltIn builtIn, Set<String> present) {
        if (held == null) return null;
        if (state.isBad(held.sha256) || !present.contains(held.id())) return null;
        if (held.sha.equals(builtIn.sha) || held.commitAtMs <= builtIn.order()) return null;
        return held;
    }

    /** The page said it is up: a bundle on trial becomes the one in use, and the one before it is kept to go back to. */
    static void ready(State state, String sha256) {
        if (sha256 == null || !sha256.equals(state.boot)) return;
        state.boot = null;
        state.bootInterrupted = false;
        if (state.pending != null && state.pending.sha256.equals(sha256)) {
            if (state.active != null) state.previous = state.active;
            state.active = state.pending;
            state.pending = null;
        }
        if (state.active != null && state.active.sha256.equals(sha256)) state.active.failures = 0;
    }

    /** The app left the screen before the page said ready: that start proves nothing either way. */
    static void interrupted(State state) {
        if (state.boot != null) state.bootInterrupted = true;
    }

    /** Back on screen while still waiting: the wait starts again. */
    static void resumed(State state) {
        state.bootInterrupted = false;
    }

    /**
     * The bundle {@code sha256} failed to start (no ready signal in time with the app in front, or the app died
     * waiting). Returns what to use now: for a new bundle, the one in use before it; else the app's own copy.
     */
    static Launch failed(State state, String sha256) {
        boolean trial = sha256 != null && state.pending != null && state.pending.sha256.equals(sha256);
        if (sha256 != null && sha256.equals(state.boot)) {
            state.boot = null;
            state.bootInterrupted = false;
        }
        fail(state, sha256);
        // A new bundle goes back to the one that worked; a bundle that worked goes back to the app's own copy.
        if (trial && state.active != null) {
            state.boot = state.active.sha256;
            return new Launch(state.active, false);
        }
        return new Launch(null, false);
    }

    private static void fail(State state, String sha256) {
        if (sha256 == null) return;
        if (state.pending != null && state.pending.sha256.equals(sha256)) {
            // Never worked: dropped at once.
            state.markBad(sha256);
            state.pending = null;
            return;
        }
        if (state.active != null && state.active.sha256.equals(sha256)) {
            state.active.failures++;
            if (state.active.failures >= MAX_FAILURES) {
                state.markBad(sha256);
                state.active = state.previous;
                state.previous = null;
            }
        }
    }

    /** What a check makes of a verified manifest. */
    enum Decision {
        /** Download it: it is used from the next start. */
        DOWNLOAD,
        /** The bundle in use is the current release. */
        CURRENT,
        /** Already downloaded, waiting for the next start. */
        WAITING,
        /** The app's own copy is this release (or newer). */
        CARRIED,
        /** It needs a newer app (minNative): it waits for the store build. */
        NEEDS_NATIVE,
        /** It failed to start on this phone before. */
        REFUSED_BEFORE,
        /** An older manifest than one already acted on (played back). */
        OLDER,
    }

    static Decision decide(State state, Manifest manifest, BuiltIn builtIn, int nativeContract, long now) {
        state.checkedAtMs = now;
        if (manifest.minNative > nativeContract) return Decision.NEEDS_NATIVE;
        if (state.isBad(manifest.sha256)) return Decision.REFUSED_BEFORE;
        if (state.active != null && state.active.sha256.equals(manifest.sha256)) {
            state.active.confirmedAtMs = now;
            state.sequence = Math.max(state.sequence, manifest.sequence);
            return Decision.CURRENT;
        }
        if (state.pending != null && state.pending.sha256.equals(manifest.sha256)) return Decision.WAITING;
        if (manifest.sequence < state.sequence) return Decision.OLDER;
        if (manifest.sha.equals(builtIn.sha) || manifest.commitAtMs <= builtIn.order()) {
            // The release on the server is the app's own copy (or older): use the copy, drop what was downloaded.
            state.sequence = Math.max(state.sequence, manifest.sequence);
            if (manifest.sha.equals(builtIn.sha)) {
                state.builtInSha = builtIn.sha;
                state.builtInConfirmedAtMs = now;
            }
            state.pending = null;
            if (state.active != null) {
                state.previous = null;
                state.active = null;
            }
            return Decision.CARRIED;
        }
        return Decision.DOWNLOAD;
    }

    /** A downloaded bundle, hash-checked and unpacked: used from the next start. */
    static void downloaded(State state, Manifest manifest, long now) {
        state.pending = Held.of(manifest, now);
        state.sequence = Math.max(state.sequence, manifest.sequence);
    }

    /** May a copy built at {@code builtAtMs}, last confirmed current at {@code confirmedAtMs}, still be used? */
    static boolean fresh(long builtAtMs, long confirmedAtMs, long now) {
        long since = Math.max(builtAtMs, confirmedAtMs);
        return since > 0 && now - since <= MAX_AGE_MS && now >= builtAtMs - 86_400_000L;
    }

    // ---- The zip: hash-checked, then unpacked with nothing outside its folder and nothing but web files. ----

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) digest.update(buffer, 0, count);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    /** The entry's path inside the bundle, or null when it may not be unpacked (outside the folder, odd names, or not a web file). */
    static String safeEntry(String name) {
        if (name == null || name.isEmpty() || name.length() > 512) return null;
        if (name.startsWith("/") || name.contains("\\") || name.contains(":")) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
        }
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        }
        return allowedType(name) ? name : null;
    }

    static boolean allowedType(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = file.lastIndexOf('.');
        return dot > 0 && TYPES.contains(file.substring(dot + 1));
    }

    /**
     * Unpacks {@code zip} into the empty folder {@code dir}. Any entry that isn't a web file inside the folder,
     * a folder entry aside, refuses the whole bundle. Returns the files unpacked.
     */
    static Set<String> unpack(InputStream zip, File dir) throws Refused, IOException {
        Set<String> files = new HashSet<>();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (safeDirectory(name)) continue;
                    throw new Refused("zip_entry");
                }
                String path = safeEntry(name);
                if (path == null) throw new Refused(allowedTypeName(name) ? "zip_entry" : "file_type");
                if (!files.add(path) || files.size() > MAX_FILES) throw new Refused("zip_entry");
                File target = new File(dir, path);
                if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new Refused("zip_entry");
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdirs " + parent);
                try (OutputStream out = new FileOutputStream(target)) {
                    int count;
                    while ((count = in.read(buffer)) > 0) {
                        total += count;
                        if (total > MAX_UNPACKED_BYTES) throw new Refused("too_big");
                        out.write(buffer, 0, count);
                    }
                }
            }
        }
        return files;
    }

    private static boolean safeDirectory(String name) {
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.contains("\\")) return false;
        for (String part : trimmed.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private static boolean allowedTypeName(String name) {
        return name != null && allowedType(name);
    }

    /**
     * An unpacked bundle is whole: its sx-web.json is for this site and commit, lists exactly the files unpacked,
     * and the pages every bundle needs are there.
     */
    static void checkUnpacked(Set<String> files, String webJson, Manifest manifest) throws Refused {
        for (String page : REQUIRED) if (!files.contains(page)) throw new Refused("missing_" + page);
        JSONObject json;
        try {
            json = new JSONObject(webJson);
        } catch (JSONException error) {
            throw new Refused("web_json");
        }
        if (!manifest.host.equals(json.optString("host")) || !manifest.sha.equals(json.optString("sha"))) throw new Refused("web_json");
        JSONArray list = json.optJSONArray("files");
        Set<String> listed = new HashSet<>();
        for (int i = 0; list != null && i < list.length(); i++) listed.add(list.optString(i));
        Set<String> unpacked = new HashSet<>(files);
        unpacked.remove("sx-web.json");
        if (!listed.equals(unpacked)) throw new Refused("web_json");
    }

    static String read(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) > 0) {
            out.write(buffer, 0, count);
            if (out.size() > limit) throw new IOException("too_big");
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
