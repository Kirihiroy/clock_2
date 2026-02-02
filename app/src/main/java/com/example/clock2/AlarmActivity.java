package com.example.clock2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TimePicker;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.media.RingtoneManager;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;

public class AlarmActivity extends AppCompatActivity {

    private TimePicker timePicker;
    private Button setAlarmButton;
    private Spinner alarmToneSpinner;
    private AlarmManager alarmManager;
    private final List<String> alarmToneUris = new ArrayList<>();
    private static final String PREFS_NAME = "alarm_prefs";
    private static final String KEY_ALARM_TONE_URI = "alarm_tone_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        timePicker = findViewById(R.id.time_picker);
        setAlarmButton = findViewById(R.id.btn_set_alarm);
        alarmToneSpinner = findViewById(R.id.spinner_alarm_tone);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        setupAlarmToneSpinner();

        setAlarmButton.setOnClickListener(v -> checkPermissionAndSetAlarm());
    }

    private void setupAlarmToneSpinner() {
        List<String> alarmToneTitles = new ArrayList<>();
        RingtoneManager ringtoneManager = new RingtoneManager(this);
        ringtoneManager.setType(RingtoneManager.TYPE_ALARM);
        Cursor cursor = ringtoneManager.getCursor();
        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int position = cursor.getPosition();
                    Uri uri = ringtoneManager.getRingtoneUri(position);
                    if (uri != null) {
                        String title = ringtoneManager.getRingtone(position).getTitle(this);
                        alarmToneUris.add(uri.toString());
                        alarmToneTitles.add(title);
                    }
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (alarmToneTitles.isEmpty()) {
            alarmToneTitles.add("Системная мелодия");
            alarmToneUris.add("");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                alarmToneTitles
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alarmToneSpinner.setAdapter(adapter);

        String savedUri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ALARM_TONE_URI, "");
        int selectedIndex = alarmToneUris.indexOf(savedUri);
        if (selectedIndex >= 0) {
            alarmToneSpinner.setSelection(selectedIndex);
        }
    }

    private void checkPermissionAndSetAlarm() {
        // Для Android 12 и выше нужно включить доступ "Будильники и напоминания"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager != null && alarmManager.canScheduleExactAlarms()) {
                setAlarm();
                return;
            }
            Toast.makeText(this, "Включите доступ «Будильники и напоминания»", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            startActivity(intent);
            return;
        }
        setAlarm();
    }

    private void setAlarm() {
        int hour = timePicker.getHour();
        int minute = timePicker.getMinute();

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);

        // Если время уже прошло, устанавливаем на следующий день
        if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        Intent intent = new Intent(this, AlarmReceiver.class);
        String selectedToneUri = alarmToneUris.get(alarmToneSpinner.getSelectedItemPosition());
        intent.putExtra(KEY_ALARM_TONE_URI, selectedToneUri);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_ALARM_TONE_URI, selectedToneUri)
                .apply();

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.getTimeInMillis(),
                pendingIntent
        );

        Toast.makeText(this, "Будильник установлен на " + hour + ":" + String.format("%02d", minute),
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
