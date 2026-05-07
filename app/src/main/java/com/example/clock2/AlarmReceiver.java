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

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String toneUri    = intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI);
        int    alarmId    = intent.getIntExtra(AlarmActivity.KEY_ALARM_ID, -1);
        int    hour       = intent.getIntExtra(AlarmActivity.KEY_ALARM_HOUR, 0);
        int    minute     = intent.getIntExtra(AlarmActivity.KEY_ALARM_MINUTE, 0);
        int[]  repeatDays = intent.getIntArrayExtra(AlarmActivity.KEY_REPEAT_DAYS);

        // Запускаем звонок будильника
        AlarmRingingService.start(context, alarmId, toneUri);

        if (repeatDays != null && repeatDays.length > 0) {
            // Повторяющийся будильник: сразу планируем следующее срабатывание
            rescheduleRepeating(context, intent, alarmId, hour, minute, repeatDays);
        } else {
            // Однократный будильник: помечаем как выключенный в SharedPreferences,
            // чтобы при открытии AlarmActivity карточка отображалась корректно
            markAlarmDisabled(context, alarmId);
        }
    }

    // -----------------------------------------------------------------------

    private void rescheduleRepeating(Context context, Intent originalIntent,
                                      int alarmId, int hour, int minute, int[] repeatDays) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        long nextMillis = AlarmActivity.nextTriggerMillis(hour, minute, repeatDays);

        // Создаём свежий Intent, а не переиспользуем полученный broadcast-intent:
        // фреймворк может его модифицировать, добавить служебные флаги,
        // не установить компонент — всё это ломает PendingIntent.
        String toneUri = originalIntent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI);
        Intent freshIntent = new Intent(context, AlarmReceiver.class);
        freshIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
        freshIntent.putExtra(AlarmActivity.KEY_ALARM_ID,       alarmId);
        freshIntent.putExtra(AlarmActivity.KEY_ALARM_HOUR,     hour);
        freshIntent.putExtra(AlarmActivity.KEY_ALARM_MINUTE,   minute);
        freshIntent.putExtra(AlarmActivity.KEY_REPEAT_DAYS,    repeatDays);

        PendingIntent triggerIntent = PendingIntent.getBroadcast(
                context, alarmId, freshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent showIntent = PendingIntent.getActivity(
                context, alarmId, new Intent(context, AlarmActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(nextMillis, showIntent),
                    triggerIntent);
        } catch (SecurityException ignored) {
            // SCHEDULE_EXACT_ALARM не выдан — перепланировать не удастся
        }
    }

    private void markAlarmDisabled(Context context, int alarmId) {
        if (alarmId < 0) return;
        SharedPreferences prefs = context.getSharedPreferences(
                AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(AlarmActivity.KEY_ALARMS_JSON, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (obj.getInt("id") == alarmId) {
                    obj.put("enabled", false);
                    break;
                }
            }
            prefs.edit()
                    .putString(AlarmActivity.KEY_ALARMS_JSON, array.toString())
                    .apply();
        } catch (JSONException ignored) {}
    }
}
