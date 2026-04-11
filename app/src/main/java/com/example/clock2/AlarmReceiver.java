package com.example.clock2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int alarmId = intent != null ? intent.getIntExtra(AlarmActivity.KEY_ALARM_ID, -1) : -1;
        String toneUri = intent != null ? intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI) : null;
        AlarmRingingService.start(context, alarmId, toneUri);
    }
}
