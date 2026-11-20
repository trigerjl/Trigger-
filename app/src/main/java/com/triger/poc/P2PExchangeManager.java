package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P2P-обменник TRG на фиат. Объявления и заявки подписываются ключом
 * инициатора, а TRG продавца удерживаются в эскроу до подтверждения фиата.
 */
public class P2PExchangeManager {
    private static final String TAG = "P2PExchangeManager";
    private static final String PREFS_NAME = "triger_p2p_exchange";
    private static final String OFFER_PREFIX = "offer_";
    private static final String OFFER_SECRET_PREFIX = "osecret_";
    private static final String OFFER_STATE_PREFIX = "ostate_";
    private static final String REQUEST_PREFIX = "request_";
    private static final String REQUEST_SECRET_PREFIX = "rsecret_";
    private static final String REQUEST_STATE_PREFIX = "rstate_";
    private static final String ORDER_PREFIX = "order_";
    private static final String ORDER_SECRET_PREFIX = "osecret_order_";

    public static final String OFFER_STATE_NONE = "NONE";
    public static final String OFFER_STATE_LOCKED = "LOCKED";
    public static final String OFFER_STATE_RELEASED = "RELEASED";
    public static final String OFFER_STATE_REFUNDED = "REFUNDED";

    public static final String ACTION_EXCHANGE_CHANGED =
            "com.triger.poc.action.EXCHANGE_CHANGED";

    /** Валюты, поддерживаемые обменником. RUB — валюта по умолчанию. */
    public static final List<String> SUPPORTED_CURRENCIES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                    ExchangeOffer.CURRENCY_RUB,
                    ExchangeOffer.CURRENCY_USD,
                    ExchangeOffer.CURRENCY_EUR,
                    ExchangeOffer.CURRENCY_KZT,
                    ExchangeOffer.CURRENCY_UAH,
                    ExchangeOffer.CURRENCY_BYN,
                    ExchangeOffer.CURRENCY_TRY,
                    ExchangeOffer.CURRENCY_CNY,
                    ExchangeOffer.CURRENCY_INR));

    /** Символы поддерживаемых валют. */
    public static String currencySymbol(String currency) {
        String value = currency == null ? "" : currency.trim()
                .toUpperCase(java.util.Locale.US);
        switch (value) {
            case ExchangeOffer.CURRENCY_USD:
                return "$";
            case ExchangeOffer.CURRENCY_EUR:
                return "€";
            case ExchangeOffer.CURRENCY_KZT:
                return "₸";
            case ExchangeOffer.CURRENCY_UAH:
                return "₴";
            case ExchangeOffer.CURRENCY_BYN:
                return "Br";
            case ExchangeOffer.CURRENCY_TRY:
                return "₺";
            case ExchangeOffer.CURRENCY_CNY:
                return "¥";
            case ExchangeOffer.CURRENCY_INR:
                return "₹";
            default:
                return "₽";
        }
    }

    private static final Object ACTION_LOCK = new Object();
    private static final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();

    public interface Listener {
        void onExchangeChanged();
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final ExchangeOffer offer;
        public final ExchangeRequest request;
        public final Order order;

        private Result(boolean success, String message, ExchangeOffer offer,
                       ExchangeRequest request, Order order) {
            this.success = success;
            this.message = message;
            this.offer = offer;
            this.request = request;
            this.order = order;
        }

        static Result offer(Context context, ExchangeOffer offer) {
            return new Result(true, context.getString(R.string.exchange_offer_created),
                    offer, null, null);
        }

        static Result request(Context context, ExchangeRequest request) {
            return new Result(true, context.getString(R.string.exchange_request_sent),
                    null, request, null);
        }

        static Result success(Context context, int messageRes) {
            return new Result(true, context.getString(messageRes), null, null, null);
        }

        static Result order(Context context, Order order, boolean matched) {
            int messageRes = matched
                    ? R.string.order_executed : R.string.exchange_order_placed;
            return new Result(true, context.getString(messageRes), null, null, order);
        }

        static Result error(String message) {
            return new Result(false, message, null, null, null);
        }
    }

    public static final class Order {
        public static final String TYPE_BUY = "BUY";
        public static final String TYPE_SELL = "SELL";
        public static final String STATUS_OPEN = "OPEN";
        public static final String STATUS_MATCHED = "MATCHED";
        public static final String STATUS_PAID = "PAID";
        public static final String STATUS_SETTLED = "SETTLED";
        public static final String STATUS_FILLED = "FILLED";
        public static final String STATUS_CANCELLED = "CANCELLED";
        public static final String STATUS_REFUNDED = "REFUNDED";

        public static final String PAYMENT_CARD = "CARD";
        public static final String PAYMENT_SBP = "SBP";
        public static final String PAYMENT_PHONE = "PHONE";
        public static final String PAYMENT_PAYPAL = "PAYPAL";
        public static final String PAYMENT_WISE = "WISE";
        public static final String PAYMENT_OTHER = "OTHER";

        /** Способы оплаты, поддерживаемые обменником. */
        public static final List<String> SUPPORTED_PAYMENT_METHODS =
                java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                        PAYMENT_CARD,
                        PAYMENT_SBP,
                        PAYMENT_PHONE,
                        PAYMENT_PAYPAL,
                        PAYMENT_WISE,
                        PAYMENT_OTHER));

        private final String id;
        private String creatorAddress;
        private String creatorNickname;
        private String type;
        private double amountTRG;
        private double remainingAmountTRG;
        private double priceFiat;
        private String currency;
        private String paymentMethod;
        private String paymentDetails;
        private long timestamp;
        private String status;
        private String escrowAddress;
        private String escrowAmountText;
        private String dealId;
        private String dealAmountText;
        private String dealPriceText;
        private String counterpartyAddress;
        private String signature;
        private long paidAt;
        private boolean isMarketMaker;

        public Order(String id, String creatorAddress, String creatorNickname, String type,
                     double amountTRG, double remainingAmountTRG, double priceFiat,
                     String currency, String paymentMethod, String paymentDetails,
                     String escrowAddress, long timestamp, String status) {
            this.id = trim(id);
            this.creatorAddress = trim(creatorAddress);
            this.creatorNickname = trim(creatorNickname);
            this.type = trim(type).toUpperCase(java.util.Locale.US);
            this.amountTRG = amountTRG;
            this.remainingAmountTRG = remainingAmountTRG < 0.0 ? amountTRG : remainingAmountTRG;
            this.priceFiat = priceFiat;
            this.currency = trim(currency).toUpperCase(java.util.Locale.US);
            this.paymentMethod = trim(paymentMethod);
            this.paymentDetails = trim(paymentDetails);
            this.timestamp = timestamp;
            this.status = trim(status).isEmpty() ? STATUS_OPEN : trim(status);
            this.escrowAddress = trim(escrowAddress);
            this.escrowAmountText = "";
            this.dealId = "";
            this.dealAmountText = "";
            this.dealPriceText = "";
            this.counterpartyAddress = "";
            this.signature = "";
            this.paidAt = 0L;
            this.isMarketMaker = false;
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

        public boolean isBuy() {
            return TYPE_BUY.equals(type);
        }

        public boolean isSell() {
            return TYPE_SELL.equals(type);
        }

        public double getAmountTRG() {
            return amountTRG;
        }

        public double getRemainingTRG() {
            return remainingAmountTRG;
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

        public long getTimestamp() {
            return timestamp;
        }

        public String getStatus() {
            return status;
        }

        public String getEscrowAddress() {
            return escrowAddress;
        }

        public String getEscrowAmountText() {
            return escrowAmountText;
        }

        public String getDealId() {
            return dealId;
        }

        public String getDealAmountText() {
            return dealAmountText.isEmpty() ? amountText() : dealAmountText;
        }

        public String getDealPriceText() {
            return dealPriceText.isEmpty() ? priceText() : dealPriceText;
        }

        public String getCounterpartyAddress() {
            return counterpartyAddress;
        }

        public String getSignature() {
            return signature;
        }

        public long getPaidAt() {
            return paidAt;
        }

        public boolean isMarketMaker() {
            return isMarketMaker;
        }

        public void setMarketMaker(boolean value) {
            this.isMarketMaker = value;
        }

        public long getPaidAtOrTimestamp() {
            return paidAt > 0L ? paidAt : timestamp;
        }

        void setPaidAt(long value) {
            this.paidAt = value > 0L ? value : System.currentTimeMillis();
        }

        public boolean isOpen() {
            return STATUS_OPEN.equals(status) && remainingAmountTRG > 0.00000001;
        }

        public boolean isMatched() {
            return STATUS_MATCHED.equals(status);
        }

        public boolean isPaid() {
            return STATUS_PAID.equals(status);
        }

        public boolean isSettled() {
            return STATUS_SETTLED.equals(status);
        }

        public boolean isCancelled() {
            return STATUS_CANCELLED.equals(status);
        }

        public boolean isRefunded() {
            return STATUS_REFUNDED.equals(status);
        }

        public double unitPrice() {
            return amountTRG <= 0.0 ? 0.0 : priceFiat / amountTRG;
        }

        public String amountText() {
            return BigDecimal.valueOf(amountTRG).stripTrailingZeros().toPlainString();
        }

        public String remainingText() {
            return BigDecimal.valueOf(remainingAmountTRG)
                    .stripTrailingZeros().toPlainString();
        }

        public String priceText() {
            return BigDecimal.valueOf(priceFiat).stripTrailingZeros().toPlainString();
        }

        void setStatus(String value) {
            this.status = trim(value);
        }

        void setEscrow(String address, String amountText) {
            this.escrowAddress = trim(address);
            this.escrowAmountText = trim(amountText);
        }

        void setDeal(String id, String amountText, String priceText,
                     String counterpartyAddress) {
            this.dealId = trim(id);
            this.dealAmountText = trim(amountText);
            this.dealPriceText = trim(priceText);
            this.counterpartyAddress = trim(counterpartyAddress);
        }

        void clearDeal() {
            this.dealId = "";
            this.dealAmountText = "";
            this.dealPriceText = "";
            this.counterpartyAddress = "";
        }

        void setSignature(String value) {
            this.signature = trim(value);
        }

        void fill(double value) {
            if (value <= 0.0 || remainingAmountTRG <= 0.0) {
                return;
            }
            double matched = Math.min(value, remainingAmountTRG);
            BigDecimal remaining = BigDecimal.valueOf(remainingAmountTRG - matched)
                    .max(BigDecimal.ZERO)
                    .setScale(8, RoundingMode.HALF_UP);
            this.remainingAmountTRG = remaining.doubleValue();
            if (this.remainingAmountTRG <= 0.00000001) {
                this.remainingAmountTRG = 0.0;
                this.status = STATUS_MATCHED;
            }
        }

        public String signingPayload() {
            return id + "|" + creatorAddress + "|" + creatorNickname + "|" + type
                    + "|" + amountText() + "|" + priceText() + "|" + currency
                    + "|" + paymentMethod + "|" + paymentDetails + "|"
                    + escrowAddress + "|" + timestamp;
        }

        public void sign(ECKeyPair keyPair) {
            try {
                this.signature = BlockStorage.signBlockHash(signingPayload(), keyPair);
            } catch (Throwable t) {
                this.signature = "";
            }
        }

        public boolean verifySignature() {
            if (id.isEmpty() || creatorAddress.isEmpty() || signature.isEmpty()) {
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

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("type", "P2P_EXCHANGE_ORDER");
                object.put("id", id);
                object.put("creatorAddress", creatorAddress);
                object.put("creatorNickname", creatorNickname);
                object.put("orderType", type);
                object.put("amountTRG", amountTRG);
                object.put("remainingTRG", remainingAmountTRG);
                object.put("priceFiat", priceFiat);
                object.put("currency", currency);
                object.put("paymentMethod", paymentMethod);
                object.put("paymentDetails", paymentDetails);
                object.put("timestamp", timestamp);
                object.put("status", status);
                object.put("escrowAddress", escrowAddress);
                object.put("escrowAmountText", escrowAmountText);
                object.put("dealId", dealId);
                object.put("dealAmountText", dealAmountText);
                object.put("dealPriceText", dealPriceText);
                object.put("counterpartyAddress", counterpartyAddress);
                object.put("signature", signature);
                object.put("paidAt", paidAt);
                object.put("isMarketMaker", isMarketMaker);
            } catch (Throwable ignored) {
            }
            return object;
        }

        static Order fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            double amount = object.optDouble("amountTRG", 0.0);
            Order order = new Order(
                    object.optString("id", ""),
                    object.optString("creatorAddress", ""),
                    object.optString("creatorNickname", ""),
                    object.optString("orderType", object.optString("type", "")),
                    amount,
                    object.optDouble("remainingTRG", amount),
                    object.optDouble("priceFiat", 0.0),
                    object.optString("currency", ""),
                    object.optString("paymentMethod", ""),
                    object.optString("paymentDetails", ""),
                    object.optString("escrowAddress", ""),
                    object.optLong("timestamp", 0L),
                    object.optString("status", STATUS_OPEN));
            order.setEscrow(object.optString("escrowAddress", ""),
                    object.optString("escrowAmountText", ""));
            order.setDeal(object.optString("dealId", ""),
                    object.optString("dealAmountText", ""),
                    object.optString("dealPriceText", ""),
                    object.optString("counterpartyAddress", ""));
            order.setSignature(object.optString("signature", ""));
            order.setMarketMaker(object.optBoolean("isMarketMaker", false));
            long paidAt = object.optLong("paidAt", 0L);
            if (paidAt > 0L) {
                order.paidAt = paidAt;
            }
            return order;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final PriceOracle priceOracle;

    public P2PExchangeManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.priceOracle = new PriceOracle(this.context);
        MarketMakerAPI.initialize(this.context);
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Создаёт и подписывает объявление. Для SELL TRG сразу уходят в эскроу. */
    public Result createOffer(String type, double amountTRG, double priceFiat,
                              String currency, String paymentMethod,
                              String paymentDetails) {
        synchronized (ACTION_LOCK) {
            String cleanType = normalizeType(type);
            String cleanCurrency = normalizeCurrency(currency);
            String cleanMethod = trim(paymentMethod);
            String cleanDetails = trim(paymentDetails);
            if (cleanType.isEmpty() || cleanCurrency.isEmpty()
                    || !isValidAmount(amountTRG) || !isValidAmount(priceFiat)
                    || cleanMethod.isEmpty() || cleanMethod.length() > 100
                    || cleanDetails.length() > 300) {
                return Result.error(context.getString(R.string.exchange_invalid));
            }
            try {
                ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
                if (keyPair == null) {
                    return Result.error(context.getString(
                            R.string.error_signing_key_unavailable));
                }
                String creator = TransferManager.normalizeAddress(
                        KeyManager.getOrCreateAddress(context));
                String nickname = IrohNode.getInstance().getLocalUsername();
                String escrowAddress = "";
                String escrowPrivateKey = "";

                if (ExchangeOffer.TYPE_SELL.equals(cleanType)) {
                    ECKeyPair escrowKey = Keys.createEcKeyPair(new SecureRandom());
                    escrowAddress = TransferManager.normalizeAddress(
                            "0x" + Keys.getAddress(escrowKey));
                    escrowPrivateKey = Numeric.toHexStringNoPrefixZeroPadded(
                            escrowKey.getPrivateKey(), 64);
                    EscrowManager.trackAddress(context, escrowAddress);
                    BigDecimal amount = BigDecimal.valueOf(amountTRG)
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal escrowAmount = amount.add(
                            TransferManager.calculateFee(amount));
                    TransferManager.Result locked = TransferManager.sendTransfer(
                            context, escrowAddress, escrowAmount.toPlainString());
                    if (!locked.success) {
                        return Result.error(locked.message);
                    }
                }

                String id = UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                ExchangeOffer unsigned = new ExchangeOffer(id, creator, nickname,
                        cleanType, amountTRG, priceFiat, cleanCurrency,
                        cleanMethod, cleanDetails, escrowAddress, timestamp, "");
                String signature = ExchangeOffer.sign(unsigned.signingPayload(), keyPair);
                ExchangeOffer offer = new ExchangeOffer(id, creator, nickname,
                        cleanType, amountTRG, priceFiat, cleanCurrency,
                        cleanMethod, cleanDetails, escrowAddress, timestamp, signature);

                saveOffer(offer);
                setOfferState(id, ExchangeOffer.TYPE_SELL.equals(cleanType)
                        ? OFFER_STATE_LOCKED : OFFER_STATE_NONE);
                if (!escrowPrivateKey.isEmpty()) {
                    prefs.edit().putString(OFFER_SECRET_PREFIX + id, escrowPrivateKey).apply();
                }
                broadcastOffer(offer);
                notifyChanged();
                return Result.offer(context, offer);
            } catch (Throwable t) {
                Log.w(TAG, "failed to create exchange offer", t);
                return Result.error(context.getString(R.string.exchange_invalid));
            }
        }
    }

    public List<ExchangeOffer> getOffers() {
        List<ExchangeOffer> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(OFFER_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                ExchangeOffer offer = ExchangeOffer.fromJson(
                        new JSONObject((String) value));
                if (isValidOffer(offer)) {
                    offer.setState(getOfferState(offer.getId()));
                    result.add(offer);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparingLong(ExchangeOffer::getTimestamp).reversed());
        return result;
    }

    public ExchangeOffer getOffer(String offerId) {
        if (offerId == null) {
            return null;
        }
        String raw = prefs.getString(OFFER_PREFIX + offerId, null);
        if (raw == null) {
            return null;
        }
        try {
            ExchangeOffer offer = ExchangeOffer.fromJson(new JSONObject(raw));
            if (offer != null) {
                offer.setState(getOfferState(offerId));
            }
            return offer;
        } catch (Throwable t) {
            return null;
        }
    }

    public Result removeOffer(String offerId) {
        synchronized (ACTION_LOCK) {
            ExchangeOffer offer = getOffer(offerId);
            if (offer == null) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (!local.equals(TransferManager.normalizeAddress(
                    offer.getCreatorAddress()))) {
                return Result.error(context.getString(R.string.exchange_error_forbidden));
            }
            if (offer.isSell() && OFFER_STATE_LOCKED.equals(offer.getState())) {
                if (hasActiveRequests(offerId)) {
                    return Result.error(context.getString(
                            R.string.exchange_error_active_deal));
                }
                String secret = prefs.getString(OFFER_SECRET_PREFIX + offerId, "");
                ECKeyPair escrowKey = keyPairFromHex(secret);
                if (escrowKey == null || !TransferManager.isValidAddress(
                        offer.getEscrowAddress())) {
                    return Result.error(context.getString(
                            R.string.error_signing_key_unavailable));
                }
                TransferManager.Result refund = TransferManager.sendTransferFromEscrow(
                        context, offer.getEscrowAddress(), escrowKey,
                        offer.getCreatorAddress(), offer.amountText());
                if (!refund.success) {
                    return Result.error(refund.message);
                }
                setOfferState(offerId, OFFER_STATE_REFUNDED);
            }
            prefs.edit()
                    .remove(OFFER_PREFIX + offerId)
                    .remove(OFFER_SECRET_PREFIX + offerId)
                    .remove(OFFER_STATE_PREFIX + offerId)
                    .apply();
            IrohNode.getInstance().broadcastExchangeOfferRemoved(offerId);
            notifyChanged();
            return Result.success(context, R.string.exchange_offer_removed);
        }
    }

    /** Создаёт ордер. Для SELL TRG сразу блокируются в эскроу до исполнения или отмены. */
    public Result placeOrder(Order order) {
        synchronized (ACTION_LOCK) {
            if (order == null) {
                return Result.error(context.getString(R.string.exchange_invalid));
            }
            String type = normalizeType(order.getType());
            String currency = normalizeCurrency(order.getCurrency());
            String method = trim(order.getPaymentMethod());
            String details = trim(order.getPaymentDetails());
            String nickname = trim(order.getCreatorNickname());
            if (type.isEmpty() || currency.isEmpty()
                    || !isValidAmount(order.getAmountTRG())
                    || !isValidAmount(order.getPriceFiat())
                    || method.length() > 100 || details.length() > 300
                    || nickname.length() > 100
                    || (Order.TYPE_SELL.equals(type) && details.isEmpty())) {
                return Result.error(context.getString(R.string.exchange_invalid));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (!TransferManager.isValidAddress(local)) {
                return Result.error(context.getString(
                        R.string.error_signing_key_unavailable));
            }
            ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
            if (keyPair == null) {
                return Result.error(context.getString(
                        R.string.error_signing_key_unavailable));
            }
            if (nickname.isEmpty()) {
                nickname = trim(IrohNode.getInstance().getLocalUsername());
            }
            String id = order.getId().trim().isEmpty()
                    ? UUID.randomUUID().toString() : order.getId().trim();
            long timestamp = order.getTimestamp() <= 0L
                    ? System.currentTimeMillis() : order.getTimestamp();
            String escrowAddress = "";
            String escrowAmountText = "";
            if (Order.TYPE_SELL.equals(type)) {
                try {
                    ECKeyPair escrowKey = Keys.createEcKeyPair(new SecureRandom());
                    escrowAddress = TransferManager.normalizeAddress(
                            "0x" + Keys.getAddress(escrowKey));
                    BigDecimal amount = BigDecimal.valueOf(order.getAmountTRG())
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal escrowAmount = amount.add(
                            TransferManager.calculateFee(amount));
                    EscrowManager.trackAddress(context, escrowAddress);
                    TransferManager.Result locked = TransferManager.sendTransfer(
                            context, escrowAddress, escrowAmount.toPlainString());
                    if (!locked.success) {
                        return Result.error(locked.message);
                    }
                    escrowAmountText = escrowAmount.toPlainString();
                    prefs.edit().putString(ORDER_SECRET_PREFIX + id,
                            Numeric.toHexStringNoPrefixZeroPadded(
                                    escrowKey.getPrivateKey(), 64)).apply();
                } catch (Throwable t) {
                    Log.w(TAG, "failed to lock order escrow", t);
                    return Result.error(context.getString(R.string.exchange_invalid));
                }
            }
            Order placed = new Order(id, local, nickname, type,
                    order.getAmountTRG(), order.getAmountTRG(),
                    order.getPriceFiat(), currency, method, details,
                    escrowAddress, timestamp, Order.STATUS_OPEN);
            placed.setEscrow(escrowAddress, escrowAmountText);
            placed.setMarketMaker(order.isMarketMaker());
            placed.sign(keyPair);
            if (!isValidOrder(placed)) {
                return Result.error(context.getString(R.string.exchange_invalid));
            }
            saveOrder(placed);
            IrohNode.getInstance().broadcastOrder(placed.toJson());
            boolean matched = placed.isMarketMaker() && matchOrdersLocked() > 0;
            notifyChanged();
            Order result = getOrder(id);
            return Result.order(context, result == null ? placed : result, matched);
        }
    }

    public List<Order> getOpenOrders() {
        List<Order> result = new ArrayList<>();
        for (Order order : getOrders()) {
            if (order.isOpen()) {
                result.add(order);
            }
        }
        return result;
    }

    public List<Order> getOrders() {
        List<Order> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(ORDER_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                Order order = Order.fromJson(new JSONObject((String) value));
                if (isValidOrder(order)) {
                    result.add(order);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparingLong(Order::getTimestamp).reversed());
        return result;
    }

    public Order getOrder(String orderId) {
        if (orderId == null) {
            return null;
        }
        String raw = prefs.getString(ORDER_PREFIX + orderId, null);
        if (raw == null) {
            return null;
        }
        try {
            Order order = Order.fromJson(new JSONObject(raw));
            return isValidOrder(order) ? order : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public Order getOrderByDealId(String dealId, boolean sell) {
        if (dealId == null || dealId.trim().isEmpty()) {
            return null;
        }
        for (Order order : getOrders()) {
            if (sell != order.isSell()) {
                continue;
            }
            if (dealId.equals(order.getDealId())) {
                return order;
            }
        }
        return null;
    }

    public List<Order> getOrdersForDeal(String dealId) {
        List<Order> result = new ArrayList<>();
        if (dealId == null || dealId.trim().isEmpty()) {
            return result;
        }
        for (Order order : getOrders()) {
            if (dealId.equals(order.getDealId())) {
                result.add(order);
            }
        }
        return result;
    }

    public int matchOrders() {
        synchronized (ACTION_LOCK) {
            return matchOrdersLocked();
        }
    }

    public Result cancelOrder(String orderId) {
        synchronized (ACTION_LOCK) {
            Order order = getOrder(orderId);
            if (order == null) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (!local.equals(TransferManager.normalizeAddress(
                    order.getCreatorAddress()))) {
                return Result.error(context.getString(R.string.exchange_error_forbidden));
            }
            if (!order.isOpen()) {
                return Result.error(context.getString(R.string.exchange_error_status));
            }
            if (!order.getDealId().isEmpty()) {
                EscrowDeal deal = EscrowManager.getDeal(context, order.getDealId());
                if (deal != null && (EscrowManager.STATUS_ESCROW.equals(deal.getStatus())
                        || EscrowManager.STATUS_PENDING.equals(deal.getStatus()))) {
                    return Result.error(context.getString(
                            R.string.exchange_error_active_deal));
                }
            }
            if (order.isSell() && TransferManager.isValidAddress(order.getEscrowAddress())) {
                String secret = prefs.getString(ORDER_SECRET_PREFIX + order.getId(), "");
                ECKeyPair escrowKey = keyPairFromHex(secret);
                if (escrowKey == null) {
                    return Result.error(context.getString(
                            R.string.error_signing_key_unavailable));
                }
                BigDecimal remaining = BigDecimal.valueOf(order.getRemainingTRG())
                        .setScale(8, RoundingMode.HALF_UP);
                if (remaining.signum() <= 0) {
                    remaining = BigDecimal.valueOf(order.getAmountTRG())
                            .setScale(8, RoundingMode.HALF_UP);
                }
                String refundAmount = remaining.add(
                        TransferManager.calculateFee(remaining))
                        .stripTrailingZeros().toPlainString();
                TransferManager.Result refund = TransferManager.sendTransferFromEscrow(
                        context, order.getEscrowAddress(), escrowKey,
                        order.getCreatorAddress(), refundAmount);
                if (!refund.success) {
                    return Result.error(refund.message);
                }
            }
            order.setStatus(Order.STATUS_CANCELLED);
            saveOrder(order);
            IrohNode.getInstance().broadcastOrder(order.toJson());
            notifyChanged();
            return Result.success(context, R.string.exchange_order_cancelled);
        }
    }

    public Result markOrderPaid(String orderId) {
        synchronized (ACTION_LOCK) {
            Order order = getOrder(orderId);
            if (order == null || order.getDealId().isEmpty()) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            String buyer = order.isBuy() ? order.getCreatorAddress()
                    : order.getCounterpartyAddress();
            if (!local.equals(TransferManager.normalizeAddress(buyer))) {
                return Result.error(context.getString(R.string.exchange_error_forbidden));
            }
            updateDealStatus(order.getDealId(), Order.STATUS_PAID);
            IrohNode.getInstance().broadcastExchangeOrderStatus(
                    orderStatusJson(order.getId(), order.getDealId(), "FIAT_PAID"));
            notifyChanged();
            return Result.success(context, R.string.exchange_fiat_marked_paid);
        }
    }

    public Result confirmOrderPayment(String orderId) {
        synchronized (ACTION_LOCK) {
            Order order = getOrder(orderId);
            if (order == null || order.getDealId().isEmpty()) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            String seller = order.isSell() ? order.getCreatorAddress()
                    : order.getCounterpartyAddress();
            if (!local.equals(TransferManager.normalizeAddress(seller))) {
                return Result.error(context.getString(R.string.exchange_error_forbidden));
            }
            EscrowDeal deal = EscrowManager.getDeal(context, order.getDealId());
            if (deal == null) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            if (!order.isPaid() && !order.isMatched()) {
                return Result.error(context.getString(R.string.exchange_error_status));
            }
            TransferManager.Result released =
                    EscrowManager.releaseExchangeToBuyer(context, deal.getId());
            if (!released.success) {
                return Result.error(released.message);
            }
            IrohNode.getInstance().broadcastExchangeOrderStatus(
                    orderStatusJson(order.getId(), order.getDealId(), "SETTLED"));
            finalizeDealOrders(order.getDealId(), Order.STATUS_SETTLED);
            KeyManager.refreshLocalBalance(context);
            notifyChanged();
            return Result.success(context, R.string.exchange_confirm_done);
        }
    }

    public boolean handleIncomingOrder(JSONObject json) {
        if (json == null) {
            return false;
        }
        Order incoming = Order.fromJson(json);
        if (!isValidOrder(incoming)) {
            Log.w(TAG, "rejected invalid exchange order");
            return false;
        }
        Order existing = getOrder(incoming.getId());
        if (existing != null && isReopenedPartial(existing, incoming)) {
            saveOrder(incoming);
            matchOrdersLocked();
            notifyChanged();
            return true;
        }
        if (existing != null) {
            if (isTerminalStatus(existing.getStatus())
                    && !existing.getStatus().equals(incoming.getStatus())) {
                return false;
            }
            if (statusRank(incoming.getStatus()) < statusRank(existing.getStatus())) {
                return false;
            }
            if (existing.getStatus().equals(incoming.getStatus())
                    && existing.getDealId().equals(incoming.getDealId())
                    && Math.abs(existing.getRemainingTRG() - incoming.getRemainingTRG())
                    < 0.00000001) {
                return false;
            }
            if (existing.isOpen() && incoming.isOpen()
                    && incoming.getDealId().isEmpty()) {
                return false;
            }
        }
        saveOrder(incoming);
        matchOrdersLocked();
        notifyChanged();
        return true;
    }

    public void handleIncomingOrderStatus(JSONObject json) {
        handleIncomingOrderStatus(json, "");
    }

    public void handleIncomingOrderStatus(JSONObject json, String senderPeerId) {
        if (json == null) {
            return;
        }
        String sender = senderPeerId == null ? "" : senderPeerId.trim();
        boolean senderConnected = false;
        try {
            IrohNode node = IrohNode.getInstance();
            String selfEndpoint = node.getSelfEndpointId();
            if (!sender.isEmpty()) {
                if (sender.equalsIgnoreCase(selfEndpoint)) {
                    Log.w(TAG, "Ignoring own order status (endpoint match)");
                    return;
                }
                senderConnected = node.getConnectedPeers().contains(sender);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to validate order status sender", t);
        }
        String senderDeviceId = json.optString("deviceId", "").trim();
        String localDeviceId = DeviceIdentity.getOrCreateDeviceId(context).trim();
        if (!senderDeviceId.isEmpty() && senderDeviceId.equalsIgnoreCase(localDeviceId)) {
            Log.w(TAG, "Ignoring own order status (device_id match)");
            return;
        }
        String orderId = json.optString("orderId", "");
        String dealId = json.optString("dealId", "");
        String status = json.optString("status", "");
        if (dealId.isEmpty() && !orderId.isEmpty()) {
            Order order = getOrder(orderId);
            if (order != null) {
                dealId = order.getDealId();
            }
        }
        if (dealId.isEmpty()) {
            return;
        }
        List<Order> affected = getOrdersForDeal(dealId);
        if (affected.isEmpty()) {
            return;
        }
        if ("FIAT_PAID".equals(status)) {
            updateDealStatus(dealId, Order.STATUS_PAID);
            Order sell = getOrderByDealId(dealId, true);
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (sell != null && local.equals(TransferManager.normalizeAddress(
                    sell.getCreatorAddress()))) {
                if (!sender.isEmpty() && senderConnected) {
                    NotificationHelper.showMessageNotification(context, sender,
                            context.getString(R.string.exchange_title),
                            context.getString(R.string.exchange_notify_paid));
                } else {
                    Log.w(TAG, "Skipping paid notification: sender is not an active peer");
                }
            }
        } else if ("SETTLED".equals(status)) {
            finalizeDealOrders(dealId, Order.STATUS_SETTLED);
        } else if ("REFUNDED".equals(status)) {
            finalizeDealOrders(dealId, Order.STATUS_REFUNDED);
        } else {
            return;
        }
        notifyChanged();
    }

    public void handleLocalDealRefunded(String dealId) {
        if (dealId == null || dealId.trim().isEmpty()) {
            return;
        }
        synchronized (ACTION_LOCK) {
            IrohNode.getInstance().broadcastExchangeOrderStatus(
                    orderStatusJson("", dealId, "REFUNDED"));
            finalizeDealOrders(dealId, Order.STATUS_REFUNDED);
            notifyChanged();
        }
    }

    /** Завершает ордера сделки после решения арбитров. */
    public void handleDisputeResolved(String dealId, boolean releaseToBuyer) {
        if (dealId == null || dealId.trim().isEmpty()) {
            return;
        }
        synchronized (ACTION_LOCK) {
            IrohNode.getInstance().broadcastExchangeOrderStatus(
                    orderStatusJson("", dealId, "SETTLED"));
            finalizeDealOrders(dealId, Order.STATUS_SETTLED);
            notifyChanged();
        }
    }

    public void broadcastOrder(Order order) {
        if (order != null && isValidOrder(order)) {
            IrohNode.getInstance().broadcastOrder(order.toJson());
        }
    }

    public void broadcastOffer(ExchangeOffer offer) {
        if (offer == null || !isValidOffer(offer)) {
            return;
        }
        IrohNode.getInstance().broadcastExchangeOffer(offer.toJson());
    }

    /** Принимает объявление от пира и сохраняет его после проверки подписи. */
    public boolean handleIncomingOffer(JSONObject json) {
        if (json == null) {
            return false;
        }
        ExchangeOffer offer = ExchangeOffer.fromJson(json);
        if (!isValidOffer(offer)) {
            Log.w(TAG, "rejected invalid exchange offer");
            return false;
        }
        if (prefs.contains(OFFER_PREFIX + offer.getId())) {
            return false;
        }
        saveOffer(offer);
        if (offer.isSell()) {
            setOfferState(offer.getId(), OFFER_STATE_LOCKED);
        }
        notifyChanged();
        return true;
    }

    public void handleIncomingOfferRemoved(String offerId) {
        if (offerId == null || offerId.trim().isEmpty()) {
            return;
        }
        prefs.edit()
                .remove(OFFER_PREFIX + offerId)
                .remove(OFFER_STATE_PREFIX + offerId)
                .apply();
        notifyChanged();
    }

    /**
     * Создаёт заявку на обмен.
     * Для SELL-объявления покупатель сообщает, что фиат отправлен.
     * Для BUY-объявления продавец TRG сразу замораживает свои TRG в эскроу.
     */
    public Result sendExchangeRequest(ExchangeOffer offer, String paymentMethod,
                                      String paymentDetails) {
        synchronized (ACTION_LOCK) {
            if (offer == null || !isValidOffer(offer)) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            String creator = TransferManager.normalizeAddress(
                    offer.getCreatorAddress());
            if (local.equals(creator)) {
                return Result.error(context.getString(R.string.exchange_error_own_offer));
            }
            try {
                ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
                if (keyPair == null) {
                    return Result.error(context.getString(
                            R.string.error_signing_key_unavailable));
                }
                String buyer;
                String seller;
                String escrowAddress;
                String method;
                String details;
                String requestSecret = "";

                if (offer.isSell()) {
                    buyer = local;
                    seller = creator;
                    escrowAddress = TransferManager.normalizeAddress(
                            offer.getEscrowAddress());
                    method = offer.getPaymentMethod();
                    details = offer.getPaymentDetails();
                } else {
                    buyer = creator;
                    seller = local;
                    method = trim(paymentMethod);
                    details = trim(paymentDetails);
                    if (method.isEmpty() || method.length() > 100
                            || details.length() > 300) {
                        return Result.error(context.getString(
                                R.string.exchange_invalid));
                    }
                    ECKeyPair escrowKey = Keys.createEcKeyPair(new SecureRandom());
                    escrowAddress = TransferManager.normalizeAddress(
                            "0x" + Keys.getAddress(escrowKey));
                    requestSecret = Numeric.toHexStringNoPrefixZeroPadded(
                            escrowKey.getPrivateKey(), 64);
                    EscrowManager.trackAddress(context, escrowAddress);
                    BigDecimal amount = BigDecimal.valueOf(offer.getAmountTRG())
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal escrowAmount = amount.add(
                            TransferManager.calculateFee(amount));
                    TransferManager.Result locked = TransferManager.sendTransfer(
                            context, escrowAddress, escrowAmount.toPlainString());
                    if (!locked.success) {
                        return Result.error(locked.message);
                    }
                }
                if (!TransferManager.isValidAddress(escrowAddress)) {
                    return Result.error(context.getString(R.string.exchange_error_missing));
                }

                String id = UUID.randomUUID().toString();
                long timestamp = System.currentTimeMillis();
                ExchangeRequest unsigned = new ExchangeRequest(id, offer.getId(),
                        offer.getType(), buyer, seller, offer.getAmountTRG(),
                        offer.getPriceFiat(), offer.getCurrency(), method, details,
                        escrowAddress, timestamp, "");
                String signature = ExchangeRequest.sign(unsigned.signingPayload(), keyPair);
                ExchangeRequest request = new ExchangeRequest(id, offer.getId(),
                        offer.getType(), buyer, seller, offer.getAmountTRG(),
                        offer.getPriceFiat(), offer.getCurrency(), method, details,
                        escrowAddress, timestamp, signature);
                saveRequest(request);
                setRequestStatus(id, ExchangeRequest.STATUS_PENDING);
                if (!requestSecret.isEmpty()) {
                    prefs.edit().putString(
                            REQUEST_SECRET_PREFIX + id, requestSecret).apply();
                }
                IrohNode.getInstance().broadcastExchangeRequest(request.toJson());
                notifyChanged();
                return Result.request(context, request);
            } catch (Throwable t) {
                Log.w(TAG, "failed to send exchange request", t);
                return Result.error(context.getString(R.string.exchange_invalid));
            }
        }
    }

    public List<ExchangeRequest> getRequests() {
        List<ExchangeRequest> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(REQUEST_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                ExchangeRequest request = ExchangeRequest.fromJson(
                        new JSONObject((String) value));
                if (isValidRequest(request)) {
                    request.setStatus(getRequestStatus(request.getId()));
                    result.add(request);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparingLong(ExchangeRequest::getTimestamp).reversed());
        return result;
    }

    public ExchangeRequest getRequest(String requestId) {
        if (requestId == null) {
            return null;
        }
        String raw = prefs.getString(REQUEST_PREFIX + requestId, null);
        if (raw == null) {
            return null;
        }
        try {
            ExchangeRequest request = ExchangeRequest.fromJson(new JSONObject(raw));
            if (request != null) {
                request.setStatus(getRequestStatus(requestId));
            }
            return request;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean handleIncomingRequest(JSONObject json) {
        if (json == null) {
            return false;
        }
        ExchangeRequest request = ExchangeRequest.fromJson(json);
        if (!isValidRequest(request)) {
            Log.w(TAG, "rejected invalid exchange request");
            return false;
        }
        String local = TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(context));
        boolean involved = local.equals(TransferManager.normalizeAddress(
                request.getBuyerAddress()))
                || local.equals(TransferManager.normalizeAddress(
                request.getSellerAddress()));
        if (!involved) {
            return false;
        }
        if (prefs.contains(REQUEST_PREFIX + request.getId())) {
            return false;
        }
        saveRequest(request);
        setRequestStatus(request.getId(), ExchangeRequest.STATUS_PENDING);
        notifyChanged();
        return true;
    }

    /** Покупатель TRG отмечает, что перевёл фиат продавцу (BUY-объявление). */
    public Result markFiatPaid(String requestId) {
        synchronized (ACTION_LOCK) {
            ExchangeRequest request = getRequest(requestId);
            if (request == null) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (!local.equals(TransferManager.normalizeAddress(
                    request.getBuyerAddress()))) {
                return Result.error(context.getString(R.string.exchange_error_forbidden));
            }
            setRequestStatus(requestId, ExchangeRequest.STATUS_PAID);
            IrohNode.getInstance().broadcastExchangeStatus(
                    statusJson(requestId, ExchangeRequest.STATUS_PAID));
            notifyChanged();
            return Result.success(context, R.string.exchange_fiat_marked_paid);
        }
    }

    /**
     * Продавец TRG подтверждает получение фиата и освобождает эскроу
     * в пользу покупателя TRG.
     */
    public Result confirmFiatReceived(String requestId) {
        synchronized (ACTION_LOCK) {
            ExchangeRequest request = getRequest(requestId);
            if (request == null) {
                return Result.error(context.getString(R.string.exchange_error_missing));
            }
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (!local.equals(TransferManager.normalizeAddress(
                    request.getSellerAddress()))) {
                return Result.error(context.getString(R.string.exchange_error_forbidden));
            }
            if (ExchangeRequest.STATUS_CONFIRMED.equals(request.getStatus())) {
                return Result.error(context.getString(R.string.exchange_error_status));
            }
            String secret;
            String escrowAddress;
            if (request.isSell()) {
                secret = prefs.getString(
                        OFFER_SECRET_PREFIX + request.getOfferId(), "");
                escrowAddress = request.getEscrowAddress();
            } else {
                secret = prefs.getString(
                        REQUEST_SECRET_PREFIX + request.getId(), "");
                escrowAddress = request.getEscrowAddress();
            }
            ECKeyPair escrowKey = keyPairFromHex(secret);
            if (escrowKey == null || !TransferManager.isValidAddress(escrowAddress)) {
                return Result.error(context.getString(
                        R.string.error_signing_key_unavailable));
            }
            TransferManager.Result released = TransferManager.sendTransferFromEscrow(
                    context, escrowAddress, escrowKey,
                    request.getBuyerAddress(), request.amountText());
            if (!released.success) {
                return Result.error(released.message);
            }
            setRequestStatus(requestId, ExchangeRequest.STATUS_CONFIRMED);
            if (request.isSell()) {
                setOfferState(request.getOfferId(), OFFER_STATE_RELEASED);
            }
            IrohNode.getInstance().broadcastExchangeStatus(
                    statusJson(requestId, ExchangeRequest.STATUS_CONFIRMED));
            if (request.isSell()) {
                IrohNode.getInstance().broadcastExchangeOfferRemoved(
                        request.getOfferId());
                prefs.edit()
                        .remove(OFFER_PREFIX + request.getOfferId())
                        .remove(OFFER_SECRET_PREFIX + request.getOfferId())
                        .apply();
            }
            KeyManager.refreshLocalBalance(context);
            notifyChanged();
            return Result.success(context, R.string.exchange_confirm_done);
        }
    }

    public void handleIncomingStatus(JSONObject json) {
        if (json == null) {
            return;
        }
        String senderDeviceId = json.optString("deviceId", "").trim();
        String localDeviceId = DeviceIdentity.getOrCreateDeviceId(context).trim();
        if (!senderDeviceId.isEmpty() && senderDeviceId.equalsIgnoreCase(localDeviceId)) {
            Log.w(TAG, "Ignoring own exchange status (device_id match)");
            return;
        }
        String requestId = json.optString("requestId", "");
        String status = json.optString("status", "");
        if (requestId.isEmpty()
                || !(ExchangeRequest.STATUS_PAID.equals(status)
                || ExchangeRequest.STATUS_CONFIRMED.equals(status)
                || ExchangeRequest.STATUS_CANCELLED.equals(status))) {
            return;
        }
        if (!prefs.contains(REQUEST_PREFIX + requestId)) {
            return;
        }
        String current = getRequestStatus(requestId);
        if (ExchangeRequest.STATUS_CONFIRMED.equals(current)) {
            return;
        }
        setRequestStatus(requestId, status);
        notifyChanged();
    }

    /** Исходящие заявки, которые ещё актуальны и могут быть переотправлены пиру. */
    public List<JSONObject> getPendingRequestPayloads() {
        List<JSONObject> result = new ArrayList<>();
        String local = TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(context));
        for (ExchangeRequest request : getRequests()) {
            if (ExchangeRequest.STATUS_CONFIRMED.equals(request.getStatus())
                    || ExchangeRequest.STATUS_CANCELLED.equals(request.getStatus())) {
                continue;
            }
            if (!local.equals(TransferManager.normalizeAddress(
                    request.initiatorAddress()))) {
                continue;
            }
            result.add(request.toJson());
        }
        return result;
    }

    public void handleIncomingOfferRemoved(JSONObject json) {
        if (json == null) {
            return;
        }
        handleIncomingOfferRemoved(json.optString("offerId", ""));
    }

    private JSONObject statusJson(String requestId, String status) {
        JSONObject object = new JSONObject();
        try {
            object.put("requestId", requestId);
            object.put("status", status);
            object.put("deviceId", DeviceIdentity.getOrCreateDeviceId(context));
            object.put("timestamp", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return object;
    }

    private String getOfferState(String offerId) {
        return prefs.getString(OFFER_STATE_PREFIX + offerId, OFFER_STATE_NONE);
    }

    private void setOfferState(String offerId, String state) {
        prefs.edit().putString(OFFER_STATE_PREFIX + offerId, state).apply();
    }

    private String getRequestStatus(String requestId) {
        return prefs.getString(REQUEST_STATE_PREFIX + requestId,
                ExchangeRequest.STATUS_PENDING);
    }

    private void setRequestStatus(String requestId, String status) {
        prefs.edit().putString(REQUEST_STATE_PREFIX + requestId, status).apply();
    }

    private int matchOrdersLocked() {
        int matches = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            List<Order> sells = new ArrayList<>();
            List<Order> buys = new ArrayList<>();
            for (Order order : getOpenOrders()) {
                if (order.isSell()) {
                    sells.add(order);
                } else if (order.isBuy()) {
                    buys.add(order);
                }
            }
            sells.sort(Comparator.comparingDouble(Order::unitPrice)
                    .thenComparingLong(Order::getTimestamp));
            buys.sort(Comparator.comparingDouble(Order::unitPrice).reversed()
                    .thenComparingLong(Order::getTimestamp));
            for (Order sell : sells) {
                if (!prefs.contains(ORDER_SECRET_PREFIX + sell.getId())) {
                    continue;
                }
                for (Order buy : buys) {
                    if (!sell.getCurrency().equals(buy.getCurrency())) {
                        continue;
                    }
                    if (TransferManager.normalizeAddress(sell.getCreatorAddress())
                            .equals(TransferManager.normalizeAddress(
                                    buy.getCreatorAddress()))) {
                        continue;
                    }
                    if (buy.unitPrice() + 0.00000001 < sell.unitPrice()) {
                        continue;
                    }
                    if (executeMatchLocked(sell, buy)) {
                        matches++;
                        progress = true;
                    }
                    break;
                }
                if (progress) {
                    break;
                }
            }
        }
        return matches;
    }

    private boolean executeMatchLocked(Order sell, Order buy) {
        double amount = Math.min(sell.getRemainingTRG(), buy.getRemainingTRG());
        if (!isValidAmount(amount)) {
            return false;
        }
        String secret = prefs.getString(ORDER_SECRET_PREFIX + sell.getId(), "");
        ECKeyPair escrowKey = keyPairFromHex(secret);
        if (escrowKey == null) {
            return false;
        }
        BigDecimal amountValue = BigDecimal.valueOf(amount)
                .setScale(8, RoundingMode.HALF_UP);
        String amountText = amountValue.stripTrailingZeros().toPlainString();
        String escrowAmountText = amountValue.add(
                TransferManager.calculateFee(amountValue))
                .stripTrailingZeros().toPlainString();
        BigDecimal fiatValue = BigDecimal.valueOf(sell.unitPrice())
                .multiply(amountValue)
                .setScale(8, RoundingMode.HALF_UP);
        String fiatText = fiatValue.stripTrailingZeros().toPlainString();
        String title = "P2P " + amountText + " TRG";
        EscrowDeal deal = EscrowManager.createExchangeDeal(context,
                sell.getId() + ":" + UUID.randomUUID(),
                buy.getCreatorAddress(), sell.getCreatorAddress(),
                sell.getEscrowAddress(), secret, amountText, escrowAmountText, title);
        if (deal == null) {
            return false;
        }
        sell.setDeal(deal.getId(), amountText, fiatText, buy.getCreatorAddress());
        buy.setDeal(deal.getId(), amountText, fiatText, sell.getCreatorAddress());
        sell.fill(amount);
        buy.fill(amount);
        sell.setStatus(Order.STATUS_MATCHED);
        buy.setStatus(Order.STATUS_MATCHED);
        saveOrder(sell);
        saveOrder(buy);
        String tradeId = deal.getId();
        long tradeTime = System.currentTimeMillis();
        priceOracle.recordTrade(tradeId, sell.unitPrice(), amount,
                sell.getCurrency(), tradeTime);
        IrohNode.getInstance().broadcastPriceUpdate(priceOracle.createPriceUpdate(
                tradeId, sell.unitPrice(), amount, sell.getCurrency(), tradeTime));
        PriceOracle.notifyPriceChanged();
        IrohNode.getInstance().broadcastOrder(sell.toJson());
        IrohNode.getInstance().broadcastOrder(buy.toJson());
        notifyChanged();
        return true;
    }

    private void saveOrder(Order order) {
        if (order == null) {
            return;
        }
        prefs.edit().putString(ORDER_PREFIX + order.getId(),
                order.toJson().toString()).apply();
    }

    private boolean isValidOrder(Order order) {
        if (order == null) {
            return false;
        }
        String id = order.getId().trim();
        String creator = TransferManager.normalizeAddress(order.getCreatorAddress());
        String type = normalizeType(order.getType());
        String currency = normalizeCurrency(order.getCurrency());
        String status = trim(order.getStatus());
        if (id.isEmpty() || id.length() > 128
                || type.isEmpty() || currency.isEmpty()
                || !isValidAmount(order.getAmountTRG())
                || !isValidAmount(order.getPriceFiat())
                || order.getRemainingTRG() < -0.00000001
                || order.getRemainingTRG() > order.getAmountTRG() + 0.00000001
                || order.getPaymentMethod().length() > 100
                || order.getPaymentDetails().length() > 300
                || order.getCreatorNickname().length() > 100
                || order.getTimestamp() <= 0L
                || !TransferManager.isValidAddress(creator)
                || !isValidOrderStatus(status)) {
            return false;
        }
        if (order.isSell() && !TransferManager.isValidAddress(
                TransferManager.normalizeAddress(order.getEscrowAddress()))) {
            return false;
        }
        return order.verifySignature();
    }

    private static boolean isValidOrderStatus(String status) {
        return Order.STATUS_OPEN.equals(status)
                || Order.STATUS_MATCHED.equals(status)
                || Order.STATUS_PAID.equals(status)
                || Order.STATUS_SETTLED.equals(status)
                || Order.STATUS_FILLED.equals(status)
                || Order.STATUS_CANCELLED.equals(status)
                || Order.STATUS_REFUNDED.equals(status);
    }

    private static int statusRank(String status) {
        if (Order.STATUS_OPEN.equals(status)) {
            return 0;
        }
        if (Order.STATUS_MATCHED.equals(status) || Order.STATUS_FILLED.equals(status)) {
            return 1;
        }
        if (Order.STATUS_PAID.equals(status)) {
            return 2;
        }
        if (Order.STATUS_SETTLED.equals(status)) {
            return 3;
        }
        if (Order.STATUS_REFUNDED.equals(status)) {
            return 4;
        }
        if (Order.STATUS_CANCELLED.equals(status)) {
            return 5;
        }
        return -1;
    }

    private static boolean isTerminalStatus(String status) {
        return Order.STATUS_SETTLED.equals(status)
                || Order.STATUS_CANCELLED.equals(status)
                || Order.STATUS_REFUNDED.equals(status);
    }

    private static boolean isReopenedPartial(Order existing, Order incoming) {
        if (!incoming.isOpen() || !incoming.getDealId().isEmpty()) {
            return false;
        }
        if (existing.getDealId().isEmpty()) {
            return false;
        }
        if (incoming.getRemainingTRG() <= 0.00000001
                || incoming.getRemainingTRG() > existing.getRemainingTRG() + 0.00000001) {
            return false;
        }
        String status = existing.getStatus();
        return Order.STATUS_MATCHED.equals(status)
                || Order.STATUS_PAID.equals(status)
                || Order.STATUS_SETTLED.equals(status)
                || Order.STATUS_REFUNDED.equals(status);
    }

    private void updateDealStatus(String dealId, String status) {
        for (Order order : getOrdersForDeal(dealId)) {
            order.setStatus(status);
            if (Order.STATUS_PAID.equals(status) && order.getPaidAt() <= 0L) {
                order.setPaidAt(System.currentTimeMillis());
            }
            saveOrder(order);
            IrohNode.getInstance().broadcastOrder(order.toJson());
        }
    }

    private void finalizeDealOrders(String dealId, String status) {
        List<Order> affected = getOrdersForDeal(dealId);
        for (Order order : affected) {
            if (order.getRemainingTRG() > 0.00000001) {
                order.setStatus(Order.STATUS_OPEN);
                order.clearDeal();
            } else {
                order.setStatus(status);
            }
            saveOrder(order);
            IrohNode.getInstance().broadcastOrder(order.toJson());
        }
    }

    private JSONObject orderStatusJson(String orderId, String dealId, String status) {
        JSONObject object = new JSONObject();
        try {
            object.put("type", "P2P_EXCHANGE_ORDER_STATUS");
            object.put("orderId", orderId);
            object.put("dealId", dealId);
            object.put("status", status);
            object.put("deviceId", DeviceIdentity.getOrCreateDeviceId(context));
            object.put("timestamp", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return object;
    }

    private void saveOffer(ExchangeOffer offer) {
        prefs.edit().putString(OFFER_PREFIX + offer.getId(),
                offer.toJson().toString()).apply();
    }

    private void saveRequest(ExchangeRequest request) {
        prefs.edit().putString(REQUEST_PREFIX + request.getId(),
                request.toJson().toString()).apply();
    }

    private boolean hasActiveRequests(String offerId) {
        for (ExchangeRequest request : getRequests()) {
            if (!offerId.equals(request.getOfferId())) {
                continue;
            }
            if (ExchangeRequest.STATUS_PENDING.equals(request.getStatus())
                    || ExchangeRequest.STATUS_PAID.equals(request.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidOffer(ExchangeOffer offer) {
        if (offer == null) {
            return false;
        }
        String id = offer.getId().trim();
        String creator = TransferManager.normalizeAddress(offer.getCreatorAddress());
        String type = normalizeType(offer.getType());
        String currency = normalizeCurrency(offer.getCurrency());
        if (id.isEmpty() || id.length() > 128
                || type.isEmpty() || currency.isEmpty()
                || !isValidAmount(offer.getAmountTRG())
                || !isValidAmount(offer.getPriceFiat())
                || offer.getPaymentMethod().length() > 100
                || offer.getPaymentDetails().length() > 300
                || offer.getCreatorNickname().length() > 100
                || offer.getTimestamp() <= 0L
                || !TransferManager.isValidAddress(creator)) {
            return false;
        }
        if (offer.isSell() && !TransferManager.isValidAddress(
                TransferManager.normalizeAddress(offer.getEscrowAddress()))) {
            return false;
        }
        return offer.verifySignature();
    }

    private boolean isValidRequest(ExchangeRequest request) {
        if (request == null) {
            return false;
        }
        String id = request.getId().trim();
        String type = normalizeType(request.getType());
        String buyer = TransferManager.normalizeAddress(request.getBuyerAddress());
        String seller = TransferManager.normalizeAddress(request.getSellerAddress());
        if (id.isEmpty() || id.length() > 128
                || request.getOfferId().trim().isEmpty()
                || type.isEmpty()
                || !isValidAmount(request.getAmountTRG())
                || !isValidAmount(request.getPriceFiat())
                || !TransferManager.isValidAddress(buyer)
                || !TransferManager.isValidAddress(seller)
                || buyer.equals(seller)
                || !TransferManager.isValidAddress(TransferManager.normalizeAddress(
                request.getEscrowAddress()))
                || request.getPaymentMethod().length() > 100
                || request.getPaymentDetails().length() > 300
                || request.getTimestamp() <= 0L) {
            return false;
        }
        return request.verifySignature();
    }

    private boolean isValidAmount(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0
                && BigDecimal.valueOf(value).scale() <= 8;
    }

    private static String normalizeType(String type) {
        String value = trim(type).toUpperCase(java.util.Locale.US);
        if (ExchangeOffer.TYPE_SELL.equals(value) || ExchangeOffer.TYPE_BUY.equals(value)) {
            return value;
        }
        return "";
    }

    private static String normalizeCurrency(String currency) {
        String value = trim(currency).toUpperCase(java.util.Locale.US);
        if (SUPPORTED_CURRENCIES.contains(value)) {
            return value;
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static ECKeyPair keyPairFromHex(String hex) {
        try {
            String clean = hex == null ? "" : hex.trim();
            if (clean.startsWith("0x") || clean.startsWith("0X")) {
                clean = clean.substring(2);
            }
            if (clean.isEmpty()) {
                return null;
            }
            return ECKeyPair.create(new BigInteger(clean, 16));
        } catch (Throwable t) {
            return null;
        }
    }

    private void notifyChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onExchangeChanged();
            } catch (Throwable t) {
                Log.w(TAG, "exchange listener failed", t);
            }
        }
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context)
                    .sendBroadcast(new android.content.Intent(ACTION_EXCHANGE_CHANGED));
        } catch (Throwable ignored) {
        }
    }
}
