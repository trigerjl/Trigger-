package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

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
    private static final int SHORT_ID_LENGTH = 12;

    private static final Map<String, String> peerDeviceIds = new ConcurrentHashMap<>();
    private static final Set<String> suspiciousPeers = ConcurrentHashMap.newKeySet();

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
            return cached;
        }

        String deviceId = generateDeviceIdWithKeystore();
        if (deviceId != null && !deviceId.isEmpty()) {
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
            Log.i(TAG, "generated device_id");
            return deviceId;
        }

        // Резервный вариант, если Android Keystore недоступен (редко).
        String fallback = java.util.UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_DEVICE_ID, fallback).apply();
        Log.w(TAG, "keystore unavailable, using fallback device_id");
        return fallback;
    }

    private static String generateDeviceIdWithKeystore() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
                KeyPairGenerator keyPairGenerator =
                        KeyPairGenerator.getInstance(
                                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
                KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                        DEVICE_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setUserAuthenticationRequired(false);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setUnlockedDeviceRequired(false);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    builder.setInvalidatedByBiometricEnrollment(false);
                }

                keyPairGenerator.initialize(builder.build());
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                if (keyPair.getPublic() == null) {
                    return null;
                }
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
