package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Локальное хранилище и сетевая синхронизация объявлений маркета. */
public class MarketManager {
    private static final String TAG = "MarketManager";
    private static final String PREFS_NAME = "triger_market";
    private static final String ITEM_PREFIX = "item_";

    public interface Listener {
        void onItemsChanged();
    }

    /** Плата за создание объявления: 1 TRG сжигается из оборота. */
    public static final double LISTING_FEE_TRG = 1.0;

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Context context;
    private final SharedPreferences prefs;

    public MarketManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Создаёт и подписывает объявление от имени локального пользователя. */
    public MarketItem createItem(String title, String description, double price) {
        return createItem(title, description, price, true);
    }

    /** Создаёт объявление; escrowEnabled включает безопасную сделку. */
    public MarketItem createItem(String title, String description, double price,
                                 boolean escrowEnabled) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanDescription = description == null ? "" : description.trim();
        if (cleanTitle.isEmpty() || !isValidPrice(price)) {
            return null;
        }
        try {
            ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
            if (keyPair == null) {
                return null;
            }
            String address = KeyManager.getOrCreateAddress(context);
            String nickname = IrohNode.getInstance().getLocalUsername();
            long timestamp = System.currentTimeMillis();
            String id = UUID.randomUUID().toString();
            MarketItem unsigned = new MarketItem(id, address, nickname, cleanTitle,
                    cleanDescription, price, timestamp, "", escrowEnabled);
            String signature = MarketItem.sign(unsigned.signingPayload(), keyPair);
            return new MarketItem(id, address, nickname, cleanTitle, cleanDescription,
                    price, timestamp, signature, escrowEnabled);
        } catch (Throwable t) {
            Log.w(TAG, "failed to create market item", t);
            return null;
        }
    }

    public boolean addItem(MarketItem item) {
        if (!isValid(item)) {
            return false;
        }
        String key = ITEM_PREFIX + item.getId();
        if (prefs.contains(key)) {
            return false;
        }
        prefs.edit().putString(key, item.toJson().toString()).apply();
        notifyItemsChanged();
        return true;
    }

    /** Хватает ли TRG (с учётом ожидающих транзакций) на плату за объявление. */
    public boolean hasListingFee() {
        String address = KeyManager.getOrCreateAddress(context);
        return TransferManager.getSpendableBalance(context, address)
                >= LISTING_FEE_TRG - 1e-9;
    }

    /** Сжигает плату за объявление; ошибка содержит причину (например, нет TRG). */
    public TransferManager.Result chargeListingFee() {
        if (!hasListingFee()) {
            return TransferManager.Result.error(
                    context.getString(R.string.market_insufficient_trg));
        }
        return TransferManager.burnTokens(context,
                BigDecimal.valueOf(LISTING_FEE_TRG).toPlainString(), "");
    }

    public List<MarketItem> getItems() {
        List<MarketItem> result = new ArrayList<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(ITEM_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                MarketItem item = MarketItem.fromJson(new JSONObject((String) value));
                if (isValid(item)) {
                    result.add(item);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparingLong(MarketItem::getTimestamp).reversed());
        return result;
    }

    public boolean removeItem(String id) {
        String key = ITEM_PREFIX + (id == null ? "" : id.trim());
        String raw = prefs.getString(key, null);
        if (raw == null) {
            return false;
        }
        try {
            MarketItem item = MarketItem.fromJson(new JSONObject(raw));
            String local = TransferManager.normalizeAddress(
                    KeyManager.getOrCreateAddress(context));
            if (item == null || !local.equals(
                    TransferManager.normalizeAddress(item.getSellerAddress()))) {
                return false;
            }
            prefs.edit().remove(key).apply();
            notifyItemsChanged();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void broadcastItem(MarketItem item) {
        if (item == null || !isValid(item)) {
            return;
        }
        IrohNode.getInstance().broadcastMarketItem(item.toJson());
    }

    /** Принимает объявление от пира, проверяет подпись и сохраняет его. */
    public boolean handleIncomingItem(JSONObject json) {
        if (json == null) {
            return false;
        }
        MarketItem item = MarketItem.fromJson(json);
        if (!isValid(item)) {
            Log.w(TAG, "rejected invalid market item");
            return false;
        }
        return addItem(item);
    }

    private boolean isValid(MarketItem item) {
        if (item == null) {
            return false;
        }
        String id = item.getId().trim();
        String title = item.getTitle().trim();
        String seller = TransferManager.normalizeAddress(item.getSellerAddress());
        if (id.isEmpty() || id.length() > 128
                || title.isEmpty() || title.length() > 200
                || item.getDescription().length() > 2000
                || item.getTimestamp() <= 0L
                || !isValidPrice(item.getPrice())
                || !TransferManager.isValidAddress(seller)
                || item.getSellerNickname().length() > 100) {
            return false;
        }
        return item.verifySignature();
    }

    private boolean isValidPrice(double price) {
        return !Double.isNaN(price) && !Double.isInfinite(price) && price > 0.0
                && BigDecimal.valueOf(price).scale() <= 8;
    }

    private static void notifyItemsChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onItemsChanged();
            } catch (Throwable t) {
                Log.w(TAG, "market listener failed", t);
            }
        }
    }
}
