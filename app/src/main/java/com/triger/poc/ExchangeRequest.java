package com.triger.poc;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

import java.math.BigDecimal;

/** Заявка на обмен по объявлению P2P-обменника. */
public class ExchangeRequest {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final String id;
    private final String offerId;
    private final String type;
    private final String buyerAddress;
    private final String sellerAddress;
    private final double amountTRG;
    private final double priceFiat;
    private final String currency;
    private final String paymentMethod;
    private final String paymentDetails;
    private final String escrowAddress;
    private final long timestamp;
    private final String signature;

    private String status = STATUS_PENDING;

    public ExchangeRequest(String id, String offerId, String type,
                           String buyerAddress, String sellerAddress,
                           double amountTRG, double priceFiat, String currency,
                           String paymentMethod, String paymentDetails,
                           String escrowAddress, long timestamp, String signature) {
        this.id = safe(id);
        this.offerId = safe(offerId);
        this.type = safe(type);
        this.buyerAddress = safe(buyerAddress);
        this.sellerAddress = safe(sellerAddress);
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

    public String getOfferId() {
        return offerId;
    }

    public String getType() {
        return type;
    }

    public boolean isSell() {
        return ExchangeOffer.TYPE_SELL.equals(type);
    }

    public String getBuyerAddress() {
        return buyerAddress;
    }

    public String getSellerAddress() {
        return sellerAddress;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = safe(status);
    }

    /** Адрес инициатора заявки: покупатель TRG для SELL и продавец TRG для BUY. */
    public String initiatorAddress() {
        return isSell() ? buyerAddress : sellerAddress;
    }

    public String amountText() {
        return BigDecimal.valueOf(amountTRG).stripTrailingZeros().toPlainString();
    }

    public String priceText() {
        return BigDecimal.valueOf(priceFiat).stripTrailingZeros().toPlainString();
    }

    public String signingPayload() {
        return id + "|" + offerId + "|" + type + "|" + buyerAddress + "|"
                + sellerAddress + "|" + amountText() + "|" + priceText() + "|"
                + currency + "|" + paymentMethod + "|" + paymentDetails + "|"
                + escrowAddress + "|" + timestamp;
    }

    public boolean verifySignature() {
        if (id.trim().isEmpty() || initiatorAddress().trim().isEmpty()
                || signature.trim().isEmpty()) {
            return false;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("validator", initiatorAddress());
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
            object.put("type", "P2P_EXCHANGE_REQUEST");
            object.put("id", id);
            object.put("offerId", offerId);
            object.put("requestType", type);
            object.put("buyerAddress", buyerAddress);
            object.put("sellerAddress", sellerAddress);
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

    public static ExchangeRequest fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new ExchangeRequest(
                object.optString("id", ""),
                object.optString("offerId", ""),
                object.optString("requestType", object.optString("type", "")),
                object.optString("buyerAddress", ""),
                object.optString("sellerAddress", ""),
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
