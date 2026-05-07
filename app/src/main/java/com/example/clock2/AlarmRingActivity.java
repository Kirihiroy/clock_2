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

    private static final String KEY_CORRECT_ANSWER = "correct_answer";
    private static final String KEY_EXPRESSION     = "expression";

    private final Random random = new Random();
    private int    correctAnswer;
    private int    difficulty;
    private String currentExpression;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alarm_ring);
        prepareWakeUpScreen();

        difficulty = getSharedPreferences(AlarmActivity.PREFS_NAME, MODE_PRIVATE)
                .getInt(AlarmActivity.KEY_DIFFICULTY, 1);

        // Блокируем системную кнопку Back — нельзя обойти задачу
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { /* заблокировано */ }
        });

        TextView exampleText  = findViewById(R.id.tv_math_example);
        EditText answerInput  = findViewById(R.id.et_answer);
        Button   dismissButton = findViewById(R.id.btn_dismiss_alarm);

        // Восстанавливаем состояние после поворота экрана:
        // без этого correctAnswer сбрасывался в 0, а пользователь видел старый пример
        if (savedInstanceState != null) {
            correctAnswer     = savedInstanceState.getInt(KEY_CORRECT_ANSWER, 0);
            currentExpression = savedInstanceState.getString(KEY_EXPRESSION, "");
            exampleText.setText(currentExpression);
        } else {
            generateMathExample(exampleText);
        }

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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Сохраняем текущую задачу и ответ, чтобы поворот экрана не сбросил состояние
        outState.putInt(KEY_CORRECT_ANSWER, correctAnswer);
        outState.putString(KEY_EXPRESSION, currentExpression);
    }

    private void prepareWakeUpScreen() {
        // FLAG_KEEP_SCREEN_ON актуален на всех версиях — без него экран может
        // погаснуть пока пользователь решает математический пример
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    private void generateMathExample(TextView view) {
        int    a, b;
        String expression;

        if (difficulty == 1) {
            if (random.nextBoolean()) {
                a = 1 + random.nextInt(5);
                b = 1 + random.nextInt(10 - a);    // b ∈ [1, 9-a], сумма ≤ 10
                correctAnswer = a + b;
                expression = a + " + " + b + " = ?";
            } else {
                a = 1 + random.nextInt(9);          // a ∈ [1, 9]
                b = random.nextInt(a);              // b ∈ [0, a-1], результат ≥ 1
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
                a = 2 + random.nextInt(6);          // a ∈ [2, 7]
                b = 2 + random.nextInt(6);          // b ∈ [2, 7]
                correctAnswer = a * b;
                expression = a + " × " + b + " = ?";
            }

        } else {
            // difficulty == 3 (или любое другое значение)
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
                // Умножение: гарантируем, что nextInt получает аргумент > 0
                a = 2 + random.nextInt(9);          // a ∈ [2, 10]
                int maxB = Math.max(1, 100 / a - 1);// защита от nextInt(0) при a = 100
                b = 2 + random.nextInt(maxB);
                correctAnswer = a * b;
                expression = a + " × " + b + " = ?";
            }
        }

        currentExpression = expression;
        view.setText(expression);
    }
}
