package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

public class UnreadCounter {
    private static final String PREFS = "triger_unread";
    private static final String KEY_PREFIX = "unread_";

    private static volatile UnreadCounter instance;
    private final Context appContext;

    private UnreadCounter(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static synchronized UnreadCounter getInstance(Context ctx) {
        if (instance == null) {
            instance = new UnreadCounter(ctx);
        }
        return instance;
    }

    public int getUnread(String peerId) {
        if (peerId == null || peerId.isEmpty() || appContext == null) {
            return 0;
        }
        SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getInt(KEY_PREFIX + peerId, 0);
    }

    public int getTotalUnread() {
        if (appContext == null) {
            return 0;
        }
        SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        int total = 0;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith(KEY_PREFIX)) {
                Object v = e.getValue();
                if (v instanceof Integer) {
                    total += (Integer) v;
                }
            }
        }
        return total;
    }

    public void increment(String peerId) {
        if (peerId == null || peerId.isEmpty() || appContext == null) {
            return;
        }
        if (ChatActivity.isActiveFor(peerId)) {
            return;
        }
        int current = getUnread(peerId);
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PREFIX + peerId, current + 1)
                .apply();
    }

    public void clear(String peerId) {
        if (peerId == null || peerId.isEmpty() || appContext == null) {
            return;
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PREFIX + peerId)
                .apply();
    }

    public void clearAll() {
        if (appContext == null) {
            return;
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }
}
