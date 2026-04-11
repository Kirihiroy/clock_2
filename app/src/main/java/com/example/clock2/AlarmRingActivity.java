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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm_ring);
        prepareWakeUpScreen();

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
        int operation = random.nextInt(3);
        int a;
        int b;
        String expression;

        if (operation == 0) {
            a = 10 + random.nextInt(90);
            b = 10 + random.nextInt(90);
            correctAnswer = a + b;
            expression = a + " + " + b + " = ?";
        } else if (operation == 1) {
            a = 30 + random.nextInt(70);
            b = 10 + random.nextInt(30);
            if (b > a) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            correctAnswer = a - b;
            expression = a + " - " + b + " = ?";
        } else {
            a = 2 + random.nextInt(8);
            b = 2 + random.nextInt(8);
            correctAnswer = a * b;
            expression = a + " × " + b + " = ?";
        }

        view.setText(expression);
    }
}
