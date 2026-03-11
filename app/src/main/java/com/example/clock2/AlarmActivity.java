package com.example.clock2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.RingtoneManager;
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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class AlarmActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "alarm_prefs";
    public static final String KEY_ALARM_TONE_URI = "alarm_tone_uri";

    private TimePicker timePicker;
    private Spinner alarmToneSpinner;
    private AlarmManager alarmManager;
    private final List<String> alarmToneUris = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        timePicker = findViewById(R.id.time_picker);
        Button setAlarmButton = findViewById(R.id.btn_set_alarm);
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
                        alarmToneUris.add(uri.toString());
                        alarmToneTitles.add(ringtoneManager.getRingtone(position).getTitle(this));
                    }
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        if (alarmToneTitles.isEmpty()) {
            alarmToneTitles.add(getString(R.string.default_alarm_tone));
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && alarmManager != null
                && !alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(this, R.string.request_exact_alarm_permission, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
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
        if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        String selectedToneUri = alarmToneUris.get(alarmToneSpinner.getSelectedItemPosition());
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra(KEY_ALARM_TONE_URI, selectedToneUri);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_ALARM_TONE_URI, selectedToneUri)
                .apply();

        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    pendingIntent
            );
        }

        Toast.makeText(this, getString(R.string.alarm_set_message, hour, minute), Toast.LENGTH_SHORT).show();
        finish();
    }
}
