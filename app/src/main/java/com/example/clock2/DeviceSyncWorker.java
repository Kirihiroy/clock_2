package com.example.clock2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновая синхронизация с устройством CatClock через WorkManager.
 * Без входных данных — отправляет время и список будильников; с ключом
 * {@link #KEY_COMMAND} — отправляет команду (например, "stop").
 */
public class DeviceSyncWorker extends Worker {

    public static final String KEY_COMMAND = "command";

    private static final String PERIODIC_WORK = "catclock_periodic_sync";
    private static final String ONESHOT_WORK  = "catclock_oneshot_sync";
    private static final String COMMAND_WORK  = "catclock_command";

    private static final long SYNC_PERIOD_HOURS = 1L;
    private static final long AWAIT_TIMEOUT_SEC = 30L;

    public DeviceSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        CatClockBleManager mgr = CatClockBleManager.get(ctx);

        // Нет привязанного устройства или не выдано разрешение Bluetooth —
        // повторять бессмысленно, завершаем успешно.
        if (!mgr.hasPairedDevice() || !mgr.hasConnectPermission()) {
            return Result.success();
        }

        String command = getInputData().getString(KEY_COMMAND);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        CatClockBleManager.Callback cb = new CatClockBleManager.Callback() {
            @Override public void onSuccess() { ok.set(true); latch.countDown(); }
            @Override public void onError(String message) { latch.countDown(); }
        };

        // Методы менеджера @MainThread — выполняем их на главном потоке.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (command != null) mgr.sendCommand(command, cb);
            else                 mgr.connectAndSyncAll(cb);
        });

        try {
            latch.await(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get() ? Result.success() : Result.retry();
    }

    // ---------- планирование ----------

    /** Периодическая фоновая синхронизация (раз в час). Идемпотентно. */
    public static void schedulePeriodic(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                DeviceSyncWorker.class, SYNC_PERIOD_HOURS, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void cancelPeriodic(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC_WORK);
    }

    /** Разовая синхронизация как можно скорее (например, после загрузки телефона). */
    public static void requestOneShot(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DeviceSyncWorker.class).build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONESHOT_WORK, ExistingWorkPolicy.REPLACE, req);
    }

    /** Разовая отправка команды устройству ("stop" и т.п.) с гарантией доставки. */
    public static void requestCommand(Context ctx, String command) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DeviceSyncWorker.class)
                .setInputData(new Data.Builder().putString(KEY_COMMAND, command).build())
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                COMMAND_WORK + "_" + command, ExistingWorkPolicy.REPLACE, req);
    }
}
