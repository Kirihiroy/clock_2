package com.example.clock2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

public class AlarmRingingService extends Service {

    public static final String ACTION_START   = "com.example.clock2.action.START_ALARM";
    public static final String ACTION_DISMISS = "com.example.clock2.action.DISMISS_ALARM";

    private static final String CHANNEL_ID      = "alarm_ringing_channel";
    private static final int    NOTIFICATION_ID = 1107;

    private MediaPlayer          mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    // -----------------------------------------------------------------------
    // Статические хелперы — единственный способ запустить/остановить сервис
    // -----------------------------------------------------------------------

    public static void start(Context context, int alarmId, @Nullable String toneUri) {
        Intent intent = new Intent(context, AlarmRingingService.class);
        intent.setAction(ACTION_START);
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

        int    alarmId = intent != null ? intent.getIntExtra(AlarmActivity.KEY_ALARM_ID, -1)          : -1;
        String toneUri = intent != null ? intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI) : null;

        Notification notification = createAlarmNotification(alarmId, toneUri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        acquireWakeLock();
        startAlarmSound(toneUri);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAlarmSound();
        releaseWakeLock();
        // Будильник на телефоне остановлен — гасим звонок и на устройстве CatClock.
        // Через WorkManager: доставка гарантирована, даже если процесс будет убит.
        if (CatClockBleManager.get(this).hasPairedDevice()) {
            DeviceSyncWorker.requestCommand(getApplicationContext(), "stop");
        }
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

    private Notification createAlarmNotification(int alarmId, @Nullable String toneUri) {
        ensureNotificationChannel();

        Intent openAlarmIntent = new Intent(this, AlarmRingActivity.class);
        openAlarmIntent.putExtra(AlarmActivity.KEY_ALARM_ID, alarmId);
        openAlarmIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
        openAlarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Используем alarmId как request code, чтобы разные будильники не затирали друг друга
        int reqCode = alarmId >= 0 ? alarmId : 3001;
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this, reqCode, openAlarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.alarm_notification_title))
                .setContentText(getString(R.string.alarm_notification_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .build();
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

    private void startAlarmSound(@Nullable String uriString) {
        Uri alarmUri = (uriString == null || uriString.isEmpty())
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                : Uri.parse(uriString);
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        stopAlarmSound();

        // Сохраняем ссылку локально ДО асинхронного prepare.
        // В onPrepared проверяем, что player не был заменён stopAlarmSound() за это время.
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;

        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setLooping(true);

        try {
            player.setDataSource(this, alarmUri);
            player.setOnPreparedListener(mp -> {
                // Защита от race condition: запускаем только если это актуальный плеер
                if (mp == mediaPlayer) {
                    mp.start();
                }
            });
            player.prepareAsync();
        } catch (IOException e) {
            if (player == mediaPlayer) mediaPlayer = null;
            player.release();
        }
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
        stopAlarmSound();
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
