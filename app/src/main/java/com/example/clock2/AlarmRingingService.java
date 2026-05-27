package com.example.clock2;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;

public class AlarmRingingService extends Service {

    public static final String ACTION_START      = "com.example.clock2.action.START_ALARM";
    public static final String ACTION_START_FADE = "com.example.clock2.action.START_ALARM_FADE";
    public static final String ACTION_DISMISS    = "com.example.clock2.action.DISMISS_ALARM";

    private static final String CHANNEL_ID      = "alarm_ringing_channel";
    private static final int    NOTIFICATION_ID = 1107;

    // Плавное нарастание: 60 с от тихого старта до полной громкости.
    private static final long  FADE_DURATION_MS = 60_000L;
    private static final long  FADE_TICK_MS     = 1_000L;
    private static final float MIN_VOLUME       = 0.05f;

    private MediaPlayer          mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    private int     currentAlarmId = -1;
    private boolean fading;
    private long    fadeStartElapsed;
    @Nullable private Handler  fadeHandler;
    @Nullable private Runnable fadeRunnable;

    // -----------------------------------------------------------------------
    // Статические хелперы — единственный способ запустить/остановить сервис
    // -----------------------------------------------------------------------

    /** Запуск будильника на полной громкости (момент срабатывания). */
    public static void start(Context context, int alarmId, @Nullable String toneUri) {
        dispatch(context, ACTION_START, alarmId, toneUri);
    }

    /** Запуск фазы нарастания за минуту до будильника: тихий звук с разгоном. */
    public static void startFadeIn(Context context, int alarmId, @Nullable String toneUri) {
        dispatch(context, ACTION_START_FADE, alarmId, toneUri);
    }

    private static void dispatch(Context context, String action, int alarmId,
                                 @Nullable String toneUri) {
        Intent intent = new Intent(context, AlarmRingingService.class);
        intent.setAction(action);
        intent.putExtra(AlarmActivity.KEY_ALARM_ID, alarmId);
        intent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Останавливает сервис.
     * stopService() безопаснее startForegroundService(): не требует последующего
     * startForeground() и корректно работает даже если сервис уже остановлен.
     */
    public static void stop(Context context) {
        context.stopService(new Intent(context, AlarmRingingService.class));
    }

    // -----------------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_DISMISS.equals(action)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        int    alarmId = intent != null ? intent.getIntExtra(AlarmActivity.KEY_ALARM_ID, -1)      : -1;
        String toneUri = intent != null ? intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI) : null;
        currentAlarmId = alarmId;

        boolean preAlarm = ACTION_START_FADE.equals(action);

        Notification notification = createAlarmNotification(alarmId, toneUri, preAlarm);
        // На Android 13+ без POST_NOTIFICATIONS уведомление foreground-сервиса не покажется,
        // а в редких сценариях startForeground может бросить SecurityException — оборачиваем.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException | RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();

        if (preAlarm) {
            // Фаза нарастания: запускаем тихий звук с разгоном.
            startAlarmSound(toneUri, true);
        } else if (mediaPlayer != null && fading) {
            // Звук уже играет с нарастанием — момент T: мгновенно на полную громкость.
            stopFadeRamp();
            fading = false;
            try { mediaPlayer.setVolume(1f, 1f); } catch (IllegalStateException ignored) {}
        } else if (mediaPlayer == null) {
            // Нарастание было выключено/пропущено — играем сразу на полной громкости.
            startAlarmSound(toneUri, false);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFadeRamp();
        stopAlarmSound();
        releaseWakeLock();
        // Помечаем будильник выключенным: если выключение случилось во время
        // фазы нарастания, основной сигнал в момент T не зазвонит повторно.
        AlarmScheduler.markDismissed(this, currentAlarmId);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -----------------------------------------------------------------------
    // Уведомление
    // -----------------------------------------------------------------------

    private Notification createAlarmNotification(int alarmId, @Nullable String toneUri,
                                                 boolean preAlarm) {
        ensureNotificationChannel();

        Intent openAlarmIntent = new Intent(this, AlarmRingActivity.class);
        openAlarmIntent.putExtra(AlarmActivity.KEY_ALARM_ID, alarmId);
        openAlarmIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
        openAlarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Используем alarmId как request code, чтобы разные будильники не затирали друг друга
        int reqCode = alarmId >= 0 ? alarmId : 3001;
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, reqCode, openAlarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.alarm_notification_title))
                .setContentText(getString(preAlarm
                        ? R.string.alarm_prealarm_text
                        : R.string.alarm_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(contentPendingIntent);

        // В фазе нарастания НЕ задаём fullScreenIntent: иначе на заблокированном
        // экране окно с примером открылось бы за минуту до будильника.
        if (!preAlarm) {
            builder.setFullScreenIntent(contentPendingIntent, true);
        }
        return builder.build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.alarm_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null); // звук воспроизводит MediaPlayer, не уведомление
        manager.createNotificationChannel(channel);
    }

    // -----------------------------------------------------------------------
    // Звук будильника — исправлен race condition
    // -----------------------------------------------------------------------

    private void startAlarmSound(@Nullable String uriString, boolean fade) {
        Uri alarmUri = sanitizeToneUri(uriString);
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        }
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        stopAlarmSound();
        stopFadeRamp();
        fading = fade;

        // Сохраняем ссылку локально ДО асинхронного prepare.
        // В onPrepared проверяем, что player не был заменён stopAlarmSound() за это время.
        final MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;

        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setLooping(true);

        // OnError должен освобождать конкретно этот плеер, даже если в mediaPlayer уже лежит другой
        player.setOnErrorListener((mp, what, extra) -> {
            try { mp.reset(); } catch (IllegalStateException ignored) {}
            try { mp.release(); } catch (IllegalStateException ignored) {}
            if (mp == mediaPlayer) mediaPlayer = null;
            return true;
        });

        try {
            player.setDataSource(this, alarmUri);
            player.setOnPreparedListener(mp -> {
                // Защита от race condition: запускаем только если этот плеер всё ещё актуален
                if (mp != mediaPlayer || mp != player) return;
                try {
                    if (fading) {
                        mp.setVolume(MIN_VOLUME, MIN_VOLUME);
                        fadeStartElapsed = SystemClock.elapsedRealtime();
                        startFadeRamp();
                    } else {
                        mp.setVolume(1f, 1f);
                    }
                    mp.start();
                } catch (IllegalStateException ignored) {}
            });
            player.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            if (player == mediaPlayer) mediaPlayer = null;
            try { player.release(); } catch (IllegalStateException ignored) {}
        }
    }

    /**
     * Допускает только безопасные источники: content://, android.resource:// и file:// внутри
     * пакета приложения. Защищает от подмены SharedPreferences (например, через adb backup)
     * на URI с произвольной схемой (http/javascript/etc.), который мог бы быть передан в
     * MediaPlayer.setDataSource.
     */
    @Nullable
    private Uri sanitizeToneUri(@Nullable String uriString) {
        if (uriString == null || uriString.isEmpty()) return null;
        Uri uri;
        try {
            uri = Uri.parse(uriString);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        return (ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme))
                ? uri : null;
    }

    /**
     * true, если на API 33+ выдано разрешение POST_NOTIFICATIONS. На более старых версиях
     * разрешение не требуется — возвращаем true.
     */
    @SuppressWarnings("unused")
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void stopAlarmSound() {
        if (mediaPlayer != null) {
            try {
                // isPlaying() / stop() бросают IllegalStateException если плеер
                // в состоянии Error или Idle — release() вызываем в любом случае
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            } finally {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Плавное нарастание громкости
    // -----------------------------------------------------------------------

    private void startFadeRamp() {
        stopFadeRamp();
        fadeHandler = new Handler(Looper.getMainLooper());
        fadeRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer == null || !fading) return;
                long  elapsed = SystemClock.elapsedRealtime() - fadeStartElapsed;
                float frac    = Math.max(0f, Math.min(1f, elapsed / (float) FADE_DURATION_MS));
                float vol     = MIN_VOLUME + (1f - MIN_VOLUME) * frac;
                try { mediaPlayer.setVolume(vol, vol); } catch (IllegalStateException ignored) {}
                if (frac >= 1f) { fading = false; return; }
                if (fadeHandler != null) fadeHandler.postDelayed(this, FADE_TICK_MS);
            }
        };
        fadeHandler.post(fadeRunnable);
    }

    private void stopFadeRamp() {
        if (fadeHandler != null && fadeRunnable != null) {
            fadeHandler.removeCallbacks(fadeRunnable);
        }
    }

    // -----------------------------------------------------------------------
    // WakeLock — не даём ЦП уснуть пока играет будильник
    // -----------------------------------------------------------------------

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        if (wakeLock != null && wakeLock.isHeld()) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clock2:AlarmRinging");
        wakeLock.acquire(10 * 60 * 1000L); // авто-освобождение через 10 минут
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void stopSelfSafely() {
        stopFadeRamp();
        stopAlarmSound();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
