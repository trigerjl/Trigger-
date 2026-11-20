package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Очки доверия валидаторов. Хранятся локально в SharedPreferences
 * "triger_reputation" по ключу "reputation_<sim_hash>".
 */
public final class ReputationManager {

    public static final String LEVEL_NEWBIE = "newbie";
    public static final String LEVEL_RELIABLE = "reliable";
    public static final String LEVEL_EXPERT = "expert";

    /** Штраф узлу без аппаратной защиты ключа (StrongBox). */
    public static final int STRONGBOX_PENALTY = -10;

    private static final String PREFS_NAME = "triger_reputation";
    private static final String KEY_PREFIX = "reputation_";
    private static final String KEY_STRONGBOX_PENALTY_PREFIX = "strongbox_penalty_";

    private ReputationManager() {
    }

    /** Увеличивает очки доверия валидатора. */
    public static void addReputation(Context context, String simHash, int points) {
        if (context == null || points == 0) {
            return;
        }
        String key = keyFor(simHash);
        if (key == null) {
            return;
        }
        synchronized (ReputationManager.class) {
            SharedPreferences prefs = prefs(context);
            int current = prefs.getInt(key, 0);
            int updated = Math.max(0, current + points);
            prefs.edit().putInt(key, updated).apply();
        }
    }

    /** Возвращает текущее количество очков доверия. */
    public static int getReputation(Context context, String simHash) {
        String key = keyFor(simHash);
        if (context == null || key == null) {
            return 0;
        }
        return prefs(context).getInt(key, 0);
    }

    /** Возвращает текстовый уровень репутации. */
    public static String getReputationLevel(Context context, String simHash) {
        int points = getReputation(context, simHash);
        if (context == null) {
            return levelFor(points);
        }
        if (points >= 500) {
            return context.getString(R.string.reputation_level_expert);
        }
        if (points >= 100) {
            return context.getString(R.string.reputation_level_reliable);
        }
        return context.getString(R.string.reputation_level_newbie);
    }

    public static String levelFor(int points) {
        if (points >= 500) {
            return LEVEL_EXPERT;
        }
        if (points >= 100) {
            return LEVEL_RELIABLE;
        }
        return LEVEL_NEWBIE;
    }

    /**
     * Применяет аппаратный статус локального узла к его репутации.
     * Узел без StrongBox один раз получает штраф {@link #STRONGBOX_PENALTY},
     * но продолжает работать (майнинг не блокируется).
     */
    public static void applyHardwareStatus(Context context) {
        if (context == null) {
            return;
        }
        String simHash;
        try {
            simHash = SimIdentity.getOrCreateSimHash(context);
        } catch (Throwable t) {
            return;
        }
        applyHardwareStatus(context, simHash);
    }

    public static void applyHardwareStatus(Context context, String simHash) {
        String key = keyFor(simHash);
        if (context == null || key == null) {
            return;
        }
        String penaltyKey = KEY_STRONGBOX_PENALTY_PREFIX + simHash.trim();
        synchronized (ReputationManager.class) {
            SharedPreferences prefs = prefs(context);
            if (DeviceIdentity.isStrongBoxBacked(context)) {
                return;
            }
            if (prefs.getBoolean(penaltyKey, false)) {
                return;
            }
            int current = prefs.getInt(key, 0);
            int updated = Math.max(0, current + STRONGBOX_PENALTY);
            prefs.edit()
                    .putInt(key, updated)
                    .putBoolean(penaltyKey, true)
                    .apply();
        }
    }

    /** true, если узел не имеет аппаратной защиты ключа и считается менее надёжным. */
    public static boolean isLessReliable(Context context) {
        return context != null && !DeviceIdentity.isStrongBoxBacked(context);
    }

    private static String keyFor(String simHash) {
        if (simHash == null) {
            return null;
        }
        String hash = simHash.trim();
        return hash.isEmpty() ? null : KEY_PREFIX + hash;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
