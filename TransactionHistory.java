package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class TransactionHistory {

    public static final String TYPE_MINING_REWARD = "mining_reward";
    public static final String TYPE_NETWORK_BONUS = "network_bonus";
    public static final String TYPE_BURN = "burn";

    // Комиссия сжигания: 1% от суммы перевода
    public static final double BURN_RATE = 0.01;
    public static final String TYPE_SEND = "send";
    public static final String TYPE_RECEIVE = "receive";

    private static final String PREFS_NAME = "triger_history";
    private static final String KEY_TRANSACTIONS = "transactions";
    private static final int MAX_TRANSACTIONS = 50;
    private static final Object LOCK = new Object();

    private TransactionHistory() {
    }

    public static final class Transaction {
        public final String id;
        public final String type;
        public final double amount;
        public final long timestamp;

        Transaction(String id, String type, double amount, long timestamp) {
            this.id = id;
            this.type = type;
            this.amount = amount;
            this.timestamp = timestamp;
        }
    }

    public static void addTransaction(Context context, String type, double amount) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            JSONArray array = readArray(context);
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", UUID.randomUUID().toString());
                obj.put("type", type == null ? "" : type);
                obj.put("amount", amount);
                obj.put("timestamp", System.currentTimeMillis());
                array.put(obj);
            } catch (Exception e) {
                return;
            }
            trim(array);
            writeArray(context, array);
        }
    }

    // Сжигает 1% от суммы перевода (минимум 1 TRG) и фиксирует запись TYPE_BURN.
    // Возвращает фактически сожжённое количество.
    public static int burnFee(Context context, int sendAmount) {
        if (context == null || sendAmount <= 0) {
            return 0;
        }
        int fee = (int) Math.ceil(sendAmount * BURN_RATE);
        if (fee < 1) {
            fee = 1;
        }
        addTransaction(context, TYPE_BURN, fee);
        return fee;
    }

    public static List<Transaction> getTransactions(Context context) {        List<Transaction> result = new ArrayList<>();
        if (context == null) {
            return result;
        }
        synchronized (LOCK) {
            JSONArray array = readArray(context);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                result.add(new Transaction(
                        obj.optString("id", ""),
                        obj.optString("type", ""),
                        obj.optDouble("amount", 0),
                        obj.optLong("timestamp", 0)));
            }
        }
        Collections.sort(result, new Comparator<Transaction>() {
            @Override
            public int compare(Transaction a, Transaction b) {
                return Long.compare(b.timestamp, a.timestamp);
            }
        });
        return result;
    }

    public static void clearHistory(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            writeArray(context, new JSONArray());
        }
    }

    private static void trim(JSONArray array) {
        while (array.length() > MAX_TRANSACTIONS) {
            array.remove(0);
        }
    }

    private static JSONArray readArray(Context context) {
        String raw = prefs(context).getString(KEY_TRANSACTIONS, "");
        if (raw == null || raw.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeArray(Context context, JSONArray array) {
        prefs(context).edit().putString(KEY_TRANSACTIONS, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
