package com.triger.poc;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

public class NewsItem {
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_NICKNAME_LENGTH = 100;
    private static final int MAX_TEXT_LENGTH = 500;

    private final String id;
    private final String authorAddress;
    private final String authorNickname;
    private final String text;
    private final long timestamp;
    private final String signature;

    public NewsItem(String id, String authorAddress, String authorNickname, String text,
                    long timestamp, String signature) {
        this.id = id == null ? "" : id;
        this.authorAddress = authorAddress == null ? "" : authorAddress;
        this.authorNickname = authorNickname == null ? "" : authorNickname;
        this.text = text == null ? "" : text;
        this.timestamp = timestamp;
        this.signature = signature == null ? "" : signature;
    }

    public String getId() {
        return id;
    }

    public String getAuthorAddress() {
        return authorAddress;
    }

    public String getAuthorNickname() {
        return authorNickname;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSignature() {
        return signature;
    }

    public NewsItem withSignature(String value) {
        return new NewsItem(id, authorAddress, authorNickname, text, timestamp, value);
    }

    public String signingPayload() {
        return id + "|" + authorAddress + "|" + authorNickname + "|" + text + "|" + timestamp;
    }

    public boolean isValid() {
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH
                || text.isEmpty() || text.length() > MAX_TEXT_LENGTH
                || authorNickname.length() > MAX_NICKNAME_LENGTH
                || timestamp <= 0L
                || !TransferManager.isValidAddress(authorAddress)) {
            return false;
        }
        return verifySignature();
    }

    public boolean verifySignature() {
        if (id.trim().isEmpty() || authorAddress.trim().isEmpty()
                || signature.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("validator", authorAddress);
            object.put("signature", signature);
            object.put("hash", signingPayload());
            return BlockStorage.verifyBlockSignature(object);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String sign(String payload, ECKeyPair keyPair) throws Exception {
        return BlockStorage.signBlockHash(payload, keyPair);
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("type", "NEWS_BROADCAST");
            object.put("id", id);
            object.put("authorAddress", authorAddress);
            object.put("authorNickname", authorNickname);
            object.put("text", text);
            object.put("timestamp", timestamp);
            object.put("signature", signature);
        } catch (Throwable ignored) {
        }
        return object;
    }

    public static NewsItem fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new NewsItem(
                object.optString("id", ""),
                object.optString("authorAddress", ""),
                object.optString("authorNickname", ""),
                object.optString("text", ""),
                object.optLong("timestamp", 0L),
                object.optString("signature", ""));
    }
}
