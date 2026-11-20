package com.triger.poc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Эскроу-сделки маркета: генерация escrow-адреса, удержание TRG,
 * подтверждение получения и автоматический возврат через 7 дней
 * (для P2P-ордеров обменника — 24 часа).
 */
public final class EscrowManager {
    private static final String TAG = "EscrowManager";
    private static final String PREFS_NAME = "triger_escrow";
    private static final String DEAL_PREFIX = "deal_";
    private static final String BALANCE_PREFIX = "balance_";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ESCROW = "ESCROW";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public static final String ACTION_DEALS_CHANGED = "com.triger.poc.action.ESCROW_DEALS_CHANGED";
    public static final String ACTION_PROCESS_EXPIRY = "com.triger.poc.action.PROCESS_ESCROW_EXPIRY";

    public static final long HOLD_PERIOD_MS = 7L * 24L * 60L * 60L * 1000L;
    public static final long EXCHANGE_HOLD_PERIOD_MS = 24L * 60L * 60L * 1000L;
    private static final long RETRY_MS = 15L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static final Object ACTION_LOCK = new Object();

    public interface Listener {
        void onDealsChanged();
    }

    private static final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();

    private EscrowManager() {
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Создаёт сделку с уникальным escrow-адресом и сохраняет её в статусе PENDING. */
    public static EscrowDeal createDeal(Context context, MarketItem item) {
        if (context == null || item == null) {
            return null;
        }
        CryptoUtils.setupBouncyCastle();
        String buyer = TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(context));
        String seller = TransferManager.normalizeAddress(item.getSellerAddress());
        if (!TransferManager.isValidAddress(buyer)
                || !TransferManager.isValidAddress(seller)
                || buyer.equals(seller)) {
            return null;
        }
        for (EscrowDeal existing : getDeals(context)) {
            boolean active = STATUS_PENDING.equals(existing.getStatus())
                    || STATUS_ESCROW.equals(existing.getStatus());
            if (active
                    && existing.getItemId().equals(item.getId())
                    && existing.getBuyerAddress().equals(buyer)) {
                return existing;
            }
        }
        try {
            ECKeyPair keyPair = Keys.createEcKeyPair(new SecureRandom());
            String escrowAddress = TransferManager.normalizeAddress(
                    "0x" + Keys.getAddress(keyPair));
            String privateKey = Numeric.toHexStringNoPrefixZeroPadded(
                    keyPair.getPrivateKey(), 64);
            BigDecimal price = BigDecimal.valueOf(item.getPrice())
                    .setScale(8, RoundingMode.HALF_UP);
            BigDecimal escrowAmount = price.add(TransferManager.calculateFee(price));
            long now = System.currentTimeMillis();
            EscrowDeal deal = new EscrowDeal(
                    UUID.randomUUID().toString(),
                    item.getId(),
                    item.getTitle(),
                    buyer,
                    seller,
                    item.getSellerNickname(),
                    escrowAddress,
                    privateKey,
                    price.toPlainString(),
                    escrowAmount.toPlainString(),
                    STATUS_PENDING,
                    now,
                    now + HOLD_PERIOD_MS,
                    "",
                    "",
                    now);
            saveDeal(context, deal);
            ensureTrackedBalance(context, escrowAddress);
            notifyDealsChanged(context);
            return deal;
        } catch (Throwable t) {
            Log.w(TAG, "failed to create escrow deal", t);
            return null;
        }
    }

    /** Создаёт эскроу-сделку по уже замороженным средствам P2P-ордера. */
    public static EscrowDeal createExchangeDeal(Context context, String orderId,
                                                String buyer, String seller,
                                                String escrowAddress, String escrowPrivateKey,
                                                String amountText, String escrowAmountText,
                                                String title) {
        if (context == null || orderId == null || orderId.trim().isEmpty()) {
            return null;
        }
        String cleanBuyer = TransferManager.normalizeAddress(buyer);
        String cleanSeller = TransferManager.normalizeAddress(seller);
        String cleanEscrow = TransferManager.normalizeAddress(escrowAddress);
        if (!TransferManager.isValidAddress(cleanBuyer)
                || !TransferManager.isValidAddress(cleanSeller)
                || !TransferManager.isValidAddress(cleanEscrow)) {
            return null;
        }
        long now = System.currentTimeMillis();
        String safeAmount = amountText == null ? "0" : amountText;
        String safeEscrowAmount = escrowAmountText == null ? safeAmount : escrowAmountText;
        EscrowDeal deal = new EscrowDeal(
                UUID.randomUUID().toString(),
                "p2p_order:" + orderId,
                title == null ? "" : title,
                cleanBuyer,
                cleanSeller,
                "",
                cleanEscrow,
                escrowPrivateKey == null ? "" : escrowPrivateKey,
                safeAmount,
                safeEscrowAmount,
                STATUS_ESCROW,
                now,
                now + EXCHANGE_HOLD_PERIOD_MS,
                "",
                "",
                now);
        saveDeal(context, deal);
        ensureTrackedBalance(context, cleanEscrow);
        notifyDealsChanged(context);
        scheduleExpiry(context);
        return deal;
    }

    /**
     * Распределяет замороженные TRG по решению арбитров.
     * releaseToBuyer = true — возврат покупателю, иначе — продавцу.
     */
    public static TransferManager.Result resolveDispute(Context context, String dealId,
                                                        boolean releaseToBuyer) {
        synchronized (ACTION_LOCK) {
            EscrowDeal deal = getDeal(context, dealId);
            if (deal == null) {
                return TransferManager.Result.error(
                        context.getString(R.string.market_escrow_error_missing));
            }
            if (!STATUS_ESCROW.equals(deal.getStatus())) {
                return TransferManager.Result.error(
                        context.getString(R.string.market_escrow_error_status));
            }
            ECKeyPair keyPair = keyPairFromHex(deal.getEscrowPrivateKey());
            if (keyPair == null) {
                return TransferManager.Result.error(
                        context.getString(R.string.error_signing_key_unavailable));
            }
            String recipient = releaseToBuyer
                    ? deal.getBuyerAddress() : deal.getSellerAddress();
            TransferManager.Result result = TransferManager.sendTransferFromEscrow(
                    context, deal.getEscrowAddress(), keyPair,
                    recipient, deal.getAmountText());
            if (!result.success) {
                return result;
            }
            long now = System.currentTimeMillis();
            if (releaseToBuyer) {
                deal.setStatus(STATUS_REFUNDED);
                deal.setRefundedTxId(deal.getEscrowAddress());
            } else {
                deal.setStatus(STATUS_COMPLETED);
                deal.setReleasedTxId(deal.getEscrowAddress());
            }
            deal.setUpdatedAt(now);
            saveDeal(context, deal);
            notifyDealsChanged(context);
            scheduleExpiry(context);
            return TransferManager.Result.success(context.getString(releaseToBuyer
                    ? R.string.dispute_resolved_buyer
                    : R.string.dispute_resolved_seller));
        }
    }

    /** Освобождает TRG из P2P-эскроу покупателю после подтверждения оплаты. */
    public static TransferManager.Result releaseExchangeToBuyer(Context context, String dealId) {
        synchronized (ACTION_LOCK) {
            EscrowDeal deal = getDeal(context, dealId);
            if (deal == null) {
                return TransferManager.Result.error(
                        context.getString(R.string.market_escrow_error_missing));
            }
            if (!STATUS_ESCROW.equals(deal.getStatus())) {
                return TransferManager.Result.error(
                        context.getString(R.string.market_escrow_error_status));
            }
            ECKeyPair keyPair = keyPairFromHex(deal.getEscrowPrivateKey());
            if (keyPair == null) {
                return TransferManager.Result.error(
                        context.getString(R.string.error_signing_key_unavailable));
            }
            TransferManager.Result result = TransferManager.sendTransferFromEscrow(
                    context, deal.getEscrowAddress(), keyPair,
                    deal.getBuyerAddress(), deal.getAmountText());
            if (!result.success) {
                return result;
            }
            long now = System.currentTimeMillis();
            deal.setStatus(STATUS_COMPLETED);
            deal.setReleasedTxId(deal.getEscrowAddress());
            deal.setUpdatedAt(now);
            saveDeal(context, deal);
            KeyManager.refreshLocalBalance(context);
            notifyDealsChanged(context);
            scheduleExpiry(context);
            return TransferManager.Result.success(
                    context.getString(R.string.exchange_confirm_done));
        }
    }

    /** Отправляет цену товара (плюс комиссия эскроу) с кошелька покупателя в эскроу. */
    public static TransferManager.Result payIntoEscrow(Context context, String dealId) {
        synchronized (ACTION_LOCK) {
            return payIntoEscrowLocked(context, dealId);
        }
    }

    private static TransferManager.Result payIntoEscrowLocked(Context context, String dealId) {
        EscrowDeal deal = getDeal(context, dealId);
        if (deal == null) {
            return TransferManager.Result.error(
                    context.getString(R.string.market_escrow_error_missing));
        }
        if (!STATUS_PENDING.equals(deal.getStatus())) {
            return TransferManager.Result.error(
                    context.getString(R.string.market_escrow_error_status));
        }
        TransferManager.Result result = TransferManager.sendTransfer(
                context, deal.getEscrowAddress(), deal.getEscrowAmountText());
        if (result.success) {
            long now = System.currentTimeMillis();
            deal.setStatus(STATUS_ESCROW);
            deal.setDeadline(now + HOLD_PERIOD_MS);
            deal.setUpdatedAt(now);
            saveDeal(context, deal);
            notifyDealsChanged(context);
            scheduleExpiry(context);
            return TransferManager.Result.success(
                    context.getString(R.string.market_escrow_paid));
        }
        return result;
    }

    /** Покупатель подтверждает получение: TRG уходят с эскроу на адрес продавца. */
    public static TransferManager.Result confirmReceipt(Context context, String dealId) {
        synchronized (ACTION_LOCK) {
            return confirmReceiptLocked(context, dealId);
        }
    }

    private static TransferManager.Result confirmReceiptLocked(Context context, String dealId) {
        EscrowDeal deal = getDeal(context, dealId);
        if (deal == null) {
            return TransferManager.Result.error(
                    context.getString(R.string.market_escrow_error_missing));
        }
        if (!STATUS_ESCROW.equals(deal.getStatus())) {
            return TransferManager.Result.error(
                    context.getString(R.string.market_escrow_error_status));
        }
        ECKeyPair keyPair = keyPairFromHex(deal.getEscrowPrivateKey());
        if (keyPair == null) {
            return TransferManager.Result.error(
                    context.getString(R.string.error_signing_key_unavailable));
        }
        TransferManager.Result result = TransferManager.sendTransferFromEscrow(
                context, deal.getEscrowAddress(), keyPair,
                deal.getSellerAddress(), deal.getAmountText());
        if (result.success) {
            long now = System.currentTimeMillis();
            deal.setStatus(STATUS_COMPLETED);
            deal.setReleasedTxId(deal.getEscrowAddress());
            deal.setUpdatedAt(now);
            saveDeal(context, deal);
            new ReviewManager(context).scheduleAutoReview(deal);
            KeyManager.refreshLocalBalance(context);
            notifyDealsChanged(context);
            scheduleExpiry(context);
            return TransferManager.Result.success(
                    context.getString(R.string.market_escrow_released));
        }
        return result;
    }

    /**
     * Проверяет просроченные сделки и возвращает TRG покупателю.
     * Вызывается по таймеру (AlarmManager), при открытии приложения и вручную.
     */
    public static void processDueDeals(Context context) {
        if (context == null) {
            return;
        }
        List<String> refundedP2pDeals = new ArrayList<>();
        // Сначала закрываем споры, которые висят дольше 7 дней без решения арбитров.
        try {
            new DisputeManager(context).processExpiredDisputes();
        } catch (Throwable t) {
            Log.w(TAG, "dispute timeout processing failed", t);
        }
        synchronized (ACTION_LOCK) {
            new ReviewManager(context).processDueReviews();
            long now = System.currentTimeMillis();
            for (EscrowDeal deal : getDeals(context)) {
                if (!STATUS_ESCROW.equals(deal.getStatus())) {
                    continue;
                }
                long deadline = effectiveDeadline(context, deal);
                if (deadline <= 0L || now < deadline) {
                    continue;
                }
                // Пока идёт спор, средства остаются замороженными.
                if (DisputeManager.hasActiveDispute(context, deal.getId())) {
                    continue;
                }
                // Перечитываем статус под блокировкой: покупатель мог только что подтвердить сделку.
                EscrowDeal fresh = getDeal(context, deal.getId());
                if (fresh == null || !STATUS_ESCROW.equals(fresh.getStatus())) {
                    continue;
                }
                if (refundDeal(context, fresh)
                        && fresh.getItemId().startsWith("p2p_order:")) {
                    refundedP2pDeals.add(fresh.getId());
                }
            }
            scheduleExpiry(context);
        }
        if (!refundedP2pDeals.isEmpty()) {
            P2PExchangeManager exchangeManager = new P2PExchangeManager(context);
            for (String dealId : refundedP2pDeals) {
                exchangeManager.handleLocalDealRefunded(dealId);
            }
        }
    }

    public static void processDueDealsAsync(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                processDueDeals(app);
            } catch (Throwable t) {
                Log.w(TAG, "escrow expiry check failed", t);
            }
        }, "EscrowExpiry").start();
    }

    /**
     * Для P2P-ордеров срок возврата считается от момента отметки FIAT PAID:
     * если продавец не подтвердил оплату в течение 24 часов, TRG возвращаются покупателю.
     */
    private static long effectiveDeadline(Context context, EscrowDeal deal) {
        long deadline = deal.getDeadline();
        if (deal.getItemId() == null || !deal.getItemId().startsWith("p2p_order:")) {
            return deadline;
        }
        long paidDeadline = 0L;
        try {
            for (P2PExchangeManager.Order order :
                    new P2PExchangeManager(context).getOrdersForDeal(deal.getId())) {
                if (order.isPaid() && order.getPaidAt() > 0L) {
                    paidDeadline = Math.max(paidDeadline,
                            order.getPaidAt() + EXCHANGE_HOLD_PERIOD_MS);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to read p2p order deadline", t);
        }
        return paidDeadline > 0L ? paidDeadline : deadline;
    }

    private static boolean refundDeal(Context context, EscrowDeal deal) {
        ECKeyPair keyPair = keyPairFromHex(deal.getEscrowPrivateKey());
        if (keyPair == null) {
            Log.w(TAG, "refund impossible: no escrow key for " + deal.getId());
            return false;
        }
        String recipient = deal.getBuyerAddress();
        TransferManager.Result result = TransferManager.sendTransferFromEscrow(
                context, deal.getEscrowAddress(), keyPair,
                recipient, deal.getAmountText());
        if (result.success) {
            long now = System.currentTimeMillis();
            deal.setStatus(STATUS_REFUNDED);
            deal.setRefundedTxId(deal.getEscrowAddress());
            deal.setUpdatedAt(now);
            saveDeal(context, deal);
            notifyDealsChanged(context);
            Log.i(TAG, "escrow deal refunded: " + deal.getId());
            return true;
        }
        Log.w(TAG, "escrow refund failed: " + deal.getId() + " " + result.message);
        return false;
    }

    /** Планирует ближайшую проверку через AlarmManager (в т.ч. при закрытом приложении). */
    public static void scheduleExpiry(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (EscrowDeal deal : getDeals(app)) {
            if (!STATUS_ESCROW.equals(deal.getStatus())) {
                continue;
            }
            long deadline = deal.getDeadline();
            if (deadline <= now) {
                deadline = now + RETRY_MS;
            }
            if (deadline < next) {
                next = deadline;
            }
        }
        long reviewDue = new ReviewManager(app).getNextPendingReviewDueAt();
        if (reviewDue > 0L) {
            if (reviewDue <= now) {
                reviewDue = now + RETRY_MS;
            }
            if (reviewDue < next) {
                next = reviewDue;
            }
        }
        AlarmManager alarmManager =
                (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(app, EscrowReceiver.class)
                .setAction(ACTION_PROCESS_EXPIRY);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(app, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (next == Long.MAX_VALUE) {
            alarmManager.cancel(pendingIntent);
            return;
        }
        long triggerAt = Math.max(next, now + 1000L);
        try {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } catch (Throwable t) {
            Log.w(TAG, "failed to schedule escrow expiry", t);
        }
    }

    /** Обновляет локально отслеживаемые балансы escrow-адресов по подтверждённому блоку. */
    public static void applyBlockTransactions(Context context,
                                              List<JSONObject> transactions) {
        if (context == null || transactions == null || transactions.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            Set<String> tracked = new HashSet<>();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().startsWith(BALANCE_PREFIX)) {
                    tracked.add(entry.getKey().substring(BALANCE_PREFIX.length()));
                }
            }
            if (tracked.isEmpty()) {
                return;
            }
            Map<String, BigDecimal> deltas = new HashMap<>();
            for (JSONObject tx : transactions) {
                try {
                    String from = TransferManager.normalizeAddress(
                            tx.optString("from", ""));
                    String to = TransferManager.normalizeAddress(
                            tx.optString("to", ""));
                    BigDecimal amount = new BigDecimal(tx.optString("amount", "0"));
                    BigDecimal fee = new BigDecimal(tx.optString("fee", "0"));
                    if (tracked.contains(to)) {
                        deltas.put(to, deltas.getOrDefault(to, BigDecimal.ZERO).add(amount));
                    }
                    if (tracked.contains(from)) {
                        deltas.put(from, deltas.getOrDefault(from, BigDecimal.ZERO)
                                .subtract(amount.add(fee)));
                    }
                } catch (Throwable ignored) {
                }
            }
            if (deltas.isEmpty()) {
                return;
            }
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, BigDecimal> entry : deltas.entrySet()) {
                BigDecimal current = parseDecimal(
                        prefs.getString(BALANCE_PREFIX + entry.getKey(), "0"));
                BigDecimal updated = current.add(entry.getValue());
                if (updated.signum() < 0) {
                    updated = BigDecimal.ZERO;
                }
                editor.putString(BALANCE_PREFIX + entry.getKey(),
                        updated.toPlainString());
            }
            editor.apply();
        }
    }

    public static double getTrackedBalance(Context context, String address) {
        if (context == null) {
            return 0.0;
        }
        String key = BALANCE_PREFIX + TransferManager.normalizeAddress(address);
        return parseDecimal(prefs(context).getString(key, "0")).doubleValue();
    }

    /** Начинает отслеживать баланс произвольного escrow-адреса (P2P-обменник). */
    public static void trackAddress(Context context, String address) {
        ensureTrackedBalance(context, address);
    }

    private static void ensureTrackedBalance(Context context, String address) {
        String key = BALANCE_PREFIX + TransferManager.normalizeAddress(address);
        SharedPreferences prefs = prefs(context);
        if (!prefs.contains(key)) {
            prefs.edit().putString(key, "0").apply();
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (Throwable t) {
            return BigDecimal.ZERO;
        }
    }

    public static EscrowDeal getDeal(Context context, String dealId) {
        if (context == null || dealId == null) {
            return null;
        }
        String raw = prefs(context).getString(DEAL_PREFIX + dealId, null);
        if (raw == null) {
            return null;
        }
        try {
            return EscrowDeal.fromJson(new JSONObject(raw));
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<EscrowDeal> getDeals(Context context) {
        List<EscrowDeal> result = new ArrayList<>();
        if (context == null) {
            return result;
        }
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (!entry.getKey().startsWith(DEAL_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                EscrowDeal deal = EscrowDeal.fromJson(new JSONObject((String) value));
                if (deal != null && !deal.getId().isEmpty()) {
                    result.add(deal);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparingLong(EscrowDeal::getCreatedAt).reversed());
        return result;
    }

    public static boolean hasCompletedDeal(Context context, String itemId,
                                           String buyerAddress, String sellerAddress) {
        String item = itemId == null ? "" : itemId.trim();
        String buyer = TransferManager.normalizeAddress(buyerAddress);
        String seller = TransferManager.normalizeAddress(sellerAddress);
        if (item.isEmpty() || buyer.isEmpty() || seller.isEmpty()) {
            return false;
        }
        for (EscrowDeal deal : getDeals(context)) {
            if (STATUS_COMPLETED.equals(deal.getStatus())
                    && item.equals(deal.getItemId())
                    && buyer.equals(TransferManager.normalizeAddress(
                    deal.getBuyerAddress()))
                    && seller.equals(TransferManager.normalizeAddress(
                    deal.getSellerAddress()))) {
                return true;
            }
        }
        return false;
    }

    private static void saveDeal(Context context, EscrowDeal deal) {
        if (context == null || deal == null || deal.getId().isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            prefs(context).edit()
                    .putString(DEAL_PREFIX + deal.getId(), deal.toJson().toString())
                    .apply();
        }
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
            Log.w(TAG, "invalid escrow private key", t);
            return null;
        }
    }

    private static void notifyDealsChanged(Context context) {
        for (Listener listener : listeners) {
            try {
                listener.onDealsChanged();
            } catch (Throwable t) {
                Log.w(TAG, "escrow listener failed", t);
            }
        }
        try {
            LocalBroadcastManager.getInstance(context.getApplicationContext())
                    .sendBroadcast(new Intent(ACTION_DEALS_CHANGED));
        } catch (Throwable ignored) {
        }
    }
}
