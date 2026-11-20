package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Разрешение споров P2P-обменника случайными арбитрами.
 *
 * Арбитры — пользователи сети с высоким рейтингом/репутацией. Пока спор
 * открыт, TRG остаются в эскроу. После голосования большинство определяет,
 * кому уходят замороженные средства.
 */
public class DisputeManager {
    private static final String TAG = "DisputeManager";
    private static final String PREFS_NAME = "triger_disputes";
    private static final String DISPUTE_PREFIX = "dispute_";
    private static final String ARBITERS_KEY = "arbiters";
    private static final String NEXT_ID_KEY = "next_id";
    private static final int MAX_ARBITERS = 500;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final int DEFAULT_ARBITER_COUNT = 3;
    public static final long DISPUTE_WINDOW_MS = 24L * 60L * 60L * 1000L;
    /** Сколько средства остаются замороженными в ожидании голосов арбитров. */
    public static final long DISPUTE_FREEZE_MS = 48L * 60L * 60L * 1000L;
    /** Если арбитры не вынесли решение, через 7 дней средства возвращаются продавцу. */
    public static final long DISPUTE_AUTO_RESOLVE_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public interface Listener {
        void onDisputesChanged();
    }

    private final Context context;
    private final SharedPreferences prefs;

    public DisputeManager(Context context) {
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

    /** Локальная запись спора. */
    public static final class Dispute {
        private String id;
        private String dealId;
        private String dealTitle;
        private String amountText;
        private String buyerAddress;
        private String sellerAddress;
        private List<String> arbiters;
        private JSONObject votes;
        private String status;
        private boolean releaseToBuyer;
        private long createdAt;
        private long resolvedAt;

        Dispute(String id, String dealId, String dealTitle, String amountText,
                String buyerAddress, String sellerAddress, List<String> arbiters,
                JSONObject votes, String status, boolean releaseToBuyer,
                long createdAt, long resolvedAt) {
            this.id = value(id);
            this.dealId = value(dealId);
            this.dealTitle = value(dealTitle);
            this.amountText = value(amountText);
            this.buyerAddress = TransferManager.normalizeAddress(buyerAddress);
            this.sellerAddress = TransferManager.normalizeAddress(sellerAddress);
            this.arbiters = arbiters == null ? new ArrayList<>() : arbiters;
            this.votes = votes == null ? new JSONObject() : votes;
            this.status = value(status).isEmpty() ? STATUS_OPEN : status;
            this.releaseToBuyer = releaseToBuyer;
            this.createdAt = createdAt;
            this.resolvedAt = resolvedAt;
        }

        public String getId() {
            return id;
        }

        public String getDealId() {
            return dealId;
        }

        public String getDealTitle() {
            return dealTitle;
        }

        public String getAmountText() {
            return amountText;
        }

        public String getBuyerAddress() {
            return buyerAddress;
        }

        public String getSellerAddress() {
            return sellerAddress;
        }

        public List<String> getArbiters() {
            return new ArrayList<>(arbiters);
        }

        public String getStatus() {
            return status;
        }

        public boolean isOpen() {
            return STATUS_OPEN.equals(status);
        }

        public boolean isResolved() {
            return STATUS_RESOLVED.equals(status);
        }

        public boolean isReleaseToBuyer() {
            return releaseToBuyer;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getResolvedAt() {
            return resolvedAt;
        }

        public int getVoteCount() {
            return votes.length();
        }

        public int getArbiterCount() {
            return arbiters.size();
        }

        public boolean isArbiter(String address) {
            String clean = TransferManager.normalizeAddress(address);
            for (String arbiter : arbiters) {
                if (TransferManager.normalizeAddress(arbiter).equals(clean)) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasVoted(String address) {
            return votes.has(TransferManager.normalizeAddress(address));
        }

        public Boolean getVote(String address) {
            String clean = TransferManager.normalizeAddress(address);
            if (!votes.has(clean)) {
                return null;
            }
            return votes.optBoolean(clean, false);
        }

        public boolean allVotesCast() {
            if (arbiters.isEmpty()) {
                return false;
            }
            for (String arbiter : arbiters) {
                if (!votes.has(TransferManager.normalizeAddress(arbiter))) {
                    return false;
                }
            }
            return true;
        }

        public int countVotes(boolean forBuyer) {
            int count = 0;
            Iterator<String> keys = votes.keys();
            while (keys.hasNext()) {
                if (votes.optBoolean(keys.next(), false) == forBuyer) {
                    count++;
                }
            }
            return count;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("dealId", dealId);
                object.put("dealTitle", dealTitle);
                object.put("amountText", amountText);
                object.put("buyerAddress", buyerAddress);
                object.put("sellerAddress", sellerAddress);
                JSONArray arbiterArray = new JSONArray();
                for (String arbiter : arbiters) {
                    arbiterArray.put(arbiter);
                }
                object.put("arbiters", arbiterArray);
                object.put("votes", votes);
                object.put("status", status);
                object.put("releaseToBuyer", releaseToBuyer);
                object.put("createdAt", createdAt);
                object.put("resolvedAt", resolvedAt);
            } catch (Throwable ignored) {
            }
            return object;
        }

        static Dispute fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            List<String> arbiters = new ArrayList<>();
            JSONArray array = object.optJSONArray("arbiters");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String arbiter = TransferManager.normalizeAddress(
                            array.optString(i, ""));
                    if (!arbiter.isEmpty() && !arbiters.contains(arbiter)) {
                        arbiters.add(arbiter);
                    }
                }
            }
            JSONObject votes = object.optJSONObject("votes");
            return new Dispute(
                    object.optString("id", ""),
                    object.optString("dealId", ""),
                    object.optString("dealTitle", ""),
                    object.optString("amountText", ""),
                    object.optString("buyerAddress", ""),
                    object.optString("sellerAddress", ""),
                    arbiters,
                    votes,
                    object.optString("status", STATUS_OPEN),
                    object.optBoolean("releaseToBuyer", false),
                    object.optLong("createdAt", 0L),
                    object.optLong("resolvedAt", 0L));
        }
    }

    /** Создаёт спор по сделке. Повторный вызов возвращает уже открытый спор. */
    public Dispute createDispute(String dealId, String buyerAddress, String sellerAddress) {
        String deal = value(dealId).trim();
        String buyer = TransferManager.normalizeAddress(buyerAddress);
        String seller = TransferManager.normalizeAddress(sellerAddress);
        if (deal.isEmpty() || !TransferManager.isValidAddress(buyer)
                || !TransferManager.isValidAddress(seller) || buyer.equals(seller)) {
            return null;
        }
        synchronized (LOCK) {
            Dispute existing = findDisputeLocked(deal);
            if (existing != null && existing.isOpen()) {
                return existing;
            }
            EscrowDeal escrowDeal = EscrowManager.getDeal(context, deal);
            String title = escrowDeal == null ? "" : escrowDeal.getItemTitle();
            String amount = escrowDeal == null ? "" : escrowDeal.getAmountText();
            long now = System.currentTimeMillis();
            String id = String.valueOf(nextIdLocked());
            Dispute dispute = new Dispute(id, deal, title, amount, buyer, seller,
                    new ArrayList<>(), new JSONObject(), STATUS_OPEN, false, now, 0L);
            saveLocked(dispute);
            notifyChanged();
            return dispute;
        }
    }

    /** Выбирает N случайных арбитров и отправляет им уведомление. */
    public List<String> assignArbiters(String dealId, int count) {
        if (count <= 0) {
            return new ArrayList<>();
        }
        synchronized (LOCK) {
            Dispute dispute = findDisputeLocked(value(dealId).trim());
            if (dispute == null || !dispute.isOpen()) {
                return new ArrayList<>();
            }
            if (!dispute.arbiters.isEmpty()) {
                return dispute.getArbiters();
            }
            Set<String> exclude = new HashSet<>();
            exclude.add(dispute.buyerAddress);
            exclude.add(dispute.sellerAddress);
            List<String> pool = collectCandidatesLocked(exclude);
            Collections.shuffle(pool, RANDOM);
            List<String> picked = new ArrayList<>();
            for (String candidate : pool) {
                if (picked.size() >= count) {
                    break;
                }
                if (!picked.contains(candidate)) {
                    picked.add(candidate);
                }
            }
            dispute.arbiters = new ArrayList<>(picked);
            saveLocked(dispute);
            notifyArbiters(dispute);
            broadcastDispute(dispute);
            notifyChanged();
            return picked;
        }
    }

    /** Арбитр голосует: true — вернуть покупателю, false — отдать продавцу. */
    public boolean vote(String dealId, String arbiterAddress, boolean releaseToBuyer) {
        String arbiter = TransferManager.normalizeAddress(arbiterAddress);
        if (arbiter.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            Dispute dispute = findDisputeLocked(value(dealId).trim());
            if (dispute == null || !dispute.isOpen() || !dispute.isArbiter(arbiter)) {
                return false;
            }
            try {
                dispute.votes.put(arbiter, releaseToBuyer);
            } catch (Throwable t) {
                Log.w(TAG, "failed to store dispute vote", t);
                return false;
            }
            saveLocked(dispute);
            broadcastVote(dispute, arbiter, releaseToBuyer);
            notifyChanged();
            return true;
        }
    }

    /**
     * Подсчитывает голоса и распределяет TRG из эскроу по большинству.
     * Возвращает true, если спор удалось завершить.
     */
    public boolean resolveDispute(String dealId) {
        String deal = value(dealId).trim();
        synchronized (LOCK) {
            Dispute dispute = findDisputeLocked(deal);
            if (dispute == null || !dispute.isOpen()) {
                return false;
            }
            // Неполный состав арбитров не может вынести обязательное решение:
            // такой спор закрывается по таймауту в пользу продавца.
            if (dispute.getArbiterCount() < DEFAULT_ARBITER_COUNT) {
                return false;
            }
            int buyerVotes = dispute.countVotes(true);
            int sellerVotes = dispute.countVotes(false);
            if (buyerVotes == sellerVotes) {
                return false;
            }
            boolean toBuyer = buyerVotes > sellerVotes;
            TransferManager.Result distribution =
                    EscrowManager.resolveDispute(context, dispute.dealId, toBuyer);
            if (distribution == null || !distribution.success) {
                return false;
            }
            return completeResolutionLocked(dispute, toBuyer);
        }
    }

    /**
     * Автоматическое завершение спора по таймауту.
     * Если арбитры успели вынести решение большинством — оно соблюдается.
     * Если голосов нет или они разделились, через 7 дней TRG возвращаются продавцу:
     * покупатель не доказал оплату.
     */
    public boolean resolveDisputeByTimeout(String dealId) {
        String deal = value(dealId).trim();
        synchronized (LOCK) {
            Dispute dispute = findDisputeLocked(deal);
            if (dispute == null || !dispute.isOpen()) {
                return false;
            }
            boolean enoughArbiters =
                    dispute.getArbiterCount() >= DEFAULT_ARBITER_COUNT;
            boolean decided = dispute.allVotesCast()
                    && dispute.countVotes(true) != dispute.countVotes(false);
            if (enoughArbiters && decided) {
                return resolveDispute(dispute.dealId);
            }
            TransferManager.Result distribution =
                    EscrowManager.resolveDispute(context, dispute.dealId, false);
            if (distribution == null || !distribution.success) {
                return false;
            }
            return completeResolutionLocked(dispute, false);
        }
    }

    /**
     * Завершает споры, которые висят дольше DISPUTE_AUTO_RESOLVE_MS.
     * Вызывается при обработке эскроу, открытии приложения и по будильнику.
     *
     * @return количество автоматически завершённых споров
     */
    public int processExpiredDisputes() {
        long now = System.currentTimeMillis();
        int resolved = 0;
        for (Dispute dispute : getDisputes()) {
            if (!dispute.isOpen() || dispute.getCreatedAt() <= 0L) {
                continue;
            }
            if (now - dispute.getCreatedAt() < DISPUTE_AUTO_RESOLVE_MS) {
                continue;
            }
            if (resolveDisputeByTimeout(dispute.getDealId())) {
                resolved++;
            }
        }
        return resolved;
    }

    private boolean completeResolutionLocked(Dispute dispute, boolean toBuyer) {
        long now = System.currentTimeMillis();
        dispute.status = STATUS_RESOLVED;
        dispute.releaseToBuyer = toBuyer;
        dispute.resolvedAt = now;
        saveLocked(dispute);
        new P2PExchangeManager(context).handleDisputeResolved(dispute.dealId, toBuyer);
        broadcastStatus(dispute);
        notifyChanged();
        return true;
    }

    public Dispute getDisputeForDeal(String dealId) {
        synchronized (LOCK) {
            return findDisputeLocked(value(dealId).trim());
        }
    }

    public Dispute getDispute(String disputeId) {
        String id = value(disputeId).trim();
        if (id.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            String raw = prefs.getString(DISPUTE_PREFIX + id, null);
            if (raw == null) {
                return null;
            }
            try {
                return Dispute.fromJson(new JSONObject(raw));
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public List<Dispute> getDisputes() {
        List<Dispute> result = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!entry.getKey().startsWith(DISPUTE_PREFIX)) {
                    continue;
                }
                Object value = entry.getValue();
                if (!(value instanceof String)) {
                    continue;
                }
                try {
                    Dispute dispute = Dispute.fromJson(new JSONObject((String) value));
                    if (dispute != null && !dispute.getId().isEmpty()) {
                        result.add(dispute);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        result.sort(Comparator.comparingLong(Dispute::getCreatedAt).reversed());
        return result;
    }

    /** Активные споры, назначенные арбитру. */
    public List<Dispute> getActiveDisputesForArbiter(String arbiterAddress) {
        String local = TransferManager.normalizeAddress(arbiterAddress);
        List<Dispute> result = new ArrayList<>();
        for (Dispute dispute : getDisputes()) {
            if (dispute.isOpen() && dispute.isArbiter(local)) {
                result.add(dispute);
            }
        }
        return result;
    }

    /** Споры, видимые арбитру или участникам сделки. */
    public List<Dispute> getVisibleDisputes(String address) {
        String local = TransferManager.normalizeAddress(address);
        List<Dispute> result = new ArrayList<>();
        for (Dispute dispute : getDisputes()) {
            if (!dispute.isOpen()) {
                continue;
            }
            if (dispute.isArbiter(local)
                    || local.equals(dispute.getBuyerAddress())
                    || local.equals(dispute.getSellerAddress())) {
                result.add(dispute);
            }
        }
        return result;
    }

    /** Список сохранённых арбитров. */
    public List<String> getArbiters() {
        synchronized (LOCK) {
            return new ArrayList<>(getStoredArbitersLocked());
        }
    }

    /** Добавляет пользователя в список арбитров (например, после высокой репутации). */
    public void addArbiter(String address) {
        String clean = TransferManager.normalizeAddress(address);
        if (!TransferManager.isValidAddress(clean)) {
            return;
        }
        synchronized (LOCK) {
            List<String> arbiterList = getStoredArbitersLocked();
            if (!arbiterList.contains(clean) && arbiterList.size() < MAX_ARBITERS) {
                arbiterList.add(clean);
                saveArbitersLocked(arbiterList);
            }
        }
    }

    public void removeArbiter(String address) {
        String clean = TransferManager.normalizeAddress(address);
        synchronized (LOCK) {
            List<String> arbiterList = getStoredArbitersLocked();
            if (arbiterList.remove(clean)) {
                saveArbitersLocked(arbiterList);
            }
        }
    }

    public static boolean hasActiveDispute(Context context, String dealId) {
        if (context == null || TextUtils.isEmpty(dealId)) {
            return false;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(DISPUTE_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                Dispute dispute = Dispute.fromJson(new JSONObject((String) value));
                if (dispute != null && dispute.isOpen() && dealId.equals(dispute.getDealId())) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static String resultMessage(Context context, boolean releaseToBuyer) {
        if (context == null) {
            return "";
        }
        return context.getString(releaseToBuyer
                ? R.string.dispute_resolved_buyer
                : R.string.dispute_resolved_seller);
    }

    public void handleIncomingDispute(JSONObject json) {
        if (json == null) {
            return;
        }
        Dispute incoming = Dispute.fromJson(json);
        if (incoming == null || incoming.getId().isEmpty()
                || incoming.getDealId().isEmpty()) {
            return;
        }
        String local = TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(context));
        boolean involved = local.equals(incoming.getBuyerAddress())
                || local.equals(incoming.getSellerAddress())
                || incoming.isArbiter(local);
        if (!involved) {
            return;
        }
        synchronized (LOCK) {
            Dispute existing = getDisputeLocked(incoming.getId());
            if (existing != null && existing.isResolved()) {
                return;
            }
            saveLocked(incoming);
            if (incoming.isOpen() && incoming.isArbiter(local)) {
                notifyArbiters(incoming);
            }
            notifyChanged();
        }
        maybeResolveLocally(incoming);
    }

    public void handleIncomingDisputeVote(JSONObject json) {
        if (json == null) {
            return;
        }
        String disputeId = json.optString("disputeId", "");
        String dealId = json.optString("dealId", "");
        String arbiter = TransferManager.normalizeAddress(
                json.optString("arbiterAddress", ""));
        boolean releaseToBuyer = json.optBoolean("releaseToBuyer", false);
        if ((disputeId.isEmpty() && dealId.isEmpty()) || arbiter.isEmpty()) {
            return;
        }
        Dispute dispute;
        synchronized (LOCK) {
            dispute = getDisputeLocked(disputeId);
            if (dispute == null && !dealId.isEmpty()) {
                dispute = findDisputeLocked(dealId);
            }
            if (dispute == null || !dispute.isOpen() || !dispute.isArbiter(arbiter)) {
                return;
            }
            try {
                dispute.votes.put(arbiter, releaseToBuyer);
            } catch (Throwable ignored) {
                return;
            }
            saveLocked(dispute);
            notifyChanged();
        }
        maybeResolveLocally(dispute);
    }

    public void handleIncomingDisputeStatus(JSONObject json) {
        if (json == null) {
            return;
        }
        String disputeId = json.optString("disputeId", "");
        String dealId = json.optString("dealId", "");
        boolean releaseToBuyer = json.optBoolean("releaseToBuyer", false);
        synchronized (LOCK) {
            Dispute dispute = getDisputeLocked(disputeId);
            if (dispute == null && !dealId.isEmpty()) {
                dispute = findDisputeLocked(dealId);
            }
            if (dispute == null) {
                return;
            }
            dispute.status = STATUS_RESOLVED;
            dispute.releaseToBuyer = releaseToBuyer;
            dispute.resolvedAt = System.currentTimeMillis();
            saveLocked(dispute);
            notifyChanged();
        }
    }

    private void maybeResolveLocally(Dispute dispute) {
        if (dispute == null || !dispute.allVotesCast()) {
            return;
        }
        String local = TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(context));
        if (!local.equals(dispute.getBuyerAddress())
                && !local.equals(dispute.getSellerAddress())) {
            return;
        }
        resolveDispute(dispute.getDealId());
    }

    private void notifyArbiters(Dispute dispute) {
        String local = TransferManager.normalizeAddress(
                KeyManager.getOrCreateAddress(context));
        if (!dispute.isArbiter(local)) {
            return;
        }
        String text = context.getString(R.string.dispute_arbiter_notification,
                dispute.getId());
        NotificationHelper.showDisputeNotification(context, dispute.getId(), text);
    }

    private void broadcastDispute(Dispute dispute) {
        try {
            IrohNode.getInstance().broadcastDispute(dispute.toJson());
        } catch (Throwable ignored) {
        }
    }

    private void broadcastVote(Dispute dispute, String arbiter, boolean releaseToBuyer) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", "P2P_DISPUTE_VOTE");
            object.put("disputeId", dispute.getId());
            object.put("dealId", dispute.getDealId());
            object.put("arbiterAddress", arbiter);
            object.put("releaseToBuyer", releaseToBuyer);
            object.put("timestamp", System.currentTimeMillis());
            IrohNode.getInstance().broadcastDisputeVote(object);
        } catch (Throwable ignored) {
        }
    }

    private void broadcastStatus(Dispute dispute) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", "P2P_DISPUTE_STATUS");
            object.put("disputeId", dispute.getId());
            object.put("dealId", dispute.getDealId());
            object.put("status", dispute.getStatus());
            object.put("releaseToBuyer", dispute.isReleaseToBuyer());
            object.put("timestamp", System.currentTimeMillis());
            IrohNode.getInstance().broadcastDisputeStatus(object);
        } catch (Throwable ignored) {
        }
    }

    private List<String> collectCandidatesLocked(Set<String> exclude) {
        LinkedHashSet<String> trusted = new LinkedHashSet<>();
        LinkedHashSet<String> known = new LinkedHashSet<>();
        for (String stored : getStoredArbitersLocked()) {
            if (isCandidate(stored, exclude)) {
                trusted.add(stored);
            }
        }
        try {
            IrohNode node = IrohNode.getInstance();
            for (String peer : node.getKnownPeers()) {
                String wallet = node.getPeerWalletAddress(peer);
                if (isCandidate(wallet, exclude)) {
                    known.add(TransferManager.normalizeAddress(wallet));
                }
            }
        } catch (Throwable ignored) {
        }
        ReviewManager reviews = new ReviewManager(context);
        Set<String> users = new LinkedHashSet<>();
        try {
            P2PExchangeManager exchange = new P2PExchangeManager(context);
            for (P2PExchangeManager.Order order : exchange.getOrders()) {
                users.add(order.getCreatorAddress());
                users.add(order.getCounterpartyAddress());
            }
        } catch (Throwable ignored) {
        }
        try {
            for (EscrowDeal deal : EscrowManager.getDeals(context)) {
                users.add(deal.getBuyerAddress());
                users.add(deal.getSellerAddress());
            }
        } catch (Throwable ignored) {
        }
        for (String user : users) {
            String clean = TransferManager.normalizeAddress(user);
            if (!isCandidate(clean, exclude)) {
                continue;
            }
            try {
                if (reviews.getReviewCount(clean) > 0
                        && reviews.getAverageRating(clean) >= 4.0) {
                    trusted.add(clean);
                } else {
                    known.add(clean);
                }
            } catch (Throwable ignored) {
                known.add(clean);
            }
        }
        List<String> result = new ArrayList<>(trusted);
        for (String candidate : known) {
            if (!result.contains(candidate)) {
                result.add(candidate);
            }
        }
        if (result.size() > MAX_ARBITERS) {
            result = new ArrayList<>(result.subList(0, MAX_ARBITERS));
        }
        if (!result.isEmpty()) {
            List<String> stored = getStoredArbitersLocked();
            for (String candidate : result) {
                if (!stored.contains(candidate) && stored.size() < MAX_ARBITERS) {
                    stored.add(candidate);
                }
            }
            saveArbitersLocked(stored);
        }
        return result;
    }

    private boolean isCandidate(String address, Set<String> exclude) {
        String clean = TransferManager.normalizeAddress(address);
        return TransferManager.isValidAddress(clean)
                && !exclude.contains(clean);
    }

    private Dispute findDisputeLocked(String dealId) {
        if (dealId == null || dealId.isEmpty()) {
            return null;
        }
        for (Dispute dispute : getDisputesLocked()) {
            if (dealId.equals(dispute.getDealId())) {
                return dispute;
            }
        }
        return null;
    }

    private List<Dispute> getDisputesLocked() {
        List<Dispute> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(DISPUTE_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                Dispute dispute = Dispute.fromJson(new JSONObject((String) value));
                if (dispute != null && !dispute.getId().isEmpty()) {
                    result.add(dispute);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private Dispute getDisputeLocked(String disputeId) {
        String id = value(disputeId).trim();
        if (id.isEmpty()) {
            return null;
        }
        String raw = prefs.getString(DISPUTE_PREFIX + id, null);
        if (raw == null) {
            return null;
        }
        try {
            return Dispute.fromJson(new JSONObject(raw));
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveLocked(Dispute dispute) {
        if (dispute == null || dispute.getId().isEmpty()) {
            return;
        }
        prefs.edit()
                .putString(DISPUTE_PREFIX + dispute.getId(), dispute.toJson().toString())
                .apply();
    }

    private long nextIdLocked() {
        long id = prefs.getLong(NEXT_ID_KEY, 1L);
        if (id < 1L) {
            id = 1L;
        }
        prefs.edit().putLong(NEXT_ID_KEY, id + 1L).apply();
        return id;
    }

    private List<String> getStoredArbitersLocked() {
        List<String> result = new ArrayList<>();
        String raw = prefs.getString(ARBITERS_KEY, "");
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String address = TransferManager.normalizeAddress(
                        array.optString(i, ""));
                if (TransferManager.isValidAddress(address)
                        && !result.contains(address)) {
                    result.add(address);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private void saveArbitersLocked(List<String> arbiterList) {
        JSONArray array = new JSONArray();
        for (String arbiter : arbiterList) {
            array.put(arbiter);
        }
        prefs.edit().putString(ARBITERS_KEY, array.toString()).apply();
    }

    private void notifyChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onDisputesChanged();
            } catch (Throwable t) {
                Log.w(TAG, "dispute listener failed", t);
            }
        }
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context)
                    .sendBroadcast(new android.content.Intent(
                            P2PExchangeManager.ACTION_EXCHANGE_CHANGED));
        } catch (Throwable ignored) {
        }
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }
}
