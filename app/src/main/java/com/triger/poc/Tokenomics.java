package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;

/**
 * Экономика PoA-сети в модели Биткоина: ограниченная эмиссия и халвинг награды
 * за блок.
 *
 * Эмиссия ограничена {@link #MAX_SUPPLY} TRG. Награда за блок уменьшается
 * вдвое каждые {@link #HALVING_INTERVAL} блоков. Объём эмиссии всегда
 * пересчитывается по цепочке; локальное значение — только кэш для UI.
 */
public final class Tokenomics {

    private static final String TAG = "Tokenomics";
    private static final String PREFS_NAME = "triger_tokenomics";
    private static final String KEY_CACHED_TOTAL_SUPPLY = "cached_total_supply";

    /** Максимальная эмиссия TRG. */
    public static final double MAX_SUPPLY = 21_000_000.0;
    /** Начальная награда за блок. */
    public static final double BASE_REWARD = 25.0;
    /** Халвинг каждые 420 000 блоков. */
    public static final int HALVING_INTERVAL = 420_000;

    /** Канонический адрес сжигания: TRG, отправленные сюда, навсегда покидают оборот. */
    public static final String BURN_ADDRESS =
            "0x000000000000000000000000000000000000dead";

    private Tokenomics() {
    }

    private static SharedPreferences getPrefs(Context context) {
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Награда за блок с учётом халвинга. */
    public static double getBlockReward(Context context, int blockIndex) {
        return getBlockReward(blockIndex);
    }

    /** Награда за блок с учётом халвинга. */
    public static double getBlockReward(int blockIndex) {
        int epoch = Math.max(0, blockIndex) / HALVING_INTERVAL;
        if (epoch >= 63) {
            return 0.0;
        }
        return BASE_REWARD / Math.pow(2, epoch);
    }

    /** Считает общую эмиссию по цепочке блоков, но не больше {@link #MAX_SUPPLY}. */
    public static double calculateTotalSupply(List<String> chain) {
        double total = 0.0;
        if (chain == null || chain.isEmpty()) {
            return total;
        }
        for (int i = 0; i < chain.size(); i++) {
            try {
                JSONObject block = new JSONObject(chain.get(i));
                int index = block.optInt("index", i);
                total += getBlockReward(index);
                if (total >= MAX_SUPPLY) {
                    return MAX_SUPPLY;
                }
            } catch (Throwable t) {
                Log.w(TAG, "failed to parse block while calculating supply", t);
            }
        }
        return Math.min(total, MAX_SUPPLY);
    }

    /** Проверяет, помещается ли награда в оставшийся лимит эмиссии. */
    public static boolean canMintReward(List<String> chain, double reward) {
        if (reward <= 0.0) {
            return false;
        }
        double supply = calculateTotalSupply(chain);
        return supply < MAX_SUPPLY && supply + reward <= MAX_SUPPLY + 1e-9;
    }

    /** Кэш общего объёма эмиссии для отображения в UI. */
    public static double getCachedTotalSupply(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return 0.0;
        }
        String raw = prefs.getString(KEY_CACHED_TOTAL_SUPPLY, null);
        if (raw == null || raw.trim().isEmpty()) {
            return 0.0;
        }
        try {
            return Math.max(0.0, Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed to parse cached total supply", e);
            return 0.0;
        }
    }

    /** Пересчитывает и сохраняет кэш общего объёма эмиссии. */
    public static void updateCachedTotalSupply(Context context, List<String> chain) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return;
        }
        double total = calculateTotalSupply(chain);
        prefs.edit()
                .putString(KEY_CACHED_TOTAL_SUPPLY,
                        BigDecimal.valueOf(total).toPlainString())
                .apply();
    }

    /** Форматирует количество TRG без лишних нулей. */
    public static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }
}
