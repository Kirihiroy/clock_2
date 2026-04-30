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

import java.io.IOException;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AlarmRingingService extends Service {

    public static final String ACTION_START = "com.example.clock2.action.START_ALARM";
    public static final String ACTION_DISMISS = "com.example.clock2.action.DISMISS_ALARM";

    private static final String CHANNEL_ID = "alarm_ringing_channel";
    private static final int NOTIFICATION_ID = 1107;

    private MediaPlayer mediaPlayer;

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

    public static void stop(Context context) {
        Intent intent = new Intent(context, AlarmRingingService.class);
        intent.setAction(ACTION_DISMISS);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_DISMISS.equals(action)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        int alarmId = intent != null ? intent.getIntExtra(AlarmActivity.KEY_ALARM_ID, -1) : -1;
        String toneUri = intent != null ? intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI) : null;

        Notification notification = createAlarmNotification(alarmId, toneUri);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        startAlarmSound(toneUri);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAlarmSound();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createAlarmNotification(int alarmId, @Nullable String toneUri) {
        ensureNotificationChannel();

        Intent openAlarmIntent = new Intent(this, AlarmRingActivity.class);
        openAlarmIntent.putExtra(AlarmActivity.KEY_ALARM_ID, alarmId);
        openAlarmIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
        openAlarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                3001,
                openAlarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.alarm_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null); // звук воспроизводит MediaPlayer, не нотификация
        manager.createNotificationChannel(channel);
    }

    private void startAlarmSound(@Nullable String uriString) {
        Uri alarmUri = (uriString == null || uriString.isEmpty())
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                : Uri.parse(uriString);
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        stopAlarmSound();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mediaPlayer.setLooping(true);
        try {
            mediaPlayer.setDataSource(this, alarmUri);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void stopAlarmSound() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void stopSelfSafely() {
        stopAlarmSound();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
