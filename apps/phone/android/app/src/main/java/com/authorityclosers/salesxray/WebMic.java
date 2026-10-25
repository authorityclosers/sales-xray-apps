package com.authorityclosers.salesxray;

import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.HashSet;
import java.util.Set;

/**
 * The page's microphone for voice notes (MicRules): answers the WebView's request, asks Android at that
 * moment behind one line saying why, offers the app's settings when Android won't ask again, and stops
 * the recording when the app goes to the background. Replaces Capacitor's answer, which allowed any page.
 */
final class WebMic {

    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String> prompt;
    /** The page's request waiting on the seller's answer. */
    private PermissionRequest pending;

    /** In onCreate, before the activity starts (the permission launcher). */
    WebMic(AppCompatActivity activity) {
        this.activity = activity;
        prompt = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            PermissionAsks.answered(activity, Manifest.permission.RECORD_AUDIO, granted);
            PermissionRequest request = pending;
            pending = null;
            if (request == null) return;
            if (granted) {
                request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                return;
            }
            request.deny();
            // Android said no without asking: its settings are the only way now.
            if (PermissionAsks.blocked(activity, Manifest.permission.RECORD_AUDIO)) offerSettings();
        });
    }

    /** MicRules.script on our pages before their own scripts; PhoneBridge.inject adds it too, for an older WebView. */
    static void install(WebView webView, Set<String> hosts) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        Set<String> origins = new HashSet<>();
        for (String host : hosts) origins.add("https://" + host);
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, MicRules.script(), origins);
        } catch (RuntimeException ignored) {
            // PhoneBridge.inject adds it after the page loads.
        }
    }

    void onPermissionRequest(PermissionRequest request) {
        String permission = Manifest.permission.RECORD_AUDIO;
        boolean declared = Permissions.declared(activity, permission);
        boolean granted = declared && ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
        MicRules.Answer answer = MicRules.answer(
            String.valueOf(request.getOrigin()),
            request.getResources(),
            PhoneBridge.HOSTS,
            declared,
            granted,
            !granted && PermissionAsks.blocked(activity, permission)
        );
        switch (answer) {
            case GRANT:
                request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                break;
            case ASK:
                ask(request);
                break;
            case OPEN_SETTINGS:
                request.deny();
                offerSettings();
                break;
            default:
                request.deny();
        }
    }

    void onPermissionRequestCanceled(PermissionRequest request) {
        if (pending == request) pending = null;
    }

    /** The app is out of sight: a voice note being recorded stops and keeps what it has. */
    void stop(WebView webView) {
        webView.evaluateJavascript(MicRules.STOP, null);
    }

    private void ask(PermissionRequest request) {
        if (pending != null) pending.deny();
        pending = request;
        if (activity.isFinishing() || activity.isDestroyed()) {
            refuse();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("Record a voice note")
            .setMessage(MicRules.RATIONALE)
            .setPositiveButton("Continue", (shown, which) -> {
                PermissionAsks.asked(activity, Manifest.permission.RECORD_AUDIO);
                prompt.launch(Manifest.permission.RECORD_AUDIO);
            })
            .setNegativeButton("Not now", (shown, which) -> refuse())
            .setOnCancelListener(shown -> refuse())
            .show();
    }

    private void refuse() {
        PermissionRequest request = pending;
        pending = null;
        if (request != null) request.deny();
    }

    private void offerSettings() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
            .setTitle("Turn on the microphone")
            .setMessage(MicRules.RATIONALE + " Android won't ask again here: allow Microphone in Sales X-Ray's settings, then come back.")
            .setPositiveButton("Open settings", (shown, which) -> PhoneSetup.screen(activity, "appSettings"))
            .setNegativeButton("Not now", null)
            .show();
    }
}
