package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Live web updates (owner, 28 Oct 2026: web releases reach the phone app by themselves): the signed manifest,
 * the bundle's hash and contents, which app version may use it, and going back when a bundle doesn't start.
 */
public class LiveWebRulesTest {

    private static final String HOST = "salesxray.authorityclosers.com";
    private static final long NOW = 1_761_650_000_000L;
    private static final long DAY = 86_400_000L;
    private static final String SHA_A = fill('a', 40);
    private static final String SHA_B = fill('b', 40);
    private static final String SHA_BUILT_IN = fill('c', 40);
    private static final String SUM_A = fill('1', 64);
    private static final String SUM_B = fill('2', 64);
    private static final String SUM_P = fill('3', 64);

    private static KeyPair releaseKey;
    private static KeyPair otherKey;
    private static List<String> pinned;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @BeforeClass
    public static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        releaseKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
        pinned = Collections.singletonList(publicHex(releaseKey));
    }

    // ---- The signed manifest ----

    @Test
    public void aManifestSignedByThePinnedKeyOpens() throws Exception {
        LiveWebRules.Manifest manifest = LiveWebRules.open(envelope(releaseKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY)), pinned, HOST);
        assertEquals(SUM_P, manifest.sha256);
        assertEquals(SHA_A, manifest.sha);
        assertEquals("1.3.0", manifest.version);
        assertEquals(7, manifest.sequence);
        assertEquals(1, manifest.minNative);
        assertEquals("bundles/" + SUM_P + ".zip", manifest.bundlePath());
        // Keys may be written in capitals and with spaces around them.
        LiveWebRules.open(envelope(releaseKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY)), Collections.singletonList(" " + publicHex(releaseKey).toUpperCase() + " "), HOST);
    }

    @Test
    public void withNoPinnedKeyLiveUpdatesAreOff() throws Exception {
        String text = envelope(releaseKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY));
        assertRefused("no_key", text, Collections.<String>emptyList(), HOST);
        assertRefused("no_key", text, null, HOST);
    }

    @Test
    public void aManifestSignedByAnyOtherKeyIsRefused() throws Exception {
        assertRefused("unknown_key", envelope(otherKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY)), pinned, HOST);
        // Claiming the pinned key doesn't help: the signature is checked against it.
        JSONObject forged = new JSONObject(envelope(otherKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY)));
        forged.put("key", publicHex(releaseKey));
        assertRefused("signature", forged.toString(), pinned, HOST);
    }

    @Test
    public void anyChangeToASignedManifestIsRefused() throws Exception {
        JSONObject envelope = new JSONObject(envelope(releaseKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY)));
        String payload = envelope.getString("payload");
        // Another bundle's hash, a lower native version, or one changed character anywhere.
        for (String changed : new String[] {
            payload.replace(SUM_P, SUM_B).replace("bundles/" + SUM_P, "bundles/" + SUM_B),
            payload.replace("\"minNative\":1", "\"minNative\":0"),
            payload.replace("1.3.0", "1.3.1"),
            payload + " ",
        }) {
            assertFalse(changed.equals(payload));
            envelope.put("payload", changed);
            assertRefused("signature", envelope.toString(), pinned, HOST);
        }
        // A signature over the same words without the format line (another use of the key) doesn't pass either.
        envelope.put("payload", payload);
        envelope.put("signature", LiveWebRules.hex(sign(releaseKey, payload.getBytes(StandardCharsets.UTF_8))));
        assertRefused("signature", envelope.toString(), pinned, HOST);
    }

    @Test
    public void aManifestForAnotherSiteIsRefused() throws Exception {
        String other = payload(SUM_P, SHA_A, 7, 1, NOW - DAY).replace(HOST, "salesxray-dev.authorityclosers.com");
        assertRefused("host", envelope(releaseKey, other), pinned, HOST);
    }

    @Test
    public void malformedManifestsAreRefused() throws Exception {
        assertRefused("format", "not json", pinned, HOST);
        assertRefused("format", "{}", pinned, HOST);
        JSONObject wrongFormat = new JSONObject(envelope(releaseKey, payload(SUM_P, SHA_A, 7, 1, NOW - DAY)));
        wrongFormat.put("format", "sx-web-bundle/2");
        assertRefused("format", wrongFormat.toString(), pinned, HOST);
        JSONObject fields = new JSONObject(payload(SUM_P, SHA_A, 7, 1, NOW - DAY));
        // The zip's name must be its own hash (content-addressed), the native version at least 1, the size bounded.
        assertRefused("fields", envelope(releaseKey, copy(fields).put("bundle", "bundles/latest.zip").toString()), pinned, HOST);
        assertRefused("fields", envelope(releaseKey, copy(fields).put("minNative", 0).toString()), pinned, HOST);
        assertRefused("fields", envelope(releaseKey, copy(fields).put("bytes", LiveWebRules.MAX_BUNDLE_BYTES + 1).toString()), pinned, HOST);
        assertRefused("fields", envelope(releaseKey, copy(fields).put("sha256", "XYZ").toString()), pinned, HOST);
        assertRefused("fields", envelope(releaseKey, copy(fields).put("sequence", 0).toString()), pinned, HOST);
    }

    // ---- What a check does with a verified manifest ----

    @Test
    public void aBundleThatNeedsANewerAppWaitsForTheStoreBuild() throws Exception {
        LiveWebRules.State state = new LiveWebRules.State();
        LiveWebRules.Manifest needsMore = manifest(SUM_P, SHA_A, 7, LiveWebRules.NATIVE_CONTRACT + 1, NOW - DAY);
        assertEquals(LiveWebRules.Decision.NEEDS_NATIVE, LiveWebRules.decide(state, needsMore, builtIn(), LiveWebRules.NATIVE_CONTRACT, NOW));
        assertNull(state.pending);
        LiveWebRules.Manifest fits = manifest(SUM_P, SHA_A, 7, LiveWebRules.NATIVE_CONTRACT, NOW - DAY);
        assertEquals(LiveWebRules.Decision.DOWNLOAD, LiveWebRules.decide(state, fits, builtIn(), LiveWebRules.NATIVE_CONTRACT, NOW));
        // The store build that raises the contract takes it.
        assertEquals(LiveWebRules.Decision.DOWNLOAD, LiveWebRules.decide(state, needsMore, builtIn(), LiveWebRules.NATIVE_CONTRACT + 1, NOW));
    }

    @Test
    public void anOlderManifestPlayedBackIsRefused() throws Exception {
        LiveWebRules.State state = new LiveWebRules.State();
        LiveWebRules.downloaded(state, manifest(SUM_B, SHA_B, 9, 1, NOW - DAY), NOW);
        assertEquals(LiveWebRules.Decision.OLDER, LiveWebRules.decide(state, manifest(SUM_A, SHA_A, 8, 1, NOW - 2 * DAY), builtIn(), 1, NOW));
        assertEquals(LiveWebRules.Decision.WAITING, LiveWebRules.decide(state, manifest(SUM_B, SHA_B, 9, 1, NOW - DAY), builtIn(), 1, NOW));
        // Going back on purpose is a new signature with a higher sequence.
        assertEquals(LiveWebRules.Decision.DOWNLOAD, LiveWebRules.decide(state, manifest(SUM_A, SHA_A, 10, 1, NOW - 2 * DAY), builtIn(), 1, NOW));
    }

    @Test
    public void theAppsOwnCopyOfTheSameReleaseIsKept() throws Exception {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        LiveWebRules.Manifest same = manifest(SUM_B, SHA_BUILT_IN, 4, 1, NOW - 3 * DAY);
        assertEquals(LiveWebRules.Decision.CARRIED, LiveWebRules.decide(state, same, builtIn(), 1, NOW));
        assertNull(state.active);
        assertEquals(SHA_BUILT_IN, state.builtInSha);
        assertEquals(NOW, state.builtInConfirmedAtMs);
        // Or an older release than the copy: nothing to download.
        LiveWebRules.State fresh = new LiveWebRules.State();
        assertEquals(LiveWebRules.Decision.CARRIED, LiveWebRules.decide(fresh, manifest(SUM_B, SHA_B, 5, 1, NOW - 30 * DAY), builtIn(), 1, NOW));
    }

    @Test
    public void theCurrentReleaseIsConfirmedAndARefusedOneNeverComesBack() throws Exception {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.active.confirmedAtMs = NOW - 20 * DAY;
        assertEquals(LiveWebRules.Decision.CURRENT, LiveWebRules.decide(state, manifest(SUM_A, SHA_A, 3, 1, NOW - 5 * DAY), builtIn(), 1, NOW));
        assertEquals(NOW, state.active.confirmedAtMs);
        state.markBad(SUM_P);
        assertEquals(LiveWebRules.Decision.REFUSED_BEFORE, LiveWebRules.decide(state, manifest(SUM_P, SHA_B, 4, 1, NOW - DAY), builtIn(), 1, NOW));
    }

    // ---- The zip ----

    @Test
    public void theBundlesHashIsItsSha256() throws Exception {
        File file = temp.newFile("abc.zip");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LiveWebRules.sha256(file));
    }

    @Test
    public void aWholeBundleUnpacksIntoItsFolder() throws Exception {
        File dir = temp.newFolder("good");
        Set<String> files = LiveWebRules.unpack(new ByteArrayInputStream(zip(goodFiles(), "dashboard/")), dir);
        assertTrue(files.contains("dashboard/index.html"));
        assertTrue(new File(dir, "_next/static/chunks/app-1.js").isFile());
        LiveWebRules.checkUnpacked(files, webJson(HOST, SHA_A, goodFiles()), manifest(SUM_A, SHA_A, 1, 1, NOW));
    }

    @Test
    public void nothingIsUnpackedOutsideTheFolderAndNoNativeCodeAtAll() throws Exception {
        assertZipRefused("zip_entry", "../escape.js");
        assertZipRefused("zip_entry", "/etc/escape.html");
        assertZipRefused("zip_entry", "a/../../escape.js");
        assertZipRefused("zip_entry", "a\\escape.js");
        assertZipRefused("zip_entry", "C:/escape.js");
        assertZipRefused("zip_entry", "a//b.js");
        for (String code : new String[] { "lib/arm64-v8a/libsx.so", "classes.dex", "plugin.jar", "app.apk", "boot.oat", "fast.wasm", "run.sh", "README", ".htaccess" }) {
            assertZipRefused("file_type", code);
        }
    }

    @Test
    public void anUnpackedBundleMustBeWholeAndForThisSiteAndRelease() throws Exception {
        List<String> files = goodFiles();
        Set<String> unpacked = new HashSet<>(files);
        unpacked.add("sx-web.json");
        LiveWebRules.Manifest manifest = manifest(SUM_A, SHA_A, 1, 1, NOW);
        LiveWebRules.checkUnpacked(unpacked, webJson(HOST, SHA_A, files), manifest);
        assertUnpackedRefused(unpacked, webJson("salesxray-dev.authorityclosers.com", SHA_A, files), manifest);
        assertUnpackedRefused(unpacked, webJson(HOST, SHA_B, files), manifest);
        List<String> extra = new java.util.ArrayList<>(files);
        extra.add("unlisted.js");
        assertUnpackedRefused(unpacked, webJson(HOST, SHA_A, extra), manifest);
        Set<String> missingPage = new HashSet<>(unpacked);
        missingPage.remove("leads/index.html");
        assertUnpackedRefused(missingPage, webJson(HOST, SHA_A, files), manifest);
    }

    // ---- Starts, the ready signal, and going back ----

    @Test
    public void aNewBundleIsTriedAtTheNextStartAndKeptOnceItSaysReady() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.pending = held(SUM_P, SHA_B, 4, NOW - DAY);
        LiveWebRules.Launch launch = LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_P), NOW);
        assertTrue(launch.trial);
        assertEquals(SUM_P, launch.bundle.sha256);
        assertEquals(SUM_P, state.boot);
        LiveWebRules.ready(state, SUM_P);
        assertNull(state.boot);
        assertNull(state.pending);
        assertEquals(SUM_P, state.active.sha256);
        assertEquals(SUM_A, state.previous.sha256);
        // The next start uses it without a trial.
        LiveWebRules.Launch next = LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_P), NOW);
        assertFalse(next.trial);
        assertEquals(SUM_P, next.bundle.sha256);
    }

    @Test
    public void aNewBundleThatDoesntSayReadyGoesBackToTheOneBeforeAndNeverReturns() throws Exception {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.pending = held(SUM_P, SHA_B, 4, NOW - DAY);
        LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_P), NOW);
        LiveWebRules.Launch back = LiveWebRules.failed(state, SUM_P);
        assertEquals(SUM_A, back.bundle.sha256);
        assertEquals(SUM_A, state.boot);
        assertNull(state.pending);
        assertTrue(state.isBad(SUM_P));
        assertEquals(LiveWebRules.Decision.REFUSED_BEFORE, LiveWebRules.decide(state, manifest(SUM_P, SHA_B, 4, 1, NOW - DAY), builtIn(), 1, NOW));
    }

    @Test
    public void withNothingBeforeItABrokenBundleGoesBackToTheAppsOwnCopy() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.pending = held(SUM_P, SHA_B, 4, NOW - DAY);
        LiveWebRules.launch(state, builtIn(), present(SUM_P), NOW);
        assertTrue(LiveWebRules.failed(state, SUM_P).builtIn());
        assertNull(state.boot);
        assertTrue(LiveWebRules.launch(state, builtIn(), present(SUM_P), NOW).builtIn());
    }

    @Test
    public void aBundleThatWorkedIsDroppedAfterTwoFailedStartsForTheOneBeforeIt() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.previous = held(SUM_B, SHA_B, 2, NOW - 9 * DAY);
        LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_B), NOW);
        // First failure: the app's own copy for now, the bundle kept for another try.
        assertTrue(LiveWebRules.failed(state, SUM_A).builtIn());
        assertEquals(SUM_A, state.active.sha256);
        assertEquals(1, state.active.failures);
        LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_B), NOW);
        LiveWebRules.failed(state, SUM_A);
        assertTrue(state.isBad(SUM_A));
        assertEquals(SUM_B, state.active.sha256);
        assertNull(state.previous);
        // And the one before it, failing twice too, leaves the app's own copy.
        LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_B), NOW);
        LiveWebRules.failed(state, SUM_B);
        LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_B), NOW);
        LiveWebRules.failed(state, SUM_B);
        assertNull(state.active);
        assertTrue(LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_B), NOW).builtIn());
    }

    @Test
    public void aReadySignalResetsTheFailures() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        LiveWebRules.launch(state, builtIn(), present(SUM_A), NOW);
        LiveWebRules.failed(state, SUM_A);
        LiveWebRules.launch(state, builtIn(), present(SUM_A), NOW);
        LiveWebRules.ready(state, SUM_A);
        assertEquals(0, state.active.failures);
        // A ready signal for a bundle this start isn't waiting on changes nothing.
        LiveWebRules.ready(state, SUM_B);
        assertEquals(SUM_A, state.active.sha256);
    }

    @Test
    public void anAppThatDiedBeforeReadyCountsAsAFailedStart() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.pending = held(SUM_P, SHA_B, 4, NOW - DAY);
        LiveWebRules.launch(state, builtIn(), present(SUM_A, SUM_P), NOW);
        // The process ended on screen with no ready signal: the saved state is all that's left.
        LiveWebRules.State after = LiveWebRules.State.parse(state.serialize());
        LiveWebRules.Launch next = LiveWebRules.launch(after, builtIn(), present(SUM_A, SUM_P), NOW);
        assertFalse(next.trial);
        assertEquals(SUM_A, next.bundle.sha256);
        assertTrue(after.isBad(SUM_P));
    }

    @Test
    public void leavingTheAppBeforeReadyProvesNothing() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.pending = held(SUM_P, SHA_B, 4, NOW - DAY);
        LiveWebRules.launch(state, builtIn(), present(SUM_P), NOW);
        LiveWebRules.interrupted(state);
        LiveWebRules.State after = LiveWebRules.State.parse(state.serialize());
        LiveWebRules.Launch next = LiveWebRules.launch(after, builtIn(), present(SUM_P), NOW);
        assertTrue(next.trial);
        assertFalse(after.isBad(SUM_P));
        // Back on screen in the same run, the wait counts again.
        LiveWebRules.interrupted(after);
        LiveWebRules.resumed(after);
        assertFalse(after.bootInterrupted);
    }

    @Test
    public void aNewerStoreBuildWinsOverAnOlderDownload() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        LiveWebRules.BuiltIn newer = new LiveWebRules.BuiltIn(SHA_BUILT_IN, NOW - DAY, NOW - DAY);
        assertTrue(LiveWebRules.launch(state, newer, present(SUM_A), NOW).builtIn());
        assertNull(state.active);
        // Copies from before commit times were recorded compare by build time.
        LiveWebRules.State old = new LiveWebRules.State();
        old.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        assertTrue(LiveWebRules.launch(old, new LiveWebRules.BuiltIn(SHA_BUILT_IN, 0, NOW - DAY), present(SUM_A), NOW).builtIn());
    }

    @Test
    public void aFolderThatIsGoneFallsBack() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.previous = held(SUM_B, SHA_B, 2, NOW - 9 * DAY);
        LiveWebRules.Launch launch = LiveWebRules.launch(state, builtIn(), present(SUM_B), NOW);
        assertEquals(SUM_B, launch.bundle.sha256);
        assertTrue(LiveWebRules.launch(new LiveWebRules.State(), builtIn(), present(), NOW).builtIn());
    }

    @Test
    public void offlineTheAppStartsFromWhatIsOnThePhone() {
        // Saved state and folders are all a start reads: no manifest, no network.
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = new LiveWebRules.Held(SUM_A, "1.3.0", SHA_A, 3, NOW - 20 * DAY, NOW - 20 * DAY, NOW - 2 * DAY, 0);
        state.checkedAtMs = NOW - 2 * DAY;
        state.lastResult = "unreachable";
        LiveWebRules.State saved = LiveWebRules.State.parse(state.serialize());
        LiveWebRules.BuiltIn older = new LiveWebRules.BuiltIn(SHA_BUILT_IN, NOW - 30 * DAY, NOW - 30 * DAY);
        LiveWebRules.Launch launch = LiveWebRules.launch(saved, older, present(SUM_A), NOW);
        assertEquals(SUM_A, launch.bundle.sha256);
        // Built 20 days ago but confirmed current 2 days ago: still used offline.
        assertTrue(LiveWebRules.fresh(launch.bundle.builtAtMs, launch.bundle.confirmedAtMs, NOW));
        // Never confirmed for over two weeks: the live site again, as for the app's own copy.
        assertFalse(LiveWebRules.fresh(NOW - 20 * DAY, 0, NOW));
        assertTrue(LiveWebRules.fresh(NOW - 3 * DAY, 0, NOW));
    }

    @Test
    public void savedStateSurvivesARestartAndADamagedFileStartsAgain() {
        LiveWebRules.State state = new LiveWebRules.State();
        state.active = held(SUM_A, SHA_A, 3, NOW - 5 * DAY);
        state.previous = held(SUM_B, SHA_B, 2, NOW - 9 * DAY);
        state.markBad(SUM_P);
        state.sequence = 3;
        LiveWebRules.State back = LiveWebRules.State.parse(state.serialize());
        assertEquals(SUM_A, back.active.sha256);
        assertEquals(SUM_B, back.previous.sha256);
        assertTrue(back.isBad(SUM_P));
        assertEquals(3, back.sequence);
        LiveWebRules.State damaged = LiveWebRules.State.parse("{\"active\":");
        assertNull(damaged.active);
        assertTrue(LiveWebRules.launch(damaged, builtIn(), present(SUM_A), NOW).builtIn());
        assertEquals(new HashSet<>(Arrays.asList(SUM_A.substring(0, 16), SUM_B.substring(0, 16))), state.kept());
    }

    @Test
    public void onlyWebFilesAreAllowed() {
        for (String ok : new String[] { "index.html", "a/b.js", "x.css", "leads/index.txt", "f.woff2", "i.svg", "m.webmanifest" }) {
            assertTrue(ok, LiveWebRules.allowedType(ok));
        }
        for (String no : new String[] { "lib.so", "classes.dex", "x.jar", "x.apk", "y.wasm", "z.sh", "noext", ".hidden" }) {
            assertFalse(no, LiveWebRules.allowedType(no));
        }
        assertSame(null, LiveWebRules.safeEntry("../x.js"));
        assertEquals("a/b.js", LiveWebRules.safeEntry("a/b.js"));
    }

    // ---- Helpers ----

    private static String fill(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static LiveWebRules.BuiltIn builtIn() {
        return new LiveWebRules.BuiltIn(SHA_BUILT_IN, NOW - 10 * DAY, NOW - 10 * DAY);
    }

    private static LiveWebRules.Held held(String sha256, String sha, long sequence, long commitAtMs) {
        return new LiveWebRules.Held(sha256, "1." + sequence + ".0", sha, sequence, commitAtMs, commitAtMs + 3_600_000L, commitAtMs + 3_600_000L, 0);
    }

    private static Set<String> present(String... sha256s) {
        Set<String> ids = new HashSet<>();
        for (String sha256 : sha256s) ids.add(sha256.substring(0, 16));
        return ids;
    }

    private static String payload(String sha256, String sha, long sequence, int minNative, long commitAtMs) throws Exception {
        return new JSONObject()
            .put("host", HOST)
            .put("version", "1.3.0")
            .put("sha", sha)
            .put("sequence", sequence)
            .put("bundle", "bundles/" + sha256 + ".zip")
            .put("sha256", sha256)
            .put("bytes", 9_000_000)
            .put("minNative", minNative)
            .put("commitAtMs", commitAtMs)
            .put("builtAtMs", commitAtMs + 600_000)
            .toString();
    }

    private static LiveWebRules.Manifest manifest(String sha256, String sha, long sequence, int minNative, long commitAtMs) throws Exception {
        return LiveWebRules.open(envelope(releaseKey, payload(sha256, sha, sequence, minNative, commitAtMs)), pinned, HOST);
    }

    private static JSONObject copy(JSONObject json) throws Exception {
        return new JSONObject(json.toString());
    }

    private static String envelope(KeyPair key, String payload) throws Exception {
        byte[] signed = (LiveWebRules.FORMAT + "\n" + payload).getBytes(StandardCharsets.UTF_8);
        return new JSONObject()
            .put("format", LiveWebRules.FORMAT)
            .put("key", publicHex(key))
            .put("payload", payload)
            .put("signature", LiveWebRules.hex(sign(key, signed)))
            .toString();
    }

    private static byte[] sign(KeyPair key, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(data);
        return signer.sign();
    }

    private static String publicHex(KeyPair pair) {
        return LiveWebRules.hex(Ed25519Test.rawPublicKey(pair));
    }

    private static void assertRefused(String reason, String text, List<String> keys, String host) {
        try {
            LiveWebRules.open(text, keys, host);
            fail("expected " + reason);
        } catch (LiveWebRules.Refused refused) {
            assertEquals(reason, refused.reason);
        }
    }

    private static List<String> goodFiles() {
        return Arrays.asList(
            "index.html",
            "index.txt",
            "dashboard/index.html",
            "leads/index.html",
            "chats/index.html",
            "_next/static/chunks/app-1.js",
            "_next/static/css/a.css",
            "_next/static/media/font.woff2"
        );
    }

    private static String webJson(String host, String sha, List<String> files) throws Exception {
        return new JSONObject().put("host", host).put("sha", sha).put("builtAtMs", NOW).put("files", new JSONArray(files)).toString();
    }

    private byte[] zip(List<String> files, String... folders) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String folder : folders) {
                out.putNextEntry(new ZipEntry(folder));
                out.closeEntry();
            }
            for (String file : files) {
                out.putNextEntry(new ZipEntry(file));
                out.write(("/* " + file + " */").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("sx-web.json"));
            out.write(webJson(HOST, SHA_A, files).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void assertZipRefused(String reason, String entry) throws Exception {
        java.util.ArrayList<String> files = new java.util.ArrayList<>(goodFiles());
        files.add(entry);
        File dir = temp.newFolder();
        try {
            LiveWebRules.unpack(new ByteArrayInputStream(zip(files)), dir);
            fail("expected " + reason + " for " + entry);
        } catch (LiveWebRules.Refused refused) {
            assertEquals(entry, reason, refused.reason);
        }
        // Nothing landed outside the folder.
        assertFalse(new File(dir.getParentFile(), "escape.js").exists());
        assertNotNull(dir.listFiles());
    }

    private static void assertUnpackedRefused(Set<String> files, String webJson, LiveWebRules.Manifest manifest) {
        try {
            LiveWebRules.checkUnpacked(files, webJson, manifest);
            fail("expected refused");
        } catch (LiveWebRules.Refused refused) {
            assertTrue(refused.reason, refused.reason.startsWith("web_json") || refused.reason.startsWith("missing_"));
        }
    }
}
