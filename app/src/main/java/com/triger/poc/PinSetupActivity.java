package com.triger.poc;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Полноэкранная установка PIN-кода с собственной цифровой клавиатурой.
 */
public class PinSetupActivity extends AppCompatActivity {

    public static final int RESULT_SKIPPED = RESULT_FIRST_USER;

    private static final int MAX_PIN_LENGTH = 6;
    private static final int MIN_PIN_LENGTH = 4;

    private final StringBuilder pin = new StringBuilder();
    private TextView tvError;
    private View[] dots;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin);

        findViewById(R.id.btn_forgot_pin).setVisibility(View.GONE);
        tvError = findViewById(R.id.tv_pin_error);
        LinearLayout dotsContainer = findViewById(R.id.ll_pin_dots);
        dots = new View[dotsContainer.getChildCount()];
        for (int i = 0; i < dots.length; i++) {
            dots[i] = dotsContainer.getChildAt(i);
        }
        updateDots();

        findViewById(R.id.btn_key_0).setOnClickListener(v -> append('0'));
        findViewById(R.id.btn_key_1).setOnClickListener(v -> append('1'));
        findViewById(R.id.btn_key_2).setOnClickListener(v -> append('2'));
        findViewById(R.id.btn_key_3).setOnClickListener(v -> append('3'));
        findViewById(R.id.btn_key_4).setOnClickListener(v -> append('4'));
        findViewById(R.id.btn_key_5).setOnClickListener(v -> append('5'));
        findViewById(R.id.btn_key_6).setOnClickListener(v -> append('6'));
        findViewById(R.id.btn_key_7).setOnClickListener(v -> append('7'));
        findViewById(R.id.btn_key_8).setOnClickListener(v -> append('8'));
        findViewById(R.id.btn_key_9).setOnClickListener(v -> append('9'));
        findViewById(R.id.btn_key_delete).setOnClickListener(v -> deleteLast());
        findViewById(R.id.btn_key_ok).setOnClickListener(v -> submit());

        MaterialButton skip = findViewById(R.id.btn_pin_skip);
        MaterialButton cancel = findViewById(R.id.btn_pin_cancel);
        skip.setOnClickListener(v -> confirmSkip());
        cancel.setOnClickListener(v -> cancelSetup());
    }

    private void append(char digit) {
        if (pin.length() >= MAX_PIN_LENGTH) {
            return;
        }
        pin.append(digit);
        hideError();
        updateDots();
    }

    private void deleteLast() {
        if (pin.length() == 0) {
            return;
        }
        pin.deleteCharAt(pin.length() - 1);
        hideError();
        updateDots();
    }

    private void updateDots() {
        if (dots == null) {
            return;
        }
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(i < pin.length()
                    ? R.drawable.bg_pin_dot_filled
                    : R.drawable.bg_pin_dot_empty);
        }
    }

    private void submit() {
        if (pin.length() < MIN_PIN_LENGTH) {
            showError(getString(R.string.pin_error_min_4));
            return;
        }
        PinManager.setPin(this, pin.toString());
        setResult(RESULT_OK);
        finish();
    }

    private void confirmSkip() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pin_skip_title)
                .setMessage(R.string.pin_skip_message)
                .setNegativeButton(R.string.action_back, null)
                .setPositiveButton(R.string.action_continue, (dialog, which) -> {
                    setResult(RESULT_SKIPPED);
                    finish();
                })
                .show();
    }

    private void cancelSetup() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void showError(String message) {
        if (tvError != null) {
            tvError.setText(message);
            tvError.setVisibility(View.VISIBLE);
        }
    }

    private void hideError() {
        if (tvError != null) {
            tvError.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        cancelSetup();
    }
}
