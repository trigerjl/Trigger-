package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class PriceOracle {

    private static final String PREFS_NAME = "triger_price_oracle";
    private static final String KEY_TRADES = "trades";
    private static final long WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();

    public interface Listener {
        void onPriceChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private final Context context;
    private final SharedPreferences prefs;

    public PriceOracle(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void notifyPriceChanged() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onPriceChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    public void recordTrade(double price, double amount) {
        recordTrade(price, amount, ExchangeOffer.CURRENCY_RUB);
    }

    /** Записывает сделку в корзину своей валюты — VWAP считается для каждой валюты отдельно. */
    public void recordTrade(double price, double amount, String currency) {
        recordTrade(UUID.randomUUID().toString(), price, amount, currency,
                System.currentTimeMillis());
    }

    /**
     * Записывает сделку с внешним id и временем (для синхронизации между узлами).
     *
     * @return true, если сделка новая и была добавлена.
     */
    public boolean recordTrade(String tradeId, double price, double amount,
                               String currency, long timestamp) {
        if (!isValid(price) || !isValid(amount)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long tradeTime = timestamp > 0L ? timestamp : now;
        if (now - tradeTime >= WINDOW_MS) {
            return false;
        }
        String cleanCurrency = normalizeCurrency(currency);
        String id = normalizeTradeId(tradeId, tradeTime, price, amount, cleanCurrency);
        synchronized (LOCK) {
            List<Trade> trades = loadTrades(null);
            for (Trade trade : trades) {
                if (trade.id.equals(id)) {
                    return false;
                }
            }
            trades.add(new Trade(id, tradeTime, price, amount, cleanCurrency));
            saveTrades(freshOnly(trades, now));
            return true;
        }
    }

    /** Применяет входящий PRICE_UPDATE и возвращает true, если сделка была новой. */
    public boolean applyPriceUpdate(JSONObject update) {
        if (update == null) {
            return false;
        }
        String id = update.optString("tradeId", "");
        if (id.trim().isEmpty()) {
            id = update.optString("id", "");
        }
        double price = update.optDouble("price", 0.0);
        double amount = update.optDouble("amount", 0.0);
        String currency = update.optString("currency", ExchangeOffer.CURRENCY_RUB);
        long timestamp = update.optLong("timestamp", System.currentTimeMillis());
        return recordTrade(id, price, amount, currency, timestamp);
    }

    /** JSON-пакет PRICE_UPDATE для новой локальной сделки. */
    public JSONObject createPriceUpdate(String tradeId, double price, double amount,
                                        String currency, long timestamp) {
        String cleanCurrency = normalizeCurrency(currency);
        JSONObject object = new JSONObject();
        try {
            object.put("tradeId", normalizeTradeId(tradeId, timestamp, price,
                    amount, cleanCurrency));
            object.put("price", price);
            object.put("amount", amount);
            object.put("currency", cleanCurrency);
            object.put("timestamp", timestamp);
            object.put("deviceId", DeviceIdentity.getOrCreateDeviceId(context));
            Double vwap = getVWAP(cleanCurrency);
            if (vwap != null) {
                object.put("vwap", vwap);
                object.put("vwapCurrency", cleanCurrency);
            }
        } catch (Throwable ignored) {
        }
        return object;
    }

    /** Все сделки за последние 24 часа для первичной синхронизации с новым пиром. */
    public List<JSONObject> getRecentTradeUpdates() {
        long now = System.currentTimeMillis();
        List<JSONObject> updates = new ArrayList<>();
        synchronized (LOCK) {
            for (Trade trade : freshOnly(loadTrades(null), now)) {
                updates.add(trade.toJson());
            }
        }
        return updates;
    }

    /** История цен сделок в указанной валюте, начиная с заданного времени. */
    public List<PricePoint> getPriceHistory(String currency, long sinceMs) {
        long now = System.currentTimeMillis();
        long cutoff = sinceMs > 0L ? sinceMs : now - WINDOW_MS;
        String cleanCurrency = normalizeCurrency(currency);
        Map<String, Double> rates = CurrencyConverter.getCachedRates(context);
        List<PricePoint> history = new ArrayList<>();
        synchronized (LOCK) {
            for (Trade trade : freshOnly(loadTrades(null), now)) {
                if (trade.timestamp < cutoff) {
                    continue;
                }
                Double converted = convertPrice(
                        trade.price, trade.currency, cleanCurrency, rates);
                if (converted == null || !isValid(converted)) {
                    continue;
                }
                history.add(new PricePoint(trade.timestamp, converted));
            }
        }
        history.sort((first, second) -> Long.compare(first.timestamp, second.timestamp));
        return history;
    }

    /** Изменение цены за последние 24 часа в процентах или null, если данных мало. */
    public Double getChangePercent24h(String currency) {
        List<PricePoint> history = getPriceHistory(
                currency, System.currentTimeMillis() - WINDOW_MS);
        if (history.size() < 2) {
            return null;
        }
        double first = history.get(0).price;
        double last = history.get(history.size() - 1).price;
        if (!isValid(first) || !isValid(last)) {
            return null;
        }
        return (last - first) / first * 100.0;
    }

    public void cleanOldTrades() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            saveTrades(freshOnly(loadTrades(null), now));
        }
    }

    public Double getVWAP() {
        return getVWAP(ExchangeOffer.CURRENCY_RUB);
    }

    public Double getVWAP(String currency) {
        long now = System.currentTimeMillis();
        String cleanCurrency = normalizeCurrency(currency);
        Map<String, Double> rates = CurrencyConverter.getCachedRates(context);
        synchronized (LOCK) {
            List<Trade> all = loadTrades(null);
            List<Trade> fresh = freshOnly(all, now);
            if (fresh.size() != all.size()) {
                saveTrades(fresh);
            }
            double totalValue = 0.0;
            double totalVolume = 0.0;
            for (Trade trade : fresh) {
                Double convertedPrice = convertPrice(
                        trade.price, trade.currency, cleanCurrency, rates);
                if (convertedPrice == null) {
                    continue;
                }
                totalValue += convertedPrice * trade.amount;
                totalVolume += trade.amount;
            }
            if (totalVolume > 0.0) {
                return totalValue / totalVolume;
            }
            return null;
        }
    }

    /** Переводит цену сделки из одной валюты в другую по текущим кэшированным курсам. */
    private static Double convertPrice(double price, String fromCurrency,
                                       String toCurrency, Map<String, Double> rates) {
        if (!isValid(price)) {
            return null;
        }
        String from = normalizeCurrency(fromCurrency);
        String to = normalizeCurrency(toCurrency);
        if (from.equals(to)) {
            return price;
        }
        double fromRate = rateFor(from, rates);
        double toRate = rateFor(to, rates);
        if (!isValid(fromRate) || !isValid(toRate)) {
            return null;
        }
        double priceInRub = price / fromRate;
        double converted = priceInRub * toRate;
        return isValid(converted) ? converted : null;
    }

    private static double rateFor(String currency, Map<String, Double> rates) {
        if (ExchangeOffer.CURRENCY_RUB.equals(currency)) {
            return 1.0;
        }
        if (rates == null) {
            return Double.NaN;
        }
        Double rate = rates.get(currency);
        return rate == null ? Double.NaN : rate;
    }

    public Double getPrice() {
        return getPrice(ExchangeOffer.CURRENCY_RUB);
    }

    public Double getPrice(String currency) {
        String cleanCurrency = normalizeCurrency(currency);
        Double vwap = getVWAP(cleanCurrency);
        if (vwap != null && isValid(vwap)) {
            return vwap;
        }
        return getOrderPrice(cleanCurrency);
    }

    private Double getOrderPrice(String currency) {
        List<P2PExchangeManager.Order> orders = new P2PExchangeManager(context).getOpenOrders();
        Map<String, Double> rates = CurrencyConverter.getCachedRates(context);
        double bestBid = 0.0;
        double bestAsk = 0.0;
        for (P2PExchangeManager.Order order : orders) {
            Double price = convertPrice(order.unitPrice(), order.getCurrency(),
                    currency, rates);
            if (price == null || !isValid(price)) {
                continue;
            }
            if (order.isBuy()) {
                if (bestBid <= 0.0 || price > bestBid) {
                    bestBid = price;
                }
            } else if (order.isSell()) {
                if (bestAsk <= 0.0 || price < bestAsk) {
                    bestAsk = price;
                }
            }
        }
        if (bestBid > 0.0 && bestAsk > 0.0) {
            return (bestBid + bestAsk) / 2.0;
        }
        if (bestBid > 0.0) {
            return bestBid;
        }
        if (bestAsk > 0.0) {
            return bestAsk;
        }
        return null;
    }

    private static String normalizeCurrency(String currency) {
        String value = currency == null ? "" : currency.trim()
                .toUpperCase(java.util.Locale.US);
        return P2PExchangeManager.SUPPORTED_CURRENCIES.contains(value)
                ? value : ExchangeOffer.CURRENCY_RUB;
    }

    private static List<Trade> filterCurrency(List<Trade> trades, String currency) {
        List<Trade> result = new ArrayList<>();
        for (Trade trade : trades) {
            if (trade.currency.equals(currency)) {
                result.add(trade);
            }
        }
        return result;
    }

    private static List<Trade> freshOnly(List<Trade> trades, long now) {
        List<Trade> fresh = new ArrayList<>();
        for (Trade trade : trades) {
            if (now - trade.timestamp < WINDOW_MS) {
                fresh.add(trade);
            }
        }
        return fresh;
    }

    private static String normalizeTradeId(String tradeId, long timestamp,
                                           double price, double amount,
                                           String currency) {
        String id = tradeId == null ? "" : tradeId.trim();
        if (!id.isEmpty()) {
            return id;
        }
        return "legacy:" + timestamp + ":" + currency + ":" + price + ":" + amount;
    }

    private List<Trade> loadTrades(String currency) {
        List<Trade> trades = new ArrayList<>();
        String raw = prefs.getString(KEY_TRADES, "");
        if (raw == null || raw.trim().isEmpty()) {
            return trades;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String id = object.optString("id", "");
                long timestamp = object.optLong("timestamp", 0L);
                double price = object.optDouble("price", 0.0);
                double amount = object.optDouble("amount", 0.0);
                String tradeCurrency = object.optString("currency", "");
                if (tradeCurrency.trim().isEmpty()) {
                    tradeCurrency = ExchangeOffer.CURRENCY_RUB;
                }
                if (timestamp > 0L && isValid(price) && isValid(amount)) {
                    String cleanCurrency = normalizeCurrency(tradeCurrency);
                    trades.add(new Trade(
                            normalizeTradeId(id, timestamp, price, amount,
                                    cleanCurrency),
                            timestamp, price, amount, cleanCurrency));
                }
            }
        } catch (Throwable ignored) {
        }
        return currency == null ? trades : filterCurrency(trades,
                normalizeCurrency(currency));
    }

    private void saveTrades(List<Trade> trades) {
        JSONArray array = new JSONArray();
        for (Trade trade : trades) {
            array.put(trade.toJson());
        }
        prefs.edit().putString(KEY_TRADES, array.toString()).apply();
    }

    private static boolean isValid(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static final class PricePoint {
        public final long timestamp;
        public final double price;

        PricePoint(long timestamp, double price) {
            this.timestamp = timestamp;
            this.price = price;
        }
    }

    private static final class Trade {
        final String id;
        final long timestamp;
        final double price;
        final double amount;
        final String currency;

        Trade(String id, long timestamp, double price, double amount, String currency) {
            this.id = id == null ? "" : id;
            this.timestamp = timestamp;
            this.price = price;
            this.amount = amount;
            this.currency = currency == null || currency.trim().isEmpty()
                    ? ExchangeOffer.CURRENCY_RUB : currency;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("tradeId", id);
                object.put("timestamp", timestamp);
                object.put("price", price);
                object.put("amount", amount);
                object.put("currency", currency);
            } catch (Throwable ignored) {
            }
            return object;
        }
    }
}
