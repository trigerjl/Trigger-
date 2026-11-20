package com.triger.poc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class SeedBackupActivity extends AppCompatActivity {

    private String mnemonic = "";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageActivity.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seed_backup);

        KeyManager.getOrCreateAddress(this);
        mnemonic = KeyManager.getMnemonic(this);
        if (mnemonic.isEmpty()) {
            Toast.makeText(this, R.string.seed_phrase_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        renderSeedGrid();

        findViewById(R.id.btn_seed_backup_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_seed_backup_later).setOnClickListener(v -> finish());
        findViewById(R.id.btn_copy_seed_backup).setOnClickListener(v -> copyMnemonic());
        findViewById(R.id.btn_seed_backup_saved).setOnClickListener(v -> confirmSaved());
    }

    private void renderSeedGrid() {
        GridLayout grid = findViewById(R.id.grid_seed_backup);
        grid.removeAllViews();
        String[] words = mnemonic.split("\\s+");
        int margin = dp(4);
        int padding = dp(10);
        for (int i = 0; i < words.length; i++) {
            TextView cell = new TextView(this);
            cell.setText(getString(R.string.seed_word_format, i + 1, words[i]));
            cell.setTextColor(getColor(R.color.text_primary_dark));
            cell.setTextSize(15);
            cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.bg_input_rounded);
            cell.setPadding(padding, padding, padding, padding);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(i % 3, 1f);
            params.rowSpec = GridLayout.spec(i / 3);
            params.setMargins(margin, margin, margin, margin);
            cell.setLayoutParams(params);
            grid.addView(cell);
        }
    }

    private void copyMnemonic() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        String phrase = mnemonic.trim().replaceAll("\\s+", " ");
        clipboard.setPrimaryClip(ClipData.newPlainText("wallet_secret", phrase));
        Toast.makeText(this, R.string.seed_copied, Toast.LENGTH_LONG).show();
    }

    private void confirmSaved() {
        getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(OnboardingActivity.KEY_SEED_BACKUP_CONFIRMED, true)
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
