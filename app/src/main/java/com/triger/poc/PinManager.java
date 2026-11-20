package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.biometric.BiometricManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class PinManager {

    public static final String PREFS_NAME = "triger_security";
    public static final String KEY_PIN_HASH = "pin_hash";

    private static volatile boolean unlocked = false;

    private PinManager() {
    }

    public static void setPin(Context context, String pin) {
        if (context == null || !isValidPin(pin)) {
            return;
        }
        prefs(context).edit().putString(KEY_PIN_HASH, sha256(pin)).apply();
        unlocked = true;
    }

    public static boolean checkPin(Context context, String pin) {
        if (context == null || pin == null) {
            return false;
        }
        String stored = prefs(context).getString(KEY_PIN_HASH, "");
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                sha256(pin).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isPinSet(Context context) {
        if (context == null) {
            return false;
        }
        String stored = prefs(context).getString(KEY_PIN_HASH, "");
        return stored != null && !stored.isEmpty();
    }

    /** Доступна ли настроенная на устройстве биометрия (отпечаток/лицо). */
    public static boolean isBiometricAvailable(Context context) {
        if (context == null) {
            return false;
        }
        try {
            int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.BIOMETRIC_WEAK;
            return BiometricManager.from(context).canAuthenticate(authenticators)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearPin(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().remove(KEY_PIN_HASH).apply();
        unlocked = true;
    }

    public static boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{4,6}");
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void markUnlocked() {
        unlocked = true;
    }

    public static void lock() {
        unlocked = false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
