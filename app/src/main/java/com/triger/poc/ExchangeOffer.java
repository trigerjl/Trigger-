package com.triger.poc;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

import java.math.BigDecimal;

/** Объявление P2P-обменника TRG на фиат, подписанное создателем. */
public class ExchangeOffer {
    public static final String TYPE_SELL = "SELL";
    public static final String TYPE_BUY = "BUY";
    public static final String CURRENCY_RUB = "RUB";
    public static final String CURRENCY_USD = "USD";
    public static final String CURRENCY_EUR = "EUR";
    public static final String CURRENCY_KZT = "KZT";
    public static final String CURRENCY_UAH = "UAH";
    public static final String CURRENCY_BYN = "BYN";
    public static final String CURRENCY_TRY = "TRY";
    public static final String CURRENCY_CNY = "CNY";
    public static final String CURRENCY_INR = "INR";

    private final String id;
    private final String creatorAddress;
    private final String creatorNickname;
    private final String type;
    private final double amountTRG;
    private final double priceFiat;
    private final String currency;
    private final String paymentMethod;
    private final String paymentDetails;
    private final String escrowAddress;
    private final long timestamp;
    private final String signature;

    private String state = "";

    public ExchangeOffer(String id, String creatorAddress, String creatorNickname,
                         String type, double amountTRG, double priceFiat,
                         String currency, String paymentMethod, String paymentDetails,
                         String escrowAddress, long timestamp, String signature) {
        this.id = safe(id);
        this.creatorAddress = safe(creatorAddress);
        this.creatorNickname = safe(creatorNickname);
        this.type = safe(type);
        this.amountTRG = amountTRG;
        this.priceFiat = priceFiat;
        this.currency = safe(currency);
        this.paymentMethod = safe(paymentMethod);
        this.paymentDetails = safe(paymentDetails);
        this.escrowAddress = safe(escrowAddress);
        this.timestamp = timestamp;
        this.signature = safe(signature);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public String getId() {
        return id;
    }

    public String getCreatorAddress() {
        return creatorAddress;
    }

    public String getCreatorNickname() {
        return creatorNickname;
    }

    public String getType() {
        return type;
    }

    public boolean isSell() {
        return TYPE_SELL.equals(type);
    }

    public double getAmountTRG() {
        return amountTRG;
    }

    public double getPriceFiat() {
        return priceFiat;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getPaymentDetails() {
        return paymentDetails;
    }

    public String getEscrowAddress() {
        return escrowAddress;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSignature() {
        return signature;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = safe(state);
    }

    public String amountText() {
        return BigDecimal.valueOf(amountTRG).stripTrailingZeros().toPlainString();
    }

    public String priceText() {
        return BigDecimal.valueOf(priceFiat).stripTrailingZeros().toPlainString();
    }

    public String signingPayload() {
        return id + "|" + creatorAddress + "|" + creatorNickname + "|" + type
                + "|" + amountText() + "|" + priceText() + "|" + currency
                + "|" + paymentMethod + "|" + paymentDetails + "|"
                + escrowAddress + "|" + timestamp;
    }

    public boolean verifySignature() {
        if (id.trim().isEmpty() || creatorAddress.trim().isEmpty()
                || signature.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("validator", creatorAddress);
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
            object.put("type", "P2P_EXCHANGE_OFFER");
            object.put("id", id);
            object.put("creatorAddress", creatorAddress);
            object.put("creatorNickname", creatorNickname);
            object.put("offerType", type);
            object.put("amountTRG", amountTRG);
            object.put("priceFiat", priceFiat);
            object.put("currency", currency);
            object.put("paymentMethod", paymentMethod);
            object.put("paymentDetails", paymentDetails);
            object.put("escrowAddress", escrowAddress);
            object.put("timestamp", timestamp);
            object.put("signature", signature);
        } catch (Throwable ignored) {
        }
        return object;
    }

    public static ExchangeOffer fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new ExchangeOffer(
                object.optString("id", ""),
                object.optString("creatorAddress", ""),
                object.optString("creatorNickname", ""),
                object.optString("offerType", object.optString("type", "")),
                object.optDouble("amountTRG", 0.0),
                object.optDouble("priceFiat", 0.0),
                object.optString("currency", ""),
                object.optString("paymentMethod", ""),
                object.optString("paymentDetails", ""),
                object.optString("escrowAddress", ""),
                object.optLong("timestamp", 0L),
                object.optString("signature", ""));
    }
}
