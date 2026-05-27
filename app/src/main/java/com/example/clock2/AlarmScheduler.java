package com.example.clock2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Планирование будильников через {@link AlarmManager}: основной сигнал в момент T
 * и пред-сигнал (плавное нарастание звука) в T − 60 с.
 * Единая точка — используется из {@link AlarmActivity}, {@link AlarmReceiver}
 * и {@link BootReceiver}.
 */
public final class AlarmScheduler {

    /** Экстра-флаг: true у PendingIntent пред-сигнала. */
    public static final String KEY_PRE_ALARM = "pre_alarm";

    /** Смещение requestCode пред-сигнала, чтобы его PendingIntent не совпал с основным. */
    public static final int PRE_REQ_OFFSET = 1_000_000;

    /** За сколько до срабатывания запускается плавное нарастание. */
    public static final long PRE_ALARM_LEAD_MS = 60_000L;

    private static final String PREDISMISS_PREFIX    = "predismiss_";
    private static final long   PREDISMISS_WINDOW_MS = 90_000L;

    private AlarmScheduler() {}

    /**
     * Помечает, что будильник был выключен. Используется, чтобы основной сигнал
     * не зазвонил повторно, если будильник выключили во время фазы нарастания.
     */
    public static void markDismissed(Context ctx, int id) {
        if (id < 0) return;
        ctx.getSharedPreferences(AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(PREDISMISS_PREFIX + id, System.currentTimeMillis()).apply();
    }

    /** true, если будильник выключали в последние 90 с; метка при этом снимается. */
    public static boolean consumeRecentDismiss(Context ctx, int id) {
        if (id < 0) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(
                AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String key = PREDISMISS_PREFIX + id;
        long marker = prefs.getLong(key, 0L);
        if (marker == 0L) return false;
        prefs.edit().remove(key).apply();
        return System.currentTimeMillis() - marker < PREDISMISS_WINDOW_MS;
    }

    public static boolean isFadeEnabled(Context ctx) {
        return ctx.getSharedPreferences(AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AlarmActivity.KEY_FADE_IN, true);
    }

    /**
     * Планирует основной сигнал и (если включено нарастание и до срабатывания
     * ещё больше минуты) пред-сигнал.
     * @return false, если не выдано разрешение на точные будильники.
     */
    public static boolean schedule(Context ctx, int id, int hour, int minute,
                                   int[] repeatDays, String toneUri) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;

        // На Android 12+ setAlarmClock требует разрешения SCHEDULE_EXACT_ALARM,
        // которое пользователь может отозвать в настройках. Проверяем явно, не
        // полагаясь на SecurityException — система иначе тихо отклонит вызов.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            return false;
        }

        long triggerMillis = AlarmActivity.nextTriggerMillis(hour, minute, repeatDays);

        // setAlarmClock: сигнал доставляется даже при агрессивной оптимизации
        // батареи, система показывает иконку будильника.
        PendingIntent showIntent = PendingIntent.getActivity(
                ctx, id, new Intent(ctx, AlarmActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent mainPi = receiverPendingIntent(
                ctx, id, id, hour, minute, repeatDays, toneUri, false);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMillis, showIntent), mainPi);
        } catch (SecurityException e) {
            return false;
        }

        // Пред-сигнал: сначала снимаем прежний (вдруг нарастание выключили),
        // затем ставим заново, если включено и есть запас времени.
        cancelPre(ctx, am, id);
        if (isFadeEnabled(ctx)) {
            long preMillis = triggerMillis - PRE_ALARM_LEAD_MS;
            if (preMillis > System.currentTimeMillis()) {
                PendingIntent prePi = receiverPendingIntent(
                        ctx, PRE_REQ_OFFSET + id, id, hour, minute, repeatDays, toneUri, true);
                try {
                    am.setAlarmClock(
                            new AlarmManager.AlarmClockInfo(preMillis, showIntent), prePi);
                } catch (SecurityException ignored) {
                    // основной сигнал уже стоит — просто не будет нарастания
                }
            }
        }
        return true;
    }

    /** Отменяет основной сигнал и пред-сигнал будильника. */
    public static void cancel(Context ctx, int id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        cancelByRequestCode(ctx, am, id);
        cancelPre(ctx, am, id);
    }

    private static void cancelPre(Context ctx, AlarmManager am, int id) {
        cancelByRequestCode(ctx, am, PRE_REQ_OFFSET + id);
    }

    private static void cancelByRequestCode(Context ctx, AlarmManager am, int requestCode) {
        // Совпадение PendingIntent определяется по requestCode и компоненту —
        // экстры не важны, поэтому достаточно «пустого» Intent.
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, requestCode, new Intent(ctx, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
    }

    private static PendingIntent receiverPendingIntent(Context ctx, int requestCode, int alarmId,
            int hour, int minute, int[] repeatDays, String toneUri, boolean pre) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(AlarmActivity.KEY_ALARM_TONE_URI, toneUri);
        intent.putExtra(AlarmActivity.KEY_ALARM_ID,       alarmId);
        intent.putExtra(AlarmActivity.KEY_ALARM_HOUR,     hour);
        intent.putExtra(AlarmActivity.KEY_ALARM_MINUTE,   minute);
        intent.putExtra(AlarmActivity.KEY_REPEAT_DAYS,    repeatDays);
        intent.putExtra(KEY_PRE_ALARM,                    pre);
        return PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
