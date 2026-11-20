package com.triger.poc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Идентичность SIM-карты для PoA ("одна SIM = один узел").
 *
 * На Android 10+ ICCID/IMSI недоступны обычным приложениям, поэтому при
 * невозможности прочитать ICCID используется device_id как fallback.
 */
public final class SimIdentity {

    private static final String TAG = "SimIdentity";
    private static final String PREFS_NAME = "triger_prefs";
    private static final String KEY_SIM_HASH = "sim_hash";
    private static final String KEY_SIM_FALLBACK = "sim_fallback";

    private SimIdentity() {
    }

    private static SharedPreferences getPrefs(Context context) {
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Возвращает SHA-256 от ICCID либо device_id, если ICCID недоступен.
     * Результат кешируется в SharedPreferences (triger_prefs/sim_hash).
     */
    public static synchronized String getOrCreateSimHash(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) {
            return "";
        }
        String cached = prefs.getString(KEY_SIM_HASH, null);
        if (cached != null && !cached.trim().isEmpty()) {
            return cached;
        }

        String iccid = readIccid(context);
        String simHash;
        boolean fallback;
        if (iccid != null && !iccid.isEmpty()) {
            simHash = sha256Hex(iccid);
            fallback = false;
            Log.i(TAG, "sim_hash created from ICCID");
        } else {
            String deviceId = DeviceIdentity.getOrCreateDeviceId(context);
            simHash = (deviceId == null || deviceId.trim().isEmpty())
                    ? "" : sha256Hex(deviceId);
            fallback = true;
            Log.w(TAG, "ICCID unavailable, sim_hash falls back to device_id hash");
        }

        if (simHash == null || simHash.trim().isEmpty()) {
            return "";
        }
        prefs.edit()
                .putString(KEY_SIM_HASH, simHash)
                .putBoolean(KEY_SIM_FALLBACK, fallback)
                .apply();
        return simHash;
    }

    /**
     * true, если в устройстве есть реальная SIM-карта.
     *
     * На Android 10+ ICCID может быть недоступен даже при готовой SIM, поэтому
     * основным признаком является SIM_STATE_READY. ICCID читается только как
     * дополнительная проверка для неопределённых состояний.
     */
    public static boolean hasRealSim(Context context) {
        if (context == null) {
            return false;
        }
        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            return false;
        }

        int state;
        try {
            state = tm.getSimState();
        } catch (Throwable t) {
            Log.w(TAG, "failed to read SIM state", t);
            state = TelephonyManager.SIM_STATE_UNKNOWN;
        }

        switch (state) {
            case TelephonyManager.SIM_STATE_READY:
                return true;
            case TelephonyManager.SIM_STATE_ABSENT:
                return false;
            default:
                String iccid = readIccid(context);
                return iccid != null && !iccid.trim().isEmpty();
        }
    }

    /** true, если sim_hash получен из device_id, а не из ICCID. */
    public static boolean isFallback(Context context) {
        SharedPreferences prefs = getPrefs(context);
        return prefs != null && prefs.getBoolean(KEY_SIM_FALLBACK, true);
    }

    @SuppressLint({"MissingPermission", "HardwareIds"})
    private static String readIccid(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return null;
            }
            String iccid = tm.getSimSerialNumber();
            return iccid == null ? null : iccid.trim();
        } catch (SecurityException e) {
            Log.w(TAG, "ICCID access denied by platform", e);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "failed to read ICCID", t);
            return null;
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "failed to hash ICCID", e);
            return "";
        }
    }
}
