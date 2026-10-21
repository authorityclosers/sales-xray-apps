package com.authorityclosers.salesxray;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Finds the call recordings a phone makes by itself (Samsung, Xiaomi, OnePlus,
 * Realme, Oppo, Vivo, Motorola and others save them as ordinary audio files).
 * Nothing leaves the phone here; the web app uploads only what the person picks.
 */
final class RecordingScanner {

    /** Folder names the built-in phone apps use, relative to shared storage. */
    static final String[] KNOWN_FOLDERS = {
        "Recordings/Call",
        "Recordings/Call recordings",
        "Recordings/Call Recordings",
        "Recordings/PhoneRecord",
        "Music/Recordings/Call Recordings",
        "MIUI/sound_recorder/call_rec",
        "Record/Call",
        "Record/PhoneRecord",
        "PhoneRecord",
        "Call",
        "CallRecordings",
        "Call Recordings",
        "Sounds/CallRecord",
        "Recordings/Call recordings",
        "Recordings/Record/Call",
        "Recordings/CallRecord",
        "Music/Call Recordings",
        "record",
    };

    /** Every folder a maker's dialer may use (RecorderRules), plus the older list above. */
    static String[] folders() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(Arrays.asList(KNOWN_FOLDERS));
        for (String[] maker : new String[][] { RecorderRules.XIAOMI, RecorderRules.SAMSUNG, RecorderRules.OPLUS, RecorderRules.VIVO, RecorderRules.HUAWEI, RecorderRules.OTHER }) {
            all.addAll(Arrays.asList(maker));
        }
        return all.toArray(new String[0]);
    }

    /** Path fragments that mark a call-recorder folder in the media index. */
    private static final String[] PATH_HINTS = { "%call%", "%phonerecord%", "%call_rec%", "%callrecord%" };

    private static final Set<String> AUDIO = new HashSet<>(
        Arrays.asList("m4a", "mp3", "mpeg", "aac", "amr", "awb", "wav", "3gp", "ogg", "opus", "flac")
    );

    /**
     * Formats the Sales Xray upload accepts and analyses. AAC, AMR (narrow and
     * wide band), 3GP and Opus joined on 18 Oct (UX study item 7): the server's
     * media allowlist and decoder already took them.
     */
    private static final Set<String> SUPPORTED = new HashSet<>(
        Arrays.asList("m4a", "mp3", "mpeg", "wav", "ogg", "flac", "aac", "amr", "awb", "3gp", "opus")
    );

    private static final String[] SKIP = { "whatsapp", "telegram", "signal", "notification", "ringtone", "alarm" };

    private RecordingScanner() {}

    static boolean hasAudioAccess(Context context) {
        String permission = Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_AUDIO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    /**
     * Can the recorder folders be read by path? With all-files access always;
     * with audio access on Android 9 and older, and on Android 11 and newer
     * (shared storage lets an app read audio files it may see by their path).
     */
    static boolean canReadFolders(Context context) {
        if (hasAllFilesAccess()) return true;
        boolean audio = hasAudioAccess(context);
        return audio && (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT >= 30);
    }

    static final class FolderCount {

        int recordings;
        long newestMs;
    }

    /** Audio files in one folder and the newest one's time (0 when none or unreadable). */
    static FolderCount count(File folder) {
        FolderCount count = new FolderCount();
        File[] files = folder.listFiles();
        if (files == null) return count;
        for (File file : files) {
            if (!file.isFile() || !AUDIO.contains(extension(file.getName()))) continue;
            count.recordings++;
            count.newestMs = Math.max(count.newestMs, file.lastModified());
        }
        return count;
    }

    /** Newest first, at most {@code limit} items. */
    static List<JSONObject> list(Context context, int limit) {
        List<JSONObject> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        fromApp(context, found, seen);
        if (hasAudioAccess(context) || hasAllFilesAccess()) fromMediaIndex(context, found, seen);
        // Some phones hide the call folder from the media index (.nomedia), so
        // the maker's folders are also read by path where Android allows it.
        if (canReadFolders(context)) fromFolders(found, seen);
        found.sort((a, b) -> Long.compare(b.optLong("addedMs"), a.optLong("addedMs")));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    /**
     * The newest recording saved since {@code sinceMs}. One named after the
     * number or the person wins over one that is merely newer.
     */
    static JSONObject newestSince(Context context, long sinceMs, String number, String name) {
        String digits = CallTracker.digits(number);
        String tail = digits.length() > 6 ? digits.substring(digits.length() - 6) : digits;
        String first = name == null ? "" : name.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        JSONObject fallback = null;
        for (JSONObject item : list(context, 40)) {
            if (item.optLong("addedMs") < sinceMs) continue;
            String file = item.optString("name").toLowerCase(Locale.ROOT);
            boolean named = (tail.length() >= 6 && file.replaceAll("\\D", "").contains(tail)) || (first.length() > 1 && file.contains(first));
            if (named) return item;
            if (fallback == null) fallback = item;
        }
        return fallback;
    }

    static JSONArray toJson(List<JSONObject> items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) array.put(item);
        return array;
    }

    private static void fromMediaIndex(Context context, List<JSONObject> found, Set<String> seen) {
        Uri collection = Build.VERSION.SDK_INT >= 29
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String pathColumn = Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
        String[] projection = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            pathColumn,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            "duration",
        };
        StringBuilder selection = new StringBuilder("(");
        for (int i = 0; i < PATH_HINTS.length; i++) {
            if (i > 0) selection.append(" OR ");
            selection.append("LOWER(").append(pathColumn).append(") LIKE ?");
        }
        selection.append(")");
        try (
            Cursor cursor = context
                .getContentResolver()
                .query(collection, projection, selection.toString(), PATH_HINTS, MediaStore.MediaColumns.DATE_ADDED + " DESC")
        ) {
            if (cursor == null) return;
            while (cursor.moveToNext() && found.size() < 500) {
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                String folder = cursor.getString(2);
                if (name == null || skip(folder) || !AUDIO.contains(extension(name))) continue;
                long size = cursor.getLong(4);
                if (!seen.add(name.toLowerCase(Locale.ROOT) + ":" + size)) continue;
                Uri uri = ContentUris.withAppendedId(collection, id);
                found.add(item("media:" + uri, name, folderLabel(folder), cursor.getLong(3) * 1000L, cursor.getLong(6), size, cursor.getString(5)));
            }
        } catch (RuntimeException ignored) {
            // A phone without a media index, or a revoked permission: show nothing.
        }
    }

    private static void fromFolders(List<JSONObject> found, Set<String> seen) {
        File root = Environment.getExternalStorageDirectory();
        for (String relative : folders()) {
            File folder = new File(root, relative);
            File[] files = folder.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile() || !AUDIO.contains(extension(file.getName()))) continue;
                if (!seen.add(file.getName().toLowerCase(Locale.ROOT) + ":" + file.length())) continue;
                found.add(item("file:" + file.getAbsolutePath(), file.getName(), relative, file.lastModified(), 0, file.length(), null));
            }
        }
    }

    /** Conversations recorded in Sales Xray itself (AppRecorder). */
    private static void fromApp(Context context, List<JSONObject> found, Set<String> seen) {
        File dir = context.getExternalFilesDir(AppRecorder.FOLDER);
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile() || file.length() == 0 || !AUDIO.contains(extension(file.getName()))) continue;
            if (!seen.add(file.getName().toLowerCase(Locale.ROOT) + ":" + file.length())) continue;
            JSONObject entry = item("app:" + file.getName(), file.getName(), "Sales Xray", file.lastModified(), 0, file.length(), null);
            boolean call = file.getName().startsWith(CallRecorderService.PREFIX);
            try {
                entry.put("title", call ? "Call recorded by Sales Xray" : "Recorded in Sales Xray");
                entry.put("inApp", true);
                entry.put("captured", call ? "accessibility" : "in_app");
            } catch (JSONException ignored) {}
            found.add(entry);
        }
    }

    private static JSONObject item(String source, String name, String folder, long addedMs, long durationMs, long size, String mime) {
        JSONObject json = new JSONObject();
        String ext = extension(name);
        try {
            json.put("source", source);
            json.put("name", name);
            json.put("title", title(name));
            json.put("folder", folder);
            json.put("addedMs", addedMs);
            json.put("durationMs", durationMs);
            json.put("size", size);
            json.put("ext", ext);
            json.put("mime", mime != null ? mime : mimeFor(ext));
            json.put("supported", SUPPORTED.contains(ext));
            // How it was captured, so quality can be compared (CEO 19 Oct): the phone's own
            // dialer here; "accessibility" or "in_app" for Sales Xray's own files (fromApp).
            json.put("captured", "dialer");
        } catch (JSONException ignored) {}
        return json;
    }

    /** "Call recording Ravi Sharma_241005_101530.m4a" becomes "Ravi Sharma". */
    static String title(String name) {
        String base = name.replaceFirst("\\.[^.]+$", "");
        base = base.replaceAll("(?i)^(call ?recording|call_rec|record|callrecord)[ _-]*", "");
        base = base.replaceAll("[_-]?\\d{6,}([_-]\\d{4,6})*$", "");
        base = base.replace('_', ' ').trim();
        return base.isEmpty() ? "Call recording" : base;
    }

    private static boolean skip(String folder) {
        if (folder == null) return false;
        String lower = folder.toLowerCase(Locale.ROOT);
        for (String word : SKIP) if (lower.contains(word)) return true;
        return false;
    }

    private static String folderLabel(String folder) {
        if (folder == null) return "";
        String trimmed = folder.replaceAll("/+$", "");
        int storage = trimmed.indexOf("/0/");
        return storage >= 0 ? trimmed.substring(storage + 3) : trimmed;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mimeFor(String ext) {
        switch (ext) {
            case "m4a":
                return "audio/mp4";
            case "mp3":
            case "mpeg":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "ogg":
            case "opus":
                return "audio/ogg";
            case "flac":
                return "audio/flac";
            case "aac":
                return "audio/aac";
            case "amr":
                return "audio/amr";
            case "awb":
                return "audio/amr-wb";
            case "3gp":
                return "audio/3gpp";
            default:
                return "application/octet-stream";
        }
    }

    static InputStream open(Context context, String source) throws IOException {
        if (source.startsWith("media:")) {
            InputStream stream = context.getContentResolver().openInputStream(mediaUri(source));
            if (stream == null) throw new IOException("recording_unavailable");
            return stream;
        }
        return new FileInputStream(allowedFile(context, source));
    }

    /** The same recordings as {@link #open}, as a file descriptor (AudioLevels decodes from it). */
    static ParcelFileDescriptor descriptor(Context context, String source) throws IOException {
        if (source.startsWith("media:")) {
            ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(mediaUri(source), "r");
            if (descriptor == null) throw new IOException("recording_unavailable");
            return descriptor;
        }
        return ParcelFileDescriptor.open(allowedFile(context, source), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Only the shared audio index, never contacts, calls or other providers. */
    private static Uri mediaUri(String source) throws IOException {
        Uri uri = Uri.parse(source.substring(6));
        if (!"media".equals(uri.getAuthority()) || uri.getPath() == null || !uri.getPath().contains("/audio/media/")) {
            throw new IOException("recording_not_allowed");
        }
        return uri;
    }

    private static File allowedFile(Context context, String source) throws IOException {
        if (source.startsWith("app:")) {
            // Only a file directly inside the app's own Recordings folder.
            File dir = context.getExternalFilesDir(AppRecorder.FOLDER);
            String name = source.substring(4);
            if (dir != null && !name.contains("/") && !name.contains("\\") && !name.startsWith(".")) {
                File file = new File(dir, name);
                if (file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) return file;
            }
            throw new IOException("recording_not_allowed");
        }
        if (source.startsWith("file:")) {
            File file = new File(source.substring(5));
            // Only files inside the known call-recorder folders may be read.
            String path = file.getCanonicalPath();
            File root = Environment.getExternalStorageDirectory();
            for (String relative : folders()) {
                if (path.startsWith(new File(root, relative).getCanonicalPath() + File.separator)) return file;
            }
        }
        throw new IOException("recording_not_allowed");
    }
}
