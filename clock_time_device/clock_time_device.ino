// CatClock — ESP32 firmware for clock_2 Android app sync via BLE.
// Libraries: TFT_eSPI (Bodmer), ArduinoJson v7. BLE from ESP32 core.
// REQUIRES ESP32 Arduino core 3.x — uses the new LEDC API
// (ledcAttach / ledcWriteTone(pin, freq)). Will not compile on core 2.x.

#include <Arduino.h>
#include <TFT_eSPI.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <time.h>
#include <vector>

// ---------- Pins ----------
static const int PIN_BUZZER     = 5;
static const int PIN_BTN_STOP   = 19;
static const int PIN_BTN_SNOOZE = 21;
// Одноцветная LED-лента, через MOSFET (logic-level, например IRLZ44N).
// 220 Ом между GPIO и Gate, 10 кОм pull-down с Gate на GND.
static const int PIN_LED_STRIP  = 17;

// ---------- BLE UUIDs ----------
#define SERVICE_UUID      "5a0f0001-7e8b-4d6c-9a2f-0e2b3c4d5e6f"
#define CHAR_TIME_UUID    "5a0f0002-7e8b-4d6c-9a2f-0e2b3c4d5e6f"
#define CHAR_ALARMS_UUID  "5a0f0003-7e8b-4d6c-9a2f-0e2b3c4d5e6f"
#define CHAR_CMD_UUID     "5a0f0004-7e8b-4d6c-9a2f-0e2b3c4d5e6f"
#define CHAR_STATUS_UUID  "5a0f0005-7e8b-4d6c-9a2f-0e2b3c4d5e6f"
#define CHAR_LED_UUID     "5a0f0006-7e8b-4d6c-9a2f-0e2b3c4d5e6f"

#define DEVICE_NAME       "CatClock"
#define FW_VERSION        "0.1"

// ---------- Alarm model ----------
struct Alarm {
  uint8_t id;
  uint8_t hour;
  uint8_t minute;
  bool    enabled;
  uint8_t daysMask;  // bits 1..7 follow java.util.Calendar (1=Sun..7=Sat); 0 = one-shot
};

// Defined up here (not next to its function) so Arduino IDE's auto-generated
// prototypes at the top of the file can resolve the type.
struct BtnState {
  int pin;
  bool lastStable;   // confirmed debounced state
  bool lastRaw;      // last raw reading (restarts debounce timer on change)
  uint32_t lastChangeMs;
};

// ---------- Globals ----------
TFT_eSPI tft = TFT_eSPI();
Preferences prefs;

std::vector<Alarm> alarms;

volatile bool   bleConnected   = false;
int64_t  epochAtSyncUtc = 0;     // UTC seconds at last sync
uint32_t millisAtSync   = 0;
int32_t  tzOffsetSec    = 0;     // current UTC offset, includes DST
String   tzName         = "";    // IANA id, for display only
bool     timeKnown      = false;

uint8_t  ringingId      = 0;     // 0 = not ringing; otherwise alarm id (or 255 for snooze-fired)
uint32_t ringStartedMs  = 0;
int64_t  snoozeAtUtc    = 0;     // 0 = no snooze pending
int16_t  lastFiredMinute = -1;   // minute-of-day of last fire, to avoid re-trigger

// Подсветка лентой: целевая яркость задаётся телефоном по BLE,
// фактическая плавно подтягивается к ней (fade ~500 мс).
uint8_t  ledTarget       = 0;
float    ledCurrent      = 0.0f;
uint32_t ledLastTickMs   = 0;

BLECharacteristic* pCharStatus = nullptr;

// ---------- Helpers ----------
static int64_t nowUtc() {
  if (!timeKnown) return 0;
  uint32_t delta = (uint32_t)(millis() - millisAtSync);
  return epochAtSyncUtc + (int64_t)(delta / 1000);
}

static void breakdownLocal(int64_t utc, struct tm& out) {
  time_t local = (time_t)(utc + tzOffsetSec);
  gmtime_r(&local, &out);  // treat shifted time as if it were UTC -> fields become local
}

static uint8_t calendarDow(const struct tm& t) {
  return (uint8_t)(t.tm_wday + 1);  // 1=Sun..7=Sat
}

// ---------- Persistence ----------
static void saveAlarms() {
  JsonDocument doc;
  JsonArray arr = doc.to<JsonArray>();
  for (const auto& a : alarms) {
    JsonObject o = arr.add<JsonObject>();
    o["id"] = a.id;
    o["hour"] = a.hour;
    o["minute"] = a.minute;
    o["enabled"] = a.enabled;
    JsonArray d = o["repeatDays"].to<JsonArray>();
    for (uint8_t i = 1; i <= 7; ++i) if (a.daysMask & (1 << i)) d.add(i);
  }
  String s;
  serializeJson(doc, s);
  prefs.putString("alarms_json", s);
}

static void loadAlarms() {
  alarms.clear();
  String s = prefs.getString("alarms_json", "[]");
  JsonDocument doc;
  if (deserializeJson(doc, s)) return;
  for (JsonObject o : doc.as<JsonArray>()) {
    Alarm a{};
    a.id      = (uint8_t)(o["id"] | 0);
    a.hour    = (uint8_t)constrain((int)(o["hour"]   | 0), 0, 23);
    a.minute  = (uint8_t)constrain((int)(o["minute"] | 0), 0, 59);
    a.enabled = o["enabled"] | false;
    uint8_t mask = 0;
    for (int d : o["repeatDays"].as<JsonArray>()) {
      if (d >= 1 && d <= 7) mask |= (1 << d);
    }
    a.daysMask = mask;
    alarms.push_back(a);
  }
}

static void loadTzState() {
  tzOffsetSec = prefs.getInt("tz_off", 0);
  tzName      = prefs.getString("tz_name", "");
}

// ---------- Display ----------
static String lastTimeStr  = "";
static String lastDateStr  = "";
static String lastNextStr  = "";
static String lastTzStr    = "";
static bool   lastConn     = false;
static bool   forceRedraw  = true;

static String nextAlarmString() {
  if (!timeKnown) return "";
  int64_t now = nowUtc();
  int64_t bestDelta = INT64_MAX;
  const Alarm* best = nullptr;
  bool bestIsSnooze = false;

  if (snoozeAtUtc > 0 && snoozeAtUtc > now) {
    bestDelta = snoozeAtUtc - now;
    bestIsSnooze = true;
  }

  struct tm lt; breakdownLocal(now, lt);
  for (const auto& a : alarms) {
    if (!a.enabled) continue;
    // For each of the next 8 days, see if this alarm fires.
    for (int dayAhead = 0; dayAhead < 8; ++dayAhead) {
      struct tm cand = lt;
      cand.tm_mday += dayAhead;
      cand.tm_hour = a.hour;
      cand.tm_min  = a.minute;
      cand.tm_sec  = 0;
      time_t candLocal = mktime(&cand);  // normalizes; cand fields are "local-as-UTC"
      int64_t candUtc = (int64_t)candLocal - tzOffsetSec;
      if (candUtc <= now) continue;
      // Re-derive weekday after normalization
      struct tm norm; breakdownLocal(candUtc, norm);
      uint8_t dow = calendarDow(norm);
      bool match = (a.daysMask == 0) || (a.daysMask & (1 << dow));
      if (!match) continue;
      int64_t delta = candUtc - now;
      if (delta < bestDelta) { bestDelta = delta; best = &a; bestIsSnooze = false; }
      break;  // earliest hit for this alarm
    }
  }

  char buf[48];
  if (bestIsSnooze) {
    struct tm st; breakdownLocal(snoozeAtUtc, st);
    snprintf(buf, sizeof(buf), "Snooze %02d:%02d", st.tm_hour, st.tm_min);
    return String(buf);
  }
  if (!best) return "No alarms";
  snprintf(buf, sizeof(buf), "Next %02d:%02d", best->hour, best->minute);
  return String(buf);
}

static void drawStatic() {
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
}

static void renderDisplay() {
  if (forceRedraw) { drawStatic(); forceRedraw = false; lastTimeStr=lastDateStr=lastNextStr=lastTzStr=""; lastConn=!bleConnected; }

  int w = tft.width();

  // BLE indicator + TZ name (top row)
  if (lastConn != bleConnected || lastTzStr != tzName) {
    tft.fillRect(0, 0, w, 18, TFT_BLACK);
    tft.setTextColor(bleConnected ? TFT_CYAN : TFT_DARKGREY, TFT_BLACK);
    tft.setTextDatum(TL_DATUM);
    tft.drawString(bleConnected ? "BLE *" : "BLE -", 2, 2, 2);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(TR_DATUM);
    tft.drawString(tzName.length() ? tzName : "(no tz)", w - 2, 2, 2);
    lastConn  = bleConnected;
    lastTzStr = tzName;
  }

  if (!timeKnown) {
    String msg = "Waiting for phone...";
    if (msg != lastTimeStr) {
      tft.fillRect(0, 20, w, tft.height() - 20, TFT_BLACK);
      tft.setTextColor(TFT_YELLOW, TFT_BLACK);
      tft.setTextDatum(MC_DATUM);
      tft.drawString(msg, w / 2, tft.height() / 2, 2);
      lastTimeStr = msg;
    }
    return;
  }

  struct tm lt; breakdownLocal(nowUtc(), lt);
  char tbuf[8]; snprintf(tbuf, sizeof(tbuf), "%02d:%02d", lt.tm_hour, lt.tm_min);
  char dbuf[24];
  static const char* monNames[] = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
  static const char* dowNames[] = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
  snprintf(dbuf, sizeof(dbuf), "%s %02d %s", dowNames[lt.tm_wday], lt.tm_mday, monNames[lt.tm_mon]);

  String tstr = String(tbuf);
  String dstr = String(dbuf);

  // Big time
  if (tstr != lastTimeStr) {
    bool ringing = (ringingId != 0);
    uint16_t fg = ringing ? TFT_RED : TFT_GREEN;
    tft.setTextColor(fg, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    int cy = tft.height() / 2 - 8;
    tft.fillRect(0, cy - 30, w, 60, TFT_BLACK);
    tft.drawString(tstr, w / 2, cy, 7);
    lastTimeStr = tstr;
  }

  // Date
  if (dstr != lastDateStr) {
    int y = tft.height() - 38;
    tft.fillRect(0, y, w, 18, TFT_BLACK);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.drawString(dstr, w / 2, y + 2, 2);
    lastDateStr = dstr;
  }

  // Next alarm line
  String nstr = nextAlarmString();
  if (nstr != lastNextStr) {
    int y = tft.height() - 18;
    tft.fillRect(0, y, w, 18, TFT_BLACK);
    tft.setTextColor(TFT_ORANGE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.drawString(nstr, w / 2, y, 2);
    lastNextStr = nstr;
  }
}

// ---------- Buzzer ----------
static const int LEDC_RES   = 8;
static const int BEEP_HZ    = 2200;

static void buzzerOn()  { ledcWriteTone(PIN_BUZZER, BEEP_HZ); }
static void buzzerOff() { ledcWriteTone(PIN_BUZZER, 0); }

static void updateBuzzer() {
  if (ringingId == 0) { buzzerOff(); return; }
  uint32_t t = millis() - ringStartedMs;
  // Pattern: 250 ms on, 250 ms off
  bool on = ((t / 250) % 2) == 0;
  if (on) buzzerOn(); else buzzerOff();
}

// ---------- LED-лента ----------
// Отдельный LEDC-канал для PWM яркости. Частота 5 кГц, разрешение 8 бит.
static const int LED_PWM_FREQ = 5000;
static const int LED_PWM_RES  = 8;

// Скорость fade: за 500 мс полный диапазон 0..255 (~0.5 единицы яркости за мс).
static const float LED_FADE_PER_MS = 255.0f / 500.0f;

static void ledWriteRaw(uint8_t value) {
  ledcWrite(PIN_LED_STRIP, value);
}

static void updateLed() {
  uint32_t now = millis();
  uint32_t dt  = now - ledLastTickMs;
  if (dt < 10) return;             // обновляем не чаще ~100 Гц
  ledLastTickMs = now;

  // Когда звонит будильник — переопределяем подсветку: пульс ~1 Гц на полную.
  uint8_t target;
  if (ringingId != 0) {
    uint32_t t = now - ringStartedMs;
    // треугольная волна 0..255..0 с периодом 1000 мс
    uint32_t phase = t % 1000;
    target = (phase < 500) ? (uint8_t)(phase * 255 / 500)
                           : (uint8_t)((1000 - phase) * 255 / 500);
  } else {
    target = ledTarget;
  }

  // Плавная подтяжка ledCurrent к target
  float step = LED_FADE_PER_MS * (float)dt;
  if (ledCurrent < (float)target) {
    ledCurrent += step;
    if (ledCurrent > (float)target) ledCurrent = (float)target;
  } else if (ledCurrent > (float)target) {
    ledCurrent -= step;
    if (ledCurrent < (float)target) ledCurrent = (float)target;
  }
  ledWriteRaw((uint8_t)ledCurrent);
}

// ---------- Alarm engine ----------
static void notifyStatus() {
  if (!pCharStatus) return;
  JsonDocument d;
  d["fw"] = FW_VERSION;
  d["alarms"] = (int)alarms.size();
  d["ringing"] = (int)ringingId;
  d["timeKnown"] = timeKnown;
  String s; serializeJson(d, s);
  pCharStatus->setValue(s.c_str());
  if (bleConnected) pCharStatus->notify();
}

static void triggerAlarm(uint8_t id) {
  ringingId = id;
  ringStartedMs = millis();
  Serial.printf("[CatClock] alarm fire id=%u\n", (unsigned)id);
  notifyStatus();
}

static void stopRinging() {
  if (ringingId == 0) return;
  ringingId = 0;
  buzzerOff();
  notifyStatus();
}

static void snoozeRinging() {
  if (ringingId == 0) return;
  snoozeAtUtc = nowUtc() + 5 * 60;
  ringingId = 0;
  buzzerOff();
  notifyStatus();
}

static void checkAlarms() {
  if (!timeKnown) return;
  struct tm lt; breakdownLocal(nowUtc(), lt);
  int16_t minuteOfDay = lt.tm_hour * 60 + lt.tm_min;
  if (minuteOfDay == lastFiredMinute) return;  // already evaluated this minute
  lastFiredMinute = minuteOfDay;

  // Snooze fires first
  if (snoozeAtUtc > 0 && nowUtc() >= snoozeAtUtc) {
    snoozeAtUtc = 0;
    triggerAlarm(255);
    return;
  }

  uint8_t dow = calendarDow(lt);
  bool fired = false;
  bool oneShotConsumed = false;
  for (auto& a : alarms) {
    if (!a.enabled) continue;
    if (a.hour != lt.tm_hour || a.minute != lt.tm_min) continue;
    if (a.daysMask != 0 && !(a.daysMask & (1 << dow))) continue;
    // One-shot alarm (no repeat days): fires once, then disables itself so it
    // does not ring again the next day. Repeating alarms stay enabled.
    if (a.daysMask == 0) { a.enabled = false; oneShotConsumed = true; }
    // Only one buzzer at a time; the first match rings, the rest just disarm.
    if (!fired) { triggerAlarm(a.id); fired = true; }
  }
  if (oneShotConsumed) { saveAlarms(); notifyStatus(); }
}

// ---------- BLE callbacks ----------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override    { bleConnected = true;  notifyStatus(); }
  void onDisconnect(BLEServer* s) override {
    bleConnected = false;
    s->getAdvertising()->start();  // resume advertising
  }
};

class TimeCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    JsonDocument doc;
    if (deserializeJson(doc, v)) return;
    int64_t epoch = (int64_t)(doc["epoch"] | (long long)0);
    int32_t off   = (int32_t)(doc["offset"] | 0);
    const char* tz = doc["tz"] | "";
    if (epoch <= 0) return;
    epochAtSyncUtc = epoch;
    millisAtSync   = millis();
    tzOffsetSec    = off;
    tzName         = String(tz);
    timeKnown      = true;
    lastFiredMinute = -1;  // re-arm so a just-passed alarm in current minute can fire if applicable
    prefs.putInt("tz_off", tzOffsetSec);
    prefs.putString("tz_name", tzName);
    Serial.printf("[CatClock] time sync: epoch=%lld off=%d tz=%s\n",
                  (long long)epoch, (int)off, tz);
    notifyStatus();
  }
};

class AlarmsCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    JsonDocument doc;
    if (deserializeJson(doc, v)) return;
    if (!doc.is<JsonArray>()) return;
    alarms.clear();
    for (JsonObject o : doc.as<JsonArray>()) {
      Alarm a{};
      a.id      = (uint8_t)(o["id"] | 0);
      a.hour    = (uint8_t)constrain((int)(o["hour"]   | 0), 0, 23);
      a.minute  = (uint8_t)constrain((int)(o["minute"] | 0), 0, 59);
      a.enabled = o["enabled"] | false;
      uint8_t mask = 0;
      for (int d : o["repeatDays"].as<JsonArray>()) {
        if (d >= 1 && d <= 7) mask |= (1 << d);
      }
      a.daysMask = mask;
      alarms.push_back(a);
    }
    saveAlarms();
    notifyStatus();
  }
};

class CmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    if (v == "stop")        stopRinging();
    else if (v == "snooze") snoozeRinging();
    else if (v == "ping")   notifyStatus();
  }
};

class LedCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    if (v.length() == 0) return;
    // Принимаем два формата: одиночный байт (0..255) или JSON {"b":N}.
    // Это даёт совместимость с будущими расширениями (например, режим).
    int value = -1;
    if (v.length() == 1) {
      value = (int)(uint8_t)v[0];
    } else {
      JsonDocument doc;
      if (deserializeJson(doc, v) == DeserializationError::Ok) {
        value = doc["b"] | -1;
      } else {
        // Не JSON и не один байт — пробуем как ASCII число
        value = atoi(v.c_str());
      }
    }
    if (value < 0)   value = 0;
    if (value > 255) value = 255;
    ledTarget = (uint8_t)value;
    Serial.printf("[CatClock] led target=%d\n", value);
  }
};

static void startBle() {
  BLEDevice::init(DEVICE_NAME);
  // Negotiate a large MTU so the phone can push the time/alarms JSON in a
  // single ATT write instead of relying on the long-write (prepare/execute)
  // fallback. The phone requests 247; without this the link stays at 23.
  BLEDevice::setMTU(247);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService* svc = server->createService(SERVICE_UUID);

  auto* cTime = svc->createCharacteristic(CHAR_TIME_UUID,   BLECharacteristic::PROPERTY_WRITE);
  cTime->setCallbacks(new TimeCallbacks());

  auto* cAlarms = svc->createCharacteristic(CHAR_ALARMS_UUID, BLECharacteristic::PROPERTY_WRITE);
  cAlarms->setCallbacks(new AlarmsCallbacks());

  auto* cCmd = svc->createCharacteristic(CHAR_CMD_UUID, BLECharacteristic::PROPERTY_WRITE);
  cCmd->setCallbacks(new CmdCallbacks());

  auto* cLed = svc->createCharacteristic(CHAR_LED_UUID, BLECharacteristic::PROPERTY_WRITE);
  cLed->setCallbacks(new LedCallbacks());

  pCharStatus = svc->createCharacteristic(CHAR_STATUS_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pCharStatus->addDescriptor(new BLE2902());

  svc->start();

  // The 128-bit service UUID (16 B) almost fills the 31-byte advertising
  // packet, so keep the device name in the separate scan-response packet to
  // avoid overflow (which would silently drop the name or the UUID).
  BLEAdvertising* adv = BLEDevice::getAdvertising();

  BLEAdvertisementData advData;
  advData.setFlags(0x06);  // LE General Discoverable, BR/EDR not supported
  advData.setCompleteServices(BLEUUID(SERVICE_UUID));
  adv->setAdvertisementData(advData);

  BLEAdvertisementData scanResp;
  scanResp.setName(DEVICE_NAME);
  adv->setScanResponseData(scanResp);
  adv->setScanResponse(true);

  BLEDevice::startAdvertising();
}

// ---------- Buttons ----------
BtnState btnStop   { PIN_BTN_STOP,   true, true, 0 };
BtnState btnSnooze { PIN_BTN_SNOOZE, true, true, 0 };

static bool buttonPressed(BtnState& b) {
  bool raw = digitalRead(b.pin);  // INPUT_PULLUP: LOW = pressed
  uint32_t now = millis();
  // Restart debounce timer on every raw change (proper Bounce2 approach).
  if (raw != b.lastRaw) {
    b.lastRaw = raw;
    b.lastChangeMs = now;
  }
  // Confirm stable state only after 30 ms of no change.
  if (b.lastRaw != b.lastStable && (now - b.lastChangeMs) >= 30) {
    b.lastStable = b.lastRaw;
    if (b.lastStable == LOW) {
      Serial.printf("[CatClock] btn pin=%d pressed\n", b.pin);
      return true;
    }
  }
  return false;
}

// ---------- Setup / loop ----------
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println("[CatClock] boot");

  pinMode(PIN_BTN_STOP,   INPUT_PULLUP);
  pinMode(PIN_BTN_SNOOZE, INPUT_PULLUP);

  ledcAttach(PIN_BUZZER, BEEP_HZ, LEDC_RES);
  buzzerOff();

  // Отдельный PWM-канал для LED-ленты.
  ledcAttach(PIN_LED_STRIP, LED_PWM_FREQ, LED_PWM_RES);
  ledWriteRaw(0);
  ledLastTickMs = millis();

  // Тест LED-ленты.
  Serial.println("[CatClock] LED strip test: PWM fade");
  for (int v = 0; v <= 255; v += 3) { ledWriteRaw((uint8_t)v); delay(6); }
  for (int v = 255; v >= 0; v -= 3) { ledWriteRaw((uint8_t)v); delay(6); }
  // Постоянный HIGH — должна гореть на 100%, легко мерить мультиметром на GPIO17.
  Serial.println("[CatClock] LED strip test: full ON 3s (GPIO17 = HIGH)");
  digitalWrite(PIN_LED_STRIP, HIGH);
  delay(3000);
  digitalWrite(PIN_LED_STRIP, LOW);
  // Восстанавливаем LEDC для нормальной работы.
  ledcAttach(PIN_LED_STRIP, LED_PWM_FREQ, LED_PWM_RES);
  ledWriteRaw(0);
  Serial.println("[CatClock] LED strip test done");

  Serial.println("[CatClock] TFT init");
  tft.init();
  tft.setRotation(3);

  // Smoke-test: R/G/B stripes. If you don't see them, the display/pinout is wrong.
  int w = tft.width(), h = tft.height();
  tft.fillRect(0,        0, w / 3,         h, TFT_RED);
  tft.fillRect(w / 3,    0, w / 3,         h, TFT_GREEN);
  tft.fillRect(2 * w/3,  0, w - 2*(w/3),   h, TFT_BLUE);
  delay(1000);

  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.drawString("CatClock", tft.width()/2, tft.height()/2 - 10, 4);
  tft.drawString("starting...", tft.width()/2, tft.height()/2 + 18, 2);

  prefs.begin("clock", false);
  loadTzState();
  loadAlarms();
  Serial.printf("[CatClock] loaded %d alarms, tz=%s off=%d\n",
                (int)alarms.size(), tzName.c_str(), (int)tzOffsetSec);

  Serial.println("[CatClock] BLE start");
  startBle();

  forceRedraw = true;
}

void loop() {
  static uint32_t lastSec   = 0;
  static uint32_t lastCheck = 0;

  if (buttonPressed(btnStop))   stopRinging();
  if (buttonPressed(btnSnooze)) snoozeRinging();

  // Auto-stop after 60 s
  if (ringingId != 0 && (millis() - ringStartedMs) > 60000UL) stopRinging();

  updateBuzzer();
  updateLed();

  uint32_t now = millis();
  if (now - lastSec >= 250) {
    lastSec = now;
    renderDisplay();
  }
  if (now - lastCheck >= 1000) {
    lastCheck = now;
    checkAlarms();
  }

  delay(5);
}
