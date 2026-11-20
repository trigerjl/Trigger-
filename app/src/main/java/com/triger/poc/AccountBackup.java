package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ручной экспорт/импорт данных аккаунта для PoC: баланс, история транзакций и ник.
 * Опционально (для экспорта с расширенным флагом) — история чатов и настройки
 * (тема, язык, интервал майнинга, купленные и применённые рамки/аватары).
 * Приватный ключ в файл не попадает — для него есть отдельное восстановление/экспорт.
 */
public final class AccountBackup {

    public static final int VERSION = 2;

    private AccountBackup() {
    }

    public static final class ImportResult {
        public final String address;
        public final String nickname;
        public final double balance;
        public final int transactions;
        public final int chatMessages;
        public final boolean settingsRestored;

        ImportResult(String address, String nickname, double balance, int transactions,
                     int chatMessages, boolean settingsRestored) {
            this.address = address;
            this.nickname = nickname;
            this.balance = balance;
            this.transactions = transactions;
            this.chatMessages = chatMessages;
            this.settingsRestored = settingsRestored;
        }
    }

    public static String exportJson(Context context) throws JSONException {
        return exportJson(context, false);
    }

    public static String exportJson(Context context, boolean includeExtended)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("address", KeyManager.getOrCreateAddress(context));
        root.put("nickname", prefs(context).getString(SettingsActivity.KEY_USERNAME, ""));
        root.put("balance", KeyManager.getLocalBalance(context));

        JSONArray transactions = new JSONArray();
        for (TransactionHistory.Transaction tx : TransactionHistory.getTransactions(context)) {
            JSONObject obj = new JSONObject();
            obj.put("id", tx.id);
            obj.put("type", tx.type);
            obj.put("amount", tx.amount);
            obj.put("timestamp", tx.timestamp);
            obj.put("hash", tx.hash);
            obj.put("memo", tx.memo);
            transactions.put(obj);
        }
        root.put("transactions", transactions);

        if (includeExtended) {
            root.put("settings", exportSettings(context));
            root.put("cosmetics", exportCosmetics(context));
            root.put("chat", exportChat(context));
        }
        return root.toString(2);
    }

    private static JSONObject exportSettings(Context context) throws JSONException {
        SharedPreferences sp = prefs(context);
        JSONObject settings = new JSONObject();
        settings.put("theme", sp.getBoolean(SettingsActivity.KEY_THEME, true));
        settings.put("language", sp.getString(LanguageActivity.KEY_APP_LANGUAGE, ""));
        settings.put("miningInterval", SettingsActivity.getMiningIntervalSeconds(context));
        return settings;
    }

    private static JSONObject exportCosmetics(Context context) throws JSONException {
        SharedPreferences sp = context.getSharedPreferences(
                CosmeticManager.PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject cosmetics = new JSONObject();
        cosmetics.put(CosmeticManager.KEY_EQUIPPED_FRAME,
                sp.getString(CosmeticManager.KEY_EQUIPPED_FRAME, ""));
        cosmetics.put(CosmeticManager.KEY_EQUIPPED_AVATAR,
                sp.getString(CosmeticManager.KEY_EQUIPPED_AVATAR, ""));
        JSONArray owned = new JSONArray();
        Map<String, ?> all = sp.getAll();
        for (String key : all.keySet()) {
            Object value = all.get(key);
            if (key.startsWith(CosmeticManager.KEY_OWNED_PREFIX)
                    && value instanceof Boolean && Boolean.TRUE.equals(value)) {
                owned.put(key.substring(CosmeticManager.KEY_OWNED_PREFIX.length()));
            }
        }
        cosmetics.put("owned", owned);
        return cosmetics;
    }

    private static JSONArray exportChat(Context context) throws JSONException {
        JSONArray chat = new JSONArray();
        for (ChatDatabase.Message message
                : ChatDatabase.getInstance(context).getAllMessages()) {
            JSONObject obj = new JSONObject();
            obj.put("id", message.id);
            obj.put("peerId", message.peerId);
            obj.put("senderId", message.senderId);
            obj.put("text", message.text);
            obj.put("timestamp", message.timestamp);
            obj.put("isRead", message.isRead);
            obj.put("status", message.status);
            obj.put("replyToId", message.replyToId);
            obj.put("replyPreview", message.replyPreview);
            obj.put("messageType", message.messageType);
            obj.put("audioPath", message.audioPath);
            chat.put(obj);
        }
        return chat;
    }

    public static ImportResult importJson(Context context, String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            throw new JSONException("empty backup");
        }
        JSONObject root = new JSONObject(json);
        if (root.optInt("version", 0) > VERSION) {
            throw new JSONException("unsupported backup version");
        }

        double balance = root.optDouble("balance", 0);
        if (Double.isNaN(balance) || Double.isInfinite(balance) || balance < 0) {
            throw new JSONException("invalid balance");
        }

        String address = root.optString("address", "");
        String nickname = root.optString("nickname", "");
        prefs(context).edit()
                .putString(SettingsActivity.KEY_USERNAME, nickname)
                .apply();

        List<TransactionHistory.Transaction> transactions = new ArrayList<>();
        JSONArray array = root.optJSONArray("transactions");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                transactions.add(new TransactionHistory.Transaction(
                        obj.optString("id", ""),
                        obj.optString("type", ""),
                        obj.optDouble("amount", 0),
                        obj.optLong("timestamp", 0),
                        obj.optString("hash", ""),
                        obj.optString("memo", "")));
            }
        }

        int chatMessages = importChat(context, root.optJSONArray("chat"));
        boolean settingsRestored = importSettings(context, root.optJSONObject("settings"))
                | importCosmetics(context, root.optJSONObject("cosmetics"));

        KeyManager.setLocalBalance(context, balance);
        TransactionHistory.replaceTransactions(context, transactions);
        return new ImportResult(address, nickname, balance, transactions.size(),
                chatMessages, settingsRestored);
    }

    private static int importChat(Context context, JSONArray chat) {
        if (chat == null) {
            return 0;
        }
        List<ChatDatabase.Message> messages = new ArrayList<>();
        for (int i = 0; i < chat.length(); i++) {
            JSONObject obj = chat.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            messages.add(new ChatDatabase.Message(
                    obj.optLong("id", 0),
                    obj.optString("peerId", ""),
                    obj.optString("senderId", ""),
                    obj.optString("text", ""),
                    obj.optLong("timestamp", 0),
                    obj.optBoolean("isRead", true),
                    obj.optInt("status", ChatDatabase.STATUS_DELIVERED),
                    obj.optLong("replyToId", 0),
                    obj.optString("replyPreview", ""),
                    obj.optString("messageType", ChatDatabase.TYPE_TEXT),
                    obj.optString("audioPath", "")));
        }
        ChatDatabase.getInstance(context).replaceAllMessages(messages);
        return messages.size();
    }

    private static boolean importSettings(Context context, JSONObject settings) {
        if (settings == null) {
            return false;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putBoolean(SettingsActivity.KEY_THEME, settings.optBoolean("theme", true));
        String language = settings.optString("language", "").trim();
        if (!language.isEmpty()) {
            editor.putString(LanguageActivity.KEY_APP_LANGUAGE, language);
        }
        int miningInterval = settings.optInt("miningInterval",
                SettingsActivity.DEFAULT_MINING_INTERVAL);
        editor.putInt(SettingsActivity.KEY_MINING_INTERVAL,
                Math.max(1, miningInterval));
        editor.apply();
        return true;
    }

    private static boolean importCosmetics(Context context, JSONObject cosmetics) {
        if (cosmetics == null) {
            return false;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(
                CosmeticManager.PREFS_NAME, Context.MODE_PRIVATE).edit();
        String frame = cosmetics.optString(CosmeticManager.KEY_EQUIPPED_FRAME, "").trim();
        if (!frame.isEmpty()) {
            editor.putString(CosmeticManager.KEY_EQUIPPED_FRAME, frame);
        }
        String avatar = cosmetics.optString(CosmeticManager.KEY_EQUIPPED_AVATAR, "").trim();
        if (!avatar.isEmpty()) {
            editor.putString(CosmeticManager.KEY_EQUIPPED_AVATAR, avatar);
        }
        JSONArray owned = cosmetics.optJSONArray("owned");
        if (owned != null) {
            for (int i = 0; i < owned.length(); i++) {
                String id = owned.optString(i, "").trim().toLowerCase(Locale.US);
                if (!id.isEmpty()) {
                    editor.putBoolean(CosmeticManager.KEY_OWNED_PREFIX + id, true);
                }
            }
        }
        editor.apply();
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
