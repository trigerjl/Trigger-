package com.triger.poc;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NewsManager {
    private static final String TAG = "NewsManager";
    public static final double PUBLISH_FEE_TRG = 0.1;

    public interface Listener {
        void onNewsChanged();
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyNewsChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onNewsChanged();
            } catch (Throwable t) {
                Log.w(TAG, "news listener failed", t);
            }
        }
    }

    private final Context context;

    public NewsManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasPublishFee() {
        String address = KeyManager.getOrCreateAddress(context);
        return TransferManager.getSpendableBalance(context, address)
                >= PUBLISH_FEE_TRG - 1e-9;
    }

    public List<NewsItem> getNews() {
        return ChatDatabase.getInstance(context).getRecentNews(100);
    }

    public NewsItem publish(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty() || !hasPublishFee()) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        TransferManager.Result burn = TransferManager.burnTokens(context,
                "0.1", "NEWS_PUBLISH:" + id);
        if (!burn.success) {
            Log.w(TAG, "news burn failed: " + burn.message);
            return null;
        }
        ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
        if (keyPair == null) {
            return null;
        }
        NewsItem unsigned = new NewsItem(id, KeyManager.getOrCreateAddress(context),
                IrohNode.getInstance().getLocalUsername(), clean,
                System.currentTimeMillis(), "");
        try {
            String signature = NewsItem.sign(unsigned.signingPayload(), keyPair);
            NewsItem signed = unsigned.withSignature(signature);
            ChatDatabase.getInstance(context).saveNews(signed);
            IrohNode.getInstance().broadcastNews(signed.toJson());
            notifyNewsChanged();
            return signed;
        } catch (Throwable t) {
            Log.w(TAG, "news publish failed", t);
            return null;
        }
    }

    public boolean handleIncomingNews(JSONObject object) {
        NewsItem item = NewsItem.fromJson(object);
        if (item == null || !item.isValid()) {
            return false;
        }
        ChatDatabase database = ChatDatabase.getInstance(context);
        if (database.hasNews(item.getId())) {
            return false;
        }
        database.saveNews(item);
        notifyNewsChanged();
        return true;
    }
}
