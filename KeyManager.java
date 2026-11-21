package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

public class KeyManager {

    private static final String PREFS_NAME = "triger_prefs";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_LOCAL_BALANCE = "local_balance";
    private static final String KEY_TEST_PRIVATE_KEY = "test_private_key";
    private static final String KEY_TOTAL_BLOCKS_SIGNED = "total_blocks_signed";

    private static ECKeyPair createKeyPair() throws Exception {
        return Keys.createEcKeyPair();
    }

    public static String getOrCreateAddress(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String savedAddress = prefs.getString(KEY_ADDRESS, null);
        if (savedAddress != null && !savedAddress.isEmpty()) {
            return savedAddress;
        }

        try {
            ECKeyPair keyPair = createKeyPair();
            String address = "0x" + Keys.getAddress(keyPair);
            String privateKeyHex = "0x" + keyPair.getPrivateKey().toString(16);

            prefs.edit()
                    .putString(KEY_ADDRESS, address)
                    .putString(KEY_PRIVATE_KEY, privateKeyHex)
                    .apply();

            return address;
        } catch (Exception e) {
            throw new RuntimeException("Не удалось сгенерировать ключ валидатора", e);
        }
    }

    public static String getPrivateKeyHex(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String override = prefs.getString(KEY_TEST_PRIVATE_KEY, "");
        if (override != null && !override.trim().isEmpty()) {
            return normalizeKey(override);
        }
        return prefs.getString(KEY_PRIVATE_KEY, null);
    }

    public static void setTestPrivateKey(Context context, String privateKeyHex) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TEST_PRIVATE_KEY, privateKeyHex == null ? "" : privateKeyHex.trim())
                .apply();
    }

    private static String normalizeKey(String key) {
        String k = key.trim();
        if (!k.startsWith("0x") && !k.startsWith("0X")) {
            k = "0x" + k;
        }
        return k;
    }

    public static double getLocalBalance(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Object value = prefs.getAll().get(KEY_LOCAL_BALANCE);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    public static void addLocalBalance(Context context, double amount) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double newBalance = getLocalBalance(context) + amount;
        prefs.edit()
                .putString(KEY_LOCAL_BALANCE,
                        java.math.BigDecimal.valueOf(newBalance).toPlainString())
                .apply();
    }

    public static int getTotalBlocksSigned(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TOTAL_BLOCKS_SIGNED, 0);
    }

    public static void incrementTotalBlocksSigned(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_TOTAL_BLOCKS_SIGNED, getTotalBlocksSigned(context) + 1).apply();
    }

    /**
     * Уникальный идентификатор устройства/установки, привязанный к ключу в Android Keystore.
     * Ключ EC P-256 нельзя экспортировать; при сбросе телефона ключ пропадёт.
     */
    public static String getOrCreateDeviceId(Context context) {
        return DeviceIdentity.getOrCreateDeviceId(context);
    }
}
