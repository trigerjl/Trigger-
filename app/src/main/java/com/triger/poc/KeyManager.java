package com.triger.poc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.MnemonicUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KeyManager {

    private static final String PREFS_NAME = "triger_prefs";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_MNEMONIC = "mnemonic";
    private static final String KEY_LOCAL_BALANCE = "local_balance";
    private static final String KEY_TOTAL_BLOCKS_SIGNED = "total_blocks_signed";
    private static final Object BALANCE_LOCK = new Object();

    private static final BigInteger SECP256K1_ORDER = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    public static String getOrCreateAddress(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        String savedAddress = prefs.getString(KEY_ADDRESS, null);
        if (savedAddress != null && !savedAddress.isEmpty()) {
            return savedAddress;
        }

        try {
            String mnemonic = createMnemonic();
            String normalizedKey = derivePrivateKeyFromMnemonic(mnemonic);
            ECKeyPair keyPair = ECKeyPair.create(new BigInteger(normalizedKey, 16));
            String address = "0x" + Keys.getAddress(keyPair);
            String privateKeyHex = "0x" + normalizedKey;

            prefs.edit()
                    .putString(KEY_ADDRESS, address)
                    .putString(KEY_PRIVATE_KEY, privateKeyHex)
                    .putString(KEY_MNEMONIC, mnemonic)
                    .apply();

            return address;
        } catch (Exception e) {
            throw new RuntimeException(
                    context.getString(R.string.error_generate_validator_key), e);
        }
    }

    private static String createMnemonic() {
        byte[] entropy = new byte[16];
        new SecureRandom().nextBytes(entropy);
        return MnemonicUtils.generateMnemonic(entropy);
    }

    public static String getMnemonic(Context context) {
        if (context == null) {
            return "";
        }
        String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MNEMONIC, "");
        return saved == null ? "" : saved.trim();
    }

    public static String getPrivateKeyHex(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_PRIVATE_KEY, "");
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        try {
            getOrCreateAddress(context);
        } catch (RuntimeException ignored) {
            return "";
        }
        saved = prefs.getString(KEY_PRIVATE_KEY, "");
        return saved == null ? "" : saved;
    }

    /**
     * Приводит введённый ключ к виду 64 hex-символа без префикса 0x.
     * Возвращает null, если строка пустая, содержит не hex-символы
     * или длиннее 64 символов. Короткие ключи дополняются ведущими нулями.
     */
    public static String normalizePrivateKey(String input) {
        if (input == null) {
            return null;
        }
        String key = input.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (key.startsWith("0x") || key.startsWith("0X")) {
            key = key.substring(2);
        }
        if (key.isEmpty() || key.length() > 64 || !key.matches("[0-9a-f]+")) {
            return null;
        }
        StringBuilder padded = new StringBuilder();
        for (int i = key.length(); i < 64; i++) {
            padded.append('0');
        }
        padded.append(key);
        return padded.toString();
    }

    /** Проверяет длину/формат ключа и что значение лежит в допустимом диапазоне secp256k1. */
    public static boolean isValidPrivateKey(String input) {
        String normalized = normalizePrivateKey(input);
        if (normalized == null) {
            return false;
        }
        try {
            BigInteger value = new BigInteger(normalized, 16);
            if (value.signum() <= 0 || value.compareTo(SECP256K1_ORDER) >= 0) {
                return false;
            }
            Keys.getAddress(ECKeyPair.create(value));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Проверяет BIP-39 сид-фразу (12/15/18/21/24 слова, контрольная сумма). */
    public static boolean isValidMnemonic(String input) {
        String mnemonic = normalizeMnemonic(input);
        if (mnemonic == null) {
            return false;
        }
        try {
            return MnemonicUtils.validateMnemonic(mnemonic);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String normalizeMnemonic(String input) {
        if (input == null) {
            return null;
        }
        String mnemonic = input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String[] words = mnemonic.split(" ");
        if (words.length != 12 && words.length != 15 && words.length != 18
                && words.length != 21 && words.length != 24) {
            return null;
        }
        return mnemonic;
    }

    /** Выводит приватный ключ по пути m/44'/60'/0'/0/0 (Ethereum BIP-44). */
    private static String derivePrivateKeyFromMnemonic(String input) {
        String mnemonic = normalizeMnemonic(input);
        if (mnemonic == null) {
            throw new IllegalArgumentException("invalid_mnemonic");
        }
        try {
            byte[] seed = MnemonicUtils.generateSeed(mnemonic, "");
            Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);
            int[] path = {
                    44 | Bip32ECKeyPair.HARDENED_BIT,
                    60 | Bip32ECKeyPair.HARDENED_BIT,
                    0 | Bip32ECKeyPair.HARDENED_BIT,
                    0,
                    0
            };
            Bip32ECKeyPair child = Bip32ECKeyPair.deriveKeyPair(master, path);
            return normalizePrivateKey(child.getPrivateKey().toString(16));
        } catch (Throwable t) {
            throw new IllegalArgumentException("invalid_mnemonic", t);
        }
    }

    /**
     * Восстанавливает аккаунт из приватного ключа или BIP-39 сид-фразы:
     * перезаписывает ключ и адрес в SharedPreferences. Возвращает адрес.
     *
     * @throws IllegalArgumentException если ключ/фраза не прошли проверку
     */
    public static String recoverAccount(Context context, String input) {
        String normalized;
        String mnemonic = null;
        if (isValidMnemonic(input)) {
            mnemonic = normalizeMnemonic(input);
            normalized = derivePrivateKeyFromMnemonic(input);
        } else {
            normalized = normalizePrivateKey(input);
        }

        if (normalized == null) {
            throw new IllegalArgumentException("invalid_private_key");
        }
        BigInteger value = new BigInteger(normalized, 16);
        if (value.signum() <= 0 || value.compareTo(SECP256K1_ORDER) >= 0) {
            throw new IllegalArgumentException("invalid_private_key");
        }

        ECKeyPair keyPair;
        String address;
        try {
            keyPair = ECKeyPair.create(value);
            address = "0x" + Keys.getAddress(keyPair);
        } catch (Throwable t) {
            throw new IllegalArgumentException("invalid_private_key", t);
        }

        SharedPreferences.Editor editor = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PRIVATE_KEY, "0x" + normalized)
                .putString(KEY_ADDRESS, address);
        if (mnemonic == null) {
            editor.remove(KEY_MNEMONIC);
        } else {
            editor.putString(KEY_MNEMONIC, mnemonic);
        }
        editor.apply();
        return address;
    }

    /** Считает балансы всех адресов по цепочке: награды + входящие - исходящие - комиссии. */
    public static Map<String, Double> calculateBalancesFromChain(
            Context context, List<String> chain) {
        Map<String, Double> balances = new HashMap<>();
        if (context == null || chain == null) {
            return balances;
        }
        for (int i = 0; i < chain.size(); i++) {
            try {
                JSONObject block = new JSONObject(chain.get(i));
                String validator = TransferManager.normalizeAddress(
                        block.optString("validator", ""));
                if (!validator.isEmpty()) {
                    double reward = Tokenomics.getBlockReward(
                            context, block.optInt("index", i));
                    if (reward > 0.0) {
                        balances.put(validator,
                                balances.getOrDefault(validator, 0.0) + reward);
                    }
                }
                for (JSONObject tx : TransferManager.extractTransactions(
                        block.optString("data", ""))) {
                    applyTransactionDelta(balances, tx);
                }
            } catch (Throwable ignored) {
            }
        }
        return balances;
    }

    public static double calculateBalanceFromChain(
            Context context, List<String> chain, String address) {
        String target = TransferManager.normalizeAddress(address);
        if (target.isEmpty()) {
            return 0.0;
        }
        return calculateBalancesFromChain(context, chain).getOrDefault(target, 0.0);
    }

    /** Пересчитывает и сохраняет локальный баланс из цепочки блоков. */
    public static double recalculateBalanceFromChain(
            Context context, List<String> chain, String myAddress) {
        double balance = calculateBalanceFromChain(context, chain, myAddress);
        setLocalBalance(context, balance);
        return balance;
    }

    public static double recalculateBalanceFromChain(
            Context context, BlockStorage storage, String myAddress) {
        List<String> chain = storage == null
                ? java.util.Collections.emptyList() : storage.getAllBlocks();
        return recalculateBalanceFromChain(context, chain, myAddress);
    }

    /**
     * Немедленно пересчитывает баланс из локальной цепочки и уведомляет UI.
     * В SPV-режиме полной цепочки нет, поэтому оставляем текущий баланс.
     */
    public static double refreshLocalBalance(Context context) {
        if (context == null) {
            return 0.0;
        }
        double balance;
        try {
            BlockStorage storage = new BlockStorage(context);
            balance = storage.isSpvMode()
                    ? getLocalBalance(context)
                    : recalculateBalanceFromChain(
                            context, storage, getOrCreateAddress(context));
        } catch (Throwable t) {
            balance = getLocalBalance(context);
        }
        try {
            LocalBroadcastManager.getInstance(context.getApplicationContext())
                    .sendBroadcast(new Intent(TransferManager.ACTION_BALANCE_CHANGED));
        } catch (Throwable ignored) {
        }
        return balance;
    }

    /**
     * Инкрементально применяет один блок к локальному балансу.
     * Используется в SPV-режиме, где полная цепочка не хранится.
     */
    public static double applyBlockToLocalBalance(
            Context context, String blockJson, String myAddress) {
        if (context == null || blockJson == null || blockJson.trim().isEmpty()) {
            return getLocalBalance(context);
        }
        String local = TransferManager.normalizeAddress(myAddress);
        if (local.isEmpty()) {
            return getLocalBalance(context);
        }
        try {
            JSONObject block = new JSONObject(blockJson);
            int index = block.optInt("index", -1);
            String validator = TransferManager.normalizeAddress(
                    block.optString("validator", ""));
            double delta = 0.0;
            if (local.equals(validator)) {
                delta += Tokenomics.getBlockReward(context, index);
            }
            for (JSONObject tx : TransferManager.extractTransactions(
                    block.optString("data", ""))) {
                try {
                    String from = TransferManager.normalizeAddress(tx.optString("from", ""));
                    String to = TransferManager.normalizeAddress(tx.optString("to", ""));
                    BigDecimal amount = new BigDecimal(tx.optString("amount", "0"));
                    BigDecimal fee = new BigDecimal(tx.optString("fee", "0"));
                    double value = amount.doubleValue();
                    if (value <= 0.0) {
                        continue;
                    }
                    if (local.equals(from)) {
                        delta -= value + fee.doubleValue();
                    }
                    if (local.equals(to)) {
                        delta += value;
                    }
                } catch (Throwable ignored) {
                }
            }
            double balance = Math.max(0.0, getLocalBalance(context) + delta);
            setLocalBalance(context, balance);
            return balance;
        } catch (Throwable t) {
            return getLocalBalance(context);
        }
    }

    private static void applyTransactionDelta(Map<String, Double> balances, JSONObject tx) {
        try {
            String from = TransferManager.normalizeAddress(tx.optString("from", ""));
            String to = TransferManager.normalizeAddress(tx.optString("to", ""));
            BigDecimal amount = new BigDecimal(tx.optString("amount", "0"));
            BigDecimal fee = new BigDecimal(tx.optString("fee", "0"));
            double value = amount.doubleValue();
            double commission = fee.doubleValue();
            if (value <= 0.0) {
                return;
            }
            if (!from.isEmpty()) {
                balances.put(from, balances.getOrDefault(from, 0.0) - value - commission);
            }
            if (!to.isEmpty()) {
                balances.put(to, balances.getOrDefault(to, 0.0) + value);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void setLocalBalance(Context context, double balance) {
        if (context == null) {
            return;
        }
        synchronized (BALANCE_LOCK) {
            double safeBalance = balance;
            if (Double.isNaN(safeBalance) || Double.isInfinite(safeBalance) || safeBalance < 0) {
                safeBalance = 0;
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LOCAL_BALANCE,
                            java.math.BigDecimal.valueOf(safeBalance).toPlainString())
                    .apply();
        }
    }

    public static double getLocalBalance(Context context) {
        if (context == null) {
            return 0.0;
        }
        synchronized (BALANCE_LOCK) {
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
    }

    public static void addLocalBalance(Context context, double amount) {
        if (context == null) {
            return;
        }
        synchronized (BALANCE_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            double newBalance = getLocalBalance(context) + amount;
            prefs.edit()
                    .putString(KEY_LOCAL_BALANCE,
                            java.math.BigDecimal.valueOf(newBalance).toPlainString())
                    .apply();
        }
    }


    public static ECKeyPair getOrCreateKeyPair(Context context) {
        String hex = getPrivateKeyHex(context);
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        String clean = hex.startsWith("0x") || hex.startsWith("0X")
                ? hex.substring(2) : hex;
        try {
            return ECKeyPair.create(new java.math.BigInteger(clean, 16));
        } catch (Throwable t) {
            return null;
        }
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
