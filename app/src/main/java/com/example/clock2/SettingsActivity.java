package com.example.clock2;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences preferences;
    private Spinner timeZoneSpinner;
    private Switch themeSwitch;

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
        themeSwitch = findViewById(R.id.switch_theme);
        Button saveButton = findViewById(R.id.btn_save_settings);

        restoreSettings();
        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void restoreSettings() {
        String[] timezones = getResources().getStringArray(R.array.timezones);
        String savedTz = preferences.getString(MainActivity.KEY_TIMEZONE, timezones[0]);
        int index = 0;
        for (int i = 0; i < timezones.length; i++) {
            if (timezones[i].equals(savedTz)) {
                index = i;
                break;
            }
        }
        timeZoneSpinner.setSelection(index);
        themeSwitch.setChecked(preferences.getBoolean(MainActivity.KEY_DARK_MODE, false));
    }

    private void saveSettings() {
        String[] timezones = getResources().getStringArray(R.array.timezones);
        String selectedTimeZone = timezones[timeZoneSpinner.getSelectedItemPosition()];
        boolean isDarkMode = themeSwitch.isChecked();

        preferences.edit()
                .putString(MainActivity.KEY_TIMEZONE, selectedTimeZone)
                .putBoolean(MainActivity.KEY_DARK_MODE, isDarkMode)
                .apply();

        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );

        finish();
    }
}
