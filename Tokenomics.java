package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.math.BigDecimal;

/**
 * Экономика PoA-сети: ограниченная эмиссия и халвинг награды за блок.
 *
 * Эмиссия ограничена {@link #MAX_SUPPLY} TRG. Награда за блок уменьшается
 * вдвое каждые {@link #HALVING_INTERVAL} блоков.
 */
public final class Tokenomics {

    private static final String TAG = "Tokenomics";
    private static final String PREFS_NAME = "triger_tokenomics";
    private static final String KEY_TOTAL_SUPPLY = "total_supply";

    /** Максимальная эмиссия TRG (как у Биткоина). */
    public static final double MAX_SUPPLY = 21_000_000.0;
    /** Начальная награда за блок. */
    public static final double BASE_REWARD = 5.0;
    /** Халвинг каждые 2 102 400 блоков (4 года при интервале 60 секунд). */
    public static final int HALVING_INTERVAL = 2_102_400;

    private Tokenomics() {
    }

    private static SharedPreferences getPrefs(Context context) {
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Текущее количество выпущенных TRG (сумма всех начисленных наград). */
    public static synchronized double getTotalSupply(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return 0.0;
        }
        String raw = prefs.getString(KEY_TOTAL_SUPPLY, null);
        if (raw == null || raw.trim().isEmpty()) {
            return 0.0;
        }
        try {
            return Math.max(0.0, Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed to parse total supply", e);
            return 0.0;
        }
    }

    /**
     * Награда за блок с учётом халвинга и лимита эмиссии.
     * Формула: baseReward / 2^(blockIndex / HALVING_INTERVAL).
     */
    public static synchronized double getBlockReward(Context context, int blockIndex) {
        int halvings = Math.max(0, blockIndex) / HALVING_INTERVAL;
        double reward = halvings >= 63
                ? 0.0
                : BASE_REWARD / Math.pow(2, halvings);

        double remaining = MAX_SUPPLY - getTotalSupply(context);
        if (remaining <= 0.0) {
            reward = 0.0;
        } else {
            reward = Math.min(reward, remaining);
        }

        Log.d(TAG, "Halving: block index = " + blockIndex
                + ", reward = " + formatAmount(reward) + " TRG");
        return reward;
    }

    /**
     * Выпускает награду с учётом лимита MAX_SUPPLY.
     * Возвращает фактически выпущенное количество TRG.
     */
    public static synchronized double mint(Context context, double amount) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null || amount <= 0.0) {
            return 0.0;
        }
        double supply = getTotalSupply(context);
        double remaining = MAX_SUPPLY - supply;
        if (remaining <= 0.0) {
            return 0.0;
        }
        double minted = Math.min(amount, remaining);
        double newSupply = supply + minted;
        prefs.edit()
                .putString(KEY_TOTAL_SUPPLY, BigDecimal.valueOf(newSupply).toPlainString())
                .apply();
        return minted;
    }

    /** Форматирует количество TRG без лишних нулей. */
    public static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }
}
