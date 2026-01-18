package com.example.clock2;


import android.content.BroadcastReceiver;
import android.content. Context;
import android.content. Intent;
import android.media.RingtoneManager;
import android.media. Ringtone;
import android. widget.Toast;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // Включаем звук будильника
        Ringtone manager = RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        manager.play();

        Toast.makeText(context, "⏰ Будильник!", Toast.LENGTH_LONG).show();
    }
}