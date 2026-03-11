package com.example.clock2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent alarmIntent = new Intent(context, AlarmRingActivity.class);
        if (intent != null) {
            alarmIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI,
                    intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI));
        }
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(alarmIntent);
    }
}
