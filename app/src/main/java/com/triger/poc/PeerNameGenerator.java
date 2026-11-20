package com.triger.poc;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Детерминированное имя пира: первые 4 байта SHA-256 от Endpoint ID
 * превращаются в пару «прилагательное + существительное».
 */
public final class PeerNameGenerator {

    private static final String[] DEFAULT_ADJECTIVES = {
            "Brave", "Swift", "Clever", "Smart", "Strong", "Calm",
            "Cheerful", "Quiet", "Bold", "Wise", "Daring", "Loyal",
            "Steadfast", "Bright", "Sharp", "Free", "Fierce", "Sly",
            "Light", "Steel", "Reliable", "Courageous", "Decisive", "Agile"
    };

    private static final String[] DEFAULT_NOUNS = {
            "Cat", "Fox", "Wolf", "Bear", "Eagle", "Falcon",
            "Tiger", "Lion", "Leopard", "Hedgehog", "Hare", "Beaver",
            "Dolphin", "Whale", "Dragon", "Phoenix", "Hawk", "Raven",
            "Elk", "Deer", "Penguin", "Cheetah", "Lynx", "Coyote"
    };

    private PeerNameGenerator() {
    }

    public static String generateName(String endpointId) {
        return generateName(null, endpointId);
    }

    public static String generateName(Context context, String endpointId) {
        String id = endpointId == null ? "" : endpointId.trim();
        if (id.isEmpty()) {
            return context == null
                    ? "Unknown Peer"
                    : context.getString(R.string.peer_unknown);
        }
        String[] adjectives = array(context, R.array.peer_adjectives, DEFAULT_ADJECTIVES);
        String[] nouns = array(context, R.array.peer_nouns, DEFAULT_NOUNS);
        byte[] hash = sha256(id);
        int adjectiveIndex = (((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF)) % adjectives.length;
        int nounIndex = (((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF)) % nouns.length;
        return adjectives[adjectiveIndex] + " " + nouns[nounIndex];
    }

    private static String[] array(Context context, int resId, String[] fallback) {
        if (context == null) {
            return fallback;
        }
        try {
            String[] values = context.getResources().getStringArray(resId);
            return values.length == 0 ? fallback : values;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Короткий Endpoint ID для отладки (первые 12 символов). */
    public static String shortId(String endpointId) {
        String id = endpointId == null ? "" : endpointId.trim();
        if (id.length() <= 12) {
            return id;
        }
        return id.substring(0, 12) + "…";
    }

    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
