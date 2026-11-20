package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Локальная адресная книга: ник → Endpoint ID.
 *
 * Пары сохраняются после получения профиля пира. Ник уникален без учёта
 * регистра: новый Endpoint ID не может перезаписать уже известный контакт
 * с тем же ником (защита от подмены при повторном подключении).
 */
public final class AddressBook {

    public static final String PREFS_NAME = "triger_contacts";
    private static final String TAG = "AddressBook";
    private static final String KEY_CONTACTS = "contacts";
    private static final int MAX_USERNAME_LENGTH = 32;
    private static final int MAX_CONTACTS = 500;

    public static final class Contact {
        public final String username;
        public final String endpointId;

        public Contact(String username, String endpointId) {
            this.username = username;
            this.endpointId = endpointId;
        }
    }

    private AddressBook() {
    }

    /**
     * Сохраняет пару ник → Endpoint ID. Если ник уже закреплён за другим
     * Endpoint ID, запись не перезаписывается.
     */
    public static void saveContact(Context context, String username, String endpointId) {
        if (context == null) {
            return;
        }
        String name = normalizeUsername(username);
        String id = endpointId == null ? "" : endpointId.trim();
        if (name.isEmpty() || id.isEmpty()) {
            return;
        }

        List<Contact> contacts = load(context);
        int endpointIndex = -1;
        String currentName = "";
        for (int i = 0; i < contacts.size(); i++) {
            Contact contact = contacts.get(i);
            if (contact.endpointId.equals(id)) {
                endpointIndex = i;
                currentName = contact.username;
                break;
            }
        }
        if (endpointIndex >= 0 && currentName.equals(name)) {
            return;
        }

        for (Contact contact : contacts) {
            if (!contact.endpointId.equals(id)
                    && contact.username.equalsIgnoreCase(name)) {
                Log.w(TAG, "username '" + name + "' already belongs to another endpoint; "
                        + "ignoring update for " + id);
                return;
            }
        }

        if (endpointIndex >= 0) {
            contacts.set(endpointIndex, new Contact(name, id));
        } else {
            if (contacts.size() >= MAX_CONTACTS) {
                contacts.remove(0);
            }
            contacts.add(new Contact(name, id));
        }
        persist(context, contacts);
    }

    /** Поиск контакта по нику без учёта регистра. */
    public static Contact findByUsername(Context context, String username) {
        String name = normalizeUsername(username);
        if (context == null || name.isEmpty()) {
            return null;
        }
        for (Contact contact : load(context)) {
            if (contact.username.equalsIgnoreCase(name)) {
                return contact;
            }
        }
        return null;
    }

    /** Ник, сохранённый для Endpoint ID (пусто, если контакта нет). */
    public static String getUsername(Context context, String endpointId) {
        String id = endpointId == null ? "" : endpointId.trim();
        if (context == null || id.isEmpty()) {
            return "";
        }
        for (Contact contact : load(context)) {
            if (contact.endpointId.equals(id)) {
                return contact.username;
            }
        }
        return "";
    }

    private static List<Contact> load(Context context) {
        List<Contact> contacts = new ArrayList<>();
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_CONTACTS, "");
            if (raw == null || raw.isEmpty()) {
                return contacts;
            }
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String username = normalizeUsername(obj.optString("username", ""));
                String endpointId = obj.optString("endpointId", "").trim();
                if (!username.isEmpty() && !endpointId.isEmpty()) {
                    contacts.add(new Contact(username, endpointId));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to load contacts: " + t);
        }
        return contacts;
    }

    private static void persist(Context context, List<Contact> contacts) {
        try {
            JSONArray array = new JSONArray();
            for (Contact contact : contacts) {
                JSONObject obj = new JSONObject();
                obj.put("username", contact.username);
                obj.put("endpointId", contact.endpointId);
                array.put(obj);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CONTACTS, array.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "failed to persist contacts: " + t);
        }
    }

    private static String normalizeUsername(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace('\n', ' ').replace('\r', ' ');
        if (value.length() > MAX_USERNAME_LENGTH) {
            value = value.substring(0, MAX_USERNAME_LENGTH).trim();
        }
        return value;
    }
}
