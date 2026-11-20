package com.triger.poc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "triger_prefs";
    public static final String KEY_THEME = "theme";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_MINING_INTERVAL = "mining_interval";
    public static final String KEY_DISPLAY_CURRENCY = "display_currency";
    public static final String KEY_DND = "dnd_enabled";
    public static final int DEFAULT_MINING_INTERVAL = 60;

    private static final String TAG = "SettingsActivity";

    public static int getMiningIntervalSeconds(Context context) {
        return prefs(context).getInt(KEY_MINING_INTERVAL, DEFAULT_MINING_INTERVAL);
    }

    public static boolean isDndEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DND, false);
    }

    public static void setDndEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DND, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Единая точка определения темы. Тёмная тема включена по умолчанию,
     * но явный выбор пользователя (включая старый ключ theme_dark) сохраняется.
     */
    public static boolean isDarkTheme(Context context) {
        SharedPreferences sp = prefs(context);
        if (sp.contains(KEY_THEME)) {
            return sp.getBoolean(KEY_THEME, true);
        }
        boolean legacyDark = sp.contains("theme_dark")
                ? sp.getBoolean("theme_dark", true)
                : true;
        sp.edit().putBoolean(KEY_THEME, legacyDark).apply();
        return legacyDark;
    }

    public static int themeRes(Context context) {
        return isDarkTheme(context) ? R.style.Theme_Triger_Dark : R.style.Theme_Triger_Light;
    }

    private TextView tvAppVersion;
    private SwitchMaterial switchDarkTheme;
    private SwitchMaterial switchDnd;
    private TextView tvLanguageStatus;
    private String currentLanguageCode;
    private int aboutTapCount;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageActivity.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(themeRes(this));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        switchDarkTheme = findViewById(R.id.switch_dark_theme);
        switchDnd = findViewById(R.id.switch_dnd);
        tvAppVersion = findViewById(R.id.tv_app_version);
        tvLanguageStatus = findViewById(R.id.tv_language_status);
        currentLanguageCode = LanguageActivity.getSavedLanguage(this);

        switchDarkTheme.setChecked(isDarkTheme(this));
        switchDnd.setChecked(isDndEnabled(this));
        tvAppVersion.setText(appVersionText());
        updateLanguageStatus();

        switchDarkTheme.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs(SettingsActivity.this).edit().putBoolean(KEY_THEME, isChecked).apply();
            recreate();
        });
        switchDnd.setOnCheckedChangeListener((buttonView, isChecked) ->
                setDndEnabled(SettingsActivity.this, isChecked));

        findViewById(R.id.item_theme).setOnClickListener(v -> switchDarkTheme.toggle());
        findViewById(R.id.item_dnd).setOnClickListener(v -> switchDnd.toggle());
        findViewById(R.id.item_language).setOnClickListener(v -> {
            Intent intent = new Intent(this, LanguageActivity.class);
            intent.putExtra(LanguageActivity.EXTRA_RETURN_TO_CALLER, true);
            startActivity(intent);
        });
        findViewById(R.id.item_account_security).setOnClickListener(
                v -> startActivity(new Intent(this, SecurityActivity.class)));
        findViewById(R.id.item_help).setOnClickListener(
                v -> startActivity(new Intent(this, HelpActivity.class)));
        findViewById(R.id.item_check_updates).setOnClickListener(
                v -> UpdateChecker.check(this));
        findViewById(R.id.item_about).setOnClickListener(v -> onAboutClicked());
    }

    private void onAboutClicked() {
        aboutTapCount++;
        if (aboutTapCount >= 7) {
            aboutTapCount = 0;
            startActivity(new Intent(this, DebugActivity.class));
            return;
        }
        showAboutDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String savedLanguage = LanguageActivity.getSavedLanguage(this);
        if (currentLanguageCode != null && !currentLanguageCode.equals(savedLanguage)) {
            recreate();
            return;
        }
        updateLanguageStatus();
    }

    private void updateLanguageStatus() {
        if (tvLanguageStatus != null) {
            tvLanguageStatus.setText(LanguageActivity.getSavedLanguageName(this));
        }
    }

    private void showAboutDialog() {
        AboutSheetDialogFragment.newInstance()
                .show(getSupportFragmentManager(), "about");
    }

    private String appVersionText() {
        String name = getString(R.string.app_name);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return getString(R.string.app_version_name_format, name,
                    info.versionName == null ? "1.0" : info.versionName);
        } catch (Exception e) {
            return getString(R.string.app_version_name_format, name, "1.0");
        }
    }
}
