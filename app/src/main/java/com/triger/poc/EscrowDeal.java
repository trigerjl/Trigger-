package com.triger.poc;

import org.json.JSONObject;

/** Локальная запись эскроу-сделки между покупателем и продавцом. */
public class EscrowDeal {
    private String id;
    private String itemId;
    private String itemTitle;
    private String buyerAddress;
    private String sellerAddress;
    private String sellerNickname;
    private String escrowAddress;
    private String escrowPrivateKey;
    private String amountText;
    private String escrowAmountText;
    private String status;
    private long createdAt;
    private long deadline;
    private String releasedTxId;
    private String refundedTxId;
    private long updatedAt;

    public EscrowDeal(String id, String itemId, String itemTitle,
                      String buyerAddress, String sellerAddress, String sellerNickname,
                      String escrowAddress, String escrowPrivateKey,
                      String amountText, String escrowAmountText, String status,
                      long createdAt, long deadline, String releasedTxId,
                      String refundedTxId, long updatedAt) {
        this.id = value(id);
        this.itemId = value(itemId);
        this.itemTitle = value(itemTitle);
        this.buyerAddress = value(buyerAddress);
        this.sellerAddress = value(sellerAddress);
        this.sellerNickname = value(sellerNickname);
        this.escrowAddress = value(escrowAddress);
        this.escrowPrivateKey = value(escrowPrivateKey);
        this.amountText = value(amountText);
        this.escrowAmountText = value(escrowAmountText);
        this.status = value(status);
        this.createdAt = createdAt;
        this.deadline = deadline;
        this.releasedTxId = value(releasedTxId);
        this.refundedTxId = value(refundedTxId);
        this.updatedAt = updatedAt;
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }

    public String getId() {
        return id;
    }

    public String getItemId() {
        return itemId;
    }

    public String getItemTitle() {
        return itemTitle;
    }

    public String getBuyerAddress() {
        return buyerAddress;
    }

    public String getSellerAddress() {
        return sellerAddress;
    }

    public String getSellerNickname() {
        return sellerNickname;
    }

    public String getEscrowAddress() {
        return escrowAddress;
    }

    public String getEscrowPrivateKey() {
        return escrowPrivateKey;
    }

    public String getAmountText() {
        return amountText;
    }

    public String getEscrowAmountText() {
        return escrowAmountText;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = value(status);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getDeadline() {
        return deadline;
    }

    public void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    public String getReleasedTxId() {
        return releasedTxId;
    }

    public void setReleasedTxId(String releasedTxId) {
        this.releasedTxId = value(releasedTxId);
    }

    public String getRefundedTxId() {
        return refundedTxId;
    }

    public void setRefundedTxId(String refundedTxId) {
        this.refundedTxId = value(refundedTxId);
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("itemId", itemId);
            object.put("itemTitle", itemTitle);
            object.put("buyerAddress", buyerAddress);
            object.put("sellerAddress", sellerAddress);
            object.put("sellerNickname", sellerNickname);
            object.put("escrowAddress", escrowAddress);
            object.put("escrowPrivateKey", escrowPrivateKey);
            object.put("amountText", amountText);
            object.put("escrowAmountText", escrowAmountText);
            object.put("status", status);
            object.put("createdAt", createdAt);
            object.put("deadline", deadline);
            object.put("releasedTxId", releasedTxId);
            object.put("refundedTxId", refundedTxId);
            object.put("updatedAt", updatedAt);
        } catch (Throwable ignored) {
        }
        return object;
    }

    public static EscrowDeal fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new EscrowDeal(
                object.optString("id", ""),
                object.optString("itemId", ""),
                object.optString("itemTitle", ""),
                object.optString("buyerAddress", ""),
                object.optString("sellerAddress", ""),
                object.optString("sellerNickname", ""),
                object.optString("escrowAddress", ""),
                object.optString("escrowPrivateKey", ""),
                object.optString("amountText", ""),
                object.optString("escrowAmountText", ""),
                object.optString("status", ""),
                object.optLong("createdAt", 0L),
                object.optLong("deadline", 0L),
                object.optString("releasedTxId", ""),
                object.optString("refundedTxId", ""),
                object.optLong("updatedAt", 0L));
    }
}
