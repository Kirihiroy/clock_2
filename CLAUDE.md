# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build and install to connected device/emulator
./gradlew build                  # Build debug + release
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumentation tests on device
./gradlew lint                   # Run Android Lint
./gradlew clean                  # Clean build artifacts
```

## Project Overview

Android clock app (min SDK 24, target SDK 36, Java 11). Single `:app` module. App ID: `com.example.clock2`. All UI strings are in Russian.

## Architecture

No MVVM or Architecture Components — plain Activities with SharedPreferences for persistence and JSON serialization (no Room/SQLite).

**Data storage pattern**: Alarm list and world timezone list are stored as JSON arrays in SharedPreferences. `loadAlarms()` / `saveAlarms()` and `loadWorldTimeZones()` / `saveWorldTimeZones()` handle serialization in their respective Activities.

**Clock ticking**: `MainActivity` uses a `Handler` posting a 1-second runnable to update the display.

## Component Roles

| Class | Role |
|---|---|
| `MainActivity` | World clock: displays home timezone + user-selected world timezones with UTC offsets |
| `AlarmActivity` | Alarm CRUD: create/enable/disable/delete alarms; schedules via `AlarmManager` |
| `AlarmReceiver` | `BroadcastReceiver` triggered by `AlarmManager`; starts `AlarmRingActivity` |
| `AlarmRingActivity` | Full-screen wake-up screen; requires solving a random math problem to dismiss |
| `AlarmRingingService` | Foreground service that plays the alarm ringtone; shows high-priority notification with dismiss action |
| `SettingsActivity` | Configures home timezone, dark mode, and default alarm tone |

## Alarm Flow

`AlarmActivity.scheduleAlarm()` → `AlarmManager` fires → `AlarmReceiver.onReceive()` → starts `AlarmRingActivity` + `AlarmRingingService.start()` → user solves math puzzle → `AlarmRingingService.stop()` + `finishAndRemoveTask()`.

The back button is disabled in `AlarmRingActivity` via `OnBackPressedCallback` to prevent bypassing the math puzzle.

## Key Permissions

- `SCHEDULE_EXACT_ALARM` — checked at runtime on API 31+
- `POST_NOTIFICATIONS` — requested at runtime on API 33+
- `USE_FULL_SCREEN_INTENT` — needed for lock-screen alarm display
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — required for `AlarmRingingService`

## SharedPreferences Keys

| Store | Key | Value |
|---|---|---|
| `clock_prefs` | `dark_mode` | boolean |
| `clock_prefs` | `timezone` | String (default `"Asia/Novosibirsk"`) |
| `clock_prefs` | `world_time_zones` | JSON array of timezone IDs |
| `alarm_prefs` | `alarms_json` | JSON array of alarm objects |
| `alarm_prefs` | `alarm_tone_uri` | String URI |
