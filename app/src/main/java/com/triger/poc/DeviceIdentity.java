package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Идентичность устройства на базе Android Keystore.
 *
 * При первом запуске генерируется неизвлекаемый ключ EC P-256. device_id —
 * SHA-256 от публичного ключа. Ключ уникален для устройства + установки:
 * при сбросе телефона/переустановке приложения он пропадёт.
 *
 * Здесь же ведётся реестр device_id пиров: если один и тот же device_id
 * встречается у разных Endpoint ID, оба помечаются подозрительными (без блокировки).
 */
public final class DeviceIdentity {

    private static final String TAG = "DeviceIdentity";
    private static final String DEVICE_ID_PREFS = "triger_device_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String DEVICE_KEY_ALIAS = "triger_device_identity_p256";
    private static final String KEY_STRONGBOX_BACKED = "strongbox_backed";
    private static final int SHORT_ID_LENGTH = 12;

    private static final Map<String, String> peerDeviceIds = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> peerStrongBox = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> deviceStrongBox = new ConcurrentHashMap<>();
    private static final Set<String> suspiciousPeers = ConcurrentHashMap.newKeySet();
    private static volatile Boolean strongBoxBackedCache;

    private DeviceIdentity() {
    }

    /** Возвращает device_id текущей установки, создавая ключ при первом вызове. */
    public static String getOrCreateDeviceId(Context context) {
        if (context == null) {
            return "";
        }
        SharedPreferences prefs =
                context.getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_DEVICE_ID, null);
        if (cached != null && !cached.isEmpty()) {
            strongBoxBackedCache = prefs.getBoolean(KEY_STRONGBOX_BACKED, false);
            return cached;
        }

        String deviceId = generateDeviceIdWithKeystore(prefs);
        if (deviceId != null && !deviceId.isEmpty()) {
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
            Log.i(TAG, "generated device_id"
                    + (Boolean.TRUE.equals(strongBoxBackedCache) ? " (StrongBox)" : " (TEE)"));
            return deviceId;
        }

        // Резервный вариант, если Android Keystore недоступен (редко).
        String fallback = java.util.UUID.randomUUID().toString().replace("-", "");
        prefs.edit()
                .putString(KEY_DEVICE_ID, fallback)
                .putBoolean(KEY_STRONGBOX_BACKED, false)
                .apply();
        strongBoxBackedCache = false;
        Log.w(TAG, "keystore unavailable, using fallback device_id");
        return fallback;
    }

    private static String generateDeviceIdWithKeystore(SharedPreferences prefs) {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
                boolean strongBox = generateDeviceKey(keyStore);
                strongBoxBackedCache = strongBox;
                prefs.edit().putBoolean(KEY_STRONGBOX_BACKED, strongBox).apply();
                Log.i(TAG, strongBox
                        ? "device key generated in StrongBox"
                        : "device key generated in TEE (StrongBox unavailable)");
            } else {
                strongBoxBackedCache = prefs.getBoolean(KEY_STRONGBOX_BACKED, false);
            }

            PublicKey publicKey = keyStore.getCertificate(DEVICE_KEY_ALIAS).getPublicKey();
            if (publicKey == null) {
                return null;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(publicKey.getEncoded()));
        } catch (Throwable t) {
            Log.w(TAG, "keystore key generation failed", t);
            return null;
        }
    }

    /**
     * Генерирует ключ идентичности, по возможности в StrongBox.
     * Возвращает true, если ключ создан в StrongBox, false — в TEE.
     */
    private static boolean generateDeviceKey(KeyStore keyStore) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (generateStrongBoxKey(keyStore)) {
                return true;
            }
            // Неудачная попытка могла оставить alias — удаляем перед fallback.
            if (keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
                try {
                    keyStore.deleteEntry(DEVICE_KEY_ALIAS);
                } catch (Throwable ignored) {
                }
            }
        }
        generateKeyPair(keyStore, false);
        return false;
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static boolean generateStrongBoxKey(KeyStore keyStore) {
        try {
            generateKeyPair(keyStore, true);
            return true;
        } catch (StrongBoxUnavailableException e) {
            Log.i(TAG, "StrongBox unavailable on this device, falling back to TEE");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "StrongBox key generation failed, falling back to TEE", t);
            return false;
        }
    }

    private static void generateKeyPair(KeyStore keyStore, boolean strongBox) throws Exception {
        KeyPairGenerator keyPairGenerator =
                KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                DEVICE_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setUserAuthenticationRequired(false);

        if (strongBox) {
            builder.setIsStrongBoxBacked(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setInvalidatedByBiometricEnrollment(false);
        }

        keyPairGenerator.initialize(builder.build());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        if (keyPair.getPublic() == null && !keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
            throw new IllegalStateException("key pair not generated");
        }
    }

    /**
     * true, если ключ идентичности текущей установки создан в StrongBox.
     * Лениво инициализирует ключ при первом вызове.
     */
    public static boolean isStrongBoxBacked(Context context) {
        if (context == null) {
            return false;
        }
        Boolean cached = strongBoxBackedCache;
        if (cached != null) {
            return cached;
        }
        SharedPreferences prefs =
                context.getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_DEVICE_ID)) {
            getOrCreateDeviceId(context);
        }
        boolean value = prefs.getBoolean(KEY_STRONGBOX_BACKED, false);
        strongBoxBackedCache = value;
        return value;
    }

    /** Кешированное значение флага StrongBox (false, если ключ ещё не создан). */
    public static boolean isStrongBoxBacked() {
        return Boolean.TRUE.equals(strongBoxBackedCache);
    }

    /**
     * Регистрирует device_id пира и возвращает список других пиров с тем же
     * device_id. Совпадения помечаются как подозрительные, но не блокируются.
     */
    public static List<String> registerPeerDeviceId(String peerId, String deviceId) {
        List<String> conflicts = new ArrayList<>();
        String peer = peerId == null ? "" : peerId.trim();
        String dev = deviceId == null ? "" : deviceId.trim();
        if (peer.isEmpty() || dev.isEmpty()) {
            return conflicts;
        }

        peerDeviceIds.put(peer, dev);
        for (Map.Entry<String, String> entry : peerDeviceIds.entrySet()) {
            if (!entry.getKey().equals(peer) && dev.equals(entry.getValue())) {
                conflicts.add(entry.getKey());
            }
        }
        if (!conflicts.isEmpty()) {
            suspiciousPeers.add(peer);
            suspiciousPeers.addAll(conflicts);
        }
        return conflicts;
    }

    public static void markSuspicious(String peerId) {
        String peer = peerId == null ? "" : peerId.trim();
        if (!peer.isEmpty()) {
            suspiciousPeers.add(peer);
        }
    }

    /** Запоминает, что узел пира использует аппаратно защищённый ключ. */
    public static void registerPeerStrongBox(String peerId, String deviceId, boolean strongBox) {
        String peer = peerId == null ? "" : peerId.trim();
        String dev = deviceId == null ? "" : deviceId.trim();
        if (!peer.isEmpty()) {
            peerStrongBox.put(peer, strongBox);
        }
        if (!dev.isEmpty()) {
            deviceStrongBox.put(dev, strongBox);
        }
    }

    public static boolean isPeerStrongBoxBacked(String peerId) {
        String peer = peerId == null ? "" : peerId.trim();
        return !peer.isEmpty() && Boolean.TRUE.equals(peerStrongBox.get(peer));
    }

    public static boolean isDeviceStrongBoxBacked(String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        return !dev.isEmpty() && Boolean.TRUE.equals(deviceStrongBox.get(dev));
    }

    public static boolean isSuspicious(String peerId) {
        return peerId != null && suspiciousPeers.contains(peerId.trim());
    }

    public static String getPeerDeviceId(String peerId) {
        return peerId == null ? "" : peerDeviceIds.getOrDefault(peerId.trim(), "");
    }

    public static List<String> getSuspiciousPeers() {
        List<String> peers = new ArrayList<>(suspiciousPeers);
        Collections.sort(peers);
        return peers;
    }

    public static Map<String, String> getPeerDeviceIds() {
        return Collections.unmodifiableMap(peerDeviceIds);
    }

    /** Короткая форма device_id для логов/UI. */
    public static String shortId(String deviceId) {
        if (deviceId == null) {
            return "";
        }
        return deviceId.length() <= SHORT_ID_LENGTH
                ? deviceId
                : deviceId.substring(0, SHORT_ID_LENGTH);
    }

    public static void resetPeerRegistry() {
        peerDeviceIds.clear();
        peerStrongBox.clear();
        deviceStrongBox.clear();
        suspiciousPeers.clear();
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
