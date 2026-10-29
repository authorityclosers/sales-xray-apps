package com.authorityclosers.salesxray;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
public class CaptureWatchWorker extends Worker {
    public CaptureWatchWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    @NonNull public Result doWork() {
        if (!AutoCapture.prefs(getApplicationContext()).getBoolean("on", false)) return Result.success();
        try { AutoCapture.scan(getApplicationContext()); return Result.success(); }
        catch (CaptureApi.Failure e) {
            if (e.code == 401 || e.code == 403) AutoCapture.stop(getApplicationContext());
            return e.code >= 500 || e.code == 429 ? Result.retry() : Result.success();
        }
        catch (Exception e) { return Result.retry(); }
    }
}
