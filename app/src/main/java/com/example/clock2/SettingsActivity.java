package com.example.clock2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences preferences;
    private Spinner timeZoneSpinner;
    private Spinner alarmToneSpinner;
    private Spinner difficultySpinner;
    private SwitchMaterial themeSwitch;
    private final List<String> alarmToneUris = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        boolean isDarkMode = preferences.getBoolean(MainActivity.KEY_DARK_MODE, false);
        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        timeZoneSpinner = findViewById(R.id.spinner_timezone);
        alarmToneSpinner = findViewById(R.id.spinner_alarm_tone_settings);
        difficultySpinner = findViewById(R.id.spinner_difficulty);
        themeSwitch = findViewById(R.id.switch_theme);
        Button saveButton = findViewById(R.id.btn_save_settings);

        findViewById(R.id.nav_alarm).setOnClickListener(v -> startActivity(new Intent(this, AlarmActivity.class)));
        findViewById(R.id.nav_world_time).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        setupAlarmToneSpinner();
        restoreSettings();
        saveButton.setOnClickListener(v -> saveSettings());
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
                    if (uri == null) continue;
                    // getRingtone() может вернуть null если файл недоступен → NPE
                    android.media.Ringtone ringtone = ringtoneManager.getRingtone(position);
                    if (ringtone == null) continue;
                    alarmToneUris.add(uri.toString());
                    alarmToneTitles.add(ringtone.getTitle(this));
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
    }

    private void restoreSettings() {
        String[] timezones = getResources().getStringArray(R.array.timezones);
        String savedTz = preferences.getString(MainActivity.KEY_TIMEZONE, TimeZone.getDefault().getID());
        int index = 0;
        for (int i = 0; i < timezones.length; i++) {
            if (timezones[i].equals(savedTz)) {
                index = i;
                break;
            }
        }
        timeZoneSpinner.setSelection(index);
        themeSwitch.setChecked(preferences.getBoolean(MainActivity.KEY_DARK_MODE, false));

        String savedToneUri = getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .getString(AlarmActivity.KEY_ALARM_TONE_URI, "");
        int toneIndex = alarmToneUris.indexOf(savedToneUri);
        if (toneIndex >= 0) {
            alarmToneSpinner.setSelection(toneIndex);
        }

        int savedDifficulty = getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .getInt(AlarmActivity.KEY_DIFFICULTY, 1);
        int diffIndex = Math.max(0, Math.min(savedDifficulty - 1, difficultySpinner.getCount() - 1));
        difficultySpinner.setSelection(diffIndex);
    }

    private void saveSettings() {
        String[] timezones = getResources().getStringArray(R.array.timezones);
        String selectedTimeZone = timezones[timeZoneSpinner.getSelectedItemPosition()];
        boolean isDarkMode = themeSwitch.isChecked();

        preferences.edit()
                .putString(MainActivity.KEY_TIMEZONE, selectedTimeZone)
                .putBoolean(MainActivity.KEY_DARK_MODE, isDarkMode)
                .apply();

        String selectedToneUri = alarmToneUris.get(alarmToneSpinner.getSelectedItemPosition());
        getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(AlarmActivity.KEY_ALARM_TONE_URI, selectedToneUri)
                .apply();

        int selectedDifficulty = difficultySpinner.getSelectedItemPosition() + 1;
        getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(AlarmActivity.KEY_DIFFICULTY, selectedDifficulty)
                .apply();

        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );

        finish();
    }
}
