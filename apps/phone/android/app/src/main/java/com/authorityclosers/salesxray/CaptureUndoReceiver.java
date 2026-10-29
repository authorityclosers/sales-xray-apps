package com.authorityclosers.salesxray;
import android.content.*;
import androidx.work.WorkManager;
import org.json.JSONObject;
public class CaptureUndoReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id == null) return;
        synchronized (AutoCapture.class) { try {
            JSONObject job = AutoCapture.job(c, id);
            if (!"waiting".equals(job.optString("state"))) return;
            AutoCapture.save(c, job, "cancelled");
            WorkManager.getInstance(c).cancelUniqueWork("sx-capture:" + id);
            AutoCapture.notice(c, id, "Call upload cancelled", "The recording stays on your phone.", false);
        } catch (Exception ignored) { } }
    }
}
