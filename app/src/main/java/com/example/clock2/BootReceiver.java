package com.example.clock2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            return;
        }
        rescheduleAlarms(context);
    }

    private void rescheduleAlarms(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(AlarmActivity.KEY_ALARMS_JSON, "[]");

        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (JSONException ignored) {
            return; // Повреждённый массив — нечего перепланировать
        }

        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject obj = array.getJSONObject(i);
                if (!obj.optBoolean("enabled", true)) continue;

                int    id      = obj.optInt("id", -1);
                int    hour    = obj.optInt("hour", -1);
                int    minute  = obj.optInt("minute", -1);
                String toneUri = obj.optString("toneUri", "");

                // Пропускаем запись с некорректными обязательными полями
                if (id < 0 || hour < 0 || hour > 23 || minute < 0 || minute > 59) continue;

                JSONArray daysJson   = obj.optJSONArray("repeatDays");
                int[]     repeatDays = new int[daysJson != null ? daysJson.length() : 0];
                if (daysJson != null) {
                    for (int j = 0; j < daysJson.length(); j++) {
                        repeatDays[j] = daysJson.optInt(j, -1);
                    }
                }

                long triggerMillis = AlarmActivity.nextTriggerMillis(hour, minute, repeatDays);

                Intent alarmIntent = new Intent(context, AlarmReceiver.class);
                alarmIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
                alarmIntent.putExtra(AlarmActivity.KEY_ALARM_ID,       id);
                alarmIntent.putExtra(AlarmActivity.KEY_ALARM_HOUR,     hour);
                alarmIntent.putExtra(AlarmActivity.KEY_ALARM_MINUTE,   minute);
                alarmIntent.putExtra(AlarmActivity.KEY_REPEAT_DAYS,    repeatDays);

                PendingIntent triggerIntent = PendingIntent.getBroadcast(
                        context, id, alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                PendingIntent showIntent = PendingIntent.getActivity(
                        context, id, new Intent(context, AlarmActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                try {
                    alarmManager.setAlarmClock(
                            new AlarmManager.AlarmClockInfo(triggerMillis, showIntent),
                            triggerIntent);
                } catch (SecurityException ignored) {
                    // SCHEDULE_EXACT_ALARM не выдан — пропускаем этот будильник
                }
            } catch (JSONException ignored) {
                // Один битый объект — пропускаем, остальные перепланируем
            }
        }
    }
}
