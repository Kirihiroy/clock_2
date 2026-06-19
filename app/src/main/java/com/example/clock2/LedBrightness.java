package com.example.clock2;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Единая формула яркости подсветки CatClock. Используется и для немедленной
 * отправки из {@link SettingsActivity} / {@link MainActivity}, и для фонового
 * пересчёта в {@link DeviceSyncWorker}.
 *
 * Логика:
 *  • подсветка выключена пользователем → 0
 *  • светлая тема → 0 (днём подсветка не нужна, ночью пользователь и так в светлой теме)
 *  • тёмная тема → значение зависит от часа в домашнем часовом поясе:
 *      00:00 – 06:00  ночь, спим      → 25  (~10%)
 *      06:00 – 09:00  мягкое утро     → 150 (~60%)
 *      09:00 – 18:00  день            → 0   (свет не нужен)
 *      18:00 – 22:00  вечер           → 255 (полная)
 *      22:00 – 24:00  готовимся ко сну → 80  (~30%)
 */
public final class LedBrightness {

    public static final String KEY_LED_ENABLED = "led_enabled";

    private LedBrightness() {}

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LED_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LED_ENABLED, enabled).apply();
    }

    /** Возвращает целевую яркость 0..255 для текущего момента и текущих настроек. */
    public static int compute(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);

        if (!prefs.getBoolean(KEY_LED_ENABLED, true)) return 0;
        if (!prefs.getBoolean(MainActivity.KEY_DARK_MODE, false)) return 0;

        String tzId = prefs.getString(MainActivity.KEY_TIMEZONE, TimeZone.getDefault().getID());
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(tzId));
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return brightnessForHour(hour);
    }

    static int brightnessForHour(int hour) {
        if (hour < 6)  return 25;    // 00-06 ночь
        if (hour < 9)  return 150;   // 06-09 утро
        if (hour < 18) return 0;     // 09-18 день
        if (hour < 22) return 255;   // 18-22 вечер
        return 80;                   // 22-24 переход ко сну
    }
}
