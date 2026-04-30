package com.example.clock2;

import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

public class AlarmRingActivity extends AppCompatActivity {

    private final Random random = new Random();
    private int correctAnswer;
    private int difficulty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm_ring);
        prepareWakeUpScreen();
        difficulty = getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .getInt(AlarmActivity.KEY_DIFFICULTY, 1);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Блокируем обход задания через системную кнопку Back.
            }
        });

        TextView exampleText = findViewById(R.id.tv_math_example);
        EditText answerInput = findViewById(R.id.et_answer);
        Button dismissButton = findViewById(R.id.btn_dismiss_alarm);

        generateMathExample(exampleText);

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
                AlarmRingingService.stop(this);
                finishAndRemoveTask();
            } else {
                Toast.makeText(this, R.string.wrong_answer, Toast.LENGTH_SHORT).show();
                answerInput.setText("");
                generateMathExample(exampleText);
            }
        });
    }

    private void prepareWakeUpScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void generateMathExample(TextView view) {
        int a, b;
        String expression;

        if (difficulty == 1) {
            int operation = random.nextInt(2);
            if (operation == 0) {
                a = 1 + random.nextInt(5);
                b = 1 + random.nextInt(10 - a);
                correctAnswer = a + b;
                expression = a + " + " + b + " = ?";
            } else {
                a = 1 + random.nextInt(9);
                b = random.nextInt(a);
                correctAnswer = a - b;
                expression = a + " - " + b + " = ?";
            }
        } else if (difficulty == 2) {
            int operation = random.nextInt(3);
            if (operation == 0) {
                a = 1 + random.nextInt(25);
                b = 1 + random.nextInt(50 - a);
                correctAnswer = a + b;
                expression = a + " + " + b + " = ?";
            } else if (operation == 1) {
                a = 2 + random.nextInt(49);
                b = 1 + random.nextInt(a - 1);
                correctAnswer = a - b;
                expression = a + " - " + b + " = ?";
            } else {
                a = 2 + random.nextInt(6);
                b = 2 + random.nextInt(6);
                correctAnswer = a * b;
                expression = a + " × " + b + " = ?";
            }
        } else {
            int operation = random.nextInt(3);
            if (operation == 0) {
                a = 1 + random.nextInt(50);
                b = 1 + random.nextInt(100 - a);
                correctAnswer = a + b;
                expression = a + " + " + b + " = ?";
            } else if (operation == 1) {
                a = 2 + random.nextInt(98);
                b = 1 + random.nextInt(a - 1);
                correctAnswer = a - b;
                expression = a + " - " + b + " = ?";
            } else {
                a = 2 + random.nextInt(9);
                b = 2 + random.nextInt(100 / a - 1);
                correctAnswer = a * b;
                expression = a + " × " + b + " = ?";
            }
        }

        view.setText(expression);
    }
}