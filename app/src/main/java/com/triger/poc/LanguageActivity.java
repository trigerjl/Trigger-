package com.triger.poc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.lang.reflect.Method;
import java.util.Locale;

public class LanguageActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "triger_prefs";
    public static final String KEY_APP_LANGUAGE = "app_language";
    public static final String KEY_LANGUAGE_SELECTED = "language_selected";
    public static final String EXTRA_RETURN_TO_CALLER = "return_to_caller";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(SettingsActivity.themeRes(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language);

        bindLanguage(R.id.language_en, "en");
        bindLanguage(R.id.language_ru, "ru");
        bindLanguage(R.id.language_es, "es");
        bindLanguage(R.id.language_de, "de");
        bindLanguage(R.id.language_zh, "zh");
        bindLanguage(R.id.language_fr, "fr");
        bindLanguage(R.id.language_ar, "ar");
        bindLanguage(R.id.language_hi, "hi");
    }

    private void bindLanguage(int viewId, String code) {
        findViewById(viewId).setOnClickListener(v -> selectLanguage(code));
    }

    private void selectLanguage(String code) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_APP_LANGUAGE, code)
                .putBoolean(KEY_LANGUAGE_SELECTED, true)
                .apply();
        applyLocale(this, code);

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finishAffinity();
    }

    public static String getSavedLanguageName(Context context) {
        String code = getSavedLanguage(context);
        if (code == null) {
            return "";
        }
        switch (code) {
            case "ru":
                return context.getString(R.string.language_ru);
            case "es":
                return context.getString(R.string.language_es);
            case "de":
                return context.getString(R.string.language_de);
            case "zh":
                return context.getString(R.string.language_zh);
            case "fr":
                return context.getString(R.string.language_fr);
            case "ar":
                return context.getString(R.string.language_ar);
            case "hi":
                return context.getString(R.string.language_hi);
            default:
                return context.getString(R.string.language_en);
        }
    }

    public static String getSavedLanguage(Context context) {
        String code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, "");
        return code == null || code.trim().isEmpty() ? null : code.trim();
    }

    public static void applySavedLocale(Context context) {
        String code = getSavedLanguage(context);
        if (code != null) {
            applyLocale(context, code);
        }
    }

    public static void applyLocale(Context context, String code) {
        Locale locale = localeFor(code);
        Locale.setDefault(locale);

        try {
            Method method = AppCompatDelegate.class.getMethod(
                    "setApplicationLocales", LocaleListCompat.class);
            method.invoke(null, LocaleListCompat.forLanguageTags(code));
        } catch (Throwable ignored) {
        }

        try {
            Resources resources = context.getResources();
            Configuration configuration = new Configuration(resources.getConfiguration());
            configuration.setLocale(locale);
            resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        } catch (Throwable ignored) {
        }

        try {
            Resources resources = context.getApplicationContext().getResources();
            Configuration configuration = new Configuration(resources.getConfiguration());
            configuration.setLocale(locale);
            resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        } catch (Throwable ignored) {
        }
    }

    public static Context wrap(Context base) {
        String code = getSavedLanguage(base);
        if (code == null) {
            return base;
        }
        Locale locale = localeFor(code);
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }

    private static Locale localeFor(String code) {
        if ("ru".equals(code)) {
            return new Locale("ru", "RU");
        }
        if ("es".equals(code)) {
            return new Locale("es", "ES");
        }
        if ("de".equals(code)) {
            return new Locale("de", "DE");
        }
        if ("zh".equals(code)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if ("fr".equals(code)) {
            return Locale.FRENCH;
        }
        if ("ar".equals(code)) {
            return new Locale("ar", "SA");
        }
        if ("hi".equals(code)) {
            return new Locale("hi", "IN");
        }
        return Locale.ENGLISH;
    }
}
