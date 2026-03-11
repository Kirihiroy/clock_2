package com.example.clock2;

import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

public class AlarmRingActivity extends AppCompatActivity {

    private int correctAnswer;
    private Ringtone ringtone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm_ring);

        TextView exampleText = findViewById(R.id.tv_math_example);
        EditText answerInput = findViewById(R.id.et_answer);
        Button dismissButton = findViewById(R.id.btn_dismiss_alarm);

        generateMathExample(exampleText);
        startAlarmSound();

        dismissButton.setOnClickListener(v -> {
            String answer = answerInput.getText().toString().trim();
            if (answer.isEmpty()) {
                Toast.makeText(this, R.string.enter_answer, Toast.LENGTH_SHORT).show();
                return;
            }

            int userAnswer;
            try {
                userAnswer = Integer.parseInt(answer);
            } catch (NumberFormatException ex) {
                Toast.makeText(this, R.string.invalid_answer, Toast.LENGTH_SHORT).show();
                return;
            }

            if (userAnswer == correctAnswer) {
                stopAlarmSound();
                finish();
            } else {
                Toast.makeText(this, R.string.wrong_answer, Toast.LENGTH_SHORT).show();
                answerInput.setText("");
                generateMathExample(exampleText);
            }
        });
    }

    private void generateMathExample(TextView view) {
        Random random = new Random();
        int a = 10 + random.nextInt(90);
        int b = 10 + random.nextInt(90);
        correctAnswer = a + b;
        view.setText(getString(R.string.math_example_format, a, b));
    }

    private void startAlarmSound() {
        String uriString = getIntent().getStringExtra(AlarmActivity.KEY_ALARM_TONE_URI);
        Uri alarmUri = uriString == null || uriString.isEmpty()
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                : Uri.parse(uriString);

        if (alarmUri == null) {
            alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        ringtone = RingtoneManager.getRingtone(this, alarmUri);
        if (ringtone != null) {
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            ringtone.play();
        }
    }

    private void stopAlarmSound() {
        if (ringtone != null && ringtone.isPlaying()) {
            ringtone.stop();
        }
    }

    @Override
    protected void onDestroy() {
        stopAlarmSound();
        super.onDestroy();
    }
}
