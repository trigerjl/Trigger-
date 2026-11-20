package com.triger.poc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReviewManager {

    private static final String TAG = "ReviewManager";
    private static final String PREFS_NAME = "triger_reviews";
    private static final String REVIEW_PREFIX = "review_";
    private static final String PENDING_PREFIX = "pending_";
    private static final Object LOCK = new Object();

    public static final long AUTO_REVIEW_DELAY_MS = 24L * 60L * 60L * 1000L;
    public static final int MAX_COMMENT_LENGTH = 500;

    private final Context context;
    private final SharedPreferences prefs;

    public ReviewManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean addReview(Review review) {
        if (review == null || review.rating < 1 || review.rating > 5) {
            return false;
        }
        String itemId = review.itemId == null ? "" : review.itemId.trim();
        String reviewer = TransferManager.normalizeAddress(review.reviewerAddress);
        String seller = TransferManager.normalizeAddress(review.sellerAddress);
        if (itemId.isEmpty() || !TransferManager.isValidAddress(reviewer)
                || !TransferManager.isValidAddress(seller) || reviewer.equals(seller)) {
            return false;
        }
        if (!EscrowManager.hasCompletedDeal(context, itemId, reviewer, seller)) {
            return false;
        }
        synchronized (LOCK) {
            if (hasReviewLocked(reviewer, seller, itemId)) {
                removePendingLocked(reviewer, seller, itemId);
                return false;
            }
            String id = review.id == null ? "" : review.id.trim();
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
            }
            String comment = review.comment == null ? "" : review.comment.trim();
            if (comment.length() > MAX_COMMENT_LENGTH) {
                comment = comment.substring(0, MAX_COMMENT_LENGTH);
            }
            long timestamp = review.timestamp > 0L
                    ? review.timestamp : System.currentTimeMillis();
            Review stored = new Review(id, itemId, reviewer, seller,
                    review.rating, comment, timestamp);
            prefs.edit().putString(REVIEW_PREFIX + id, stored.toJson().toString()).apply();
            removePendingLocked(reviewer, seller, itemId);
            return true;
        }
    }

    public List<Review> getReviewsForSeller(String sellerAddress) {
        String seller = TransferManager.normalizeAddress(sellerAddress);
        if (seller.isEmpty()) {
            return new ArrayList<>();
        }
        synchronized (LOCK) {
            return getReviewsForSellerLocked(seller);
        }
    }

    public double getAverageRating(String sellerAddress) {
        List<Review> reviews = getReviewsForSeller(sellerAddress);
        if (reviews.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Review review : reviews) {
            total += review.rating;
        }
        return total / reviews.size();
    }

    public int getReviewCount(String sellerAddress) {
        return getReviewsForSeller(sellerAddress).size();
    }

    public boolean hasReview(String reviewerAddress, String sellerAddress, String itemId) {
        String reviewer = TransferManager.normalizeAddress(reviewerAddress);
        String seller = TransferManager.normalizeAddress(sellerAddress);
        String item = itemId == null ? "" : itemId.trim();
        if (reviewer.isEmpty() || seller.isEmpty() || item.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            return hasReviewLocked(reviewer, seller, item);
        }
    }

    public void scheduleAutoReview(EscrowDeal deal) {
        if (deal == null || !EscrowManager.STATUS_COMPLETED.equals(deal.getStatus())) {
            return;
        }
        String itemId = deal.getItemId() == null ? "" : deal.getItemId().trim();
        String reviewer = TransferManager.normalizeAddress(deal.getBuyerAddress());
        String seller = TransferManager.normalizeAddress(deal.getSellerAddress());
        if (itemId.isEmpty() || !TransferManager.isValidAddress(reviewer)
                || !TransferManager.isValidAddress(seller)) {
            return;
        }
        synchronized (LOCK) {
            String key = PENDING_PREFIX + deal.getId();
            if (prefs.contains(key) || hasReviewLocked(reviewer, seller, itemId)) {
                return;
            }
            try {
                JSONObject pending = new JSONObject();
                pending.put("dealId", deal.getId());
                pending.put("itemId", itemId);
                pending.put("reviewerAddress", reviewer);
                pending.put("sellerAddress", seller);
                pending.put("dueAt", System.currentTimeMillis() + AUTO_REVIEW_DELAY_MS);
                prefs.edit().putString(key, pending.toString()).apply();
            } catch (Throwable t) {
                Log.w(TAG, "failed to schedule auto review", t);
            }
        }
    }

    public int processDueReviews() {
        long now = System.currentTimeMillis();
        List<Review> due = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!entry.getKey().startsWith(PENDING_PREFIX)) {
                    continue;
                }
                Object value = entry.getValue();
                if (!(value instanceof String)) {
                    continue;
                }
                try {
                    JSONObject pending = new JSONObject((String) value);
                    long dueAt = pending.optLong("dueAt", 0L);
                    if (dueAt <= 0L || now < dueAt) {
                        continue;
                    }
                    due.add(new Review(
                            "",
                            pending.optString("itemId", ""),
                            pending.optString("reviewerAddress", ""),
                            pending.optString("sellerAddress", ""),
                            5,
                            "",
                            now));
                    keys.add(entry.getKey());
                } catch (Throwable ignored) {
                }
            }
        }
        int added = 0;
        for (Review review : due) {
            if (addReview(review)) {
                added++;
            }
        }
        if (!keys.isEmpty()) {
            synchronized (LOCK) {
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : keys) {
                    editor.remove(key);
                }
                editor.apply();
            }
        }
        return added;
    }

    public long getNextPendingReviewDueAt() {
        long next = Long.MAX_VALUE;
        synchronized (LOCK) {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!entry.getKey().startsWith(PENDING_PREFIX)) {
                    continue;
                }
                Object value = entry.getValue();
                if (!(value instanceof String)) {
                    continue;
                }
                try {
                    long dueAt = new JSONObject((String) value).optLong("dueAt", 0L);
                    if (dueAt > 0L && dueAt < next) {
                        next = dueAt;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return next == Long.MAX_VALUE ? 0L : next;
    }

    private List<Review> getReviewsForSellerLocked(String seller) {
        List<Review> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(REVIEW_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                Review review = Review.fromJson(new JSONObject((String) value));
                if (review != null && seller.equals(review.sellerAddress)) {
                    result.add(review);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparingLong((Review review) -> review.timestamp).reversed());
        return result;
    }

    private boolean hasReviewLocked(String reviewer, String seller, String itemId) {
        for (Review review : getReviewsForSellerLocked(seller)) {
            if (reviewer.equals(review.reviewerAddress)
                    && itemId.equals(review.itemId)) {
                return true;
            }
        }
        return false;
    }

    private void removePendingLocked(String reviewer, String seller, String itemId) {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PENDING_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof String)) {
                continue;
            }
            try {
                JSONObject pending = new JSONObject((String) value);
                if (itemId.equals(pending.optString("itemId", ""))
                        && reviewer.equals(TransferManager.normalizeAddress(
                        pending.optString("reviewerAddress", "")))
                        && seller.equals(TransferManager.normalizeAddress(
                        pending.optString("sellerAddress", "")))) {
                    remove.add(entry.getKey());
                }
            } catch (Throwable ignored) {
            }
        }
        if (!remove.isEmpty()) {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : remove) {
                editor.remove(key);
            }
            editor.apply();
        }
    }

    public static final class Review {
        public final String id;
        public final String itemId;
        public final String reviewerAddress;
        public final String sellerAddress;
        public final int rating;
        public final String comment;
        public final long timestamp;

        public Review(String id, String itemId, String reviewerAddress,
                      String sellerAddress, int rating, String comment, long timestamp) {
            this.id = id == null ? "" : id;
            this.itemId = itemId == null ? "" : itemId;
            this.reviewerAddress = TransferManager.normalizeAddress(reviewerAddress);
            this.sellerAddress = TransferManager.normalizeAddress(sellerAddress);
            this.rating = rating;
            this.comment = comment == null ? "" : comment;
            this.timestamp = timestamp;
        }

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("itemId", itemId);
                object.put("reviewerAddress", reviewerAddress);
                object.put("sellerAddress", sellerAddress);
                object.put("rating", rating);
                object.put("comment", comment);
                object.put("timestamp", timestamp);
            } catch (Throwable ignored) {
            }
            return object;
        }

        public static Review fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            return new Review(
                    object.optString("id", ""),
                    object.optString("itemId", ""),
                    object.optString("reviewerAddress", ""),
                    object.optString("sellerAddress", ""),
                    object.optInt("rating", 0),
                    object.optString("comment", ""),
                    object.optLong("timestamp", 0L));
        }
    }
}
