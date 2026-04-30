package com.example.clock2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "clock_prefs";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_TIMEZONE = "timezone";
    private static final String KEY_WORLD_TIME_ZONES = "world_time_zones";

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final List<String> worldTimeZones = new ArrayList<>();
    private final List<TextView> worldTimeClock = new ArrayList<>();
    private final List<TextView> worldTimeOffsetViews = new ArrayList<>();

    private SharedPreferences preferences;
    private TextView mainTimeText;
    private TextView mainDateText;
    private LinearLayout worldTimeList;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateMainClock();
            tickWorldTimeCards();
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applyTheme(preferences.getBoolean(KEY_DARK_MODE, false));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainTimeText = findViewById(R.id.tv_main_time);
        mainDateText = findViewById(R.id.tv_main_date);
        worldTimeList = findViewById(R.id.layout_world_time_list);

        FloatingActionButton addWorldTimeFab = findViewById(R.id.fab_add_world_time);
        LinearLayout alarmNav = findViewById(R.id.nav_alarm);
        LinearLayout settingsNav = findViewById(R.id.nav_settings);

        addWorldTimeFab.setOnClickListener(v -> showAddWorldTimeDialog());
        alarmNav.setOnClickListener(v -> startActivity(new Intent(this, AlarmActivity.class)));
        settingsNav.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        loadWorldTimeZones();
        updateMainClock();
        buildWorldTimeCards();
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTick);
    }

    private void applyTheme(boolean isDarkMode) {
        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
    }

    private void updateMainClock() {
        String homeZoneId = preferences.getString(KEY_TIMEZONE, "Europe/Moscow");
        TimeZone homeZone = TimeZone.getTimeZone(homeZoneId);
        Date now = new Date();

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        timeFormat.setTimeZone(homeZone);

        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMM", Locale.getDefault());
        dateFormat.setTimeZone(homeZone);

        mainTimeText.setText(timeFormat.format(now));
        mainDateText.setText(getString(R.string.home_clock_label, readableCityName(homeZoneId), dateFormat.format(now)));
    }

    private void showAddWorldTimeDialog() {
        String[] zones = getResources().getStringArray(R.array.timezones);
        List<String> available = new ArrayList<>();
        for (String zone : zones) {
            if (!zone.equals(preferences.getString(KEY_TIMEZONE, "Europe/Moscow")) && !worldTimeZones.contains(zone)) {
                available.add(zone);
            }
        }

        if (available.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.all_world_times_added)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        CharSequence[] labels = new CharSequence[available.size()];
        for (int i = 0; i < available.size(); i++) {
            labels[i] = readableCityName(available.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.add_world_time)
                .setItems(labels, (dialog, which) -> {
                    worldTimeZones.add(available.get(which));
                    saveWorldTimeZones();
                    buildWorldTimeCards();
                })
                .show();
    }

    private void buildWorldTimeCards() {
        worldTimeList.removeAllViews();
        worldTimeClock.clear();
        worldTimeOffsetViews.clear();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (String zoneId : worldTimeZones) {
            View card = inflater.inflate(R.layout.item_world_time_card, worldTimeList, false);
            TextView cityName = card.findViewById(R.id.tv_city_name);
            TextView cityOffset = card.findViewById(R.id.tv_city_offset);
            TextView cityTime = card.findViewById(R.id.tv_city_time);

            cityName.setText(readableCityName(zoneId));
            worldTimeClock.add(cityTime);
            worldTimeOffsetViews.add(cityOffset);

            card.setOnLongClickListener(v -> {
                worldTimeZones.remove(zoneId);
                saveWorldTimeZones();
                buildWorldTimeCards();
                return true;
            });

            worldTimeList.addView(card);
        }

        tickWorldTimeCards();
    }

    private void tickWorldTimeCards() {
        String homeZoneId = preferences.getString(KEY_TIMEZONE, "Europe/Moscow");
        TimeZone homeZone = TimeZone.getTimeZone(homeZoneId);
        Date now = new Date();
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        for (int i = 0; i < worldTimeZones.size() && i < worldTimeClock.size(); i++) {
            TimeZone zone = TimeZone.getTimeZone(worldTimeZones.get(i));
            timeFormat.setTimeZone(zone);
            worldTimeClock.get(i).setText(timeFormat.format(now));
            worldTimeOffsetViews.get(i).setText(getString(R.string.today_offset_format,
                    formatOffsetFromHome(homeZone, zone, now.getTime())));
        }
    }

    private String formatOffsetFromHome(TimeZone homeZone, TimeZone targetZone, long nowMillis) {
        int diffMs = targetZone.getOffset(nowMillis) - homeZone.getOffset(nowMillis);
        int totalMinutes = diffMs / 60000;
        if (totalMinutes == 0) {
            return getString(R.string.same_time_offset);
        }
        int absMinutes = Math.abs(totalMinutes);
        int hours = absMinutes / 60;
        int minutes = absMinutes % 60;
        if (totalMinutes > 0) {
            return minutes == 0
                    ? getString(R.string.offset_plus_hours, hours)
                    : getString(R.string.offset_plus_hours_minutes, hours, minutes);
        }
        return minutes == 0
                ? getString(R.string.offset_minus_hours, hours)
                : getString(R.string.offset_minus_hours_minutes, hours, minutes);
    }

    private String readableCityName(String zoneId) {
        String[] parts = zoneId.split("/");
        String cityPart = parts[parts.length - 1].replace('_', ' ');
        return cityPart;
    }

    private void loadWorldTimeZones() {
        worldTimeZones.clear();
        String json = preferences.getString(KEY_WORLD_TIME_ZONES, "[]");

        try {
            JSONArray array = new JSONArray(json);
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                unique.add(array.getString(i));
            }
            worldTimeZones.addAll(unique);
        } catch (JSONException ignored) {
            worldTimeZones.clear();
        }
    }

    private void saveWorldTimeZones() {
        JSONArray array = new JSONArray();
        for (String zone : worldTimeZones) {
            array.put(zone);
        }
        preferences.edit().putString(KEY_WORLD_TIME_ZONES, array.toString()).apply();
    }
}
