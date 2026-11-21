package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

public class BlockStorage {
    private static final String PREFS_NAME = "triger_blocks";
    private static final String KEY_LAST_HASH = "last_block_hash";
    private static final String KEY_LAST_INDEX = "last_block_index";
    private static final String BLOCK_PREFIX = "block_";

    private final SharedPreferences prefs;

    public BlockStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasBlock(int index) {
        return prefs.contains(BLOCK_PREFIX + index);
    }

    public String getBlock(int index) {
        return prefs.getString(BLOCK_PREFIX + index, null);
    }

    public void saveBlock(String jsonBlock) {
        try {
            JSONObject obj = new JSONObject(jsonBlock);
            int index = obj.getInt("index");
            String hash = obj.getString("hash");
            prefs.edit()
                    .putString(BLOCK_PREFIX + index, jsonBlock)
                    .putInt(KEY_LAST_INDEX, index)
                    .putString(KEY_LAST_HASH, hash)
                    .apply();
        } catch (Exception e) {
            // некорректный JSON — игнорируем
        }
    }

    public int getLastBlockIndex() {
        return prefs.getInt(KEY_LAST_INDEX, -1);
    }

    public int getBlockCount() {
        return getLastBlockIndex() + 1;
    }

    public String getLastBlockHash() {
        return prefs.getString(KEY_LAST_HASH, "0x0");
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
                expectedPrev = obj.optString("hash");
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
