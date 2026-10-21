package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.webkit.WebChromeClient;
import java.io.File;

/**
 * "Add the recording" (Redmi audit #54): Android's own file picker opened on
 * "Recent", which lists only indexed media, so MIUI's hidden call recordings
 * showed "No items". For an audio pick the app opens the picker in this
 * phone's call recordings folder instead. The picker shows that folder's files
 * even when the media index hides them, and the seller still picks the file.
 */
final class RecordingPicker {

    private static final String STORAGE = "com.android.externalstorage.documents";

    private RecordingPicker() {}

    /** True when the page asked for audio files. */
    static boolean wantsAudio(WebChromeClient.FileChooserParams params) {
        if (params == null || params.isCaptureEnabled()) return false;
        String[] types = params.getAcceptTypes();
        if (types == null) return false;
        for (String type : types) {
            if (type == null) continue;
            for (String part : type.split(",")) if (part.trim().toLowerCase(java.util.Locale.ROOT).startsWith("audio/")) return true;
        }
        return false;
    }

    /** The picker for an audio file, opened in the recordings folder when the phone has one. */
    static Intent intent(Context context, WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "audio/*", "application/ogg", "video/3gpp" });
        if (params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        Uri folder = folder(context);
        if (folder != null && Build.VERSION.SDK_INT >= 26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
        return intent;
    }

    /** The first of this phone's recorder folders that exists, as a picker address. */
    static Uri folder(Context context) {
        File root = Environment.getExternalStorageDirectory();
        for (String relative : RecorderProfile.profile(context).folders) {
            if (new File(root, relative).isDirectory()) return DocumentsContract.buildDocumentUri(STORAGE, "primary:" + relative);
        }
        return null;
    }
}
