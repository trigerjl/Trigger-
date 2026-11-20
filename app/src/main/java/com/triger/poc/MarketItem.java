package com.triger.poc;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

import java.math.BigDecimal;

/** Объявление маркета, подписанное адресом продавца. */
public class MarketItem {
    private final String id;
    private final String sellerAddress;
    private final String sellerNickname;
    private final String title;
    private final String description;
    private final double price;
    private final long timestamp;
    private final String signature;
    private final boolean escrowEnabled;

    public MarketItem(String id, String sellerAddress, String sellerNickname,
                      String title, String description, double price,
                      long timestamp, String signature) {
        this(id, sellerAddress, sellerNickname, title, description, price,
                timestamp, signature, true);
    }

    public MarketItem(String id, String sellerAddress, String sellerNickname,
                      String title, String description, double price,
                      long timestamp, String signature, boolean escrowEnabled) {
        this.id = id == null ? "" : id;
        this.sellerAddress = sellerAddress == null ? "" : sellerAddress;
        this.sellerNickname = sellerNickname == null ? "" : sellerNickname;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.price = price;
        this.timestamp = timestamp;
        this.signature = signature == null ? "" : signature;
        this.escrowEnabled = escrowEnabled;
    }

    public String getId() {
        return id;
    }

    public String getSellerAddress() {
        return sellerAddress;
    }

    public String getSellerNickname() {
        return sellerNickname;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public double getPrice() {
        return price;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSignature() {
        return signature;
    }

    public boolean isEscrowEnabled() {
        return escrowEnabled;
    }

    /** Каноническая строка, которую подписывает продавец. */
    public String signingPayload() {
        return signingPayloadLegacy() + "|" + escrowEnabled;
    }

    /** Каноническая строка старого формата (без флага эскроу). */
    public String signingPayloadLegacy() {
        return id + "|" + sellerAddress + "|" + sellerNickname + "|"
                + title + "|" + description + "|" + priceText() + "|" + timestamp;
    }

    public String priceText() {
        return BigDecimal.valueOf(price).stripTrailingZeros().toPlainString();
    }

    public boolean verifySignature() {
        if (id.trim().isEmpty() || sellerAddress.trim().isEmpty()
                || signature.trim().isEmpty()) {
            return false;
        }
        if (verifyPayload(signingPayload())) {
            return true;
        }
        // Объявления, созданные до появления эскроу, подписаны старым форматом.
        return verifyPayload(signingPayloadLegacy());
    }

    private boolean verifyPayload(String payload) {
        try {
            JSONObject object = new JSONObject();
            object.put("validator", sellerAddress);
            object.put("signature", signature);
            object.put("hash", payload);
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
            object.put("type", "MARKET_ITEM");
            object.put("id", id);
            object.put("sellerAddress", sellerAddress);
            object.put("sellerNickname", sellerNickname);
            object.put("title", title);
            object.put("description", description);
            object.put("price", price);
            object.put("timestamp", timestamp);
            object.put("signature", signature);
            object.put("escrow", escrowEnabled);
        } catch (Throwable ignored) {
        }
        return object;
    }

    public static MarketItem fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new MarketItem(
                object.optString("id", ""),
                object.optString("sellerAddress", ""),
                object.optString("sellerNickname", ""),
                object.optString("title", ""),
                object.optString("description", ""),
                object.optDouble("price", 0.0),
                object.optLong("timestamp", 0L),
                object.optString("signature", ""),
                object.optBoolean("escrow", true));
    }
}
