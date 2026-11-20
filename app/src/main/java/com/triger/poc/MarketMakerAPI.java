package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MarketMakerAPI {

    private static final String PREFS_NAME = "triger_market_maker";
    private static final String REGISTERED_PREFIX = "registered_";
    private static final String ORDER_OWNER_PREFIX = "order_owner_";

    private static volatile Context appContext;

    public MarketMakerAPI(Context context) {
        initialize(context);
    }

    public static synchronized void initialize(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static boolean registerMarketMaker(String endpointId) {
        Context context = appContext;
        String endpoint = normalize(endpointId);
        if (context == null || endpoint.isEmpty() || endpoint.length() > 256) {
            return false;
        }
        prefs(context).edit()
                .putBoolean(REGISTERED_PREFIX + endpoint, true)
                .apply();
        return true;
    }

    public static boolean isRegistered(String endpointId) {
        Context context = appContext;
        String endpoint = normalize(endpointId);
        return context != null && !endpoint.isEmpty()
                && prefs(context).getBoolean(REGISTERED_PREFIX + endpoint, false);
    }

    public static Set<String> getRegisteredMarketMakers() {
        Context context = appContext;
        Set<String> result = new HashSet<>();
        if (context == null) {
            return result;
        }
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(REGISTERED_PREFIX)) {
                result.add(key.substring(REGISTERED_PREFIX.length()));
            }
        }
        return result;
    }

    public static P2PExchangeManager.Result placeOrder(String endpointId, boolean isBuy,
                                                       double amountTRG, double priceFiat,
                                                       String currency) {
        Context context = appContext;
        String endpoint = normalize(endpointId);
        if (context == null) {
            return P2PExchangeManager.Result.error("Market maker API is not initialized");
        }
        if (!isRegistered(endpoint)) {
            return P2PExchangeManager.Result.error(
                    context.getString(R.string.exchange_error_forbidden));
        }
        String nickname = IrohNode.getInstance().getLocalUsername();
        if (TextUtils.isEmpty(nickname) || nickname.startsWith("peer_")) {
            nickname = context.getString(R.string.exchange_market_maker);
        }
        String method = P2PExchangeManager.Order.PAYMENT_OTHER;
        String details = isBuy ? "" : context.getString(R.string.exchange_bot_order)
                + " " + endpoint;
        P2PExchangeManager.Order order = new P2PExchangeManager.Order(
                "", TransferManager.normalizeAddress(
                        KeyManager.getOrCreateAddress(context)), nickname,
                isBuy ? P2PExchangeManager.Order.TYPE_BUY
                        : P2PExchangeManager.Order.TYPE_SELL,
                amountTRG, amountTRG, priceFiat,
                currency == null ? "" : currency, method, details,
                "", 0L, P2PExchangeManager.Order.STATUS_OPEN);
        order.setMarketMaker(true);
        P2PExchangeManager.Result result =
                new P2PExchangeManager(context).placeOrder(order);
        if (result.success && result.order != null) {
            prefs(context).edit()
                    .putString(ORDER_OWNER_PREFIX + result.order.getId(), endpoint)
                    .apply();
        }
        return result;
    }

    public static P2PExchangeManager.Result cancelOrder(String endpointId, String orderId) {
        Context context = appContext;
        String endpoint = normalize(endpointId);
        String id = normalize(orderId);
        if (context == null) {
            return P2PExchangeManager.Result.error("Market maker API is not initialized");
        }
        if (!isRegistered(endpoint) || id.isEmpty()) {
            return P2PExchangeManager.Result.error(
                    context.getString(R.string.exchange_error_forbidden));
        }
        String owner = prefs(context).getString(ORDER_OWNER_PREFIX + id, "");
        if (!endpoint.equals(owner)) {
            return P2PExchangeManager.Result.error(
                    context.getString(R.string.exchange_error_forbidden));
        }
        P2PExchangeManager.Result result =
                new P2PExchangeManager(context).cancelOrder(id);
        if (result.success) {
            prefs(context).edit().remove(ORDER_OWNER_PREFIX + id).apply();
        }
        return result;
    }

    public static List<P2PExchangeManager.Order> getOpenOrders() {
        Context context = appContext;
        if (context == null) {
            return new ArrayList<>();
        }
        return new P2PExchangeManager(context).getOpenOrders();
    }

    public static boolean ownsOrder(String endpointId, String orderId) {
        Context context = appContext;
        String endpoint = normalize(endpointId);
        String id = normalize(orderId);
        return context != null && !endpoint.isEmpty() && !id.isEmpty()
                && endpoint.equals(prefs(context).getString(
                        ORDER_OWNER_PREFIX + id, ""));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
