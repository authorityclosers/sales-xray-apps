package com.authorityclosers.salesxray;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Every 15 minutes while call logging is on: looks at the call history (CallLogSync). */
public class CallLogWorker extends Worker {

    public CallLogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        CallLogSync.run(getApplicationContext());
        return Result.success();
    }
}
