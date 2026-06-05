# Clock_2 — полная документация проекта

Проект состоит из двух частей:

1. **Android-приложение** (`app/`) — мировые часы, будильники с математическими примерами, фоновая синхронизация с внешним устройством.
2. **Прошивка ESP32** (`clock_time_device/clock_time_device.ino`) — настольные часы «CatClock» на TFT-дисплее, которые получают время и будильники от телефона по BLE и сами умеют звонить.

Связь между ними — Bluetooth Low Energy: телефон выступает GATT-клиентом, ESP32 — GATT-сервером с фиксированным набором характеристик.

---

## Содержание

1. [Архитектура и общая схема](#1-архитектура-и-общая-схема)
2. [Android-приложение](#2-android-приложение)
   - 2.1 [Манифест, разрешения, компоненты](#21-манифест-разрешения-компоненты)
   - 2.2 [Хранилище (SharedPreferences)](#22-хранилище-sharedpreferences)
   - 2.3 [MainActivity — мировые часы](#23-mainactivity--мировые-часы)
   - 2.4 [AlarmActivity — список будильников](#24-alarmactivity--список-будильников)
   - 2.5 [AlarmItem — модель будильника](#25-alarmitem--модель-будильника)
   - 2.6 [AlarmScheduler — планирование](#26-alarmscheduler--планирование)
   - 2.7 [AlarmReceiver — приём сигнала](#27-alarmreceiver--приём-сигнала)
   - 2.8 [AlarmRingingService — фоновый звук](#28-alarmringingservice--фоновый-звук)
   - 2.9 [AlarmRingActivity — экран побудки](#29-alarmringactivity--экран-побудки)
   - 2.10 [BootReceiver — восстановление после ребута](#210-bootreceiver--восстановление-после-ребута)
   - 2.11 [SettingsActivity — настройки](#211-settingsactivity--настройки)
   - 2.12 [DeviceConnectionActivity — выбор устройства](#212-deviceconnectionactivity--выбор-устройства)
   - 2.13 [CatClockBleManager — BLE-клиент](#213-catclockblemanager--ble-клиент)
   - 2.14 [DeviceSyncWorker — фоновая синхронизация](#214-devicesyncworker--фоновая-синхронизация)
3. [BLE-протокол CatClock](#3-ble-протокол-catclock)
4. [Прошивка ESP32 (clock_time_device.ino)](#4-прошивка-esp32-clock_time_deviceino)
5. [Сборка и запуск](#5-сборка-и-запуск)
6. [Сценарии работы (end-to-end)](#6-сценарии-работы-end-to-end)

---

## 1. Архитектура и общая схема

```
┌─────────────────────────────────────────┐         BLE (GATT)         ┌──────────────────────────┐
│              Android-телефон            │  ◄─────────────────────►  │       ESP32 «CatClock»    │
│                                         │   service 5a0f0001-…       │  TFT 128×128 (ST7735),    │
│  MainActivity ─ мировые часы            │   write CHAR_TIME          │  пьезо-зуммер,            │
│  AlarmActivity ─ CRUD будильников       │   write CHAR_ALARMS        │  2 кнопки (стоп/snooze)   │
│  AlarmRingActivity ─ экран побудки      │   write CHAR_CMD           │                           │
│  AlarmRingingService ─ звук на телефоне │   notify CHAR_STATUS       │  сам считает время,       │
│  AlarmScheduler ─ AlarmManager          │                            │  звонит в локальный       │
│  CatClockBleManager ─ GATT-клиент       │                            │  будильник                │
│  DeviceSyncWorker ─ периодика 1 раз/ч   │                            │                           │
└─────────────────────────────────────────┘                            └──────────────────────────┘
```

Приложение и прошивка работают независимо: телефон может звонить, даже если устройство отключено; устройство — даже если телефон вне зоны действия (пока актуально синхронизированное время). Список будильников и время на устройстве периодически обновляются: при изменении в приложении (немедленно при наличии связи) и раз в час фоновым воркером.

---

## 2. Android-приложение

Пакет: `com.example.clock2`. min SDK 24, target SDK 36, Java 11. Один модуль `:app`. Никакого MVVM/Room — только Activities + `SharedPreferences` + JSON. Все строки UI русские.

### 2.1 Манифест, разрешения, компоненты

`AndroidManifest.xml`:

**Разрешения:**

| Разрешение | Назначение |
|---|---|
| `SCHEDULE_EXACT_ALARM` | точные будильники на API 31+ (проверяется в рантайме) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `AlarmRingingService` играет звук как foreground media |
| `USE_FULL_SCREEN_INTENT` | показ экрана будильника поверх локскрина |
| `POST_NOTIFICATIONS` | API 33+, запрашивается в `AlarmActivity` |
| `RECEIVE_BOOT_COMPLETED` | пере-планирование будильников после ребута |
| `WAKE_LOCK` | удержание ЦП во время звонка |
| `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` | API 31+, сканирование и работа GATT |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` | те же возможности, но до API 30 |

**Компоненты:**

- 4 activity: `MainActivity` (launcher), `AlarmActivity`, `SettingsActivity`, `DeviceConnectionActivity`, `AlarmRingActivity` (`showWhenLocked + turnScreenOn`, `excludeFromRecents`).
- Сервис `AlarmRingingService` с `foregroundServiceType="mediaPlayback"`.
- Два ресивера: `AlarmReceiver` (срабатывания будильника), `BootReceiver` (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`).

### 2.2 Хранилище (SharedPreferences)

| Файл | Ключ | Тип | Что хранит |
|---|---|---|---|
| `clock_prefs` | `dark_mode` | boolean | тёмная тема |
| `clock_prefs` | `timezone` | String | домашний IANA-id (по умолчанию `Asia/Novosibirsk`) |
| `clock_prefs` | `world_time_zones` | JSON-массив | список зон мирового времени |
| `clock_prefs` | `cat_clock_mac` | String | MAC привязанного CatClock (или отсутствует) |
| `alarm_prefs` | `alarms_json` | JSON-массив | будильники: `id, hour, minute, toneUri, enabled, repeatDays[]` |
| `alarm_prefs` | `alarm_tone_uri` | String | URI рингтона по умолчанию для новых будильников |
| `alarm_prefs` | `puzzle_difficulty` | int (1/2/3) | сложность математики |
| `alarm_prefs` | `puzzle_count` | int | сколько примеров надо решить |
| `alarm_prefs` | `fade_in_enabled` | boolean | плавное нарастание громкости |
| `alarm_prefs` | `next_alarm_id` | int | счётчик id для новых будильников |
| `alarm_prefs` | `predismiss_<id>` | long | метка «выключили во время фазы fade-in» (TTL 90 с) |

### 2.3 MainActivity — мировые часы

Стартовая активность. Показывает домашние часы, список карточек мирового времени, нижнюю навигацию.

**Поля состояния:**
- `clockHandler` + `clockTick` — `Handler`-цикл, обновляющий время раз в секунду.
- Закэшированные `SimpleDateFormat` (`HH:mm:ss`, `EEEE, d MMM`, `HH:mm`) — пересоздавать форматтер каждую секунду было бы дорого.
- `worldTimeZones`, `worldTimeClock`, `worldTimeOffsetViews` — параллельные списки зон и их View.

**Жизненный цикл:**
- `onCreate` — применяет тему, инфлейтит `activity_main`, навешивает обработчики FAB и навигации, грузит сохранённые зоны, при наличии привязанного CatClock запускает фоновую синхронизацию (`DeviceSyncWorker.schedulePeriodic`, идемпотентно).
- `onResume` — стартует тикер часов; если устройство привязано — отправляет ему свежий offset (важно при переходе на летнее/зимнее время).
- `onPause` — останавливает тикер.

**Методы:**
- `applyTheme(boolean)` — переключает `AppCompatDelegate` между MODE_NIGHT_YES/NO.
- `updateMainClock()` — берёт сохранённую домашнюю зону, форматирует время и дату.
- `showAddWorldTimeDialog()` — `AlertDialog` со списком доступных зон (исключая домашнюю и уже добавленные).
- `buildWorldTimeCards()` — пересоздаёт все карточки, на каждой долгий клик удаляет зону.
- `tickWorldTimeCards()` — обновляет время и подпись «на X часов раньше/позже» в каждой карточке.
- `formatOffsetFromHome(...)` — считает разницу `targetZone.getOffset() − homeZone.getOffset()` и форматирует строку.
- `readableCityName(zoneId)` — превращает `Europe/Moscow` в `Moscow`.
- `loadWorldTimeZones()` / `saveWorldTimeZones()` — JSON ↔ `clock_prefs:world_time_zones`. На чтении используется `LinkedHashSet` чтобы убрать возможные дубликаты с сохранением порядка.

### 2.4 AlarmActivity — список будильников

CRUD будильников + диалог создания.

**Константы:** ключи SharedPreferences (`PREFS_NAME = "alarm_prefs"` и т.д.), `DAY_ORDER` — порядок отображения дней (Пн…Вс) с соответствующими `Calendar.DAY_OF_WEEK`, `DAY_VIEW_IDS` — id кнопок-кругляшей.

**Главная утилита `nextTriggerMillis(hour, minute, repeatDays)`:**
Публичный статический метод — **единый источник истины** для вычисления следующего срабатывания. Используется и `AlarmScheduler`-ом, и обратным отсчётом в UI, и `BootReceiver`-ом. Логика:
- Если `repeatDays == null/empty` → однократный: если сегодня в это время уже прошло — завтра, иначе сегодня.
- Иначе — ищет ближайший день из `repeatDays`, перебирая до 7 дней вперёд.

**Жизненный цикл (`onCreate`):**
1. `ensureNotificationPermission()` — на API 33+ запрашивает `POST_NOTIFICATIONS`.
2. `ensureBatteryOptimizationExempt()` — если приложение в whitelist-е оптимизации не лежит, показывает диалог с переходом в системные настройки.
3. `ensureFullScreenIntentPermission()` — на API 34+ просит разрешение `MANAGE_APP_USE_FULL_SCREEN_INTENT`.
4. Загружает будильники из `alarms_json`, отрисовывает карточки.

**Диалог добавления (`showAddAlarmDialog`):**
- Два `NumberPicker` с форматом `"%02d ч"` / `"%02d мин."` (по умолчанию — текущее время).
- 7 кнопок-кругляшей (Пн…Вс). Выбранные становятся синими, невыбранные — серыми (`applyDayButtonStyle`). Под пикерами — динамическая подсказка «Без повтора / Каждый день / Будни / Выходные / Пн Ср Пт».
- Кнопки `cancel` / `done`. Сам диалог — `MaterialAlertDialogBuilder` с прозрачным фоном окна.

**CRUD:**
- `addNewAlarm(hour, minute, days)` — создаёт `AlarmItem`, кладёт в начало списка, планирует через `scheduleAlarm`, сохраняет, перерисовывает.
- `deleteAlarm(item)` — отменяет в `AlarmScheduler`, убирает из списка, сохраняет.
- `scheduleAlarm(item)` — делегирует в `AlarmScheduler.schedule`, на отказ (нет разрешения EXACT_ALARM) показывает Toast.
- `cancelAlarm(item)` → `AlarmScheduler.cancel`.

**Отрисовка карточек (`renderAlarmCards`):**
- Инфлейтит `item_alarm_card` на каждый будильник.
- В заголовке (`tv_alarm_status`) пишет «Активных будильников: N» / «Будильников нет» / «Нет активных будильников».
- Тап по карточке — `toggleAlarm`: переключает `enabled`, планирует или отменяет, и **локально** обновляет визуальное состояние без полной перерисовки (`updateCardVisualState`). Полный `renderAlarmCards()` тут не нужен.
- Свитч на карточке — не кликабельный сам по себе (`setClickable(false)`), он только индикатор; вся логика — на карточке. Иконка корзины — `deleteAlarm`.

**Сериализация:**
- `loadAlarms()` — читает `alarms_json`, валидирует `hour ∈ [0..23]`, `minute ∈ [0..59]`, день ∈ `Calendar.SUNDAY..SATURDAY`. Битый объект — пропускается, не сбрасывая весь список.
- `saveAlarms()` — пишет массив. **После сохранения**, если есть привязанное устройство, дополнительно вызывает `CatClockBleManager.syncAlarms(null)` — будильники сразу уезжают на ESP32.

**Внутренний класс `AlarmItem`** (private, в `AlarmActivity`): id, hour, minute, toneUri, enabled, `repeatDays: Set<Integer>`. Метод `repeatDaysToIntArray()`.

> ⚠️ Обратите внимание: в проекте **два** `AlarmItem` — приватный внутри `AlarmActivity` и публичный в `AlarmItem.java`. Они немного отличаются (публичный имеет ещё `label` и хелперы JSON/UI). Логика `AlarmActivity` использует свой внутренний; публичный — для других мест.

### 2.5 AlarmItem — модель будильника

Публичная модель (`AlarmItem.java`). Поля:

| Поле | Тип | Назначение |
|---|---|---|
| `id` | int | уникальный, генерится из `next_alarm_id` |
| `hour`, `minute` | int | время |
| `toneUri` | String | URI рингтона |
| `enabled` | boolean | включён ли |
| `label` | String | произвольная подпись (в текущем UI не редактируется) |
| `repeatDays` | `Set<Integer>` | константы `Calendar.DAY_OF_WEEK` |

Методы:
- `toJson()` / `fromJson()` — сериализация в `JSONObject`.
- `computeNextFireTime()` — делегирует в `AlarmActivity.nextTriggerMillis`.
- `repeatDaysSorted()` — копия в `TreeSet`.
- `buildRepeatLabel()` — «Без повтора / Каждый день / Пн, Ср, Пт» для русского UI.

### 2.6 AlarmScheduler — планирование

Утилитный класс (private constructor). Единая точка планирования: использует `AlarmManager.setAlarmClock`, который доставляется даже при агрессивной экономии батареи и показывает иконку будильника в статус-баре.

**Концепция «пред-сигнала»:**
За 60 с до основного срабатывания планируется **второй** будильник — он запускает `AlarmRingingService` в режиме плавного нарастания громкости. В момент T планируется основной — он либо переводит играющий поток на полную громкость, либо запускает звук с нуля (если fade-in выключен).

**Константы:**
- `KEY_PRE_ALARM` — флаг в Intent, отличающий пред-сигнал от основного.
- `PRE_REQ_OFFSET = 1_000_000` — добавляется к `requestCode` пред-сигнала, чтобы его PendingIntent не пересёкся с основным.
- `PRE_ALARM_LEAD_MS = 60_000` — за сколько до T планировать пред-сигнал.
- `PREDISMISS_PREFIX`, `PREDISMISS_WINDOW_MS = 90_000` — окно «недавнего выключения».

**Методы:**

- `markDismissed(ctx, id)` — ставит в `alarm_prefs` метку `predismiss_<id> = now`. Вызывается из `AlarmRingingService.onDestroy()`: если пользователь выключил будильник во время фазы нарастания, основной сигнал не должен зазвонить второй раз.
- `consumeRecentDismiss(ctx, id)` — `true` если метка свежее 90 с; одновременно её удаляет. Вызывается из `AlarmReceiver` перед запуском основного сигнала.
- `isFadeEnabled(ctx)` — читает `KEY_FADE_IN` (по умолчанию `true`).
- `schedule(ctx, id, hour, minute, repeatDays, toneUri)` — главный метод:
  1. Получает `AlarmManager`. На API 31+ проверяет `canScheduleExactAlarms()` — при отказе возвращает `false`.
  2. Считает `triggerMillis` через `AlarmActivity.nextTriggerMillis`.
  3. Строит «show intent» — PendingIntent для иконки будильника в статус-баре (открывает `AlarmActivity`).
  4. Строит PendingIntent с `AlarmReceiver` (broadcast) с экстрами `KEY_ALARM_ID/HOUR/MINUTE/REPEAT_DAYS/TONE_URI/PRE_ALARM`.
  5. Зовёт `am.setAlarmClock(...)` для основного сигнала; ловит `SecurityException`.
  6. Снимает прежний пред-сигнал (`cancelPre`).
  7. Если fade-in включён и `triggerMillis − 60000 > now` — планирует второй PendingIntent (с `pre=true` и `requestCode = PRE_REQ_OFFSET + id`).
- `cancel(ctx, id)` — отменяет и основной, и пред-сигнал.
- `cancelByRequestCode` — нюанс: совпадение PendingIntent определяется по `(component, action, data, requestCode)`, экстры не важны — поэтому достаточно «пустого» Intent с тем же `requestCode`. Вызывает `am.cancel(pi)` и `pi.cancel()`.
- `receiverPendingIntent(...)` — приватная фабрика.

### 2.7 AlarmReceiver — приём сигнала

`BroadcastReceiver`, который вызывается из `AlarmManager`.

`onReceive(context, intent)`:

1. Достаёт `alarmId, hour, minute, repeatDays, toneUri, isPre`.
2. **Пред-сигнал** (`isPre == true`): запускает `AlarmRingingService.startFadeIn(...)`. Никаких экранов и перепланирования — это сделает основной сигнал в момент T.
3. **Основной сигнал** (`isPre == false`):
   - Если в последние 90 с был `markDismissed` для этого id — игнорируем (`consumeRecentDismiss` возвращает `true`). Это случай «выключили во время fade-in».
   - Иначе: `AlarmRingingService.start(...)` + явно стартует `AlarmRingActivity` с `FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP`. Прямой `startActivity` из ресивера, запущенного `setAlarmClock`, разрешён системой — это надёжнее `setFullScreenIntent`, который может не сработать на гашёном экране без специального разрешения.
   - Если будильник повторяющийся (`repeatDays.length > 0`) — переплан через `AlarmScheduler.schedule(...)`. Иначе — `markAlarmDisabled(ctx, alarmId)`: помечает в `alarms_json` `enabled=false`, чтобы при следующем открытии `AlarmActivity` карточка была серой.

### 2.8 AlarmRingingService — фоновый звук

Foreground-сервис типа `mediaPlayback`. Играет рингтон, держит wake-lock, показывает high-priority уведомление, отвечает за fade-in.

**Константы:**
- `ACTION_START` / `ACTION_START_FADE` / `ACTION_DISMISS` — три команды.
- `FADE_DURATION_MS = 60_000`, `FADE_TICK_MS = 1_000`, `MIN_VOLUME = 0.05f`.

**Статические хелперы:**
- `start(ctx, alarmId, toneUri)` — основной сигнал.
- `startFadeIn(ctx, alarmId, toneUri)` — фаза нарастания.
- `stop(ctx)` — `context.stopService(...)` (безопаснее `startForegroundService(ACTION_DISMISS)`).
- Приватный `dispatch(...)` оборачивает запуск (`startForegroundService` на API 26+, иначе `startService`).

**`onStartCommand(intent, flags, startId)`:**
1. Парсит action и экстры.
2. Если `ACTION_DISMISS` — `stopSelfSafely()` (снимает foreground, освобождает плеер и wake-lock).
3. Иначе строит уведомление (`createAlarmNotification`) и зовёт `startForeground(...)`. На API 29+ передаёт тип `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`. **Оборачивает в `try/catch RuntimeException`** — это покрывает `SecurityException` (нет POST_NOTIFICATIONS) и `ForegroundServiceStartNotAllowedException` (API 34+).
4. Получает wake-lock (`PARTIAL_WAKE_LOCK`, авто-релиз через 10 минут).
5. Логика стрима:
   - Пред-сигнал (`preAlarm`): запускает звук с `fade=true`.
   - Основной + плеер уже играет с fade — мгновенно ставит громкость 1.0.
   - Основной + плеер не запущен (fade был выключен) — стартует с `fade=false`.

**Уведомление:**
- Канал `alarm_ringing_channel`, `IMPORTANCE_HIGH`, без системного звука (`setSound(null)`), `VISIBILITY_PUBLIC`.
- `PRIORITY_MAX`, `CATEGORY_ALARM`, `ongoing=true`, `autoCancel=false`.
- `contentIntent` — открывает `AlarmRingActivity`. **Только** в основном сигнале добавляется `setFullScreenIntent` — на пред-сигнале экран побудки открывать рано.
- `requestCode` PendingIntent-а = `alarmId` (или 3001 как fallback), чтобы разные будильники не затирали друг друга.

**Звук (`startAlarmSound(uri, fade)`):**
- `sanitizeToneUri` пропускает только `content://`, `android.resource://`, `file://` — защита от подмены `SharedPreferences` (например, через `adb backup`) на URI с произвольной схемой.
- Если URI пустой/невалидный — `RingtoneManager.getDefaultUri(TYPE_ALARM)`, иначе `TYPE_NOTIFICATION` как последний резерв.
- `MediaPlayer` с `USAGE_ALARM`, `CONTENT_TYPE_MUSIC`, `setLooping(true)`.
- `prepareAsync()`. В `onPrepared` есть **защита от race condition**: сравнивает `mp == mediaPlayer && mp == player`, чтобы быстрый `stop`+`start` не привёл к двум одновременно играющим плеерам.
- `onError` обнуляет ссылку, только если этот плеер всё ещё «текущий».

**Fade-in (`startFadeRamp`):**
- `Handler` на main, тикает раз в секунду.
- Громкость = `MIN_VOLUME + (1 − MIN_VOLUME) * elapsed/duration`, ограничена `[0, 1]`.
- Останов: `stopFadeRamp` снимает callbacks; `fading = false` после достижения 100%.

**`onDestroy`:** освобождает плеер, ramp, wake-lock, и **зовёт `AlarmScheduler.markDismissed(this, currentAlarmId)`** — чтобы основной сигнал не сработал повторно, если выключение случилось в фазе нарастания.

### 2.9 AlarmRingActivity — экран побудки

Полноэкранная активность, показывается поверх локскрина. Чтобы выключить — надо решить заданное число математических примеров.

**Подготовка окна (`prepareWakeUpScreen`):**
- `FLAG_KEEP_SCREEN_ON` — экран не гаснет.
- На API 27+ — `setShowWhenLocked(true)` + `setTurnScreenOn(true)`.
- На старее — устаревшие флаги `FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON`.

**Блокировка системного Back:**
```java
getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
    @Override public void handleOnBackPressed() { /* заблокировано */ }
});
```

**Состояние:**
- `difficulty` (1/2/3) и `requiredCount` — из `alarm_prefs`.
- `correctAnswer`, `currentExpression`, `solvedCount` — сохраняются в `onSaveInstanceState`, чтобы поворот экрана не сбрасывал прогресс.

**Логика проверки:**
- При вводе пустой строки — Toast «введите ответ».
- При `NumberFormatException` — Toast «некорректный ответ».
- Верно: `solvedCount++`. Если достигли `requiredCount` → `AlarmRingingService.stop(this)` + `finishAndRemoveTask()`. Иначе — генерируется новый пример.
- Неверно — Toast «неверно» и новый пример (старый сбрасывается, нельзя «угадать число ещё раз»).

**Прогресс-бейдж:** `tv_puzzle_progress` показывает «Решено N из M» только когда `requiredCount > 1`.

**Генератор примеров (`generateMathExample`):**
- **Уровень 1:** случайное `+` или `−`. Сумма ≤ 10. Результат вычитания ≥ 1.
- **Уровень 2:** `+`, `−` (до 50) или `×` (множители 2..7).
- **Уровень 3:** числа до 100; умножение с защитой `maxB = max(1, 100/a − 1)` — иначе `Random.nextInt(0)` бросил бы исключение при больших `a`.

### 2.10 BootReceiver — восстановление после ребута

Слушает `ACTION_BOOT_COMPLETED` и `MY_PACKAGE_REPLACED`.

**`rescheduleAlarms(ctx)`:**
- Читает `alarms_json`. Битый корневой массив — выходим.
- Для каждого включённого будильника:
  - Валидирует `id ≥ 0`, `hour ∈ [0..23]`, `minute ∈ [0..59]`.
  - Парсит `repeatDays` (если есть).
  - Зовёт `AlarmScheduler.schedule(...)`.
- Один битый объект — пропускаем, не валим весь цикл.

После этого, если есть привязанный CatClock — `DeviceSyncWorker.requestOneShot(ctx)` (устройство после ребута телефона потеряло связь — нужно ему отправить свежее время) и `schedulePeriodic`.

### 2.11 SettingsActivity — настройки

Экран настроек: домашняя зона, тёмная тема, рингтон, сложность примеров, число примеров, fade-in, привязка и статус устройства CatClock.

**`onCreate`:**
- До `super.onCreate` применяет тему (иначе пересоздаётся activity).
- Находит View, навешивает кнопки навигации.
- Кнопка «Подключить устройство» → `DeviceConnectionActivity`.
- «Синхронизировать»: `CatClockBleManager.connectAndSyncAll` с Toast-ом про результат.
- «Отвязать»: `disconnect()` + `setSavedDeviceAddress(null)` + `DeviceSyncWorker.cancelPeriodic()`.

**`setupAlarmToneSpinner`:**
- `new RingtoneManager((Context) this)` — **именно `(Context)`**, не `(Activity)`. Activity-вариант делает курсор managed, и при следующем `requery` после restart может прилететь `StaleDataException`.
- `try-with-resources` для курсора.
- Пропускает рингтоны с `null` URI или `null` Ringtone (битый медиа-индекс).
- Если ни одного рингтона нет — добавляет дефолт с пустым URI.

**`restoreSettings` / `saveSettings`:** простая запись/чтение всех ключей. После сохранения, если устройство привязано — `mgr.syncTime(null)` сразу обновляет offset на CatClock.

**Жизненный цикл соединения с устройством:**
- `onResume` — `setKeepConnected(true)`, регистрирует `statusListener`, при наличии устройства и отсутствии соединения — `connectAndSyncAll(null)`.
- `onPause` — `setKeepConnected(false)`, что приводит к `disconnect()`. Фоновые операции из других экранов соединение не держат.

**`updateDeviceUi(status, connected)`:** меняет надпись («Не привязано» / «Подключаемся…» / «v0.1, 3 будильника» / «Оффлайн, AA:BB…») и видимость кнопок Sync/Disconnect.

### 2.12 DeviceConnectionActivity — выбор устройства

Сканер BLE-устройств CatClock.

**Жизненный цикл:**
- `onStart`: `ensurePermissions` → `ensureBluetoothEnabled` → `ensureLocationEnabled` → `startScan`.
- `onStop`: `stopScan`.
- `onDestroy`: `handler.removeCallbacksAndMessages(null)` — отложенные коллбеки не должны держать activity.

**Разрешения:**
- API 31+ — `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`.
- До 31 — `ACCESS_FINE_LOCATION`.

**`ensureLocationEnabled`:** до Android 12 BLE-скан не находит устройств без включённых служб геолокации (даже если разрешение выдано). Поэтому проверяем `LocationManager.isLocationEnabled()` / `isProviderEnabled(GPS|NETWORK)` и показываем Toast.

**Сканирование:**
- Фильтр по `SERVICE_UUID` (один и тот же UUID, что в прошивке).
- `SCAN_MODE_LOW_LATENCY`.
- Таймаут 15 с: `handler.postDelayed(scanTimeout, ...)`. Если ничего не найдено — `tv_scan_hint = "Устройств не найдено"`.

**`onScanResult`:** дедупликация по MAC через `LinkedHashMap`. На каждое уникальное устройство добавляет строку с именем, MAC и RSSI.

**`selectDevice(device)`:**
1. Останавливает скан.
2. `CatClockBleManager.setSavedDeviceAddress(mac)` — сохраняет привязку.
3. Запускает `connectAndSyncAll`: при успехе — Toast + `DeviceSyncWorker.schedulePeriodic` + `setResult(RESULT_OK)` + `finish`. При ошибке — Toast «не удалось», `RESULT_CANCELED`, `finish`.

### 2.13 CatClockBleManager — BLE-клиент

Singleton (`get(ctx)`) с двойной проверкой блокировки. Хранит `BluetoothGatt`, очередь операций, слушатели статуса.

**UUID:** `SERVICE_UUID`, `CHAR_TIME_UUID` (запись JSON времени), `CHAR_ALARMS_UUID` (запись JSON массива будильников), `CHAR_CMD_UUID` (запись строки команды: `stop`/`snooze`/`ping`), `CHAR_STATUS_UUID` (read + notify). CCCD стандартный `2902`.

**Запрашиваемый MTU:** 247 — чтобы JSON с временем/будильниками пролез в одну ATT-запись, а не через медленный prepare/execute long-write.

**Сохранённый MAC:** `clock_prefs:cat_clock_mac`. Методы `getSavedDeviceAddress / setSavedDeviceAddress / hasPairedDevice`.

**Слушатели (`StatusListener`):** наблюдатели — `SettingsActivity` живой UI. `registerStatusListener` сразу присылает текущий снапшот.

**Класс `DeviceStatus`:** immutable — `firmware, alarmsCount, timeKnown, ringingId`. Парсится из notify-уведомления (JSON `{fw,alarms,timeKnown,ringing}`).

**Внутренний класс `Op`:** одна операция в очереди (`TIME`/`ALARMS`/`COMMAND`), знает свою характеристику и умеет построить payload (`buildPayload`).

**Публичный API:**
- `connectAndSyncAll(cb)` — кладёт в очередь Time + Alarms, запускает.
- `syncTime(cb)` / `syncAlarms(cb)` / `sendCommand(cmd, cb)` — по одной операции.
- `setKeepConnected(boolean)` — управляет тем, разрывать ли соединение после очистки очереди. `SettingsActivity` включает `true` в `onResume`, чтобы видеть живой статус по notify.
- `disconnect()` — закрывает GATT.
- `isConnected()`, `getLastStatus()`.

**Очередь и состояния:**
- `connecting` — `connectGatt` вызван, сервисы ещё не найдены. Если `run()` вызывается повторно в этом состоянии — операции просто добавляются в очередь, без второго `connectGatt` (иначе утечка).
- `discovered` — true после `onServicesDiscovered`.
- `disconnectAfterQueue` — выставляется в `run(...)` с учётом `keepConnected`.
- `pendingCallback` — текущий callback пользователя.

**`run(cb, disconnectAfter)`:**
1. Если нет MAC, нет `BLUETOOTH_CONNECT`, нет/выключен адаптер — `failPending(...)`.
2. Сохраняет callback.
3. Если уже connected+discovered — сразу `drainQueue()`.
4. Если уже `connecting` — выходим (ждём `onServicesDiscovered`).
5. Иначе ставит флаг `connecting`, зовёт `device.connectGatt(...)`. Возврат `null` (нехватка ресурсов BT-стека) — `failPending`. Ловит `IllegalArgumentException` (битый MAC) и `SecurityException`.

**`drainQueue()`:**
- Пустая очередь → если был callback, вызываем `onSuccess`; если надо разрывать — `gatt.disconnect()` через `postDelayed(200ms)` чтобы успел уехать ack последней записи.
- Иначе берёт первую `Op`, находит характеристику, строит payload, делает `writeCharacteristic` (для API 33+ — новый API с параметром `byte[]` и кодом возврата).
- На неуспех — `failPending("BLE write rejected")`.

**Колбэки (`BluetoothGattCallback`):**
Все приходят на binder-потоке — каждый коллбек первым делом `mainHandler.post(...)`, чтобы избежать гонок с очередью и обновлений UI из чужого потока.
- `onConnectionStateChange(CONNECTED)`: запрашивает MTU 247, при отказе сразу `discoverServices()` вручную.
- `onMtuChanged`: `discoverServices()`.
- `onServicesDiscovered`: `connecting = false`, `enableStatusNotifications`, `drainQueue()`.
- `onCharacteristicWrite`: успех → `opQueue.pollFirst()` + `drainQueue()`. Ошибка → `failPending`.
- `onCharacteristicChanged`: парсит JSON статуса через `handleStatusBytes`.
- `onCharacteristicRead`: то же для первичного чтения статуса.
- `onConnectionStateChange(DISCONNECTED)`: закрывает gatt, обнуляет состояние, оповещает листенеров.

**`enableStatusNotifications`:** `setCharacteristicNotification(true)` + запись `ENABLE_NOTIFICATION_VALUE` в дескриптор CCCD + первоначальный `readCharacteristic` (чтобы UI получил статус сразу, не дожидаясь следующего notify).

**Payload-генераторы:**
- `buildTimeJson(ctx)` → `{"epoch": <сек>, "offset": <сек>, "tz": "<id>"}`.
- `buildAlarmsJson(ctx)` — конвертирует `alarms_json` в формат прошивки: только `id, hour, minute, enabled, repeatDays`. Тон, label прошивке не нужны. Валидирует диапазоны.

### 2.14 DeviceSyncWorker — фоновая синхронизация

`androidx.work.Worker`. Гарантирует доставку времени и списка будильников даже если приложение не запущено.

**Уникальные имена работ:** `catclock_periodic_sync`, `catclock_oneshot_sync`, `catclock_command_<cmd>`.

**`doWork()`:**
- Если устройство не привязано или нет `BLUETOOTH_CONNECT` — `Result.success()` (повторять незачем).
- Берёт `command` из входных данных (необязательно).
- `CountDownLatch(1)` + `AtomicBoolean ok` — на main-потоке зовёт `mgr.sendCommand(...)` или `mgr.connectAndSyncAll(...)` с коллбеком, который пинает latch.
- Ждёт до 30 секунд. Успех → `Result.success()`; иначе `Result.retry()` (WorkManager сам повторит с back-off).

**Планировщики:**
- `schedulePeriodic(ctx)` — раз в час, `KEEP` (не сбрасывает таймер при повторных вызовах).
- `cancelPeriodic(ctx)`.
- `requestOneShot(ctx)` — сразу же, `REPLACE`.
- `requestCommand(ctx, cmd)` — одноразовая команда устройству («stop»/«snooze»), уникальное имя на команду.

---

## 3. BLE-протокол CatClock

| UUID | Имя | Свойства | Формат |
|---|---|---|---|
| `5a0f0001-7e8b-4d6c-9a2f-0e2b3c4d5e6f` | Service | — | — |
| `5a0f0002-…` | Time | WRITE | JSON `{"epoch":<сек UTC>, "offset":<сек>, "tz":"<id>"}` |
| `5a0f0003-…` | Alarms | WRITE | JSON `[{"id":…,"hour":…,"minute":…,"enabled":…,"repeatDays":[1..7]}, …]` |
| `5a0f0004-…` | Cmd | WRITE | ASCII: `stop` / `snooze` / `ping` |
| `5a0f0005-…` | Status | READ + NOTIFY | JSON `{"fw":"0.1","alarms":N,"ringing":<id|0>,"timeKnown":bool}` |

CCCD статуса включается приложением сразу после `onServicesDiscovered`. Прошивка анонсирует Service UUID в `advertisingData`, имя `CatClock` — в `scanResponseData` (иначе пакет в 31 байт переполнится). Имя устройства — `DEVICE_NAME = "CatClock"`, версия `FW_VERSION = "0.1"`.

**Дни недели:** репрезентация в JSON — числа 1..7 в стиле `java.util.Calendar` (1 = воскресенье, 2 = понедельник, …, 7 = суббота). Прошивка хранит их в `daysMask` (`bit i` для дня `i`), `daysMask == 0` означает однократный.

---

## 4. Прошивка ESP32 (clock_time_device.ino)

Файл `clock_time_device/clock_time_device.ino`. Требует **ESP32 Arduino core 3.x** (новый `ledcAttach(pin, freq, res)` / `ledcWriteTone(pin, freq)`), `TFT_eSPI` (Bodmer), `ArduinoJson v7`, встроенные `BLE*` из core.

### Пины и константы

| Имя | Значение | Назначение |
|---|---|---|
| `PIN_BUZZER` | 25 | пьезо-зуммер, PWM через LEDC |
| `PIN_BTN_STOP` | 32 | кнопка остановки (INPUT_PULLUP) |
| `PIN_BTN_SNOOZE` | 33 | кнопка snooze |
| `BEEP_HZ` | 2200 | частота пищания |
| `LEDC_RES` | 8 | разрешение LEDC |

Пины TFT-дисплея конфигурируются в `User_Setup.h` библиотеки `TFT_eSPI` (вне репозитория — см. историю с белым экраном выше).

### Глобальное состояние

| Переменная | Что хранит |
|---|---|
| `tft` | `TFT_eSPI` |
| `prefs` | `Preferences` (NVS namespace `clock`) |
| `alarms` | `std::vector<Alarm>` (id, hour, minute, enabled, daysMask) |
| `bleConnected` | флаг текущей connection-state |
| `epochAtSyncUtc`, `millisAtSync` | момент последней синхронизации |
| `tzOffsetSec`, `tzName` | смещение и id зоны |
| `timeKnown` | была ли хоть одна синхронизация |
| `ringingId` | 0 = не звонит; иначе id будильника или 255 для snooze |
| `ringStartedMs` | начало звонка (для авто-стопа через 60 с и pattern beep) |
| `snoozeAtUtc` | время повторного звонка после snooze |
| `lastFiredMinute` | минута-дня последнего срабатывания (защита от двойного триггера) |
| `pCharStatus` | характеристика статуса (для `notify`) |

### Структуры

```cpp
struct Alarm  { uint8_t id, hour, minute; bool enabled; uint8_t daysMask; };
struct BtnState { int pin; bool lastStable; uint32_t lastChangeMs; };
```

### Хелперы времени

- `nowUtc()` — `epochAtSyncUtc + (millis()-millisAtSync)/1000`.
- `breakdownLocal(utc, tm&)` — сдвигает UTC на `tzOffsetSec` и зовёт `gmtime_r`, получая локальные поля.
- `calendarDow(tm)` — конвертирует `tm_wday` в стиль `Calendar` (1..7).

### Персистенс

`Preferences` namespace `clock`:
- `alarms_json` — массив будильников в JSON.
- `tz_off` — int, секунд от UTC.
- `tz_name` — строка.

Методы `saveAlarms()` / `loadAlarms()` / `loadTzState()`. Сохранение делается каждый раз, когда меняется список (запись по BLE или авто-выключение одноразового будильника).

### Дисплей

Каждые 250 мс зовётся `renderDisplay()`. Он рисует **только то, что изменилось** (сравнивает `lastTimeStr/lastDateStr/lastNextStr/lastTzStr/lastConn` с актуальными значениями). Это убирает мерцание.

Слои сверху вниз:
1. Верхняя строка: индикатор `BLE * / BLE -` и название зоны (`Asia/Novosibirsk`).
2. Центр: огромное `HH:MM` (шрифт 7, цвет зелёный или красный при звонке).
3. Под центром: дата (`Sun 05 Jun`).
4. Самый низ: «`Next 07:30`» или «`Snooze 06:35`», или «`No alarms`».

`nextAlarmString()` — перебирает все включённые будильники на 8 дней вперёд, `mktime` нормализует поля, после чего `breakdownLocal` снова достаёт день недели — нужно, потому что после нормализации `tm_wday` может поменяться. Победитель — будильник с наименьшим `delta`. Snooze считается отдельно и сравнивается на равных.

### Зуммер

LEDC настраивается в `setup()`: `ledcAttach(PIN_BUZZER, BEEP_HZ, LEDC_RES)`.
- `buzzerOn()` = `ledcWriteTone(PIN_BUZZER, BEEP_HZ)`.
- `buzzerOff()` = `ledcWriteTone(PIN_BUZZER, 0)`.
- `updateBuzzer()` — pattern: 250 мс пик, 250 мс пауза, пока `ringingId != 0`.

### Движок будильников

**`notifyStatus()`** — пакует `{fw, alarms, ringing, timeKnown}` в JSON, кладёт в `pCharStatus` и зовёт `notify()` (если есть подписка). Вызывается при каждом изменении состояния и по команде `ping`.

**`triggerAlarm(id)`** — выставляет `ringingId = id`, `ringStartedMs = millis()`, шлёт notify.

**`stopRinging()`** — сбрасывает `ringingId`, выключает зуммер, шлёт notify.

**`snoozeRinging()`** — `snoozeAtUtc = nowUtc() + 5*60`, останавливает звонок, шлёт notify.

**`checkAlarms()`** (раз в секунду):
1. Если `!timeKnown` — выходим.
2. Считает `minuteOfDay`. Если совпадает с `lastFiredMinute` — выходим (уже проверяли в этой минуте).
3. **Snooze fires first:** если `snoozeAtUtc > 0 && nowUtc() >= snoozeAtUtc` — `triggerAlarm(255)` (специальный id).
4. Перебирает все будильники: если `hour/minute/dow` совпадают и `enabled` — звонит. Одноразовый (`daysMask == 0`) после срабатывания **сам себя выключает** (`enabled = false`); если такой нашёлся — `saveAlarms()` + `notifyStatus()` после цикла.
5. На один тик зазвонит только первый совпавший — остальные совпадения просто выключаются (если они одноразовые) или игнорируются.

### BLE-сервер

`startBle()`:
1. `BLEDevice::init("CatClock")`, MTU 247.
2. Создаёт сервер с `ServerCallbacks` (флаги connect/disconnect, при дисконнекте сразу `start()` рекламы).
3. Создаёт 4 характеристики с `WRITE` или `READ|NOTIFY` и добавляет `BLE2902` к статусу.
4. Запускает сервис.
5. Разделяет advertising/scanResponse: 128-битный UUID почти заполняет 31-байтный пакет рекламы, имя «CatClock» уезжает в scanResponse.

**Колбэки характеристик:**
- `TimeCallbacks::onWrite` — десериализует `{epoch, offset, tz}`, обновляет глобалы, **сбрасывает `lastFiredMinute = -1`** (чтобы при переводе времени назад прозвонивший только что будильник смог снова сработать в этой же минуте через минуту), пишет в `Preferences`, шлёт notify.
- `AlarmsCallbacks::onWrite` — десериализует массив, валидирует, перепаковывает в `daysMask`, `saveAlarms()`, `notifyStatus()`.
- `CmdCallbacks::onWrite` — `stop` → `stopRinging`; `snooze` → `snoozeRinging`; `ping` → `notifyStatus`.

### Кнопки

`buttonPressed(BtnState&)` — анти-дребезг 30 мс. Возвращает `true` **только в момент перехода** в LOW (rising-edge press). В `loop()`:
- `btnStop` → `stopRinging()`.
- `btnSnooze` → `snoozeRinging()`.

### `setup()`

1. `Serial.begin(115200)`.
2. Настройка пинов кнопок (INPUT_PULLUP).
3. LEDC для зуммера.
4. `tft.init()`, `tft.setRotation(1)`.
5. **Smoke-test:** R/G/B полосы на 1 секунду. Если их не видно после прошивки — проблема в `User_Setup.h` библиотеки TFT_eSPI (см. предыдущий разговор).
6. Заставка `CatClock / starting...`.
7. `prefs.begin("clock", false)`, загрузка зоны и будильников.
8. `startBle()`.
9. `forceRedraw = true` — главный экран отрисуется с нуля.

### `loop()`

1. Чтение кнопок.
2. Авто-стоп звонка через 60 с.
3. `updateBuzzer()` — pattern beep.
4. Раз в 250 мс — `renderDisplay()`.
5. Раз в 1000 мс — `checkAlarms()`.
6. `delay(5)` — не грузим ЦП на 100%.

---

## 5. Сборка и запуск

**Android (`./gradlew ...`):**

```
./gradlew assembleDebug          # APK
./gradlew installDebug           # установка на подключённое устройство
./gradlew build                  # debug + release
./gradlew test                   # юнит-тесты
./gradlew connectedAndroidTest   # инструментальные
./gradlew lint                   # Android Lint
./gradlew clean                  # очистка
```

**Прошивка ESP32 (Arduino IDE):**

1. Менеджер плат → ESP32 by Espressif **3.x**.
2. Менеджер библиотек:
   - `TFT_eSPI` (Bodmer);
   - `ArduinoJson` v7.
3. Сконфигурировать `Documents/Arduino/libraries/TFT_eSPI/User_Setup.h` под свой дисплей (драйвер ST7735, пины SPI/CS/DC/RST/BL, размеры 128×128 для красной 1.44").
4. Открыть `clock_time_device/clock_time_device.ino`, выбрать порт, Upload.
5. Открыть Serial Monitor 115200. Должны быть строки:
   ```
   [CatClock] boot
   [CatClock] TFT init
   [CatClock] loaded N alarms, tz=… off=…
   [CatClock] BLE start
   ```

---

## 6. Сценарии работы (end-to-end)

### Создание будильника на телефоне

1. `AlarmActivity` → FAB «+» → диалог.
2. Пользователь крутит часы/минуты, тапает дни.
3. `done` → `addNewAlarm`.
4. `scheduleAlarm` → `AlarmScheduler.schedule`: ставит основной + пред-сигнал (за 60 с).
5. `saveAlarms` → JSON в `alarm_prefs`, **сразу же** `CatClockBleManager.syncAlarms(null)` — список улетает на ESP32 по BLE.

### Срабатывание (телефон + устройство одновременно)

**На телефоне** в момент `T − 60s`:
1. `AlarmManager` будит `AlarmReceiver` (pre).
2. Запускается `AlarmRingingService` с `ACTION_START_FADE` → MediaPlayer с громкостью 0.05 и линейным разгоном до 1.0 за 60 секунд.

**В момент T:**
1. `AlarmReceiver` (main). Проверяет `consumeRecentDismiss` — пользователь мог выключить будильник за время разгона. Если нет — ramp выключается, громкость в 1.0, `AlarmRingActivity` стартует поверх локскрина.
2. Пользователь решает примеры → `AlarmRingingService.stop` → `finishAndRemoveTask`. Одноразовый помечается `enabled=false` в `alarms_json`. Повторяющийся — перепланируется на следующий день из `repeatDays`.

**На устройстве CatClock:**
1. `checkAlarms()` сравнивает текущую `hour:minute` с каждым будильником.
2. Совпадение → `triggerAlarm(id)`: зелёный цвет цифр → красный, пьезо-зуммер пищит по pattern 250/250 мс.
3. Кнопка STOP → `stopRinging`. Кнопка SNOOZE → `snoozeRinging` (+5 минут).
4. Авто-стоп через 60 секунд.
5. Если будильник был одноразовый — `enabled=false`, `saveAlarms()`, `notifyStatus()`. Телефон, если подключён, получит обновлённый статус.

### Перезагрузка телефона

1. `BootReceiver.onReceive`.
2. `rescheduleAlarms` пробегает по `alarms_json` и заново зовёт `AlarmScheduler.schedule(...)` для каждого включённого будильника.
3. Если есть привязанный CatClock — `DeviceSyncWorker.requestOneShot(ctx)` (устройство ничего не теряет, но получит свежее время), и `schedulePeriodic` (на случай если периодика не пережила).

### Открытие настроек

1. `SettingsActivity.onResume`: `setKeepConnected(true)`, регистрация листенера.
2. Если устройство привязано и не подключено — `connectAndSyncAll(null)` (отправит время и будильники, заодно получит notify со статусом).
3. UI обновляется по `onStatusChanged`: «CatClock v0.1, 3 будильника».
4. `onPause`: `setKeepConnected(false)` → соединение разрывается, чтобы не держать радио зря.

### Фоновая синхронизация

- `DeviceSyncWorker.schedulePeriodic` ставит уникальную периодическую работу `catclock_periodic_sync` раз в час (KEEP-policy).
- Каждый запуск `doWork()` идёт по очереди Time+Alarms через `CatClockBleManager`. Это поддерживает offset актуальным после перехода на летнее/зимнее время, даже если приложение не открывали неделями.
