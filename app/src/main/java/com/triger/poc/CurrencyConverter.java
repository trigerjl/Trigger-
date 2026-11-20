package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Конвертация TRG в местную валюту. Курсы фиатных валют загружаются с
 * open.er-api.com (база — RUB) и кэшируются на 24 часа.
 */
public final class CurrencyConverter {

    private static final String TAG = "CurrencyConverter";
    private static final String API_URL = "https://open.er-api.com/v6/latest/RUB";
    private static final String PREFS_NAME = "triger_rates";
    private static final String KEY_RATES = "rates";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    public static final String DEFAULT_CURRENCY = ExchangeOffer.CURRENCY_RUB;

    public static final List<String> SUPPORTED_CURRENCIES =
            Collections.unmodifiableList(Arrays.asList(
                    ExchangeOffer.CURRENCY_RUB,
                    ExchangeOffer.CURRENCY_USD,
                    ExchangeOffer.CURRENCY_EUR,
                    ExchangeOffer.CURRENCY_KZT,
                    ExchangeOffer.CURRENCY_UAH,
                    ExchangeOffer.CURRENCY_BYN,
                    ExchangeOffer.CURRENCY_TRY,
                    ExchangeOffer.CURRENCY_CNY,
                    ExchangeOffer.CURRENCY_INR));

    private static final Object FETCH_LOCK = new Object();
    private static final List<Callback> WAITING = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean fetching;

    public interface Callback {
        void onRates(Map<String, Double> rates);

        void onError(Exception error);
    }

    private final Context context;

    public CurrencyConverter(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    public void fetchRates(Callback callback) {
        fetchRates(context, callback);
    }

    public double convertToLocal(double amountTRG, String targetCurrency) {
        return convertToLocal(context, amountTRG, targetCurrency);
    }

    /** Загружает курсы в фоне. Если кэш моложе 24 часов, сеть не используется. */
    public static void fetchRates(Context context, Callback callback) {
        if (context == null) {
            notifyError(callback, new IllegalArgumentException("context is null"));
            return;
        }
        final Context appContext = context.getApplicationContext();
        Map<String, Double> cached = getCachedRates(appContext);
        if (cached != null && !isStale(appContext)) {
            notifyRates(callback, cached);
            return;
        }
        synchronized (FETCH_LOCK) {
            if (fetching) {
                if (callback != null) {
                    WAITING.add(callback);
                }
                return;
            }
            fetching = true;
        }
        new Thread(() -> {
            Map<String, Double> rates = null;
            Exception error = null;
            try {
                rates = requestRates();
                saveRates(appContext, rates);
            } catch (Exception e) {
                error = e;
                Log.w(TAG, "Failed to fetch currency rates", e);
            }
            List<Callback> waiting;
            synchronized (FETCH_LOCK) {
                fetching = false;
                waiting = new ArrayList<>(WAITING);
                WAITING.clear();
            }
            if (rates != null) {
                notifyRates(callback, rates);
                for (Callback pending : waiting) {
                    notifyRates(pending, rates);
                }
            } else {
                notifyError(callback, error);
                for (Callback pending : waiting) {
                    notifyError(pending, error);
                }
            }
        }, "currency-rates").start();
    }

    /**
     * Переводит сумму TRG в валюту по VWAP цене TRG в рублях и курсу фиата.
     * Возвращает {@link Double#NaN}, если цена или курс недоступны.
     */
    public static double convertToLocal(Context context, double amountTRG,
                                        String targetCurrency) {
        if (context == null || amountTRG < 0.0
                || Double.isNaN(amountTRG) || Double.isInfinite(amountTRG)) {
            return Double.NaN;
        }
        String currency = normalizeCurrency(targetCurrency);
        double rate;
        if (DEFAULT_CURRENCY.equals(currency)) {
            rate = 1.0;
        } else {
            Map<String, Double> rates = getCachedRates(context.getApplicationContext());
            if (rates == null) {
                return Double.NaN;
            }
            Double value = rates.get(currency);
            if (value == null || !isValid(value)) {
                return Double.NaN;
            }
            rate = value;
        }

        PriceOracle oracle = new PriceOracle(context);
        Double priceRub = oracle.getVWAP();
        if (priceRub == null || !isValid(priceRub)) {
            priceRub = oracle.getPrice();
        }
        if (priceRub == null || !isValid(priceRub)) {
            return Double.NaN;
        }
        double result = amountTRG * priceRub * rate;
        return Double.isNaN(result) || Double.isInfinite(result) ? Double.NaN : result;
    }

    /** Валюта отображения: выбор пользователя, затем валюта локали, затем RUB. */
    public static String resolveDisplayCurrency(Context context) {
        if (context != null) {
            String saved = context.getSharedPreferences(
                            SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(SettingsActivity.KEY_DISPLAY_CURRENCY, "");
            if (isSupported(saved)) {
                return normalizeCurrency(saved);
            }
        }
        try {
            java.util.Currency currency = java.util.Currency.getInstance(Locale.getDefault());
            if (currency != null && isSupported(currency.getCurrencyCode())) {
                return normalizeCurrency(currency.getCurrencyCode());
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_CURRENCY;
    }

    public static void setDisplayCurrency(Context context, String currency) {
        if (context == null) {
            return;
        }
        String value = isSupported(currency) ? normalizeCurrency(currency) : DEFAULT_CURRENCY;
        context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SettingsActivity.KEY_DISPLAY_CURRENCY, value)
                .apply();
    }

    public static String currencyLabel(Context context, String currency) {
        if (context == null) {
            return normalizeCurrency(currency);
        }
        return context.getString(currencyLabelRes(currency));
    }

    public static String currencySymbol(String currency) {
        return P2PExchangeManager.currencySymbol(normalizeCurrency(currency));
    }

    public static Map<String, Double> getCachedRates(Context context) {
        if (context == null) {
            return null;
        }
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_RATES, "");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(raw);
            Map<String, Double> rates = new HashMap<>();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                double value = object.optDouble(key, Double.NaN);
                if (isValid(value)) {
                    rates.put(key, value);
                }
            }
            if (rates.isEmpty()) {
                return null;
            }
            if (!rates.containsKey(DEFAULT_CURRENCY)) {
                rates.put(DEFAULT_CURRENCY, 1.0);
            }
            return rates;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read cached rates", t);
            return null;
        }
    }

    public static boolean isStale(Context context) {
        if (context == null) {
            return true;
        }
        long updatedAt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_UPDATED_AT, 0L);
        if (updatedAt <= 0L) {
            return true;
        }
        long age = System.currentTimeMillis() - updatedAt;
        return age < 0L || age > MAX_AGE_MS;
    }

    private static Map<String, Double> requestRates() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new java.io.IOException("HTTP " + code);
            }
            StringBuilder body = new StringBuilder();
            try (InputStream stream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JSONObject root = new JSONObject(body.toString());
            JSONObject ratesObject = root.optJSONObject("rates");
            if (ratesObject == null) {
                throw new org.json.JSONException("rates are missing");
            }
            Map<String, Double> rates = new HashMap<>();
            java.util.Iterator<String> keys = ratesObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                double value = ratesObject.optDouble(key, Double.NaN);
                if (isValid(value)) {
                    rates.put(key.toUpperCase(Locale.US), value);
                }
            }
            if (rates.isEmpty()) {
                throw new org.json.JSONException("rates are empty");
            }
            rates.put(DEFAULT_CURRENCY, 1.0);
            return rates;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void saveRates(Context context, Map<String, Double> rates) {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, Double> entry : rates.entrySet()) {
            try {
                object.put(entry.getKey(), entry.getValue());
            } catch (Throwable ignored) {
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RATES, object.toString())
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    private static void notifyRates(Callback callback, Map<String, Double> rates) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onRates(rates));
    }

    private static void notifyError(Callback callback, Exception error) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onError(error));
    }

    private static int currencyLabelRes(String currency) {
        String value = normalizeCurrency(currency);
        switch (value) {
            case ExchangeOffer.CURRENCY_USD:
                return R.string.currency_usd;
            case ExchangeOffer.CURRENCY_EUR:
                return R.string.currency_eur;
            case ExchangeOffer.CURRENCY_KZT:
                return R.string.currency_kzt;
            case ExchangeOffer.CURRENCY_UAH:
                return R.string.currency_uah;
            case ExchangeOffer.CURRENCY_BYN:
                return R.string.currency_byn;
            case ExchangeOffer.CURRENCY_TRY:
                return R.string.currency_try;
            case ExchangeOffer.CURRENCY_CNY:
                return R.string.currency_cny;
            case ExchangeOffer.CURRENCY_INR:
                return R.string.currency_inr;
            default:
                return R.string.currency_rub;
        }
    }

    private static String normalizeCurrency(String currency) {
        String value = currency == null ? "" : currency.trim().toUpperCase(Locale.US);
        return SUPPORTED_CURRENCIES.contains(value) ? value : DEFAULT_CURRENCY;
    }

    private static boolean isSupported(String currency) {
        String value = currency == null ? "" : currency.trim().toUpperCase(Locale.US);
        return SUPPORTED_CURRENCIES.contains(value);
    }

    private static boolean isValid(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
