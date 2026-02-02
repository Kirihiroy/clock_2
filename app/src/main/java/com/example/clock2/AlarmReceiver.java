package com.example.clock2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.media.Ringtone;
import android.net.Uri;
import android.widget.Toast;

public class AlarmReceiver extends BroadcastReceiver {

    private static final String KEY_ALARM_TONE_URI = "alarm_tone_uri";

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri alarmUri = null;
        if (intent != null) {
            String uriString = intent.getStringExtra(KEY_ALARM_TONE_URI);
            if (uriString != null && !uriString.isEmpty()) {
                alarmUri = Uri.parse(uriString);
            }
        }

        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        }
        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        // Включаем звук будильника
        Ringtone manager = RingtoneManager.getRingtone(context, alarmUri);
        if (manager != null) {
            manager.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            manager.play();
        }

        Toast.makeText(context, "⏰ Будильник!", Toast.LENGTH_LONG).show();
    }
}
