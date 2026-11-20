package com.triger.poc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class OnboardingActivity extends AppCompatActivity {

    public static final String KEY_ONBOARDING_COMPLETED = "onboarding_completed";
    public static final String KEY_SEED_BACKUP_CONFIRMED = "seed_backup_confirmed";

    private static final int STEP_COUNT = 3;
    private static final int REQUEST_PIN_SETUP = 1201;

    private int step = 0;
    private boolean completed = false;
    private String mnemonic = "";

    private TextView stepIndicator;
    private MaterialButton nextButton;
    private View[] stepViews;
    private View[] dots;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        KeyManager.getOrCreateAddress(this);
        mnemonic = KeyManager.getMnemonic(this);

        stepViews = new View[]{
                findViewById(R.id.step_onboarding_welcome),
                findViewById(R.id.step_onboarding_mining),
                findViewById(R.id.step_onboarding_chat)};
        dots = new View[]{
                findViewById(R.id.dot_onboarding_1),
                findViewById(R.id.dot_onboarding_2),
                findViewById(R.id.dot_onboarding_3)};
        stepIndicator = findViewById(R.id.tv_onboarding_step);
        nextButton = findViewById(R.id.btn_onboarding_next);
        nextButton.setOnClickListener(v -> onNextClicked());
        showStep(0);
    }

    private void showStep(int index) {
        step = index;
        for (int i = 0; i < stepViews.length; i++) {
            if (stepViews[i] != null) {
                stepViews[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        for (int i = 0; i < dots.length; i++) {
            if (dots[i] != null) {
                dots[i].setBackgroundResource(i == index
                        ? R.drawable.bg_step_dot_active
                        : R.drawable.bg_step_dot);
            }
        }
        if (stepIndicator != null) {
            stepIndicator.setText(getString(R.string.onboarding_step, index + 1, STEP_COUNT));
        }
        if (nextButton != null) {
            nextButton.setText(buttonTextFor(index));
        }
    }

    private int buttonTextFor(int index) {
        switch (index) {
            case 0:
                return R.string.onboarding_create_wallet;
            case 1:
                return R.string.onboarding_go_mining;
            default:
                return R.string.onboarding_done;
        }
    }

    private void showSeedBackupDialog() {
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            Toast.makeText(this, R.string.seed_phrase_unavailable,
                    Toast.LENGTH_LONG).show();
            showStep(1);
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, dp(8), padding, 0);

        TextView description = new TextView(this);
        description.setText(R.string.onboarding_seed_desc);
        description.setTextSize(14);
        content.addView(description);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(16);
        content.addView(grid, gridParams);

        String[] words = mnemonic.trim().split("\\s+");
        int margin = dp(4);
        int cellPadding = dp(10);
        for (int i = 0; i < words.length; i++) {
            TextView cell = new TextView(this);
            cell.setText(getString(R.string.seed_word_format, i + 1, words[i]));
            cell.setTextColor(getColor(R.color.text_primary_dark));
            cell.setTextSize(15);
            cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.bg_input_rounded);
            cell.setPadding(cellPadding, cellPadding, cellPadding, cellPadding);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(i % 3, 1f);
            params.rowSpec = GridLayout.spec(i / 3);
            params.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(params);
            grid.addView(cell);
        }

        MaterialButton copyButton = new MaterialButton(this);
        copyButton.setText(R.string.seed_copy);
        copyButton.setAllCaps(false);
        copyButton.setCornerRadius(dp(18));
        copyButton.setOnClickListener(v -> copyMnemonic());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        copyParams.topMargin = dp(12);
        content.addView(copyButton, copyParams);

        TextView warning = new TextView(this);
        warning.setText(R.string.seed_phrase_warning);
        warning.setTextColor(getColor(R.color.accent_orange));
        warning.setTextSize(13);
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        warningParams.topMargin = dp(16);
        content.addView(warning, warningParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle(R.string.onboarding_seed_title)
                .setView(scroll)
                .setPositiveButton(R.string.onboarding_seed_saved, (dialog, which) -> {
                    markSeedSaved();
                    showStep(1);
                })
                .setNegativeButton(R.string.onboarding_seed_later,
                        (dialog, which) -> showStep(1))
                .setCancelable(false)
                .show();
    }

    private void copyMnemonic() {
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        String phrase = mnemonic.trim().replaceAll("\\s+", " ");
        clipboard.setPrimaryClip(ClipData.newPlainText("wallet_secret", phrase));
        Toast.makeText(this, R.string.seed_copied, Toast.LENGTH_LONG).show();
    }

    private void onNextClicked() {
        if (completed) {
            return;
        }
        if (step == 0) {
            showSeedBackupDialog();
            return;
        }
        if (step >= STEP_COUNT - 1) {
            proceedToPinSetup();
            return;
        }
        showStep(step + 1);
    }

    private void markSeedSaved() {
        prefs().edit().putBoolean(KEY_SEED_BACKUP_CONFIRMED, true).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** После онбординга предлагаем установить PIN-код, затем открываем приложение. */
    private void proceedToPinSetup() {
        if (PinManager.isPinSet(this)) {
            completeOnboarding();
            return;
        }
        startActivityForResult(
                new Intent(this, PinSetupActivity.class), REQUEST_PIN_SETUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PIN_SETUP) {
            return;
        }
        if (resultCode == RESULT_OK || resultCode == PinSetupActivity.RESULT_SKIPPED) {
            completeOnboarding();
        }
    }

    @Override
    public void onBackPressed() {
        if (step > 0) {
            showStep(step - 1);
            return;
        }
        super.onBackPressed();
    }

    private void completeOnboarding() {
        if (completed) {
            return;
        }
        completed = true;
        prefs().edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
