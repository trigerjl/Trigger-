package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final String VOTE_COUNT_PREFIX = "validator_votes_";
    private static final String VOTE_PEERS_PREFIX = "validator_vote_peers_";
    private static final int CONSENSUS_NUMERATOR = 2;
    private static final int CONSENSUS_DENOMINATOR = 3;

    private static volatile boolean syncedWithPeers = false;

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
            List<ValidatorEntry> validators = new ArrayList<>();
            String deviceId = DeviceIdentity.getOrCreateDeviceId(context);
            if (deviceId != null && !deviceId.trim().isEmpty()) {
                validators.add(new ValidatorEntry(
                        deviceId.trim(), "", DeviceIdentity.isStrongBoxBacked(context)));
                Log.i(TAG, "PoA: added local device_id to validator list"
                        + (DeviceIdentity.isStrongBoxBacked(context) ? " (StrongBox)" : ""));
            }
            writeValidatorEntries(prefs, validators);
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
            String simHash = "";
            try {
                simHash = SimIdentity.getOrCreateSimHash(context);
            } catch (Throwable ignored) {
            }
            if (simHash == null) {
                simHash = "";
            }
            simHash = simHash.trim();

            List<ValidatorEntry> entries = readValidatorEntries(prefs);
            boolean exists = false;
            for (ValidatorEntry entry : entries) {
                if (entry.deviceId.equals(dev)) {
                    entry.strongBox = isStrongBoxDevice(context, dev);
                    if (!simHash.isEmpty() && (entry.simHash == null || entry.simHash.isEmpty())) {
                        entry.simHash = simHash;
                    } else if (simHash.isEmpty() && entry.simHash != null && !entry.simHash.isEmpty()) {
                        // keep existing
                    } else if (!simHash.isEmpty()) {
                        entry.simHash = simHash;
                    }
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                entries.add(new ValidatorEntry(dev, simHash, isStrongBoxDevice(context, dev)));
                Log.i(TAG, "PoA: validator added");
            }
            writeValidatorEntries(prefs, entries);
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
            List<ValidatorEntry> entries = readValidatorEntries(prefs);
            boolean removed = false;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).deviceId.equals(dev)) {
                    entries.remove(i);
                    removed = true;
                    break;
                }
            }
            if (removed) {
                writeValidatorEntries(prefs, entries);
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

    /**
     * Отмечает, что список валидаторов был получен от пира. Пока флаг не
     * выставлен, узел с подключёнными пирами не должен создавать блоки:
     * иначе каждый узел считает себя единственным валидатором и цепочки
     * расходятся (после этого очередь PoA навсегда блокируется).
     */
    public static void markSyncedWithPeers() {
        syncedWithPeers = true;
    }

    public static boolean isSyncedWithPeers() {
        return syncedWithPeers;
    }

    /** Индекс устройства в отсортированном списке валидаторов или -1. */
    public static int getValidatorIndex(Context context, String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (context == null || dev.isEmpty()) {
            return -1;
        }
        return getValidators(context).indexOf(dev);
    }

    /** Пора ли локальному валидатору создавать блок с данным индексом. */
    public static boolean isLocalValidatorTurn(Context context, int blockIndex) {
        int count = getValidatorCount(context);
        int myIndex = getLocalValidatorIndex(context);
        return myIndex >= 0 && count > 0 && (blockIndex % count == myIndex);
    }

    /**
     * Голосует за список валидаторов, полученный от пира.
     * Список применяется, когда за него проголосовало не менее 2/3
     * подключённых пиров. Возвращает true, если локальный список обновлён.
     */
    public static boolean voteForValidators(Context context, List<String> validatorsFromPeer) {
        return voteForValidators(context, null, validatorsFromPeer);
    }

    /** Голосует за список валидаторов от конкретного пира (endpoint ID). */
    public static boolean voteForValidators(Context context, String peerEndpointId,
                                            List<String> validatorsFromPeer) {
        if (context == null) {
            return false;
        }
        ensureInitialized(context);
        List<String> proposed = normalizeValidators(validatorsFromPeer);
        if (proposed.isEmpty()) {
            return false;
        }
        synchronized (ValidatorManager.class) {
            SharedPreferences prefs = getPrefs(context);
            if (prefs == null) {
                return false;
            }
            List<String> local = getValidators(context);
            if (proposed.equals(local)) {
                return false;
            }

            // Объединяем списки, чтобы при первом контакте двух узлов
            // оба пришли к общему набору валидаторов, а не обменялись
            // одиночными списками и не потеряли право создавать блоки.
            List<String> merged = new ArrayList<>(local);
            merged.addAll(proposed);
            proposed = normalizeValidators(merged);
            if (proposed.equals(local)) {
                return false;
            }

            int totalPeers = getConnectedPeerCount();
            if (totalPeers <= 0) {
                Log.d(TAG, "Validator vote ignored: no connected peers");
                return false;
            }

            String canonical = canonicalList(proposed);
            String listHash = sha256Hex(canonical);
            String peerKey = VOTE_PEERS_PREFIX + listHash;
            Set<String> voters = new LinkedHashSet<>(
                    prefs.getStringSet(peerKey, Collections.emptySet()));
            String voter = peerEndpointId == null ? "" : peerEndpointId.trim();
            if (voter.isEmpty()) {
                voter = "anonymous";
            }
            voters.add(voter);
            // Узлы с StrongBox получают больший вес при голосовании:
            // аппаратно защищённый ключ делает голос менее подделываемым.
            int count = weightedVoteCount(voters);
            boolean voterStrongBox = DeviceIdentity.isPeerStrongBoxBacked(voter);
            prefs.edit()
                    .putStringSet(peerKey, voters)
                    .putInt(VOTE_COUNT_PREFIX + listHash, count)
                    .commit();

            int required = (int) Math.ceil(
                    totalPeers * (double) CONSENSUS_NUMERATOR / CONSENSUS_DENOMINATOR);
            required = Math.max(1, required);
            Log.i(TAG, "Validator vote from " + voter
                    + (voterStrongBox ? " [StrongBox]" : "")
                    + ": " + count + "/" + required
                    + " (peers=" + totalPeers + ")");
            if (count < required) {
                return false;
            }

            applyValidators(prefs, proposed);
            clearVotes(prefs);
            Log.i(TAG, "Validator consensus reached: local list updated");
            Log.i(TAG, "PoA validators now: " + proposed
                    + ", localIndex=" + getLocalValidatorIndex(context));
            return true;
        }
    }

    private static int getConnectedPeerCount() {
        try {
            List<String> peers = IrohNode.getInstance().getConnectedPeers();
            return peers == null ? 0 : peers.size();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to count connected peers", t);
            return 0;
        }
    }

    private static int weightedVoteCount(Set<String> voters) {
        int count = 0;
        if (voters == null) {
            return count;
        }
        for (String voter : voters) {
            count += DeviceIdentity.isPeerStrongBoxBacked(voter) ? 2 : 1;
        }
        return count;
    }

    private static boolean isStrongBoxDevice(Context context, String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (dev.isEmpty() || context == null) {
            return false;
        }
        String local = DeviceIdentity.getOrCreateDeviceId(context);
        if (local != null && dev.equals(local.trim())) {
            return DeviceIdentity.isStrongBoxBacked(context);
        }
        return DeviceIdentity.isDeviceStrongBoxBacked(dev);
    }

    /** Сколько известных валидаторов используют StrongBox. */
    public static int getStrongBoxValidatorCount(Context context) {
        if (context == null) {
            return 0;
        }
        int count = 0;
        for (ValidatorEntry entry : readValidatorEntries(getPrefs(context))) {
            if (entry.strongBox || isStrongBoxDevice(context, entry.deviceId)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> normalizeValidators(List<String> validators) {
        Set<String> unique = new LinkedHashSet<>();
        if (validators != null) {
            for (String validator : validators) {
                if (validator != null && !validator.trim().isEmpty()) {
                    unique.add(validator.trim());
                }
            }
        }
        List<String> result = new ArrayList<>(unique);
        Collections.sort(result);
        return result;
    }

    private static String canonicalList(List<String> validators) {
        StringBuilder sb = new StringBuilder();
        for (String validator : validators) {
            sb.append(validator).append('\n');
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input == null ? 0 : input.hashCode());
        }
    }

    private static void applyValidators(SharedPreferences prefs, List<String> validators) {
        Map<String, String> simHashByDevice = new HashMap<>();
        Map<String, Boolean> strongBoxByDevice = new HashMap<>();
        for (ValidatorEntry entry : readValidatorEntries(prefs)) {
            if (!entry.deviceId.isEmpty()) {
                simHashByDevice.put(entry.deviceId, entry.simHash);
                strongBoxByDevice.put(entry.deviceId, entry.strongBox);
            }
        }
        List<ValidatorEntry> entries = new ArrayList<>();
        for (String validator : normalizeValidators(validators)) {
            String simHash = simHashByDevice.get(validator);
            Boolean strongBox = strongBoxByDevice.get(validator);
            entries.add(new ValidatorEntry(validator, simHash == null ? "" : simHash,
                    strongBox != null && strongBox));
        }
        writeValidatorEntries(prefs, entries);
    }

    private static void clearVotes(SharedPreferences prefs) {
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : all.keySet()) {
            if (key.startsWith(VOTE_COUNT_PREFIX) || key.startsWith(VOTE_PEERS_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.commit();
    }

    private static class ValidatorEntry {
        String deviceId;
        String simHash;
        boolean strongBox;

        ValidatorEntry(String deviceId, String simHash) {
            this(deviceId, simHash, false);
        }

        ValidatorEntry(String deviceId, String simHash, boolean strongBox) {
            this.deviceId = deviceId == null ? "" : deviceId.trim();
            this.simHash = simHash == null ? "" : simHash.trim();
            this.strongBox = strongBox;
        }
    }

    private static List<ValidatorEntry> readValidatorEntries(SharedPreferences prefs) {
        List<ValidatorEntry> entries = new ArrayList<>();
        String json = prefs.getString(KEY_VALIDATORS, null);
        if (json == null || json.trim().isEmpty()) {
            return entries;
        }
        try {
            JSONArray array = new JSONArray(json);
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject obj = (JSONObject) item;
                    String deviceId = obj.optString("device_id", obj.optString("deviceId", "")).trim();
                    String simHash = obj.optString("sim_hash", obj.optString("simHash", "")).trim();
                    boolean strongBox = obj.optBoolean("strongbox", false);
                    if (!deviceId.isEmpty() && seen.add(deviceId)) {
                        entries.add(new ValidatorEntry(deviceId, simHash, strongBox));
                    }
                } else if (item instanceof String) {
                    String deviceId = ((String) item).trim();
                    if (!deviceId.isEmpty() && seen.add(deviceId)) {
                        entries.add(new ValidatorEntry(deviceId, ""));
                    }
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "failed to parse validators JSON", e);
        }
        return entries;
    }

    private static void writeValidatorEntries(SharedPreferences prefs, Iterable<ValidatorEntry> entries) {
        JSONArray array = new JSONArray();
        if (entries != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (ValidatorEntry entry : entries) {
                if (entry == null || entry.deviceId.isEmpty() || !seen.add(entry.deviceId)) {
                    continue;
                }
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("device_id", entry.deviceId);
                    obj.put("sim_hash", entry.simHash == null ? "" : entry.simHash);
                    obj.put("strongbox", entry.strongBox);
                    array.put(obj);
                } catch (JSONException ignored) {
                }
            }
        }
        prefs.edit().putString(KEY_VALIDATORS, array.toString()).apply();
    }

    private static List<String> readValidators(SharedPreferences prefs) {
        List<String> validators = new ArrayList<>();
        for (ValidatorEntry entry : readValidatorEntries(prefs)) {
            if (!entry.deviceId.isEmpty()) {
                validators.add(entry.deviceId);
            }
        }
        return validators;
    }

    private static void writeValidators(SharedPreferences prefs, Iterable<String> validators) {
        List<ValidatorEntry> entries = new ArrayList<>();
        if (validators != null) {
            for (String deviceId : validators) {
                if (deviceId != null && !deviceId.trim().isEmpty()) {
                    entries.add(new ValidatorEntry(deviceId.trim(), ""));
                }
            }
        }
        writeValidatorEntries(prefs, entries);
    }

    /** Проверяет, что sim_hash не используется другим device_id. */
    public static boolean isSimHashUnique(Context context, String simHash) {
        String hash = simHash == null ? "" : simHash.trim();
        if (context == null || hash.isEmpty()) {
            return true;
        }
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return true;
        }
        ensureInitialized(context);
        List<ValidatorEntry> entries = readValidatorEntries(prefs);
        int count = 0;
        for (ValidatorEntry entry : entries) {
            if (hash.equals(entry.simHash)) {
                count++;
                if (count > 1) {
                    return false;
                }
            }
        }
        return count <= 1;
    }

    public static String getSimHashForDevice(Context context, String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (context == null || dev.isEmpty()) {
            return "";
        }
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return "";
        }
        ensureInitialized(context);
        for (ValidatorEntry entry : readValidatorEntries(prefs)) {
            if (entry.deviceId.equals(dev)) {
                return entry.simHash == null ? "" : entry.simHash;
            }
        }
        return "";
    }
}
