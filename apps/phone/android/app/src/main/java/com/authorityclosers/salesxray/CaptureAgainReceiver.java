package com.authorityclosers.salesxray;
import android.app.NotificationManager;
import android.content.*;

/**
 * "Upload anyway" on a "not uploaded" notice (Mac, 25 Nov 2026 19:20: "Any 'not uploaded' notice gets an 'Upload
 * anyway' action (one tap, same consent)"). The phone forgets that the server refused this recording and looks again
 * at once; the server decides again under the seller's same upload terms (since v1.99.1 a lead call at any hour is
 * taken). Nothing is sent from here: the look makes the same job, 60-second Undo and upload as any other call.
 */
public class CaptureAgainReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id == null) return;
        AutoCapture.prefs(c).edit().remove("refused:" + id).commit();
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(("refused:" + id).hashCode());
        AutoCapture.log(id, "upload anyway");
        AutoCapture.now(c);
    }
}
