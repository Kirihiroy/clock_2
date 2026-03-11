package com.example.clock2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextClock;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "clock_prefs";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_TIMEZONE = "timezone";

    private TextClock digitalClock;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applyTheme(preferences.getBoolean(KEY_DARK_MODE, false));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        digitalClock = findViewById(R.id.digital_clock);
        Button alarmScreenButton = findViewById(R.id.btn_alarm_screen);
        Button settingsScreenButton = findViewById(R.id.btn_settings_screen);

        alarmScreenButton.setOnClickListener(v -> startActivity(new Intent(this, AlarmActivity.class)));
        settingsScreenButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String timezone = preferences.getString(KEY_TIMEZONE, "UTC");
        digitalClock.setTimeZone(timezone);
    }

    private void applyTheme(boolean isDarkMode) {
        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
    }
}
