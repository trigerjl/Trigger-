package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class BlockStorage {
    private static final String TAG = "BlockStorage";
    private static final String PREFS_NAME = "triger_blocks";
    private static final String KEY_LAST_HASH = "last_block_hash";
    private static final String KEY_LAST_INDEX = "last_block_index";
    private static final String KEY_SPV_MODE = "spv_mode";
    private static final String BLOCK_PREFIX = "block_";
    public static final String FIELD_SIGNATURE = "signature";
    private static final String FIELD_VALIDATOR_DEVICE_ID = "validator_device_id";
    private static final String FIELD_VALIDATOR_SIM_HASH = "validator_sim_hash";

    private final Context context;
    private final SharedPreferences prefs;
    private final SpvStorage spvStorage;

    public BlockStorage(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.spvStorage = new SpvStorage(context);
    }

    public static String computeBlockHash(int index, String prevHash, long timestamp,
                                          String validator, String validatorDeviceId,
                                          String validatorSimHash, String data) {
        String input = index
                + safe(prevHash)
                + timestamp
                + safe(validator)
                + safe(validatorDeviceId)
                + safe(validatorSimHash)
                + safe(data);
        return sha256Hex(input);
    }

    public static String computeBlockHash(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        return computeBlockHash(
                obj.optInt("index", -1),
                obj.optString("prevHash", ""),
                obj.optLong("timestamp", 0L),
                obj.optString("validator", ""),
                obj.optString(FIELD_VALIDATOR_DEVICE_ID, ""),
                obj.optString(FIELD_VALIDATOR_SIM_HASH, ""),
                obj.optString("data", ""));
    }

    public static String signBlockHash(String hash, ECKeyPair keyPair) throws Exception {
        Sign.SignatureData signature = Sign.signPrefixedMessage(
                safe(hash).getBytes(StandardCharsets.UTF_8), keyPair);
        String r = String.format("%064x", new BigInteger(1, signature.getR()));
        String s = String.format("%064x", new BigInteger(1, signature.getS()));
        String v = String.format("%02x", signature.getV()[0] & 0xFF);
        return r + s + v;
    }

    public static boolean verifyBlockHash(JSONObject obj) {
        if (obj == null) {
            return false;
        }
        String stored = obj.optString("hash", "");
        return !stored.isEmpty() && stored.equalsIgnoreCase(computeBlockHash(obj));
    }

    public static boolean verifyBlockSignature(JSONObject obj) {
        if (obj == null) {
            return false;
        }
        try {
            String validator = obj.optString("validator", "").trim();
            String signatureHex = obj.optString(FIELD_SIGNATURE, "").trim();
            if (validator.isEmpty() || signatureHex.isEmpty()) {
                return false;
            }
            if (signatureHex.startsWith("0x") || signatureHex.startsWith("0X")) {
                signatureHex = signatureHex.substring(2);
            }
            if (signatureHex.length() != 130) {
                return false;
            }
            byte[] r = Numeric.hexStringToByteArray(signatureHex.substring(0, 64));
            byte[] s = Numeric.hexStringToByteArray(signatureHex.substring(64, 128));
            int v = Integer.parseInt(signatureHex.substring(128, 130), 16);
            Sign.SignatureData signature = new Sign.SignatureData((byte) v, r, s);
            String hash = obj.optString("hash", "");
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(
                    hash.getBytes(StandardCharsets.UTF_8), signature);
            String recovered = "0x" + Keys.getAddress(publicKey);
            return recovered.equalsIgnoreCase(validator);
        } catch (Throwable t) {
            Log.w(TAG, "Block signature verification failed", t);
            return false;
        }
    }

    public static boolean isBlockValid(JSONObject obj) {
        return verifyBlockHash(obj) && verifyBlockSignature(obj);
    }

    /** Проверка заголовка без транзакций и подписи. */
    public static boolean verifyHeader(JSONObject header) {
        if (header == null) {
            return false;
        }
        int index = header.optInt("index", -1);
        String hash = header.optString("hash", "").trim();
        String prevHash = header.optString("prevHash", "").trim();
        return index >= 0 && !hash.isEmpty()
                && (index == 0 ? "0x0".equals(prevHash) : !prevHash.isEmpty());
    }

    public static JSONObject buildHeader(JSONObject block) {
        if (block == null) {
            return null;
        }
        try {
            JSONObject header = new JSONObject();
            header.put("index", block.optInt("index", -1));
            header.put("prevHash", block.optString("prevHash", ""));
            header.put("timestamp", block.optLong("timestamp", 0L));
            header.put("hash", block.optString("hash", ""));
            header.put("validator", block.optString("validator", ""));
            return header;
        } catch (Throwable t) {
            Log.w(TAG, "failed to build header", t);
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "SHA-256 unavailable", e);
            return "";
        }
    }

    public boolean isSpvMode() {
        return prefs.getBoolean(KEY_SPV_MODE, false);
    }

    public boolean hasBlock(int index) {
        return isSpvMode() ? spvStorage.hasHeader(index)
                : prefs.contains(BLOCK_PREFIX + index);
    }

    public String getBlock(int index) {
        if (!isSpvMode()) {
            return getStoredBlock(index);
        }
        JSONObject header = spvStorage.getHeader(index);
        return header == null ? null : header.toString();
    }

    /** Полный блок из хранилища, независимо от SPV-режима. */
    public String getStoredBlock(int index) {
        return prefs.getString(BLOCK_PREFIX + index, null);
    }

    public boolean saveBlock(String jsonBlock) {
        try {
            JSONObject obj = new JSONObject(jsonBlock);
            int index = obj.getInt("index");
            String hash = obj.getString("hash");
            if (hasBlock(index)) {
                Log.w(TAG, "Block with index " + index + " already exists, skipping");
                return false;
            }
            if (hasBlockHash(hash)) {
                Log.w(TAG, "Block with hash " + hash + " already exists, skipping");
                return false;
            }
            int last = getLastBlockIndex();
            if (index == last + 1) {
                double reward = Tokenomics.getBlockReward(index);
                if (!Tokenomics.canMintReward(getAllBlocks(), reward)) {
                    Log.w(TAG, "Block rejected: emission limit reached, index="
                            + index);
                    return false;
                }
            }
            if (isSpvMode()) {
                JSONObject header = buildHeader(obj);
                if (header == null || !spvStorage.saveHeader(header)) {
                    return false;
                }
                prefs.edit().remove(BLOCK_PREFIX + index).apply();
                Tokenomics.updateCachedTotalSupply(context, getAllBlocks());
                return true;
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(BLOCK_PREFIX + index, jsonBlock);
            if (index == last + 1) {
                editor.putInt(KEY_LAST_INDEX, index)
                        .putString(KEY_LAST_HASH, hash);
            }
            editor.apply();
            Tokenomics.updateCachedTotalSupply(context, getAllBlocks());
            return true;
        } catch (Exception e) {
            // некорректный JSON — игнорируем
            return false;
        }
    }

    public boolean hasBlockHash(String hash) {
        if (isSpvMode()) {
            return spvStorage.hasHeaderHash(hash);
        }
        String target = hash == null ? "" : hash.trim();
        if (target.isEmpty()) {
            return false;
        }
        int last = getLastBlockIndex();
        for (int i = 0; i <= last; i++) {
            String block = getStoredBlock(i);
            if (block == null) {
                continue;
            }
            try {
                JSONObject obj = new JSONObject(block);
                if (target.equalsIgnoreCase(obj.optString("hash", ""))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public int getLastBlockIndex() {
        return isSpvMode() ? spvStorage.getLastHeaderIndex()
                : prefs.getInt(KEY_LAST_INDEX, -1);
    }

    public int getBlockCount() {
        return getLastBlockIndex() + 1;
    }

    public String getLastBlockHash() {
        return isSpvMode() ? spvStorage.getLastHeaderHash()
                : prefs.getString(KEY_LAST_HASH, "0x0");
    }

    public java.util.List<String> getAllBlocks() {
        java.util.List<String> blocks = new java.util.ArrayList<>();
        int last = getLastBlockIndex();
        for (int i = 0; i <= last; i++) {
            String block = getBlock(i);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    public boolean isChainValid() {
        int last = getLastBlockIndex();
        String expectedPrev = "0x0";
        for (int i = 0; i <= last; i++) {
            String block = getBlock(i);
            if (block == null) {
                return false;
            }
            try {
                JSONObject obj = new JSONObject(block);
                if (!obj.optString("prevHash").equals(expectedPrev)) {
                    return false;
                }
                if (isSpvMode() ? !verifyHeader(obj)
                        : (!verifyBlockHash(obj) || !verifyBlockSignature(obj))) {
                    return false;
                }
                expectedPrev = obj.optString("hash");
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** Включает SPV: конвертирует сохранённые полные блоки в заголовки и удаляет их. */
    public void enableSpvMode() {
        int last = prefs.getInt(KEY_LAST_INDEX, -1);
        String lastHash = prefs.getString(KEY_LAST_HASH, "0x0");
        for (int i = 0; i <= last; i++) {
            String full = getStoredBlock(i);
            if (full == null) {
                continue;
            }
            try {
                JSONObject header = buildHeader(new JSONObject(full));
                if (header != null) {
                    spvStorage.saveHeader(header);
                }
            } catch (Throwable ignored) {
            }
        }
        spvStorage.setLastHeader(last, lastHash);
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_SPV_MODE, true);
        java.util.Map<String, ?> all = prefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(BLOCK_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /** Выключает SPV, сохраняя текущую высоту и хеш для последующей догрузки блоков. */
    public void disableSpvMode() {
        int last = getLastBlockIndex();
        String lastHash = getLastBlockHash();
        prefs.edit()
                .putBoolean(KEY_SPV_MODE, false)
                .putInt(KEY_LAST_INDEX, last)
                .putString(KEY_LAST_HASH, lastHash)
                .apply();
    }

    public void clearAll() {
        prefs.edit().clear().apply();
        spvStorage.clear();
        Tokenomics.updateCachedTotalSupply(context, java.util.Collections.emptyList());
    }
}
