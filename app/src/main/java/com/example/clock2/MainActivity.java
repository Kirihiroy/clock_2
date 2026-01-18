package com.example.clock2;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget. Spinner;
import android.widget. TextClock;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    private TextClock digitalClock;
    private Button themeButton, alarmButton;
    private Spinner timeZoneSpinner;
    private SharedPreferences preferences;
    private boolean isDarkMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Загружаем сохранённую тему
        preferences = getSharedPreferences("clock_prefs", MODE_PRIVATE);
        isDarkMode = preferences.getBoolean("dark_mode", false);
        applyTheme();

        setContentView(R.layout.activity_main);

        initializeViews();
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
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void toggleTheme() {
        isDarkMode = !isDarkMode;
        preferences.edit().putBoolean("dark_mode", isDarkMode).apply();
        applyTheme();
        recreate();
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