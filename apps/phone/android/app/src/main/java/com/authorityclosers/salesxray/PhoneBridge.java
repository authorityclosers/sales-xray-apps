package com.authorityclosers.salesxray;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The page talks to the phone through one origin-restricted message channel
 * ("SXPhone"). Only Sales Xray's own sites can call it, and it works under the
 * site's content security policy. It places a lead's call and reports how the
 * call went, and it lists and reads the phone's own call recordings.
 */
final class PhoneBridge {

    static final Set<String> HOSTS = new HashSet<>(
        Arrays.asList(
            "salesxray.authorityclosers.com",
            "salesxray-staging.authorityclosers.com",
            "salesxray-dev.authorityclosers.com"
        )
    );

    private static final int REQUEST_AUDIO = 7101;
    private static final int REQUEST_NOTIFY = 7102;
    private static final int REQUEST_CALL = 7103;
    private static final int REQUEST_PUSH = 7104;
    private static final int REQUEST_LOG = 7105;
    private static final int REQUEST_ALERTS = 7106;
    private static final int REQUEST_MIC = 7107;

    /** The bridge of the open app, for signals from outside the page (lead alerts). */
    private static volatile PhoneBridge active;
    private static final int CHUNK = 512 * 1024;

    private final AppCompatActivity activity;
    private final WebView webView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String script;
    private String launchSource;
    private JavaScriptReplyProxy pendingReply;
    private String pendingId;
    /** The page that spoke last: a call's result is sent there. */
    private JavaScriptReplyProxy events;
    private final CallTracker calls;
    private JavaScriptReplyProxy pushReply;
    private String pushId;
    private JavaScriptReplyProxy logReply;
    private String logId;
    private String logSim;

    PhoneBridge(AppCompatActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.calls = new CallTracker(activity, this::sendCall);
    }

    void onResume() {
        calls.onResume();
        signal("resume");
    }

    private void sendCall(JSONObject call) {
        JSONObject message = new JSONObject();
        try {
            message.put("event", "call");
            message.put("call", call);
        } catch (JSONException ignored) {}
        String text = message.toString();
        main.post(() -> {
            JavaScriptReplyProxy proxy = events;
            if (proxy == null) return;
            try {
                proxy.postMessage(text);
            } catch (RuntimeException ignored) {
                // The page went away; it asks again when it opens.
            }
        });
    }

    static void signalActive(String event) {
        PhoneBridge bridge = active;
        if (bridge != null) bridge.signal(event);
    }

    void attach() {
        active = this;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        Set<String> origins = new HashSet<>();
        for (String host : HOSTS) origins.add("https://" + host);
        WebViewCompat.addWebMessageListener(webView, "SXPhone", origins, (view, message, origin, isMainFrame, reply) -> {
            if (!isMainFrame || message.getData() == null) return;
            events = reply;
            handle(message.getData(), reply);
        });
    }

    /** Adds the "Calls on this phone" panel to Sales Xray pages only. */
    void inject(WebView view, String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())) return;
        if (script == null) script = asset("sx-phone.js");
        if (script != null) view.evaluateJavascript(script, null);
    }

    void takeLaunch(Intent intent) {
        if (intent == null) return;
        String source = intent.getStringExtra(RecordingWatchWorker.EXTRA_SOURCE);
        if (source != null) launchSource = source;
        intent.removeExtra(RecordingWatchWorker.EXTRA_SOURCE);
    }

    void signal(String event) {
        main.post(() -> webView.evaluateJavascript("window.__sxPhone&&window.__sxPhone.onNative(" + JSONObject.quote(event) + ")", null));
    }

    void onPermissionResult(int requestCode) {
        if (requestCode == REQUEST_LOG && logReply != null) {
            JavaScriptReplyProxy reply = logReply;
            String id = logId;
            logReply = null;
            logId = null;
            if (CallLogSync.hasAccess(activity) && CallLogSync.setOn(activity, true, logSim)) CallLogSync.soon(activity);
            reply(reply, id, CallLogSync.status(activity));
            return;
        }
        if (requestCode == REQUEST_PUSH && pushReply != null) {
            JavaScriptReplyProxy reply = pushReply;
            String id = pushId;
            pushReply = null;
            pushId = null;
            fetchToken(reply, id);
            return;
        }
        if (requestCode == REQUEST_ALERTS && pendingReply != null) {
            JavaScriptReplyProxy reply = pendingReply;
            String id = pendingId;
            pendingReply = null;
            pendingId = null;
            io.execute(() -> reply(reply, id, setupStatus()));
            return;
        }
        if (requestCode == REQUEST_MIC && pendingReply != null) {
            JavaScriptReplyProxy reply = pendingReply;
            String id = pendingId;
            pendingReply = null;
            pendingId = null;
            reply(reply, id, recordResult(AppRecorder.hasMic(activity) ? AppRecorder.start(activity, micLead) : "no_mic"));
            return;
        }
        if ((requestCode == REQUEST_AUDIO || requestCode == REQUEST_NOTIFY || requestCode == REQUEST_CALL) && pendingReply != null) {
            if (requestCode == REQUEST_NOTIFY) RecordingWatchWorker.setWatching(activity, true);
            reply(pendingReply, pendingId, status());
            pendingReply = null;
            pendingId = null;
        }
    }

    private void handle(String raw, JavaScriptReplyProxy reply) {
        String id = null;
        try {
            JSONObject message = new JSONObject(raw);
            id = message.optString("id");
            JSONObject args = message.optJSONObject("args");
            if (args == null) args = new JSONObject();
            switch (message.optString("method")) {
                case "status":
                    reply(reply, id, status());
                    break;
                case "requestAccess":
                    requestAccess(reply, id);
                    break;
                case "requestCallAccess":
                    requestCallAccess(reply, id);
                    break;
                case "call":
                    placeCall(reply, id, args);
                    break;
                case "lastCall":
                    reply(reply, id, calls.last(args.optString("leadId")));
                    break;
                case "forgetCall":
                    calls.forget(args.optString("leadId"));
                    reply(reply, id, new JSONObject());
                    break;
                case "openAllFiles":
                    openAllFilesSettings();
                    reply(reply, id, status());
                    break;
                case "list":
                    list(reply, id, args.optInt("limit", 200));
                    break;
                case "read":
                    read(reply, id, args.optString("source"), args.optLong("offset", 0));
                    break;
                case "setWatch":
                    setWatch(reply, id, args.optBoolean("on"));
                    break;
                case "haptic":
                    haptic(args.optString("kind"));
                    reply(reply, id, new JSONObject());
                    break;
                case "pushStatus":
                    reply(reply, id, pushState());
                    break;
                case "pushEnable":
                    pushEnable(reply, id, args);
                    break;
                case "pushReset":
                    pushReset();
                    reply(reply, id, pushState());
                    break;
                case "callLogStatus":
                    reply(reply, id, CallLogSync.status(activity));
                    break;
                case "callLogSetup":
                    callLogSetup(reply, id, args.optBoolean("on"), args.optString("sim", ""));
                    break;
                case "callLogSync": {
                    String asked = id;
                    io.execute(() -> reply(reply, asked, CallLogSync.run(activity)));
                    break;
                }
                case "unknownCalls": {
                    // Work calls with numbers that aren't leads: shown here only.
                    JSONObject result = new JSONObject();
                    result.put("items", CallLogSync.unknown(activity));
                    reply(reply, id, result);
                    break;
                }
                case "ignoreNumber":
                    CallLogSync.ignore(activity, args.optString("number"));
                    reply(reply, id, new JSONObject().put("items", CallLogSync.unknown(activity)));
                    break;
                case "forgetUnknown":
                    CallLogSync.forget(activity, args.optString("number"));
                    reply(reply, id, new JSONObject().put("items", CallLogSync.unknown(activity)));
                    break;
                case "setupStatus": {
                    String asked = id;
                    io.execute(() -> reply(reply, asked, setupStatus()));
                    break;
                }
                case "setupOpen": {
                    JSONObject result = new JSONObject();
                    result.put("opened", PhoneSetup.open(activity, args.optString("what")));
                    reply(reply, id, result);
                    break;
                }
                case "setupMark": {
                    PhoneSetup.mark(activity, args.optString("what"), args.optBoolean("done"));
                    String asked = id;
                    io.execute(() -> reply(reply, asked, setupStatus()));
                    break;
                }
                case "requestNotifications":
                    requestNotifications(reply, id);
                    break;
                case "bars":
                    // The colours at the top and bottom of the page, from sx-phone.js.
                    SystemBarColors.paint(activity, args.optString("status"), args.optString("nav"));
                    reply(reply, id, new JSONObject());
                    break;
                case "recorderStatus": {
                    // Which dialer, where it saves, and whether recording is on (RecorderProfile).
                    String asked = id;
                    io.execute(() -> reply(reply, asked, RecorderProfile.status(activity)));
                    break;
                }
                case "recorderOpen": {
                    JSONObject result = new JSONObject();
                    result.put("opened", RecorderProfile.open(activity, args.optString("what")));
                    reply(reply, id, result);
                    break;
                }
                case "recordStart": {
                    String lead = args.optString("leadId", "");
                    if (!AppRecorder.hasMic(activity)) {
                        micLead = lead;
                        pendingReply = reply;
                        pendingId = id;
                        ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_MIC);
                        break;
                    }
                    reply(reply, id, recordResult(AppRecorder.start(activity, lead)));
                    break;
                }
                case "recordStop":
                    AppRecorder.stop(activity);
                    reply(reply, id, AppRecorder.state(activity));
                    break;
                case "recordState":
                    reply(reply, id, AppRecorder.state(activity));
                    break;
                case "consumeLaunch": {
                    JSONObject result = new JSONObject();
                    result.put("source", launchSource == null ? JSONObject.NULL : launchSource);
                    launchSource = null;
                    reply(reply, id, result);
                    break;
                }
                default:
                    fail(reply, id, "unknown_method");
            }
        } catch (JSONException error) {
            fail(reply, id, "bad_message");
        }
    }

    private String micLead = "";

    private JSONObject recordResult(String outcome) {
        JSONObject result = AppRecorder.state(activity);
        try {
            result.put("outcome", outcome);
        } catch (JSONException ignored) {}
        return result;
    }

    private JSONObject status() {
        JSONObject status = new JSONObject();
        try {
            status.put("platform", "android");
            status.put("sdk", Build.VERSION.SDK_INT);
            status.put("audio", RecordingScanner.hasAudioAccess(activity));
            status.put("allFiles", RecordingScanner.hasAllFilesAccess());
            status.put("allFilesSupported", Build.VERSION.SDK_INT >= 30);
            status.put("notifications", notificationsAllowed());
            status.put("watching", RecordingWatchWorker.watching(activity));
            status.put("calling", true);
            status.put("callAccess", CallTracker.hasCallAccess(activity));
            status.put("push", true);
            status.put("callLog", true);
            status.put("setup", true);
            status.put("version", versionName());
        } catch (JSONException ignored) {}
        return status;
    }

    private String versionName() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private boolean notificationsAllowed() {
        return (
            Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        );
    }

    private void requestAccess(JavaScriptReplyProxy reply, String id) {
        if (RecordingScanner.hasAudioAccess(activity)) {
            reply(reply, id, status());
            return;
        }
        pendingReply = reply;
        pendingId = id;
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(activity, new String[] { permission }, REQUEST_AUDIO);
    }

    private void requestCallAccess(JavaScriptReplyProxy reply, String id) {
        String[] missing = CallTracker.missing(activity);
        if (missing.length == 0) {
            reply(reply, id, status());
            return;
        }
        pendingReply = reply;
        pendingId = id;
        ActivityCompat.requestPermissions(activity, missing, REQUEST_CALL);
    }

    private void placeCall(JavaScriptReplyProxy reply, String id, JSONObject args) {
        String leadId = args.optString("leadId");
        String number = args.optString("number");
        if (leadId.isEmpty() || CallTracker.digits(number).length() < 6) {
            fail(reply, id, "bad_call");
            return;
        }
        main.post(() -> webView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY));
        try {
            reply(reply, id, calls.call(leadId, number, args.optString("name")));
        } catch (RuntimeException error) {
            fail(reply, id, "call_failed");
        }
    }

    /** A light buzz: a tick on a swipe, a press on a long hold, done on a save. */
    private void haptic(String kind) {
        int effect = HapticFeedbackConstants.KEYBOARD_TAP;
        if ("press".equals(kind)) effect = HapticFeedbackConstants.LONG_PRESS;
        else if ("done".equals(kind)) effect = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
        final int chosen = effect;
        main.post(() -> webView.performHapticFeedback(chosen));
    }

    private JSONObject pushState() {
        JSONObject state = new JSONObject();
        try {
            state.put("supported", true);
            state.put("allowed", LeadAlerts.allowed(activity));
            String token = LeadAlerts.token(activity);
            state.put("token", token == null ? JSONObject.NULL : token);
        } catch (JSONException ignored) {}
        return state;
    }

    /** Lead alerts on: start Firebase with Sales Xray's settings, ask to notify once, get the address. */
    private void pushEnable(JavaScriptReplyProxy reply, String id, JSONObject config) {
        if (!LeadAlerts.configure(activity, config)) {
            fail(reply, id, "push_config");
            return;
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pushReply = reply;
            pushId = id;
            ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_PUSH);
            return;
        }
        fetchToken(reply, id);
    }

    private void fetchToken(JavaScriptReplyProxy reply, String id) {
        if (!LeadAlerts.allowed(activity) || !LeadAlerts.init(activity)) {
            reply(reply, id, pushState());
            return;
        }
        try {
            FirebaseMessaging messaging = FirebaseMessaging.getInstance();
            messaging.setAutoInitEnabled(true);
            messaging
                .getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) LeadAlerts.saveToken(activity, task.getResult());
                    reply(reply, id, pushState());
                });
        } catch (RuntimeException error) {
            fail(reply, id, "push_unavailable");
        }
    }

    /** Signed out on this phone: drop its alert address, so no alert reaches it. */
    private void pushReset() {
        if (LeadAlerts.token(activity) == null) return;
        LeadAlerts.forget(activity);
        if (!LeadAlerts.init(activity)) return;
        try {
            FirebaseMessaging.getInstance().deleteToken();
        } catch (RuntimeException ignored) {
            // Firebase isn't running; the server drops the address on its next failed send.
        }
    }

    /** The phone setup checklist (UX study item 7), with this app's version. */
    private JSONObject setupStatus() {
        JSONObject status = PhoneSetup.status(activity);
        try {
            status.put("version", versionName());
        } catch (JSONException ignored) {}
        return status;
    }

    /** Notifications alone (lead alerts and new recordings use them); older Android opens the settings. */
    private void requestNotifications(JavaScriptReplyProxy reply, String id) {
        if (PhoneSetup.notificationsAllowed(activity)) {
            io.execute(() -> reply(reply, id, setupStatus()));
            return;
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingReply = reply;
            pendingId = id;
            ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_ALERTS);
            return;
        }
        PhoneSetup.open(activity, "notifications");
        io.execute(() -> reply(reply, id, setupStatus()));
    }

    /** Work calls logged by themselves (UX study item 5): call history access is asked for once. */
    private void callLogSetup(JavaScriptReplyProxy reply, String id, boolean on, String sim) {
        if (on && !CallLogSync.hasAccess(activity)) {
            logReply = reply;
            logId = id;
            logSim = sim;
            ActivityCompat.requestPermissions(activity, CallLogSync.wanted(), REQUEST_LOG);
            return;
        }
        if (CallLogSync.setOn(activity, on, sim) && on) CallLogSync.soon(activity);
        reply(reply, id, CallLogSync.status(activity));
    }

    private void setWatch(JavaScriptReplyProxy reply, String id, boolean on) {
        if (on && !notificationsAllowed()) {
            pendingReply = reply;
            pendingId = id;
            ActivityCompat.requestPermissions(activity, new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFY);
            return;
        }
        RecordingWatchWorker.setWatching(activity, on);
        reply(reply, id, status());
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (RuntimeException error) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void list(JavaScriptReplyProxy reply, String id, int limit) {
        io.execute(() -> {
            JSONObject result = new JSONObject();
            try {
                result.put("items", RecordingScanner.toJson(RecordingScanner.list(activity, Math.max(1, Math.min(limit, 500)))));
            } catch (JSONException ignored) {}
            reply(reply, id, result);
        });
    }

    private void read(JavaScriptReplyProxy reply, String id, String source, long offset) {
        io.execute(() -> {
            try (InputStream stream = RecordingScanner.open(activity, source)) {
                long skipped = 0;
                while (skipped < offset) {
                    long step = stream.skip(offset - skipped);
                    if (step <= 0) break;
                    skipped += step;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(CHUNK);
                byte[] buffer = new byte[64 * 1024];
                int total = 0;
                int count;
                while (total < CHUNK && (count = stream.read(buffer, 0, Math.min(buffer.length, CHUNK - total))) > 0) {
                    out.write(buffer, 0, count);
                    total += count;
                }
                boolean eof = total < CHUNK || stream.read() < 0;
                JSONObject result = new JSONObject();
                result.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                result.put("eof", eof);
                reply(reply, id, result);
            } catch (IOException | JSONException | RuntimeException error) {
                fail(reply, id, "read_failed");
            }
        });
    }

    private void reply(JavaScriptReplyProxy proxy, String id, JSONObject result) {
        JSONObject message = new JSONObject();
        try {
            message.put("id", id);
            message.put("ok", true);
            message.put("result", result);
        } catch (JSONException ignored) {}
        main.post(() -> proxy.postMessage(message.toString()));
    }

    private void fail(JavaScriptReplyProxy proxy, String id, String code) {
        JSONObject message = new JSONObject();
        try {
            message.put("id", id == null ? JSONObject.NULL : id);
            message.put("ok", false);
            message.put("error", code);
        } catch (JSONException ignored) {}
        main.post(() -> proxy.postMessage(message.toString()));
    }

    private String asset(String name) {
        try (InputStream stream = activity.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException error) {
            return null;
        }
    }
}
