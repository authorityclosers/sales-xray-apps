package com.authorityclosers.salesxray;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Choose your recordings folder" (S8, 23 Oct 2026). The seller picks the
 * phone's call-recordings folder once in Android's own folder picker, opened
 * at the folder this phone's dialer uses; Sales X-Ray keeps read access to that
 * folder only and lists its recordings like any other. Nothing leaves the
 * phone until the seller picks a recording to analyse.
 */
final class RecordingFolder {

    private static final String PREFS = "sx_recording_folders";
    private static final String TREES = "trees";
    private static final String STORAGE = "com.android.externalstorage.documents";
    private static final int MAX_FILES = 500;
    private static final Object LOCK = new Object();

    static final class Document {

        Uri uri;
        String name;
        /** The folder it sits in, as a path on the phone's storage. */
        String folder;
        long modifiedMs;
        long size;
        String mime;
    }

    private RecordingFolder() {}

    /** Android 8 and newer open the picker at a given folder. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Where the picker opens: the recorder folder this phone's dialer uses. */
    static Uri initial(Context context) {
        Uri existing = RecordingPicker.folder(context);
        if (existing != null) return existing;
        String[] folders = RecorderProfile.profile(context).folders;
        return folders.length == 0 ? null : DocumentsContract.buildDocumentUri(STORAGE, "primary:" + folders[0]);
    }

    /** Keeps read access to the folder the seller picked, across restarts. */
    static boolean keep(Context context, Uri tree) {
        if (tree == null || !STORAGE.equals(tree.getAuthority())) return false;
        try {
            context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException error) {
            return false;
        }
        synchronized (LOCK) {
            Set<String> saved = new HashSet<>(prefs(context).getStringSet(TREES, Collections.emptySet()));
            saved.add(tree.toString());
            prefs(context).edit().putStringSet(TREES, saved).apply();
        }
        return true;
    }

    /** The picked folders Android still lets the app read. */
    static List<Uri> trees(Context context) {
        Set<String> saved = prefs(context).getStringSet(TREES, Collections.emptySet());
        List<Uri> held = new ArrayList<>();
        if (saved.isEmpty()) return held;
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.isReadPermission() && saved.contains(permission.getUri().toString())) held.add(permission.getUri());
            }
        } catch (RuntimeException ignored) {
            // No grants to read: nothing picked.
        }
        return held;
    }

    /** The picked folders as paths on the phone's storage ("MIUI/sound_recorder/call_rec"). */
    static List<String> paths(Context context) {
        List<String> paths = new ArrayList<>();
        for (Uri tree : trees(context)) {
            try {
                paths.add(RecordingFolderRules.path(DocumentsContract.getTreeDocumentId(tree)));
            } catch (IllegalArgumentException ignored) {}
        }
        return paths;
    }

    /** Files in the picked folders, and in folders inside them (RecordingFolderRules.DEPTH). */
    static List<Document> files(Context context) {
        List<Document> found = new ArrayList<>();
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        };
        for (Uri tree : trees(context)) {
            String treeId;
            try {
                treeId = DocumentsContract.getTreeDocumentId(tree);
            } catch (IllegalArgumentException error) {
                continue;
            }
            // Each entry: a folder's document id and how deep its files are.
            Deque<String> folders = new ArrayDeque<>();
            Deque<Integer> depths = new ArrayDeque<>();
            folders.add(treeId);
            depths.add(1);
            while (!folders.isEmpty() && found.size() < MAX_FILES) {
                String folderId = folders.poll();
                int depth = depths.poll();
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId);
                try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
                    if (cursor == null) continue;
                    while (cursor.moveToNext() && found.size() < MAX_FILES) {
                        String id = cursor.getString(0);
                        String mime = cursor.getString(2);
                        if (id == null) continue;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                            if (depth < RecordingFolderRules.DEPTH) {
                                folders.add(id);
                                depths.add(depth + 1);
                            }
                            continue;
                        }
                        Document document = new Document();
                        document.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        document.name = cursor.getString(1);
                        document.folder = RecordingFolderRules.path(folderId);
                        document.modifiedMs = cursor.isNull(3) ? 0 : cursor.getLong(3);
                        document.size = cursor.isNull(4) ? 0 : cursor.getLong(4);
                        document.mime = mime;
                        if (document.name != null) found.add(document);
                    }
                } catch (RuntimeException ignored) {
                    // The folder went away or the grant was taken back: the rest still count.
                }
            }
        }
        return found;
    }

    /** A recording from a picked folder, only when it sits inside one the seller picked. */
    static Uri allowed(Context context, String source) throws IOException {
        Uri uri = Uri.parse(source.substring(5));
        if (!STORAGE.equals(uri.getAuthority())) throw new IOException("recording_not_allowed");
        String treeId;
        String documentId;
        try {
            treeId = DocumentsContract.getTreeDocumentId(uri);
            documentId = DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException error) {
            throw new IOException("recording_not_allowed");
        }
        for (Uri tree : trees(context)) {
            String held;
            try {
                held = DocumentsContract.getTreeDocumentId(tree);
            } catch (IllegalArgumentException error) {
                continue;
            }
            if (held.equals(treeId) && RecordingFolderRules.inside(treeId, documentId)) return uri;
        }
        throw new IOException("recording_not_allowed");
    }
}
