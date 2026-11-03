package com.authorityclosers.salesxray;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.OpenableColumns;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A call recording shared to Sales X-Ray from the Phone app or any recorder (ACTION_SEND or
 * SEND_MULTIPLE, audio/*). Google's Phone app keeps its recordings inside itself, so its Share is
 * the only way out (owner, 3 Nov 2026). The app keeps its own copy, finds the seller's call in the
 * call history (ShareRules), asks once, and uploads it like a recording found in the folder, under
 * the same standing consent (AutoCapture.shared). With no matching call, or uploads off, Attach to
 * a lead opens New analysis, whose next audio pick takes the shared files.
 */
final class ShareIn {

    private static final String DIR = "shared";
    /** New analysis's next audio pick takes the shared files within this long. */
    private static final long ATTACH_MS = 15L * 60 * 1000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static Uri[] attach;
    private static long attachAt;

    /** One shared recording: the app's copy, its length, the call it fits (or null), and why it can't go there. */
    static final class Shared {
        final File file;
        final long durationMs;
        ShareRules.Call call;
        String why;

        Shared(File file, long durationMs) {
            this.file = file;
            this.durationMs = durationMs;
        }
    }

    private ShareIn() {}

    static boolean wants(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return false;
        String type = intent.getType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    /** True only for the app's own shared copies, whatever a path says. */
    static boolean owns(Context c, File file) {
        try {
            return file.getCanonicalPath().startsWith(dir(c).getCanonicalPath() + File.separator);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** A share: copied off the main thread, then asked about one recording at a time. */
    static void take(AppCompatActivity activity, Intent intent, Runnable openNewAnalysis) {
        if (!wants(intent)) return;
        List<Uri> streams = streams(intent);
        String type = intent.getType();
        // Taken once: a recreated activity or a later new intent doesn't share it again.
        intent.setAction(Intent.ACTION_MAIN);
        intent.removeExtra(Intent.EXTRA_STREAM);
        if (streams.isEmpty()) return;
        Context app = activity.getApplicationContext();
        IO.execute(() -> {
            cleanup(app);
            List<Shared> files = new ArrayList<>();
            for (Uri uri : streams) {
                Shared one = copy(app, uri, type);
                if (one != null) files.add(one);
            }
            List<ShareRules.Call> calls = calls(app);
            long now = System.currentTimeMillis();
            for (Shared one : files) one.call = ShareRules.match(one.durationMs, calls, now);
            int unreadable = streams.size() - files.size();
            activity.runOnUiThread(() -> {
                if (unreadable > 0) Toast.makeText(activity, "Sales X-Ray couldn't read a shared recording.", Toast.LENGTH_LONG).show();
                ask(activity, files, 0, new ArrayList<>(), openNewAnalysis);
            });
        });
    }

    /** New analysis's audio pick: the shared files waiting for it (once), else null. */
    static Uri[] takeAttach() {
        Uri[] waiting = attach;
        attach = null;
        if (waiting == null || System.currentTimeMillis() - attachAt > ATTACH_MS) return null;
        return waiting;
    }

    private static void ask(AppCompatActivity a, List<Shared> files, int at, List<Shared> unmatched, Runnable openNewAnalysis) {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (at >= files.size()) {
            if (unmatched.isEmpty()) return;
            String why = "No call in this phone's call history matches " + (unmatched.size() == 1 ? "this recording" : "these recordings") + ".";
            for (Shared one : unmatched) if (one.why != null) why = one.why;
            offerAttach(a, unmatched, openNewAnalysis, why);
            return;
        }
        Shared one = files.get(at);
        if (one.call == null) {
            unmatched.add(one);
            ask(a, files, at + 1, unmatched, openNewAnalysis);
            return;
        }
        new AlertDialog.Builder(a)
            .setTitle("Add this recording to your call?")
            .setMessage(ShareRules.label(one.call, System.currentTimeMillis(), TimeZone.getDefault())
                + "\n\nIt uploads in 60 seconds, with Undo, like your other lead calls.")
            .setPositiveButton("Add to this call", (shown, which) -> add(a, one, unmatched, () -> ask(a, files, at + 1, unmatched, openNewAnalysis)))
            .setNeutralButton("Attach to a lead", (shown, which) -> {
                unmatched.add(one);
                ask(a, files, at + 1, unmatched, openNewAnalysis);
            })
            .setNegativeButton("Cancel", (shown, which) -> {
                forget(one);
                ask(a, files, at + 1, unmatched, openNewAnalysis);
            })
            .setCancelable(false)
            .show();
    }

    private static void add(AppCompatActivity a, Shared one, List<Shared> unmatched, Runnable next) {
        Context app = a.getApplicationContext();
        IO.execute(() -> {
            String outcome;
            try {
                outcome = AutoCapture.shared(app, one.file, one.call, Math.max(1, one.durationMs / 1000));
            } catch (Exception offline) {
                outcome = null;
            }
            String result = outcome;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (AutoCapture.SHARED_OK.equals(result)) {
                    Toast.makeText(a, "Added. It uploads in 60 seconds.", Toast.LENGTH_LONG).show();
                    next.run();
                } else if (AutoCapture.SHARED_ADDED.equals(result)) {
                    forget(one);
                    Toast.makeText(a, "This call already has its recording.", Toast.LENGTH_LONG).show();
                    next.run();
                } else if (AutoCapture.SHARED_OFF.equals(result) || AutoCapture.SHARED_NO_LEAD.equals(result)
                    || CaptureRules.REFUSED_OTHER.equals(result)) {
                    one.why = AutoCapture.SHARED_OFF.equals(result)
                        ? "Automatic call uploads are off on this phone."
                        : AutoCapture.SHARED_NO_LEAD.equals(result)
                            ? "This call's number isn't one of your leads."
                            : "Sales X-Ray can't add this recording to that call.";
                    unmatched.add(one);
                    next.run();
                } else if (result == null) {
                    forget(one);
                    say(a, "Not added", "Sales X-Ray couldn't be reached. Share the recording again when you're online.", next);
                } else {
                    // Outside work hours or before the lead: the owner's rules, never uploaded.
                    forget(one);
                    String[] words = CaptureRules.refusedNotice(result, one.call.number);
                    say(a, "Not added", words != null ? words[1] : "Sales X-Ray can't add this recording to that call.", next);
                }
            });
        });
    }

    /** Attach to a lead: New analysis, whose next audio pick takes these files. */
    private static void offerAttach(AppCompatActivity a, List<Shared> files, Runnable openNewAnalysis, String why) {
        new AlertDialog.Builder(a)
            .setTitle("Attach to a lead")
            .setMessage(why + " Attach it to a lead in New analysis instead.")
            .setPositiveButton("Attach to a lead", (shown, which) -> {
                List<Uri> uris = new ArrayList<>();
                for (Shared one : files) {
                    try {
                        uris.add(FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", one.file));
                    } catch (IllegalArgumentException outside) {
                        // Not one of the app's copies: never offered.
                    }
                }
                if (uris.isEmpty()) return;
                attach = uris.toArray(new Uri[0]);
                attachAt = System.currentTimeMillis();
                openNewAnalysis.run();
                Toast.makeText(a, "Tap Add the recording: the shared file is chosen for you.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Cancel", (shown, which) -> {
                for (Shared one : files) forget(one);
            })
            .setCancelable(false)
            .show();
    }

    private static void say(AppCompatActivity a, String title, String body, Runnable next) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(body)
            .setPositiveButton("OK", (shown, which) -> next.run())
            .setCancelable(false)
            .show();
    }

    private static void forget(Shared one) {
        if (!one.file.delete()) one.file.deleteOnExit();
    }

    private static List<Uri> streams(Intent intent) {
        List<Uri> list = new ArrayList<>();
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> many = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (many != null) for (Uri uri : many) if (uri != null) list.add(uri);
            } else {
                Uri one = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) list.add(one);
            }
        } catch (RuntimeException unreadable) {
            // A malformed share: nothing to take.
        }
        // A share of the app's own files never comes back in (file:// is refused too).
        list.removeIf(uri -> !"content".equals(uri.getScheme()));
        return list;
    }

    /** The app's own copy, read while the share's permission lasts; null when unreadable or too large. */
    private static Shared copy(Context c, Uri uri, String type) {
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String mime;
        try {
            mime = c.getContentResolver().getType(uri);
        } catch (RuntimeException unknown) {
            mime = null;
        }
        File out = new File(dir, UUID.randomUUID() + "." + ShareRules.extension(name(c, uri), mime != null ? mime : type));
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream to = new FileOutputStream(out)) {
            if (in == null) throw new IOException("no_stream");
            byte[] buffer = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > CaptureRules.MAX_BYTES) throw new IOException("too_large");
                to.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException unreadable) {
            out.delete();
            return null;
        }
        long ms = duration(out);
        if (ms <= 0) {
            out.delete();
            return null;
        }
        return new Shared(out, ms);
    }

    private static String name(Context c, Uri uri) {
        try (Cursor cursor = c.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException unknown) {
            // No name: the type decides the extension.
        }
        return null;
    }

    private static long duration(File file) {
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            media.setDataSource(file.getAbsolutePath());
            String ms = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? 0 : Long.parseLong(ms);
        } catch (RuntimeException unsupported) {
            return 0;
        } finally {
            try {
                media.release();
            } catch (Exception ignored) {
                // Released or never opened.
            }
        }
    }

    /**
     * Answered calls of the last 3 days, from the call history. Read only where work-call logging
     * is on (CallLogSync, its disclosure); nothing of it leaves the phone but the matched call.
     */
    static List<ShareRules.Call> calls(Context c) {
        List<ShareRules.Call> list = new ArrayList<>();
        if (!CallLogSync.isOn(c)) return list;
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return list;
        long since = System.currentTimeMillis() - CaptureRules.KEEP_MS;
        try (Cursor cursor = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
            new String[] { CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION },
            CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.DURATION + " > 0", new String[] { String.valueOf(since) },
            CallLog.Calls.DATE + " DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                list.add(new ShareRules.Call(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3) * 1000));
            }
        } catch (RuntimeException unreadable) {
            // No history here: Attach to a lead.
        }
        return list;
    }

    /** Copies older than any recording waits (3 days) go; a job past that is expired anyway. */
    static void cleanup(Context c) {
        File[] old = dir(c).listFiles();
        if (old == null) return;
        long now = System.currentTimeMillis();
        for (File file : old) if (now - file.lastModified() > CaptureRules.KEEP_MS) file.delete();
    }
}
