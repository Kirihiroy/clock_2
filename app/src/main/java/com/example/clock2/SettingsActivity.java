package com.example.clock2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
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
    private Spinner equationCountSpinner;
    private SwitchMaterial fadeInSwitch;
    private SwitchMaterial themeSwitch;
    private SwitchMaterial ledSwitch;
    private final List<String> alarmToneUris = new ArrayList<>();

    private TextView deviceStatusText;
    private Button   deviceConnectBtn;
    private Button   deviceSyncBtn;
    private Button   deviceDisconnectBtn;

    private final CatClockBleManager.StatusListener statusListener =
            (status, connected) -> updateDeviceUi(status, connected);

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
        equationCountSpinner = findViewById(R.id.spinner_equation_count);
        fadeInSwitch = findViewById(R.id.switch_fade_in);
        themeSwitch = findViewById(R.id.switch_theme);
        ledSwitch = findViewById(R.id.switch_led);
        Button saveButton = findViewById(R.id.btn_save_settings);

        deviceStatusText    = findViewById(R.id.tv_device_status);
        deviceConnectBtn    = findViewById(R.id.btn_device_connect);
        deviceSyncBtn       = findViewById(R.id.btn_device_sync);
        deviceDisconnectBtn = findViewById(R.id.btn_device_disconnect);

        findViewById(R.id.nav_alarm).setOnClickListener(v -> startActivity(new Intent(this, AlarmActivity.class)));
        findViewById(R.id.nav_world_time).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        deviceConnectBtn.setOnClickListener(v ->
                startActivity(new Intent(this, DeviceConnectionActivity.class)));
        deviceSyncBtn.setOnClickListener(v -> {
            Toast.makeText(this, R.string.device_status_connecting, Toast.LENGTH_SHORT).show();
            CatClockBleManager.get(this).connectAndSyncAll(new CatClockBleManager.Callback() {
                @Override public void onSuccess() {
                    runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                            R.string.device_sync_success, Toast.LENGTH_SHORT).show());
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                            getString(R.string.device_sync_failed, message), Toast.LENGTH_LONG).show());
                }
            });
        });
        deviceDisconnectBtn.setOnClickListener(v -> {
            CatClockBleManager mgr = CatClockBleManager.get(this);
            mgr.disconnect();
            mgr.setSavedDeviceAddress(null);
            DeviceSyncWorker.cancelPeriodic(this);  // больше нечего синхронизировать
            Toast.makeText(this, R.string.device_disconnected_message, Toast.LENGTH_SHORT).show();
            updateDeviceUi(null, false);
        });

        setupAlarmToneSpinner();
        restoreSettings();
        saveButton.setOnClickListener(v -> saveSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        CatClockBleManager mgr = CatClockBleManager.get(this);
        mgr.registerStatusListener(statusListener);
        // Пока экран открыт — держим соединение, чтобы виден был живой статус.
        mgr.setKeepConnected(true);
        if (mgr.hasPairedDevice() && !mgr.isConnected()) {
            mgr.connectAndSyncAll(null);
        }
        updateDeviceUi(mgr.getLastStatus(), mgr.isConnected());
    }

    @Override
    protected void onPause() {
        super.onPause();
        CatClockBleManager mgr = CatClockBleManager.get(this);
        mgr.unregisterStatusListener(statusListener);
        // Отпускаем соединение (внутри setKeepConnected(false) вызовется disconnect).
        mgr.setKeepConnected(false);
    }

    private void updateDeviceUi(@androidx.annotation.Nullable CatClockBleManager.DeviceStatus status,
                                 boolean connected) {
        CatClockBleManager mgr = CatClockBleManager.get(this);
        String mac = mgr.getSavedDeviceAddress();
        if (mac == null) {
            deviceStatusText.setText(R.string.device_status_not_paired);
            deviceSyncBtn.setVisibility(View.GONE);
            deviceDisconnectBtn.setVisibility(View.GONE);
            return;
        }
        deviceSyncBtn.setVisibility(View.VISIBLE);
        deviceDisconnectBtn.setVisibility(View.VISIBLE);
        if (connected && status != null) {
            deviceStatusText.setText(getString(R.string.device_status_connected,
                    status.firmware, status.alarmsCount));
        } else if (connected) {
            deviceStatusText.setText(R.string.device_status_connecting);
        } else {
            deviceStatusText.setText(getString(R.string.device_status_offline, mac));
        }
    }

    private void setupAlarmToneSpinner() {
        List<String> alarmToneTitles = new ArrayList<>();
        // Важно: используем конструктор (Context), а не (Activity). Activity-вариант
        // регистрирует курсор как managed cursor, и activity пытается requery после restart,
        // что падает с StaleDataException, если мы курсор уже закрыли.
        RingtoneManager ringtoneManager = new RingtoneManager((Context) this);
        ringtoneManager.setType(RingtoneManager.TYPE_ALARM);

        // try-with-resources гарантирует close() даже если moveToFirst()/getCount() бросят.
        try (Cursor cursor = ringtoneManager.getCursor()) {
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
        } catch (Exception ignored) {
            // RingtoneManager может бросать на устройствах с битым медиа-индексом —
            // не валим экран настроек целиком, ниже добавим хотя бы дефолт.
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

        SharedPreferences alarmPrefs = getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE);

        int savedDifficulty = alarmPrefs.getInt(AlarmActivity.KEY_DIFFICULTY, 1);
        int diffIndex = Math.max(0, Math.min(savedDifficulty - 1, difficultySpinner.getCount() - 1));
        difficultySpinner.setSelection(diffIndex);

        int savedCount = alarmPrefs.getInt(AlarmActivity.KEY_PUZZLE_COUNT, 1);
        int countIndex = Math.max(0, Math.min(savedCount - 1, equationCountSpinner.getCount() - 1));
        equationCountSpinner.setSelection(countIndex);

        fadeInSwitch.setChecked(alarmPrefs.getBoolean(AlarmActivity.KEY_FADE_IN, true));
        ledSwitch.setChecked(LedBrightness.isEnabled(this));
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
        int selectedCount      = equationCountSpinner.getSelectedItemPosition() + 1;
        getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(AlarmActivity.KEY_DIFFICULTY, selectedDifficulty)
                .putInt(AlarmActivity.KEY_PUZZLE_COUNT, selectedCount)
                .putBoolean(AlarmActivity.KEY_FADE_IN, fadeInSwitch.isChecked())
                .apply();

        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );

        LedBrightness.setEnabled(this, ledSwitch.isChecked());

        CatClockBleManager mgr = CatClockBleManager.get(this);
        if (mgr.hasPairedDevice()) {
            // syncTime обновит зону + одной транзакцией доедет свежая яркость через connectAndSyncAll.
            // Здесь шлём отдельно: пользователь мог поменять только переключатель LED.
            mgr.syncTime(null);
            mgr.syncLed(LedBrightness.compute(this), null);
        }

        finish();
    }
}
