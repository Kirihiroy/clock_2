package com.example.clock2;

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

        String  toneUri    = intent.getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI);
        int     alarmId    = intent.getIntExtra(AlarmActivity.KEY_ALARM_ID, -1);
        int     hour       = intent.getIntExtra(AlarmActivity.KEY_ALARM_HOUR, 0);
        int     minute     = intent.getIntExtra(AlarmActivity.KEY_ALARM_MINUTE, 0);
        int[]   repeatDays = intent.getIntArrayExtra(AlarmActivity.KEY_REPEAT_DAYS);
        boolean isPre      = intent.getBooleanExtra(AlarmScheduler.KEY_PRE_ALARM, false);

        // Пред-сигнал: за минуту до будильника — запускаем тихий звук с плавным
        // нарастанием. Экран с примером не показываем и повтор не планируем —
        // это сделает основной сигнал в момент T.
        if (isPre) {
            AlarmRingingService.startFadeIn(context, alarmId, toneUri);
            return;
        }

        // Основной сигнал. Если будильник уже выключили во время фазы нарастания,
        // повторно не звоним — но повтор/однократность обрабатываем как обычно.
        if (!AlarmScheduler.consumeRecentDismiss(context, alarmId)) {
            AlarmRingingService.start(context, alarmId, toneUri);

            // Прямой запуск экрана будильника — надёжнее fullScreenIntent,
            // который может не сработать если экран включён или нет разрешения
            // USE_FULL_SCREEN_INTENT. setAlarmClock() даёт право запускать
            // активити из фона.
            Intent ringIntent = new Intent(context, AlarmRingActivity.class);
            ringIntent.putExtra(AlarmActivity.KEY_ALARM_ID, alarmId);
            ringIntent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
            ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(ringIntent);
        }

        if (repeatDays != null && repeatDays.length > 0) {
            // Повторяющийся будильник: планируем следующее срабатывание
            // (основной сигнал + пред-сигнал).
            AlarmScheduler.schedule(context, alarmId, hour, minute, repeatDays, toneUri);
        } else {
            // Однократный будильник: помечаем как выключенный в SharedPreferences,
            // чтобы при открытии AlarmActivity карточка отображалась корректно.
            markAlarmDisabled(context, alarmId);
        }
    }

    // -----------------------------------------------------------------------

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
