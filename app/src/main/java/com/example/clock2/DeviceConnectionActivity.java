package com.example.clock2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DeviceConnectionActivity extends AppCompatActivity {

    private static final int  REQ_PERMS    = 9001;
    private static final long SCAN_TIMEOUT_MS = 15_000L;

    private LinearLayout listLayout;
    private TextView hintText;
    private BluetoothLeScanner scanner;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private boolean scanning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanTimeout = () -> {
        stopScan();
        if (found.isEmpty()) hintText.setText(R.string.device_scan_empty);
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null || d.getAddress() == null) return;
            if (found.containsKey(d.getAddress())) return;
            found.put(d.getAddress(), d);
            String name = safeDeviceName(d);
            int rssi = result.getRssi();
            addDeviceRow(d, name, rssi);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Toast.makeText(DeviceConnectionActivity.this,
                    "BLE scan failed: " + errorCode, Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_connection);
        listLayout = findViewById(R.id.layout_device_list);
        hintText   = findViewById(R.id.tv_scan_hint);

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.device_ble_not_supported, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensurePermissions()) return;
        if (!ensureBluetoothEnabled()) return;
        if (!ensureLocationEnabled()) return;
        startScan();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopScan();
    }

    // ---------- разрешения / BT ----------

    private boolean ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (needed.isEmpty()) return true;
        ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.device_permission_required, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        if (ensureBluetoothEnabled() && ensureLocationEnabled()) startScan();
    }

    /**
     * До Android 12 BLE-скан требует не только разрешения геолокации, но и
     * включённых служб геолокации — иначе startScan молча ничего не находит.
     */
    private boolean ensureLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean enabled;
        if (lm == null) {
            enabled = false;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            enabled = lm.isLocationEnabled();
        } else {
            enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        }
        if (!enabled) {
            Toast.makeText(this, R.string.device_location_required, Toast.LENGTH_LONG).show();
        }
        return enabled;
    }

    private boolean ensureBluetoothEnabled() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            Toast.makeText(this, R.string.device_ble_not_supported, Toast.LENGTH_LONG).show();
            finish();
            return false;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, R.string.device_bluetooth_off, Toast.LENGTH_LONG).show();
            return false;
        }
        scanner = adapter.getBluetoothLeScanner();
        return scanner != null;
    }

    // ---------- скан ----------

    private void startScan() {
        if (scanning || scanner == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        found.clear();
        listLayout.removeAllViews();
        hintText.setText(R.string.device_scan_hint);

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(CatClockBleManager.SERVICE_UUID))
                .build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException ex) {
            Toast.makeText(this, R.string.device_permission_required, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (!scanning || scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {}
        scanning = false;
    }

    // ---------- список ----------

    private void addDeviceRow(BluetoothDevice device, String name, int rssi) {
        TextView row = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 8);
        row.setLayoutParams(lp);
        row.setPadding(24, 24, 24, 24);
        row.setBackgroundColor(0x22FFFFFF);
        row.setTextColor(0xFFFFFFFF);
        row.setTextSize(16f);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setText(String.format(Locale.getDefault(),
                "%s\n%s · RSSI %d", name, device.getAddress(), rssi));
        row.setOnClickListener(v -> selectDevice(device));
        listLayout.addView(row);
    }

    private String safeDeviceName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? getString(R.string.device_unknown_name) : n;
        } catch (SecurityException ex) {
            return getString(R.string.device_unknown_name);
        }
    }

    private void selectDevice(BluetoothDevice device) {
        stopScan();
        CatClockBleManager mgr = CatClockBleManager.get(this);
        mgr.setSavedDeviceAddress(device.getAddress());
        Toast.makeText(this, "Подключение к " + device.getAddress() + "…", Toast.LENGTH_SHORT).show();
        mgr.connectAndSyncAll(new CatClockBleManager.Callback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(DeviceConnectionActivity.this,
                            R.string.device_sync_success, Toast.LENGTH_SHORT).show();
                    // Устройство привязано — запускаем фоновую автосинхронизацию.
                    DeviceSyncWorker.schedulePeriodic(getApplicationContext());
                    setResult(RESULT_OK, new Intent());
                    finish();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(DeviceConnectionActivity.this,
                            getString(R.string.device_sync_failed, message), Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED, new Intent());
                    finish();
                });
            }
        });
    }
}
