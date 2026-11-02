package com.authorityclosers.salesxray;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
public class CaptureWatchWorker extends Worker {
    public CaptureWatchWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    @NonNull public Result doWork() {
        Context c = getApplicationContext();
        if (!AutoCapture.prefs(c).getBoolean("on", false)) return Result.success();
        CaptureNet.begin();
        try {
            if (!CaptureNet.await(c, this)) return Result.retry();
            AutoCapture.scan(c); return Result.success();
        }
        catch (CaptureApi.Failure e) {
            // Signed out or refused: uploads stay on, the seller is told, and the next look resumes them.
            String reason = AutoCapture.explain(null, e);
            if (CaptureRules.RETRY.equals(reason)) return Result.retry();
            AutoCapture.pause(c, reason);
            return Result.success();
        }
        catch (Exception e) { return Result.retry(); }
        finally { CaptureNet.end(); }
    }
}
