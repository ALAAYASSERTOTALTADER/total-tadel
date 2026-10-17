package com.example.yacinestylebackup;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class NetworkUploadWorker extends Worker {
    public NetworkUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Intent i = new Intent(getApplicationContext(), UploadService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(getApplicationContext(), i);
            } else {
                getApplicationContext().startService(i);
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
