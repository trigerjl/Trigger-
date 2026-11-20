package com.triger.poc;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

/** Полноэкранная разблокировка приложения по PIN-коду. */
public class PinActivity extends AppCompatActivity {

    public static final String EXTRA_VERIFY_ONLY = "verify_only";

    private static final int MAX_PIN_LENGTH = 6;
    private static final int REQ_SETUP_PIN = 1001;

    private final StringBuilder pin = new StringBuilder();
    private TextView tvError;
    private View[] dots;
    private boolean biometricPromptShown = false;
    private boolean verifyOnly = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));
        super.onCreate(savedInstanceState);

        verifyOnly = getIntent().getBooleanExtra(EXTRA_VERIFY_ONLY, false);
        if (!PinManager.isPinSet(this)) {
            if (verifyOnly) {
                setResult(RESULT_CANCELED);
                finish();
            } else {
                openMain();
            }
            return;
        }

        setContentView(R.layout.activity_pin);

        ((TextView) findViewById(R.id.tv_pin_title)).setText(R.string.pin_enter_title);
        ((TextView) findViewById(R.id.tv_pin_hint))
                .setText(R.string.pin_enter_subtitle);
        findViewById(R.id.ll_pin_actions).setVisibility(View.GONE);

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
        View forgotPin = findViewById(R.id.btn_forgot_pin);
        if (verifyOnly) {
            forgotPin.setVisibility(View.GONE);
        } else {
            forgotPin.setOnClickListener(v -> showForgotPin());
        }
        View pinFallback = findViewById(R.id.btn_pin_fallback);
        if (pinFallback != null) {
            pinFallback.setOnClickListener(v -> usePinFallback());
        }
        setupBiometricLogin();
    }

    private void usePinFallback() {
        View fallback = findViewById(R.id.btn_pin_fallback);
        if (fallback != null) {
            fallback.setVisibility(View.GONE);
        }
        hideError();
        ((TextView) findViewById(R.id.tv_pin_hint))
                .setText(R.string.pin_enter_subtitle);
    }

    private void showPinFallback() {
        View fallback = findViewById(R.id.btn_pin_fallback);
        if (fallback != null) {
            fallback.setVisibility(View.VISIBLE);
        }
    }

    private void setupBiometricLogin() {
        View biometricButton = findViewById(R.id.btn_pin_biometric);
        if (biometricButton == null) {
            return;
        }
        if (!PinManager.isBiometricAvailable(this)) {
            biometricButton.setVisibility(View.GONE);
            showPinFallback();
            return;
        }
        biometricButton.setVisibility(View.VISIBLE);
        biometricButton.setOnClickListener(v -> showBiometricPrompt());
        if (!biometricPromptShown) {
            biometricPromptShown = true;
            biometricButton.postDelayed(this::showBiometricPrompt, 300);
        }
    }

    private void showBiometricPrompt() {
        if (isFinishing() || isDestroyed() || !PinManager.isBiometricAvailable(this)) {
            return;
        }
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        completeAuth();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        showError(getString(R.string.pin_biometric_failed));
                        showPinFallback();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        showPinFallback();
                        ((TextView) findViewById(R.id.tv_pin_hint))
                                .setText(R.string.pin_enter_subtitle);
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.pin_biometric_title))
                .setSubtitle(getString(R.string.pin_biometric_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText(getString(R.string.action_cancel))
                .build();
        prompt.authenticate(info);
    }

    private void showForgotPin() {
        ForgotPinSheet sheet = ForgotPinSheet.newInstance();
        sheet.setListener(this::offerNewPin);
        sheet.show(getSupportFragmentManager(), "forgot_pin");
    }

    private void offerNewPin() {
        startActivityForResult(new Intent(this, PinSetupActivity.class), REQ_SETUP_PIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETUP_PIN) {
            if (verifyOnly) {
                setResult(RESULT_CANCELED);
                finish();
            } else {
                PinManager.markUnlocked();
                openMain();
            }
        }
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
        if (pin.length() < 4) {
            showError(getString(R.string.pin_error_min_4));
            return;
        }
        if (!PinManager.checkPin(this, pin.toString())) {
            showError(getString(R.string.pin_error_incorrect));
            pin.setLength(0);
            updateDots();
            return;
        }
        completeAuth();
    }

    private void completeAuth() {
        PinManager.markUnlocked();
        if (verifyOnly) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        openMain();
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

    private void openMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (verifyOnly) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        moveTaskToBack(true);
    }
}
