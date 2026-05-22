package com.example.clock2;

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

        // После загрузки телефона устройство CatClock потеряло время — ставим
        // разовую синхронизацию и (пере)запускаем периодическую.
        if (CatClockBleManager.get(context).hasPairedDevice()) {
            DeviceSyncWorker.requestOneShot(context);
            DeviceSyncWorker.schedulePeriodic(context);
        }
    }

    private void rescheduleAlarms(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(AlarmActivity.KEY_ALARMS_JSON, "[]");

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

                // Планирует основной сигнал и пред-сигнал (плавное нарастание).
                AlarmScheduler.schedule(context, id, hour, minute, repeatDays, toneUri);
            } catch (JSONException ignored) {
                // Один битый объект — пропускаем, остальные перепланируем
            }
        }
    }
}
