package com.example.clock2;

import android.app.AlarmManager;
import android.app. PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TimePicker;
import android.widget.Toast;
import androidx.appcompat.app. AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util. Calendar;

public class AlarmActivity extends AppCompatActivity {

    private TimePicker timePicker;
    private Button setAlarmButton;
    private AlarmManager alarmManager;
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm);

        timePicker = findViewById(R.id.time_picker);
        setAlarmButton = findViewById(R.id.btn_set_alarm);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        setAlarmButton.setOnClickListener(v -> checkPermissionAndSetAlarm());
    }

    private void checkPermissionAndSetAlarm() {
        // Для Android 12 и выше требуется разрешение
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SCHEDULE_EXACT_ALARM)
                    != PackageManager.PERMISSION_GRANTED) {
                // Запрашиваем разрешение
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.SCHEDULE_EXACT_ALARM},
                        PERMISSION_REQUEST_CODE);
            } else {
                setAlarm();
            }
        } else {
            setAlarm();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setAlarm();
            } else {
                Toast.makeText(this, "Разрешение на установку будильника отклонено", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setAlarm() {
        int hour = timePicker.getHour();
        int minute = timePicker.getMinute();

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar. HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar. SECOND, 0);

        // Если время уже прошло, устанавливаем на следующий день
        if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent. FLAG_IMMUTABLE
        );

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.getTimeInMillis(),
                pendingIntent
        );

        Toast.makeText(this, "Будильник установлен на " + hour + ":" + String.format("%02d", minute),
                Toast.LENGTH_SHORT).show();
        finish();
    }
}