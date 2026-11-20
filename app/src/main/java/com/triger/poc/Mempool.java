package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Пул подписанных, но ещё не включённых в блок транзакций. */
public final class Mempool {

    private static final String TAG = "Mempool";
    private static final String PREFS_NAME = "triger_mempool";
    private static final String KEY_TRANSACTIONS = "transactions";
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    public Mempool(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void addTransaction(JSONObject tx) {
        if (tx == null) {
            return;
        }
        String id = tx.optString("id", "").trim();
        if (id.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            JSONArray array = readArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject existing = array.optJSONObject(i);
                if (existing != null && id.equals(existing.optString("id", ""))) {
                    return;
                }
            }
            array.put(tx);
            writeArray(array);
        }
    }

    public List<JSONObject> getPendingTransactions() {
        List<JSONObject> result = new ArrayList<>();
        synchronized (LOCK) {
            JSONArray array = readArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject tx = array.optJSONObject(i);
                if (tx != null) {
                    result.add(tx);
                }
            }
        }
        return result;
    }

    public void removeTransactions(List<String> txIds) {
        if (txIds == null || txIds.isEmpty()) {
            return;
        }
        Set<String> remove = new HashSet<>(txIds);
        synchronized (LOCK) {
            JSONArray array = readArray();
            JSONArray kept = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject tx = array.optJSONObject(i);
                if (tx == null) {
                    continue;
                }
                if (!remove.contains(tx.optString("id", ""))) {
                    kept.put(tx);
                }
            }
            writeArray(kept);
        }
    }

    public boolean contains(String txId) {
        String id = txId == null ? "" : txId.trim();
        if (id.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            JSONArray array = readArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject tx = array.optJSONObject(i);
                if (tx != null && id.equals(tx.optString("id", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    public void clear() {
        synchronized (LOCK) {
            prefs.edit().remove(KEY_TRANSACTIONS).apply();
        }
    }

    private JSONArray readArray() {
        String raw = prefs.getString(KEY_TRANSACTIONS, "");
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            Log.w(TAG, "failed to parse mempool", e);
            return new JSONArray();
        }
    }

    private void writeArray(JSONArray array) {
        prefs.edit().putString(KEY_TRANSACTIONS, array.toString()).apply();
    }
}
