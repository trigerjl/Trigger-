package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Хранилище заголовков блоков для лёгкого SPV-режима. */
public class SpvStorage {
    private static final String TAG = "SpvStorage";
    private static final String PREFS_NAME = "triger_spv_headers";
    private static final String HEADER_PREFIX = "header_";
    private static final String KEY_LAST_INDEX = "last_header_index";
    private static final String KEY_LAST_HASH = "last_header_hash";

    private final SharedPreferences prefs;

    public SpvStorage(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean saveHeader(JSONObject header) {
        if (header == null) {
            return false;
        }
        try {
            int index = header.optInt("index", -1);
            String hash = header.optString("hash", "").trim();
            if (index < 0 || hash.isEmpty()) {
                return false;
            }
            String key = HEADER_PREFIX + index;
            if (prefs.contains(key) || hasHeaderHash(hash)) {
                return false;
            }
            int last = prefs.getInt(KEY_LAST_INDEX, -1);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(key, header.toString());
            if (index == last + 1) {
                editor.putInt(KEY_LAST_INDEX, index)
                        .putString(KEY_LAST_HASH, hash);
            }
            editor.apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "failed to save header", t);
            return false;
        }
    }

    public List<JSONObject> getHeaders() {
        List<JSONObject> headers = new ArrayList<>();
        int last = getLastHeaderIndex();
        for (int i = 0; i <= last; i++) {
            JSONObject header = getHeader(i);
            if (header != null) {
                headers.add(header);
            }
        }
        return headers;
    }

    public JSONObject getLastHeader() {
        return getHeader(getLastHeaderIndex());
    }

    public JSONObject getHeader(int index) {
        String raw = prefs.getString(HEADER_PREFIX + index, null);
        if (raw == null) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean hasHeader(int index) {
        return prefs.contains(HEADER_PREFIX + index);
    }

    public boolean hasHeaderHash(String hash) {
        String target = hash == null ? "" : hash.trim();
        if (target.isEmpty()) {
            return false;
        }
        int last = getLastHeaderIndex();
        for (int i = 0; i <= last; i++) {
            JSONObject header = getHeader(i);
            if (header != null && target.equalsIgnoreCase(header.optString("hash", ""))) {
                return true;
            }
        }
        return false;
    }

    public void setLastHeader(int index, String hash) {
        prefs.edit()
                .putInt(KEY_LAST_INDEX, Math.max(-1, index))
                .putString(KEY_LAST_HASH, hash == null ? "0x0" : hash)
                .apply();
    }

    public int getLastHeaderIndex() {
        return prefs.getInt(KEY_LAST_INDEX, -1);
    }

    public String getLastHeaderHash() {
        return prefs.getString(KEY_LAST_HASH, "0x0");
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
