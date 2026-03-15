package com.example.clock2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class AlarmActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "alarm_prefs";
    public static final String KEY_ALARM_TONE_URI = "alarm_tone_uri";
    private static final String KEY_ALARMS_JSON = "alarms_json";

    private AlarmManager alarmManager;
    private TextView alarmStatusText;
    private LinearLayout alarmListLayout;

    private final List<AlarmItem> alarms = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        FloatingActionButton addAlarmButton = findViewById(R.id.fab_add_alarm);
        alarmStatusText = findViewById(R.id.tv_alarm_status);
        alarmListLayout = findViewById(R.id.layout_alarm_list);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        loadAlarms();
        renderAlarmCards();

        addAlarmButton.setOnClickListener(v -> showTimePickerDialog());
    }

    private void showTimePickerDialog() {
        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        int currentMinute = now.get(Calendar.MINUTE);

        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> checkPermissionAndRun(() -> addNewAlarm(hourOfDay, minute)),
                currentHour,
                currentMinute,
                DateFormat.is24HourFormat(this)
        );
        dialog.setTitle(R.string.choose_alarm_time);
        dialog.show();
    }

    private void checkPermissionAndRun(Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && alarmManager != null
                && !alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(this, R.string.request_exact_alarm_permission, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
            return;
        }
        action.run();
    }

    private void addNewAlarm(int hour, int minute) {
        String selectedToneUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ALARM_TONE_URI, "");

        AlarmItem item = new AlarmItem();
        item.id = (int) System.currentTimeMillis();
        item.hour = hour;
        item.minute = minute;
        item.toneUri = selectedToneUri;
        item.enabled = true;

        alarms.add(0, item);

        scheduleAlarm(item);
        saveAlarms();
        renderAlarmCards();

        Toast.makeText(this, getString(R.string.alarm_set_message, hour, minute), Toast.LENGTH_SHORT).show();
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

    private void scheduleAlarm(AlarmItem item) {
        if (alarmManager == null) {
            return;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, item.hour);
        calendar.set(Calendar.MINUTE, item.minute);
        calendar.set(Calendar.SECOND, 0);
        if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra(KEY_ALARM_TONE_URI, item.toneUri);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                item.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.getTimeInMillis(),
                pendingIntent
        );
    }

    private void cancelAlarm(AlarmItem item) {
        if (alarmManager == null) {
            return;
        }

        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                item.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    private void renderAlarmCards() {
        alarmListLayout.removeAllViews();

        int activeCount = 0;
        LayoutInflater inflater = LayoutInflater.from(this);

        for (AlarmItem item : alarms) {
            if (item.enabled) {
                activeCount++;
            }

            MaterialCardView card = (MaterialCardView) inflater.inflate(
                    R.layout.item_alarm_card,
                    alarmListLayout,
                    false
            );

            TextView timeText = card.findViewById(R.id.tv_alarm_time);
            TextView stateText = card.findViewById(R.id.tv_alarm_state);
            SwitchMaterial alarmSwitch = card.findViewById(R.id.switch_alarm);
            ImageButton deleteButton = card.findViewById(R.id.btn_delete_alarm);

            String time = String.format(Locale.getDefault(), "%02d:%02d", item.hour, item.minute);
            timeText.setText(time);
            alarmSwitch.setChecked(item.enabled);

            updateCardVisualState(card, timeText, stateText, item.enabled);

            card.setOnClickListener(v -> toggleAlarm(item, card, timeText, stateText, alarmSwitch));
            alarmSwitch.setOnClickListener(v -> toggleAlarm(item, card, timeText, stateText, alarmSwitch));
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

    private void updateCardVisualState(MaterialCardView card, TextView timeText, TextView stateText, boolean enabled) {
        int cardColor = enabled ? 0xFF2E3F34 : 0xFF2F2F2F;
        int primaryText = enabled ? 0xFFFFFFFF : 0xFF9E9E9E;
        int secondaryText = enabled ? 0xFFBAE6C7 : 0xFF757575;

        card.setCardBackgroundColor(cardColor);
        timeText.setTextColor(primaryText);
        stateText.setTextColor(secondaryText);
        stateText.setText(enabled ? R.string.alarm_enabled : R.string.alarm_disabled);
    }

    private void toggleAlarm(
            AlarmItem item,
            MaterialCardView card,
            TextView timeText,
            TextView stateText,
            SwitchMaterial alarmSwitch
    ) {
        boolean newState = !item.enabled;
        if (newState) {
            checkPermissionAndRun(() -> applyAlarmState(item, true, card, timeText, stateText, alarmSwitch));
            return;
        }
        applyAlarmState(item, false, card, timeText, stateText, alarmSwitch);
    }

    private void applyAlarmState(
            AlarmItem item,
            boolean enabled,
            MaterialCardView card,
            TextView timeText,
            TextView stateText,
            SwitchMaterial alarmSwitch
    ) {
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
        renderAlarmCards();
    }

    private String formatTime(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private void loadAlarms() {
        alarms.clear();
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ALARMS_JSON, "[]");

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                AlarmItem item = new AlarmItem();
                item.id = object.getInt("id");
                item.hour = object.getInt("hour");
                item.minute = object.getInt("minute");
                item.toneUri = object.optString("toneUri", "");
                item.enabled = object.optBoolean("enabled", true);
                alarms.add(item);
            }
        } catch (JSONException e) {
            alarms.clear();
        }
    }

    private void saveAlarms() {
        JSONArray array = new JSONArray();
        for (AlarmItem item : alarms) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", item.id);
                object.put("hour", item.hour);
                object.put("minute", item.minute);
                object.put("toneUri", item.toneUri);
                object.put("enabled", item.enabled);
                array.put(object);
            } catch (JSONException ignored) {
                // пропускаем некорректный объект
            }
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_ALARMS_JSON, array.toString())
                .apply();
    }

    private static class AlarmItem {
        int id;
        int hour;
        int minute;
        String toneUri;
        boolean enabled;
    }
}
