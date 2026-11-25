package com.authorityclosers.salesxray;
import android.app.NotificationManager;
import android.content.*;

/**
 * "Upload anyway" on a "not uploaded" notice (Mac, 25 Nov 2026 19:20: "Any 'not uploaded' notice gets an 'Upload
 * anyway' action (one tap, same consent)"). The recording the refusal kept becomes an upload job at once
 * (AutoCapture.uploadAnyway), under the seller's same upload terms; a call the server still calls outside work hours
 * goes on its lead as the web's "Assign to a lead" puts it. Until 1.0.159 this only forgot the refusal and looked
 * again, and the server refused the same call again: nothing was uploaded (Mac, 26 Nov 2026 02:04, Redmi 13).
 * A refusal from an older build kept no recording: that one is still looked for again.
 */
public class CaptureAgainReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id == null) return;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(("refused:" + id).hashCode());
        AutoCapture.log(id, "upload anyway");
        if (AutoCapture.prefs(c).getBoolean("on", false) && AutoCapture.uploadAnyway(c, id, CaptureUploadWorker.class)) return;
        // The seller's tap stands: when the look meets this call's refusal again, it uploads it (AutoCapture.refused).
        AutoCapture.prefs(c).edit().remove("refused:" + id).putLong("asked:" + id, System.currentTimeMillis()).commit();
        AutoCapture.now(c);
    }
}
