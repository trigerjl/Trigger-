package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Список разрешённых валидаторов (Proof of Authority) для PoC.
 * Хранится в SharedPreferences "triger_validators" по ключу "validators"
 * как JSON-массив строк (device_id).
 *
 * При первом запуске, пока список пуст, в него добавляется device_id
 * текущего телефона из {@link DeviceIdentity}.
 */
public final class ValidatorManager {

    private static final String TAG = "ValidatorManager";
    private static final String PREFS_NAME = "triger_validators";
    private static final String KEY_VALIDATORS = "validators";

    private ValidatorManager() {
    }

    private static SharedPreferences getPrefs(Context context) {
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Инициализирует список валидаторов device_id текущего телефона при первом запуске. */
    public static void ensureInitialized(Context context) {
        if (context == null) {
            return;
        }
        synchronized (ValidatorManager.class) {
            SharedPreferences prefs = getPrefs(context);
            if (prefs == null || prefs.contains(KEY_VALIDATORS)) {
                return;
            }
            List<String> validators = new ArrayList<>();
            String deviceId = DeviceIdentity.getOrCreateDeviceId(context);
            if (deviceId != null && !deviceId.trim().isEmpty()) {
                validators.add(deviceId.trim());
                Log.i(TAG, "PoA: added local device_id to validator list");
            }
            writeValidators(prefs, validators);
        }
    }

    /** Добавляет device_id в список разрешённых валидаторов. */
    public static void addValidator(Context context, String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (context == null || dev.isEmpty()) {
            return;
        }
        ensureInitialized(context);
        synchronized (ValidatorManager.class) {
            SharedPreferences prefs = getPrefs(context);
            if (prefs == null) {
                return;
            }
            Set<String> validators = new LinkedHashSet<>(readValidators(prefs));
            if (validators.add(dev)) {
                writeValidators(prefs, validators);
                Log.i(TAG, "PoA: validator added");
            }
        }
    }

    /** Удаляет device_id из списка валидаторов. */
    public static void removeValidator(Context context, String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (context == null || dev.isEmpty()) {
            return;
        }
        ensureInitialized(context);
        synchronized (ValidatorManager.class) {
            SharedPreferences prefs = getPrefs(context);
            if (prefs == null) {
                return;
            }
            Set<String> validators = new LinkedHashSet<>(readValidators(prefs));
            if (validators.remove(dev)) {
                writeValidators(prefs, validators);
                Log.i(TAG, "PoA: validator removed");
            }
        }
    }

    /** Проверяет, есть ли device_id в списке разрешённых валидаторов. */
    public static boolean isValidator(Context context, String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (context == null || dev.isEmpty()) {
            return false;
        }
        return getValidators(context).contains(dev);
    }

    /** Возвращает список разрешённых валидаторов (отсортирован для детерминизма). */
    public static List<String> getValidators(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        ensureInitialized(context);
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return Collections.emptyList();
        }
        List<String> validators = readValidators(prefs);
        Collections.sort(validators);
        return validators;
    }

    /** Возвращает индекс локального device_id в отсортированном списке валидаторов. */
    public static int getLocalValidatorIndex(Context context) {
        if (context == null) {
            return -1;
        }
        String deviceId = DeviceIdentity.getOrCreateDeviceId(context);
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return -1;
        }
        return getValidators(context).indexOf(deviceId.trim());
    }

    /** Является ли данное устройство валидатором. */
    public static boolean isLocalValidator(Context context) {
        if (context == null) {
            return false;
        }
        return isValidator(context, DeviceIdentity.getOrCreateDeviceId(context));
    }

    public static int getValidatorCount(Context context) {
        return getValidators(context).size();
    }

    private static List<String> readValidators(SharedPreferences prefs) {
        List<String> validators = new ArrayList<>();
        String json = prefs.getString(KEY_VALIDATORS, null);
        if (json == null || json.trim().isEmpty()) {
            return validators;
        }
        try {
            JSONArray array = new JSONArray(json);
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                String deviceId = array.optString(i, "").trim();
                if (!deviceId.isEmpty()) {
                    unique.add(deviceId);
                }
            }
            validators.addAll(unique);
        } catch (JSONException e) {
            Log.w(TAG, "failed to parse validators JSON", e);
        }
        return validators;
    }

    private static void writeValidators(SharedPreferences prefs, Iterable<String> validators) {
        JSONArray array = new JSONArray();
        if (validators != null) {
            for (String deviceId : validators) {
                if (deviceId != null && !deviceId.trim().isEmpty()) {
                    array.put(deviceId.trim());
                }
            }
        }
        prefs.edit().putString(KEY_VALIDATORS, array.toString()).apply();
    }
}
