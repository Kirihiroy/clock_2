package com.example.clock2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextClock;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class MainActivity extends AppCompatActivity {

    private TextClock digitalClock;
    private Button themeButton, alarmButton;
    private Spinner timeZoneSpinner;
    private SharedPreferences preferences;
    private boolean isDarkMode = false;
    private static final String PREFS_NAME = "clock_prefs";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_TIMEZONE_POSITION = "timezone_position";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Загружаем сохранённую тему
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isDarkMode = preferences.getBoolean(KEY_DARK_MODE, false);
        applyTheme();

        setContentView(R.layout.activity_main);

        initializeViews();
        restoreTimeZoneSelection();
        setupListeners();
    }

    private void initializeViews() {
        digitalClock = findViewById(R.id.digital_clock);
        themeButton = findViewById(R.id.btn_theme);
        alarmButton = findViewById(R.id.btn_alarm);
        timeZoneSpinner = findViewById(R.id.spinner_timezone);
    }

    private void setupListeners() {
        // Переключение темы
        themeButton.setOnClickListener(v -> toggleTheme());

        // Открытие экрана будильника
        alarmButton.setOnClickListener(v -> openAlarmActivity());

        // Смена часового пояса
        timeZoneSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                String[] timezones = getResources().getStringArray(R.array.timezones);
                digitalClock.setTimeZone(timezones[position]);
                preferences.edit().putInt(KEY_TIMEZONE_POSITION, position).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void toggleTheme() {
        isDarkMode = !isDarkMode;
        preferences.edit().putBoolean(KEY_DARK_MODE, isDarkMode).apply();
        applyTheme();
        recreate();
    }

    private void restoreTimeZoneSelection() {
        String[] timezones = getResources().getStringArray(R.array.timezones);
        int savedPosition = preferences.getInt(KEY_TIMEZONE_POSITION, 0);
        if (savedPosition < 0 || savedPosition >= timezones.length) {
            savedPosition = 0;
        }
        timeZoneSpinner.setSelection(savedPosition);
        digitalClock.setTimeZone(timezones[savedPosition]);
    }

    private void applyTheme() {
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    private void openAlarmActivity() {
        Intent intent = new Intent(this, AlarmActivity.class);
        startActivity(intent);
    }
}
