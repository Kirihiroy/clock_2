package com.example.clock2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * BLE-клиент устройства CatClock (см. clock_time_device.ino).
 * Открывает GATT, последовательно отправляет операции из очереди и
 * (по необходимости) отключается.
 */
public class CatClockBleManager {

    public static final UUID SERVICE_UUID     = UUID.fromString("5a0f0001-7e8b-4d6c-9a2f-0e2b3c4d5e6f");
    public static final UUID CHAR_TIME_UUID   = UUID.fromString("5a0f0002-7e8b-4d6c-9a2f-0e2b3c4d5e6f");
    public static final UUID CHAR_ALARMS_UUID = UUID.fromString("5a0f0003-7e8b-4d6c-9a2f-0e2b3c4d5e6f");
    public static final UUID CHAR_CMD_UUID    = UUID.fromString("5a0f0004-7e8b-4d6c-9a2f-0e2b3c4d5e6f");
    public static final UUID CHAR_STATUS_UUID = UUID.fromString("5a0f0005-7e8b-4d6c-9a2f-0e2b3c4d5e6f");

    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String KEY_DEVICE_MAC = "cat_clock_mac";
    private static final int    REQUESTED_MTU  = 247;

    private static volatile CatClockBleManager instance;

    public static CatClockBleManager get(Context ctx) {
        if (instance == null) {
            synchronized (CatClockBleManager.class) {
                if (instance == null) instance = new CatClockBleManager(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public interface StatusListener {
        void onStatusChanged(@Nullable DeviceStatus status, boolean connected);
    }

    public static final class DeviceStatus {
        public final String firmware;
        public final int alarmsCount;
        public final boolean timeKnown;
        public final int ringingId;

        DeviceStatus(String fw, int count, boolean timeKnown, int ringingId) {
            this.firmware = fw;
            this.alarmsCount = count;
            this.timeKnown = timeKnown;
            this.ringingId = ringingId;
        }
    }

    private final Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Deque<Op> opQueue = new ArrayDeque<>();
    private final Set<StatusListener> listeners = new LinkedHashSet<>();

    @Nullable private BluetoothGatt gatt;
    private boolean connected;
    private boolean discovered;
    private boolean connecting;       // connectGatt вызван, сервисы ещё не найдены
    private boolean keepConnected;    // не рвать соединение после очереди (экран Настроек открыт)
    @Nullable private DeviceStatus lastStatus;
    @Nullable private Callback pendingCallback;
    private boolean disconnectAfterQueue;

    private CatClockBleManager(Context ctx) {
        this.appCtx = ctx;
    }

    // ---------- сохранённый MAC ----------

    private SharedPreferences prefs() {
        return appCtx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    public String getSavedDeviceAddress() {
        String mac = prefs().getString(KEY_DEVICE_MAC, null);
        return (mac != null && !mac.isEmpty()) ? mac : null;
    }

    public void setSavedDeviceAddress(@Nullable String mac) {
        SharedPreferences.Editor e = prefs().edit();
        if (mac == null || mac.isEmpty()) e.remove(KEY_DEVICE_MAC);
        else e.putString(KEY_DEVICE_MAC, mac);
        e.apply();
    }

    public boolean hasPairedDevice() {
        return getSavedDeviceAddress() != null;
    }

    public boolean isConnected() {
        return connected;
    }

    @Nullable
    public DeviceStatus getLastStatus() {
        return lastStatus;
    }

    // ---------- слушатели статуса ----------

    public void registerStatusListener(StatusListener l) {
        listeners.add(l);
        l.onStatusChanged(lastStatus, connected);
    }

    public void unregisterStatusListener(StatusListener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (StatusListener l : listeners) l.onStatusChanged(lastStatus, connected);
    }

    // ---------- публичные точки входа ----------

    @MainThread
    public void connectAndSyncAll(@Nullable Callback cb) {
        enqueue(Op.time(), Op.alarms());
        run(cb, true);
    }

    /**
     * Управляет тем, держать ли BLE-соединение открытым после опустошения
     * очереди. Экран настроек включает это, чтобы видеть живой статус устройства,
     * и выключает в onPause. Фоновые синхронизации соединение не держат.
     */
    @MainThread
    public void setKeepConnected(boolean keep) {
        keepConnected = keep;
        if (!keep) disconnect();
    }

    @MainThread
    public void syncTime(@Nullable Callback cb) {
        enqueue(Op.time());
        run(cb, true);
    }

    @MainThread
    public void syncAlarms(@Nullable Callback cb) {
        enqueue(Op.alarms());
        run(cb, true);
    }

    /** Отправляет устройству команду (например, "stop" / "snooze"). */
    @MainThread
    public void sendCommand(String cmd, @Nullable Callback cb) {
        enqueue(Op.command(cmd));
        run(cb, true);
    }

    @MainThread
    public void disconnect() {
        if (gatt != null) {
            if (hasConnectPermission()) gatt.disconnect();
        } else {
            connected = false;
            lastStatus = null;
            notifyListeners();
        }
    }

    private void enqueue(Op... ops) {
        for (Op o : ops) opQueue.add(o);
    }

    private void run(@Nullable Callback cb, boolean disconnectAfter) {
        String mac = getSavedDeviceAddress();
        if (mac == null) {
            failPending("Устройство не привязано", cb);
            return;
        }
        if (!hasConnectPermission()) {
            failPending(appCtx.getString(R.string.device_permission_required), cb);
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            failPending(appCtx.getString(R.string.device_bluetooth_off), cb);
            return;
        }

        if (cb != null) pendingCallback = cb;
        disconnectAfterQueue = disconnectAfter && !keepConnected;

        if (connected && discovered && gatt != null) {
            drainQueue();
            return;
        }

        // Соединение уже устанавливается — операции уже в очереди, дождёмся
        // onServicesDiscovered. Повторный connectGatt привёл бы к утечке GATT.
        if (connecting) return;

        connecting = true;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            gatt = device.connectGatt(appCtx, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (IllegalArgumentException ex) {
            connecting = false;
            failPending("Некорректный MAC: " + mac, cb);
        } catch (SecurityException ex) {
            connecting = false;
            failPending(appCtx.getString(R.string.device_permission_required), cb);
        }
    }

    private void failPending(String msg, @Nullable Callback cb) {
        Callback target = cb != null ? cb : pendingCallback;
        pendingCallback = null;
        connecting = false;
        opQueue.clear();
        if (target != null) mainHandler.post(() -> target.onError(msg));
    }

    @Nullable
    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    public boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- очередь операций ----------

    private void drainQueue() {
        if (opQueue.isEmpty()) {
            Callback cb = pendingCallback;
            pendingCallback = null;
            if (cb != null) mainHandler.post(cb::onSuccess);
            if (disconnectAfterQueue && gatt != null && hasConnectPermission()) {
                mainHandler.postDelayed(() -> {
                    if (gatt != null) {
                        try { gatt.disconnect(); } catch (SecurityException ignored) {}
                    }
                }, 200);
            }
            return;
        }

        if (gatt == null) {
            failPending("Соединение потеряно", null);
            return;
        }
        BluetoothGattService svc = gatt.getService(SERVICE_UUID);
        if (svc == null) {
            failPending("Сервис CatClock не найден", null);
            return;
        }

        Op op = opQueue.peekFirst();
        if (op == null) return;
        BluetoothGattCharacteristic ch = svc.getCharacteristic(op.charUuid);
        if (ch == null) {
            failPending("Характеристика недоступна", null);
            return;
        }

        byte[] payload = op.buildPayload(appCtx);
        if (payload == null) {
            // Пустой apply — просто снимаем со стека
            opQueue.pollFirst();
            drainQueue();
            return;
        }

        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int rc = gatt.writeCharacteristic(ch, payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ok = rc == BluetoothGatt.GATT_SUCCESS;
            } else {
                ch.setValue(payload);
                ok = gatt.writeCharacteristic(ch);
            }
        } catch (SecurityException ex) {
            failPending(appCtx.getString(R.string.device_permission_required), null);
            return;
        }

        if (!ok) failPending("BLE write rejected", null);
    }

    // Все колбэки приходят на binder-потоке. Чтобы не было гонок с opQueue/gatt
    // и обновлений UI из чужого потока, вся работа перенаправляется на main.
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            mainHandler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true;
                    notifyListeners();
                    boolean mtuRequested = false;
                    try {
                        if (hasConnectPermission()) mtuRequested = g.requestMtu(REQUESTED_MTU);
                    } catch (SecurityException ignored) { /* fall back to discover */ }
                    // Если запрос MTU не стартовал — onMtuChanged не придёт,
                    // обнаружение сервисов нужно запустить вручную.
                    if (!mtuRequested) startDiscovery(g);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false;
                    discovered = false;
                    connecting = false;
                    lastStatus = null;
                    if (gatt != null && hasConnectPermission()) {
                        try { gatt.close(); } catch (SecurityException ignored) {}
                    }
                    gatt = null;
                    if (pendingCallback != null) {
                        failPending("Соединение разорвано (status=" + status + ")", null);
                    }
                    notifyListeners();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mainHandler.post(() -> startDiscovery(g));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            mainHandler.post(() -> {
                connecting = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failPending("Сервисы не найдены (status=" + status + ")", null);
                    return;
                }
                discovered = true;
                enableStatusNotifications(g);
                drainQueue();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            mainHandler.post(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failPending("Ошибка записи (status=" + status + ")", null);
                    return;
                }
                opQueue.pollFirst();
                drainQueue();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            handleStatusBytes(ch.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            handleStatusBytes(value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && CHAR_STATUS_UUID.equals(ch.getUuid())) {
                handleStatusBytes(ch.getValue());
            }
        }
    };

    private void startDiscovery(BluetoothGatt g) {
        try {
            if (hasConnectPermission()) g.discoverServices();
        } catch (SecurityException ex) {
            failPending(appCtx.getString(R.string.device_permission_required), null);
        }
    }

    private void enableStatusNotifications(BluetoothGatt g) {
        BluetoothGattService svc = g.getService(SERVICE_UUID);
        if (svc == null) return;
        BluetoothGattCharacteristic ch = svc.getCharacteristic(CHAR_STATUS_UUID);
        if (ch == null) return;
        try {
            if (!hasConnectPermission()) return;
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor d = ch.getDescriptor(CCCD_UUID);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            g.readCharacteristic(ch);
        } catch (SecurityException ignored) {}
    }

    private void handleStatusBytes(@Nullable byte[] value) {
        if (value == null || value.length == 0) return;
        try {
            JSONObject o = new JSONObject(new String(value, StandardCharsets.UTF_8));
            DeviceStatus s = new DeviceStatus(
                    o.optString("fw", "?"),
                    o.optInt("alarms", 0),
                    o.optBoolean("timeKnown", false),
                    o.optInt("ringing", 0));
            mainHandler.post(() -> {
                lastStatus = s;
                notifyListeners();
            });
        } catch (JSONException ignored) {}
    }

    // ---------- операции ----------

    private static final class Op {
        enum Kind { TIME, ALARMS, COMMAND }

        final Kind kind;
        final UUID charUuid;
        @Nullable final String command;  // только для Kind.COMMAND

        private Op(Kind kind, UUID u, @Nullable String command) {
            this.kind = kind;
            this.charUuid = u;
            this.command = command;
        }

        static Op time()                { return new Op(Kind.TIME,    CHAR_TIME_UUID,   null); }
        static Op alarms()               { return new Op(Kind.ALARMS,  CHAR_ALARMS_UUID, null); }
        static Op command(String cmd)    { return new Op(Kind.COMMAND, CHAR_CMD_UUID,    cmd);  }

        @Nullable
        byte[] buildPayload(Context ctx) {
            switch (kind) {
                case TIME:    return buildTimeJson(ctx).getBytes(StandardCharsets.UTF_8);
                case ALARMS:  return buildAlarmsJson(ctx).getBytes(StandardCharsets.UTF_8);
                case COMMAND: return (command != null ? command : "").getBytes(StandardCharsets.UTF_8);
                default:      return null;
            }
        }
    }

    private static String buildTimeJson(Context ctx) {
        SharedPreferences clockPrefs = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String tzId = clockPrefs.getString(MainActivity.KEY_TIMEZONE, TimeZone.getDefault().getID());
        TimeZone tz = TimeZone.getTimeZone(tzId);
        long nowMillis = System.currentTimeMillis();
        long epochSec  = nowMillis / 1000L;
        int  offsetSec = tz.getOffset(nowMillis) / 1000;
        try {
            JSONObject o = new JSONObject();
            o.put("epoch",  epochSec);
            o.put("offset", offsetSec);
            o.put("tz",     tzId);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Преобразует уже сохранённый список будильников из SharedPreferences в формат прошивки. */
    private static String buildAlarmsJson(Context ctx) {
        String stored = ctx.getSharedPreferences(AlarmActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AlarmActivity.KEY_ALARMS_JSON, "[]");
        try {
            JSONArray src = new JSONArray(stored);
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject in = src.getJSONObject(i);
                JSONObject dst = new JSONObject();
                dst.put("id",      in.optInt("id", i + 1));
                dst.put("hour",    Math.max(0, Math.min(23, in.optInt("hour", 0))));
                dst.put("minute",  Math.max(0, Math.min(59, in.optInt("minute", 0))));
                dst.put("enabled", in.optBoolean("enabled", true));
                JSONArray daysIn  = in.optJSONArray("repeatDays");
                JSONArray daysOut = new JSONArray();
                if (daysIn != null) {
                    for (int j = 0; j < daysIn.length(); j++) {
                        int d = daysIn.optInt(j, -1);
                        if (d >= 1 && d <= 7) daysOut.put(d);
                    }
                }
                dst.put("repeatDays", daysOut);
                out.put(dst);
            }
            return out.toString();
        } catch (JSONException e) {
            return "[]";
        }
    }
}
