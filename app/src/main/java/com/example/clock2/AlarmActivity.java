package com.example.clock2;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class AlarmActivity extends AppCompatActivity {

    public static final String PREFS_NAME        = "alarm_prefs";
    public static final String KEY_ALARM_TONE_URI = "alarm_tone_uri";
    public static final String KEY_ALARM_ID       = "alarm_id";
    public static final String KEY_ALARM_HOUR     = "alarm_hour";
    public static final String KEY_ALARM_MINUTE   = "alarm_minute";
    public static final String KEY_REPEAT_DAYS    = "repeat_days";
    public static final String KEY_DIFFICULTY     = "puzzle_difficulty";
    public static final String KEY_PUZZLE_COUNT   = "puzzle_count";
    public static final String KEY_FADE_IN        = "fade_in_enabled";
    public static final String KEY_ALARMS_JSON    = "alarms_json";

    private static final int REQ_POST_NOTIFICATIONS = 1108;

    // Порядок отображения дней (Пн…Вс). Значения — константы Calendar.DAY_OF_WEEK.
    static final int[]    DAY_ORDER  = {
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    };
    private static final String[] DAY_LABELS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    // IDs кнопок дней в dialog_add_alarm.xml — порядок совпадает с DAY_ORDER
    private static final int[] DAY_VIEW_IDS = {
        R.id.tv_day_mon, R.id.tv_day_tue, R.id.tv_day_wed, R.id.tv_day_thu,
        R.id.tv_day_fri, R.id.tv_day_sat, R.id.tv_day_sun
    };

    private TextView     alarmStatusText;
    private LinearLayout alarmListLayout;

    private final List<AlarmItem> alarms = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Публичный статический метод — используется также AlarmReceiver и BootReceiver
    // -----------------------------------------------------------------------

    /**
     * Вычисляет ближайшее время срабатывания.
     * repeatDays == null или пустой массив → однократный будильник.
     */
    public static long nextTriggerMillis(int hour, int minute, int[] repeatDays) {
        Calendar trigger = Calendar.getInstance();
        trigger.set(Calendar.HOUR_OF_DAY, hour);
        trigger.set(Calendar.MINUTE,      minute);
        trigger.set(Calendar.SECOND,      0);
        trigger.set(Calendar.MILLISECOND, 0);

        long now = System.currentTimeMillis();

        if (repeatDays == null || repeatDays.length == 0) {
            // Однократный: если время уже прошло — завтра
            if (trigger.getTimeInMillis() <= now) {
                trigger.add(Calendar.DAY_OF_MONTH, 1);
            }
            return trigger.getTimeInMillis();
        }

        // Повтор: ищем ближайший подходящий день (максимум 7 итераций)
        Set<Integer> days = new HashSet<>();
        for (int d : repeatDays) days.add(d);

        for (int i = 0; i < 7; i++) {
            if (days.contains(trigger.get(Calendar.DAY_OF_WEEK))
                    && trigger.getTimeInMillis() > now) {
                return trigger.getTimeInMillis();
            }
            trigger.add(Calendar.DAY_OF_MONTH, 1);
        }
        return trigger.getTimeInMillis(); // не должны сюда попасть при непустом repeatDays
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        ensureNotificationPermission();
        ensureBatteryOptimizationExempt();
        ensureFullScreenIntentPermission();

        FloatingActionButton addAlarmButton = findViewById(R.id.fab_add_alarm);
        alarmStatusText = findViewById(R.id.tv_alarm_status);
        alarmListLayout = findViewById(R.id.layout_alarm_list);

        View worldTimeNav = findViewById(R.id.nav_world_time);
        View settingsNav  = findViewById(R.id.nav_settings);

        loadAlarms();
        renderAlarmCards();

        addAlarmButton.setOnClickListener(v -> showAddAlarmDialog());
        worldTimeNav.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        settingsNav.setOnClickListener(v  -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    // -----------------------------------------------------------------------
    // Разрешения и системные диалоги
    // -----------------------------------------------------------------------

    private void ensureBatteryOptimizationExempt() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
                .setMessage(R.string.battery_opt_message)
                .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.canUseFullScreenIntent()) return;
        new AlertDialog.Builder(this)
                .setMessage(R.string.full_screen_intent_message)
                .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(
                        new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS
                && (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    // -----------------------------------------------------------------------
    // Диалог добавления будильника
    // -----------------------------------------------------------------------

    private void showAddAlarmDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_alarm, null, false);

        TextView     cancelButton  = dialogView.findViewById(R.id.tv_cancel_add_alarm);
        TextView     doneButton    = dialogView.findViewById(R.id.tv_done_add_alarm);
        TextView     hintText      = dialogView.findViewById(R.id.tv_ring_hint);
        NumberPicker hourPicker    = dialogView.findViewById(R.id.np_hours);
        NumberPicker minutePicker  = dialogView.findViewById(R.id.np_minutes);

        // NumberPicker
        Calendar now = Calendar.getInstance();
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setValue(now.get(Calendar.HOUR_OF_DAY));
        hourPicker.setFormatter(v -> String.format(Locale.getDefault(), "%02d ч", v));

        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setValue(now.get(Calendar.MINUTE));
        minutePicker.setFormatter(v -> String.format(Locale.getDefault(), "%02d мин.", v));

        // Кнопки дней недели
        Set<Integer> selectedDays = new TreeSet<>();
        Map<Integer, TextView> dayViews = new LinkedHashMap<>();

        for (int i = 0; i < DAY_ORDER.length; i++) {
            TextView tv  = dialogView.findViewById(DAY_VIEW_IDS[i]);
            int      day = DAY_ORDER[i];
            dayViews.put(day, tv);
            applyDayButtonStyle(tv, false);
            tv.setOnClickListener(v -> {
                boolean nowSelected = !selectedDays.contains(day);
                if (nowSelected) selectedDays.add(day); else selectedDays.remove(day);
                applyDayButtonStyle(tv, nowSelected);
                hintText.setText(formatRepeatLabel(selectedDays));
            });
        }
        hintText.setText(formatRepeatLabel(selectedDays));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        doneButton.setOnClickListener(v -> {
            addNewAlarm(hourPicker.getValue(), minutePicker.getValue(), selectedDays);
            dialog.dismiss();
        });
        dialog.show();
    }

    /** Визуальное состояние кнопки дня: выбранный — синий круг, нет — серый. */
    private void applyDayButtonStyle(TextView view, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(selected ? 0xFF4DA3FF : 0xFF555555);
        view.setBackground(bg);
        view.setTextColor(Color.WHITE);
    }

    // -----------------------------------------------------------------------
    // CRUD будильников
    // -----------------------------------------------------------------------

    private void addNewAlarm(int hour, int minute, Set<Integer> repeatDays) {
        String toneUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ALARM_TONE_URI, "");

        AlarmItem item = new AlarmItem();
        item.id         = generateAlarmId();
        item.hour       = hour;
        item.minute     = minute;
        item.toneUri    = toneUri;
        item.enabled    = true;
        item.repeatDays = new TreeSet<>(repeatDays);

        alarms.add(0, item);
        scheduleAlarm(item);
        saveAlarms();
        renderAlarmCards();

        Toast.makeText(this,
                getString(R.string.alarm_set_message, hour, minute),
                Toast.LENGTH_SHORT).show();
    }

    private void deleteAlarm(AlarmItem item) {
        cancelAlarm(item);
        alarms.remove(item);
        saveAlarms();
        renderAlarmCards();
        Toast.makeText(this,
                getString(R.string.alarm_deleted_message, formatTime(item.hour, item.minute)),
                Toast.LENGTH_SHORT).show();
    }

    // -----------------------------------------------------------------------
    // Планирование через AlarmManager
    // -----------------------------------------------------------------------

    private void scheduleAlarm(AlarmItem item) {
        boolean scheduled = AlarmScheduler.schedule(this, item.id, item.hour, item.minute,
                item.repeatDaysToIntArray(), item.toneUri);
        if (!scheduled) {
            Toast.makeText(this, R.string.request_exact_alarm_permission, Toast.LENGTH_LONG).show();
        }
    }

    private void cancelAlarm(AlarmItem item) {
        AlarmScheduler.cancel(this, item.id);
    }

    // -----------------------------------------------------------------------
    // Отрисовка карточек
    // -----------------------------------------------------------------------

    private void renderAlarmCards() {
        alarmListLayout.removeAllViews();

        int activeCount = 0;
        LayoutInflater inflater = LayoutInflater.from(this);

        for (AlarmItem item : alarms) {
            if (item.enabled) activeCount++;

            MaterialCardView card = (MaterialCardView) inflater.inflate(
                    R.layout.item_alarm_card, alarmListLayout, false);

            TextView     timeText   = card.findViewById(R.id.tv_alarm_time);
            TextView     stateText  = card.findViewById(R.id.tv_alarm_state);
            TextView     repeatText = card.findViewById(R.id.tv_repeat_days);
            SwitchMaterial alarmSwitch = card.findViewById(R.id.switch_alarm);
            ImageButton  deleteButton  = card.findViewById(R.id.btn_delete_alarm);

            timeText.setText(String.format(Locale.getDefault(), "%02d:%02d", item.hour, item.minute));
            alarmSwitch.setChecked(item.enabled);
            repeatText.setText(formatRepeatLabel(item.repeatDays));

            updateCardVisualState(card, timeText, stateText, item.enabled);

            alarmSwitch.setClickable(false);
            alarmSwitch.setFocusable(false);
            card.setOnClickListener(v ->
                    toggleAlarm(item, card, timeText, stateText, alarmSwitch));
            deleteButton.setOnClickListener(v -> deleteAlarm(item));

            alarmListLayout.addView(card);
        }

        if (alarms.isEmpty()) {
            alarmStatusText.setText(R.string.no_alarms_added);
        } else if (activeCount == 0) {
            alarmStatusText.setText(R.string.no_active_alarms);
        } else {
            alarmStatusText.setText(getString(R.string.active_alarms_count, activeCount));
        }
    }

    private void updateCardVisualState(MaterialCardView card,
                                        TextView timeText,
                                        TextView stateText,
                                        boolean enabled) {
        card.setCardBackgroundColor(ContextCompat.getColor(this,
                enabled ? R.color.alarm_card_enabled_bg : R.color.alarm_card_disabled_bg));
        timeText.setTextColor(ContextCompat.getColor(this,
                enabled ? R.color.alarm_card_enabled_text : R.color.alarm_card_disabled_text));
        stateText.setTextColor(ContextCompat.getColor(this,
                enabled ? R.color.alarm_card_enabled_secondary : R.color.alarm_card_disabled_secondary));
        stateText.setText(enabled ? R.string.alarm_enabled : R.string.alarm_disabled);
    }

    private void toggleAlarm(AlarmItem item, MaterialCardView card,
                              TextView timeText, TextView stateText, SwitchMaterial alarmSwitch) {
        applyAlarmState(item, !item.enabled, card, timeText, stateText, alarmSwitch);
    }

    private void applyAlarmState(AlarmItem item, boolean enabled, MaterialCardView card,
                                  TextView timeText, TextView stateText, SwitchMaterial alarmSwitch) {
        item.enabled = enabled;
        alarmSwitch.setChecked(enabled);

        if (enabled) {
            scheduleAlarm(item);
            Toast.makeText(this,
                    getString(R.string.alarm_enabled_message, formatTime(item.hour, item.minute)),
                    Toast.LENGTH_SHORT).show();
        } else {
            cancelAlarm(item);
            Toast.makeText(this,
                    getString(R.string.alarm_disabled_message, formatTime(item.hour, item.minute)),
                    Toast.LENGTH_SHORT).show();
        }

        updateCardVisualState(card, timeText, stateText, enabled);
        saveAlarms();
        // renderAlarmCards() здесь не нужен: updateCardVisualState уже обновил конкретную карточку
    }

    // -----------------------------------------------------------------------
    // Вспомогательные методы
    // -----------------------------------------------------------------------

    /** Формирует читаемую метку повтора для карточки и подсказки в диалоге. */
    String formatRepeatLabel(Set<Integer> repeatDays) {
        if (repeatDays.isEmpty()) {
            return getString(R.string.repeat_once);
        }
        if (repeatDays.size() == 7) {
            return getString(R.string.repeat_every_day);
        }
        if (repeatDays.size() == 5
                && !repeatDays.contains(Calendar.SATURDAY)
                && !repeatDays.contains(Calendar.SUNDAY)) {
            return getString(R.string.repeat_weekdays);
        }
        if (repeatDays.size() == 2
                && repeatDays.contains(Calendar.SATURDAY)
                && repeatDays.contains(Calendar.SUNDAY)) {
            return getString(R.string.repeat_weekends);
        }
        // Перечисление отдельных дней в порядке Пн…Вс
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DAY_ORDER.length; i++) {
            if (repeatDays.contains(DAY_ORDER[i])) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(DAY_LABELS[i]);
            }
        }
        return sb.toString();
    }

    private String formatTime(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private int generateAlarmId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int nextId = prefs.getInt("next_alarm_id", 1);
        prefs.edit().putInt("next_alarm_id", nextId + 1).apply();
        return nextId;
    }

    // -----------------------------------------------------------------------
    // Сохранение / загрузка (SharedPreferences + JSON)
    // -----------------------------------------------------------------------

    private void loadAlarms() {
        alarms.clear();
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ALARMS_JSON, "[]");
        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (JSONException e) {
            return; // корневой массив повреждён — нечего загружать
        }

        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject obj  = array.getJSONObject(i);
                AlarmItem  item = new AlarmItem();
                item.id      = obj.getInt("id");
                // Валидируем диапазон: Calendar.set() принимает любое int,
                // но значения вне [0..23]/[0..59] дают непредсказуемое поведение
                item.hour    = Math.max(0, Math.min(23, obj.getInt("hour")));
                item.minute  = Math.max(0, Math.min(59, obj.getInt("minute")));
                item.toneUri = obj.optString("toneUri", "");
                item.enabled = obj.optBoolean("enabled", true);

                JSONArray daysJson = obj.optJSONArray("repeatDays");
                if (daysJson != null) {
                    for (int j = 0; j < daysJson.length(); j++) {
                        int day = daysJson.getInt(j);
                        // Принимаем только корректные константы Calendar.DAY_OF_WEEK (1–7)
                        if (day >= Calendar.SUNDAY && day <= Calendar.SATURDAY) {
                            item.repeatDays.add(day);
                        }
                    }
                }
                alarms.add(item);
            } catch (JSONException e) {
                // Пропускаем отдельный повреждённый объект, не сбрасываем весь список
            }
        }
    }

    private void saveAlarms() {
        JSONArray array = new JSONArray();
        for (AlarmItem item : alarms) {
            try {
                JSONObject obj      = new JSONObject();
                JSONArray  daysJson = new JSONArray();
                for (int d : item.repeatDays) daysJson.put(d);

                obj.put("id",         item.id);
                obj.put("hour",       item.hour);
                obj.put("minute",     item.minute);
                obj.put("toneUri",    item.toneUri);
                obj.put("enabled",    item.enabled);
                obj.put("repeatDays", daysJson);
                array.put(obj);
            } catch (JSONException ignored) {}
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_ALARMS_JSON, array.toString())
                .apply();

        CatClockBleManager mgr = CatClockBleManager.get(this);
        if (mgr.hasPairedDevice()) {
            mgr.syncAlarms(null);
        }
    }

    // -----------------------------------------------------------------------
    // Модель данных
    // -----------------------------------------------------------------------

    static class AlarmItem {
        int           id;
        int           hour;
        int           minute;
        String        toneUri;
        boolean       enabled;
        Set<Integer>  repeatDays = new TreeSet<>();

        int[] repeatDaysToIntArray() {
            int[] arr = new int[repeatDays.size()];
            int   i   = 0;
            for (int d : repeatDays) arr[i++] = d;
            return arr;
        }
    }
}
