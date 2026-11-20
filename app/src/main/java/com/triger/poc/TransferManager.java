package com.triger.poc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Переводы TRG между кошельками с подписью и сжиганием комиссии. */
public final class TransferManager {

    private static final String TAG = "TransferManager";

    public static final String TRANSFER_PREFIX = "__triger_transfer__";
    public static final String TYPE_TRANSFER = "TRANSFER";
    public static final String ACTION_BALANCE_CHANGED = "com.triger.poc.action.BALANCE_CHANGED";
    public static final double FEE_RATE = 0.01;
    public static final int MAX_MEMO_LENGTH = 100;

    private static final BigDecimal FEE_MULTIPLIER = new BigDecimal("0.01");
    private static final int AMOUNT_SCALE = 8;
    private static final Pattern ADDRESS_PATTERN =
            Pattern.compile("(?i)0x[a-f0-9]{40}");
    private static final String PREFS_NAME = "triger_transfers";
    private static final String KEY_PROCESSED_IDS = "processed_ids";
    private static final Object PROCESSED_LOCK = new Object();

    private TransferManager() {
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static Result success(String message) {
            return new Result(true, message);
        }

        static Result error(String message) {
            return new Result(false, message);
        }
    }

    public static BigDecimal calculateFee(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(FEE_MULTIPLIER).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    public static String extractAddress(String contents) {
        if (contents == null) {
            return "";
        }
        String value = contents.trim();
        Matcher matcher = ADDRESS_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group();
        }
        int scheme = value.indexOf(':');
        if (scheme >= 0 && scheme < value.length() - 1) {
            value = value.substring(scheme + 1);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return value.trim();
    }

    public static boolean isValidAddress(String address) {
        return address != null && ADDRESS_PATTERN.matcher(address.trim()).matches();
    }

    public static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.US);
    }

    public static Result sendTransfer(Context context, String toAddress, String amountText) {
        return sendTransfer(context, toAddress, amountText, "");
    }

    public static Result sendTransfer(Context context, String toAddress, String amountText,
                                      String memo) {
        if (context == null) {
            return Result.error("");
        }
        CryptoUtils.setupBouncyCastle();
        String from = normalizeAddress(KeyManager.getOrCreateAddress(context));
        ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
        if (keyPair == null) {
            return Result.error(context.getString(R.string.error_signing_key_unavailable));
        }
        return sendTransferInternal(context, from, keyPair, toAddress, amountText, memo);
    }

    public static Result sendTransferFrom(Context context, String fromAddress,
                                          ECKeyPair keyPair, String toAddress,
                                          String amountText) {
        return sendTransferFrom(context, fromAddress, keyPair, toAddress, amountText, "");
    }

    /**
     * Отправляет перевод от имени произвольного кошелька, чей приватный ключ
     * известен приложению (используется для эскроу-адресов).
     */
    public static Result sendTransferFrom(Context context, String fromAddress,
                                          ECKeyPair keyPair, String toAddress,
                                          String amountText, String memo) {
        if (context == null) {
            return Result.error("");
        }
        CryptoUtils.setupBouncyCastle();
        String from = normalizeAddress(fromAddress);
        if (!isValidAddress(from) || keyPair == null) {
            return Result.error(context.getString(R.string.error_signing_key_unavailable));
        }
        return sendTransferInternal(context, from, keyPair, toAddress, amountText, memo);
    }

    public static Result sendTransferFromEscrow(Context context, String fromAddress,
                                                ECKeyPair keyPair, String toAddress,
                                                String amountText) {
        return sendTransferFromEscrow(context, fromAddress, keyPair, toAddress, amountText, "");
    }

    public static Result sendTransferFromEscrow(Context context, String fromAddress,
                                                ECKeyPair keyPair, String toAddress,
                                                String amountText, String memo) {
        if (context == null) {
            return Result.error("");
        }
        CryptoUtils.setupBouncyCastle();
        String from = normalizeAddress(fromAddress);
        if (!isValidAddress(from) || keyPair == null) {
            return Result.error(context.getString(R.string.error_signing_key_unavailable));
        }
        return sendTransferInternal(context, from, keyPair, toAddress, amountText, memo, false);
    }

    private static Result sendTransferInternal(Context context, String from, ECKeyPair keyPair,
                                               String toAddress, String amountText, String memo) {
        return sendTransferInternal(context, from, keyPair, toAddress, amountText, memo, true);
    }

    private static Result sendTransferInternal(Context context, String from, ECKeyPair keyPair,
                                               String toAddress, String amountText, String memo,
                                               boolean enforceBalance) {
        String to = normalizeAddress(toAddress);
        if (!isValidAddress(to)) {
            return Result.error(context.getString(R.string.error_invalid_recipient));
        }
        if (from.equals(to)) {
            return Result.error(context.getString(R.string.error_self_transfer));
        }

        String memoText = memo == null ? "" : memo.trim();
        if (memoText.length() > MAX_MEMO_LENGTH) {
            return Result.error(context.getString(R.string.transfer_memo_limit));
        }

        BigDecimal amount;
        try {
            String raw = amountText == null ? "" : amountText.trim().replace(',', '.');
            amount = new BigDecimal(raw).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        } catch (Throwable t) {
            return Result.error(context.getString(R.string.error_invalid_amount));
        }
        if (amount.signum() <= 0) {
            return Result.error(context.getString(R.string.error_amount_positive));
        }

        BigDecimal fee = calculateFee(amount);
        BigDecimal total = amount.add(fee);

        IrohNode node = IrohNode.getInstance();
        node.start(context.getApplicationContext());
        node.startDiscovery();
        if (!node.canSendTransfer(to)) {
            return Result.error(context.getString(R.string.error_recipient_not_found));
        }

        if (enforceBalance) {
            BlockStorage blockStorage = new BlockStorage(context);
            double chainBalance;
            if (blockStorage.isSpvMode()) {
                String localAddress = normalizeAddress(KeyManager.getOrCreateAddress(context));
                chainBalance = from.equals(localAddress)
                        ? KeyManager.getLocalBalance(context)
                        : EscrowManager.getTrackedBalance(context, from);
            } else {
                chainBalance = KeyManager.calculateBalanceFromChain(
                        context, blockStorage.getAllBlocks(), from);
            }
            double pendingOutgoing = pendingOutgoingTotal(context, from);
            if (total.doubleValue() > chainBalance - pendingOutgoing + 1e-9) {
                return Result.error(context.getString(R.string.error_insufficient_funds_fee));
            }
        }

        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        String payload;
        try {
            payload = buildPayload(id, from, to, amount, fee, timestamp, memoText, keyPair);
        } catch (Throwable t) {
            Log.w(TAG, "failed to sign transfer", t);
            return Result.error(context.getString(R.string.error_sign_transfer));
        }

        try {
            JSONObject tx = new JSONObject(payload);
            new Mempool(context).addTransaction(tx);
            node.broadcastBlock(TRANSFER_PREFIX + payload);
        } catch (Throwable t) {
            Log.w(TAG, "failed to queue transfer", t);
            return Result.error(context.getString(R.string.error_sign_transfer));
        }

        notifyBalanceChanged(context);
        return Result.success(context.getString(R.string.transfer_sent_format,
                Tokenomics.formatAmount(amount.doubleValue())));
    }

    /**
     * Технический конверт перевода ({@code {"type":"TRANSFER",...}}). Его нельзя
     * показывать в чате: он предназначен только для {@link #handleIncomingTransfer}.
     */
    public static boolean isTechnicalTransferPayload(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        if (trimmed.contains("\"sigR\"")) {
            return true;
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            return TYPE_TRANSFER.equalsIgnoreCase(obj.optString("type", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Баланс адреса с учётом ожидающих в mempool исходящих транзакций. */
    public static double getSpendableBalance(Context context, String address) {
        if (context == null) {
            return 0.0;
        }
        String target = normalizeAddress(address);
        BlockStorage blockStorage = new BlockStorage(context);
        double balance;
        if (blockStorage.isSpvMode()) {
            String localAddress = normalizeAddress(KeyManager.getOrCreateAddress(context));
            balance = target.equals(localAddress)
                    ? KeyManager.getLocalBalance(context)
                    : EscrowManager.getTrackedBalance(context, target);
        } else {
            balance = KeyManager.calculateBalanceFromChain(
                    context, blockStorage.getAllBlocks(), target);
        }
        double spendable = balance - pendingOutgoingTotal(context, target);
        return Math.max(0.0, spendable);
    }

    /**
     * Сжигает TRG: подписанный перевод на канонический {@link Tokenomics#BURN_ADDRESS},
     * монеты которого не контролируются никем. Транзакция уходит в mempool и
     * подтверждается блоком, как обычный перевод; запись TYPE_BURN появляется
     * в истории сразу.
     */
    public static Result burnTokens(Context context, String amountText, String memo) {
        if (context == null) {
            return Result.error("");
        }
        CryptoUtils.setupBouncyCastle();
        String from = normalizeAddress(KeyManager.getOrCreateAddress(context));
        ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(context);
        if (keyPair == null) {
            return Result.error(context.getString(R.string.error_signing_key_unavailable));
        }
        String memoText = memo == null ? "" : memo.trim();
        if (memoText.length() > MAX_MEMO_LENGTH) {
            return Result.error(context.getString(R.string.transfer_memo_limit));
        }
        BigDecimal amount;
        try {
            String raw = amountText == null ? "" : amountText.trim().replace(',', '.');
            amount = new BigDecimal(raw).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        } catch (Throwable t) {
            return Result.error(context.getString(R.string.error_invalid_amount));
        }
        if (amount.signum() <= 0) {
            return Result.error(context.getString(R.string.error_amount_positive));
        }
        if (amount.doubleValue() > getSpendableBalance(context, from) + 1e-9) {
            return Result.error(context.getString(R.string.error_insufficient_funds_fee));
        }
        String to = normalizeAddress(Tokenomics.BURN_ADDRESS);
        String id = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        String payload;
        try {
            payload = buildPayload(id, from, to, amount, BigDecimal.ZERO,
                    timestamp, memoText, keyPair);
        } catch (Throwable t) {
            Log.w(TAG, "failed to sign burn", t);
            return Result.error(context.getString(R.string.error_sign_transfer));
        }
        try {
            JSONObject tx = new JSONObject(payload);
            new Mempool(context).addTransaction(tx);
            IrohNode node = IrohNode.getInstance();
            node.start(context.getApplicationContext());
            node.sendTransferPayload(to, payload);
        } catch (Throwable t) {
            Log.w(TAG, "failed to queue burn", t);
            return Result.error(context.getString(R.string.error_sign_transfer));
        }
        TransactionHistory.addTransaction(context, TransactionHistory.TYPE_BURN,
                -amount.doubleValue(), id, memoText);
        notifyBalanceChanged(context);
        return Result.success(context.getString(R.string.transfer_sent_format,
                Tokenomics.formatAmount(amount.doubleValue())));
    }

    private static double pendingOutgoingTotal(Context context, String address) {
        String from = normalizeAddress(address);
        Set<String> confirmed = collectTransactionIds(
                new BlockStorage(context).getAllBlocks());
        double total = 0.0;
        for (JSONObject tx : new Mempool(context).getPendingTransactions()) {
            if (!from.equals(normalizeAddress(tx.optString("from", "")))) {
                continue;
            }
            String id = tx.optString("id", "").trim();
            if (!id.isEmpty() && confirmed.contains(id)) {
                continue;
            }
            try {
                BigDecimal amount = new BigDecimal(tx.optString("amount", "0"));
                BigDecimal fee = new BigDecimal(tx.optString("fee", "0"));
                total += amount.add(fee).doubleValue();
            } catch (Throwable ignored) {
            }
        }
        return total;
    }

    /** Сериализует список транзакций в поле data блока. */
    public static String packTransactions(List<JSONObject> transactions) {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        if (transactions != null) {
            for (JSONObject tx : transactions) {
                if (tx != null) {
                    array.put(tx);
                }
            }
        }
        try {
            root.put("transactions", array);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    /** Извлекает транзакции из data блока. Старые блоки без транзакций дают пустой список. */
    public static List<JSONObject> extractTransactions(String data) {
        List<JSONObject> result = new ArrayList<>();
        if (data == null || data.trim().isEmpty()) {
            return result;
        }
        String value = data.trim();
        try {
            JSONArray array;
            if (value.startsWith("[")) {
                array = new JSONArray(value);
            } else if (value.startsWith("{")) {
                JSONObject root = new JSONObject(value);
                array = root.optJSONArray("transactions");
                if (array == null && TYPE_TRANSFER.equals(root.optString("type", ""))) {
                    result.add(root);
                    return result;
                }
            } else {
                return result;
            }
            if (array == null) {
                return result;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject tx = array.optJSONObject(i);
                if (tx != null) {
                    result.add(tx);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to parse block transactions", t);
        }
        return result;
    }

    public static boolean isValidTransaction(JSONObject tx) {
        if (tx == null) {
            return false;
        }
        if (!TYPE_TRANSFER.equals(tx.optString("type", ""))) {
            return false;
        }
        CryptoUtils.setupBouncyCastle();
        try {
            String id = tx.optString("id", "").trim();
            String from = normalizeAddress(tx.optString("from", ""));
            String to = normalizeAddress(tx.optString("to", ""));
            if (id.isEmpty() || !isValidAddress(from) || !isValidAddress(to)) {
                return false;
            }
            BigDecimal amount = new BigDecimal(tx.optString("amount", "0"));
            BigDecimal fee = new BigDecimal(tx.optString("fee", "0"));
            long timestamp = tx.optLong("timestamp", 0L);
            String memo = tx.optString("memo", "");
            if (amount.signum() <= 0 || timestamp <= 0L) {
                return false;
            }
            if (memo.length() > MAX_MEMO_LENGTH) {
                return false;
            }
            boolean isBurn = Tokenomics.BURN_ADDRESS.equals(to);
            if (fee.compareTo(calculateFee(amount)) != 0
                    && !(isBurn && fee.signum() == 0)) {
                return false;
            }
            String signingMessage = signingMessage(
                    id, from, to, amount, fee, timestamp, memo);
            return verifySignature(from, signingMessage, tx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Проверяет, что все транзакции блока валидны и покрыты балансом на момент включения. */
    public static boolean validateBlockData(Context context, List<String> chain, String data) {
        return validateBlockData(context, chain, data, "");
    }

    /** Проверяет транзакции блока с учётом адреса валидатора, создавшего блок. */
    public static boolean validateBlockData(Context context, List<String> chain, String data,
                                            String validator) {
        List<JSONObject> transactions = extractTransactions(data);
        if (transactions.isEmpty()) {
            return true;
        }
        java.util.Map<String, Double> balances =
                KeyManager.calculateBalancesFromChain(context, chain);
        Set<String> seen = collectTransactionIds(chain);
        for (JSONObject tx : transactions) {
            if (!isValidTransaction(tx)) {
                return false;
            }
            String id = tx.optString("id", "");
            if (!seen.add(id)) {
                return false;
            }
            try {
                String from = normalizeAddress(tx.optString("from", ""));
                String to = normalizeAddress(tx.optString("to", ""));
                double amount = new BigDecimal(tx.optString("amount", "0")).doubleValue();
                double fee = new BigDecimal(tx.optString("fee", "0")).doubleValue();
                double available = balances.containsKey(from) ? balances.get(from) : 0.0;
                if (available + 1e-9 < amount + fee) {
                    return false;
                }
                balances.put(from, available - amount - fee);
                double recipient = balances.containsKey(to) ? balances.get(to) : 0.0;
                balances.put(to, recipient + amount);
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    /** Отбирает до max валидных транзакций для следующего блока. */
    public static List<JSONObject> selectValidTransactions(Context context, List<String> chain,
                                                           List<JSONObject> pending, int max) {
        List<JSONObject> result = new ArrayList<>();
        if (pending == null || pending.isEmpty() || max <= 0) {
            return result;
        }
        java.util.Map<String, Double> balances;
        if (new BlockStorage(context).isSpvMode()) {
            balances = new java.util.HashMap<>();
            String local = normalizeAddress(KeyManager.getOrCreateAddress(context));
            balances.put(local, KeyManager.getLocalBalance(context));
        } else {
            balances = KeyManager.calculateBalancesFromChain(context, chain);
        }
        Set<String> seen = collectTransactionIds(chain);
        for (JSONObject tx : pending) {
            if (result.size() >= max) {
                break;
            }
            if (!isValidTransaction(tx)) {
                continue;
            }
            String id = tx.optString("id", "");
            if (!seen.add(id)) {
                continue;
            }
            try {
                String from = normalizeAddress(tx.optString("from", ""));
                String to = normalizeAddress(tx.optString("to", ""));
                double amount = new BigDecimal(tx.optString("amount", "0")).doubleValue();
                double fee = new BigDecimal(tx.optString("fee", "0")).doubleValue();
                double available = balances.containsKey(from) ? balances.get(from) : 0.0;
                if (available + 1e-9 < amount + fee) {
                    continue;
                }
                balances.put(from, available - amount - fee);
                double recipient = balances.containsKey(to) ? balances.get(to) : 0.0;
                balances.put(to, recipient + amount);
                result.add(tx);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /** Применяет подтверждённые транзакции к истории/пулу. Вызывается после сохранения блока. */
    public static void onBlockConfirmed(Context context, String blockJson) {
        if (context == null || TextUtils.isEmpty(blockJson)) {
            return;
        }
        try {
            JSONObject block = new JSONObject(blockJson);
            List<JSONObject> transactions =
                    extractTransactions(block.optString("data", ""));
            if (transactions.isEmpty()) {
                return;
            }
            Mempool mempool = new Mempool(context);
            List<String> includedIds = new ArrayList<>();
            for (JSONObject tx : transactions) {
                String id = tx.optString("id", "").trim();
                if (!id.isEmpty()) {
                    includedIds.add(id);
                }
            }
            mempool.removeTransactions(includedIds);
            EscrowManager.applyBlockTransactions(context, transactions);

            String local = normalizeAddress(KeyManager.getOrCreateAddress(context));
            boolean changed = false;
            for (JSONObject tx : transactions) {
                if (!isValidTransaction(tx)) {
                    continue;
                }
                String id = tx.optString("id", "").trim();
                if (id.isEmpty() || !markProcessed(context, id)) {
                    continue;
                }
                String from = normalizeAddress(tx.optString("from", ""));
                String to = normalizeAddress(tx.optString("to", ""));
                BigDecimal amount = new BigDecimal(tx.optString("amount", "0"));
                BigDecimal fee = new BigDecimal(tx.optString("fee", "0"));
                String memo = tx.optString("memo", "");
                boolean isBurn = Tokenomics.BURN_ADDRESS.equals(to);
                if (from.equals(local)) {
                    // Сжигания фиксируются в истории в момент создания транзакции.
                    if (!isBurn) {
                        TransactionHistory.addTransaction(context, TransactionHistory.TYPE_SEND,
                                -amount.doubleValue(), id, memo);
                        if (fee.signum() > 0) {
                            TransactionHistory.addTransaction(
                                    context, TransactionHistory.TYPE_BURN,
                                    -fee.doubleValue(), id + ":fee");
                        }
                    }
                    changed = true;
                }
                if (to.equals(local)) {
                    TransactionHistory.addTransaction(context, TransactionHistory.TYPE_RECEIVE,
                            amount.doubleValue(), id, memo);
                    changed = true;
                }
            }
            if (changed) {
                notifyBalanceChanged(context);
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to apply confirmed block", t);
        }
    }

    private static Set<String> collectTransactionIds(List<String> chain) {
        Set<String> ids = new HashSet<>();
        if (chain == null) {
            return ids;
        }
        for (String block : chain) {
            try {
                JSONObject obj = new JSONObject(block);
                for (JSONObject tx : extractTransactions(obj.optString("data", ""))) {
                    String id = tx.optString("id", "").trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return ids;
    }

    /** Обрабатывает входящее сообщение TRANSFER и кладёт валидную транзакцию в mempool. */
    public static void handleIncomingTransfer(Context context, String fromEndpoint, String payload) {
        if (context == null || TextUtils.isEmpty(payload)) {
            return;
        }
        try {
            CryptoUtils.setupBouncyCastle();
            JSONObject obj = new JSONObject(payload);
            if (!isValidTransaction(obj)) {
                Log.w(TAG, "transfer rejected: invalid transaction, endpoint=" + fromEndpoint);
                return;
            }
            new Mempool(context).addTransaction(obj);
            Log.i(TAG, "queued transfer " + obj.optString("id", "")
                    + " from " + obj.optString("from", "")
                    + " to " + obj.optString("to", ""));
        } catch (Throwable t) {
            Log.w(TAG, "invalid incoming transfer", t);
        }
    }

    private static String buildPayload(String id, String from, String to, BigDecimal amount,
                                       BigDecimal fee, long timestamp, String memo,
                                       ECKeyPair keyPair)
            throws Exception {
        String signingMessage = signingMessage(id, from, to, amount, fee, timestamp, memo);
        Sign.SignatureData signature = Sign.signPrefixedMessage(
                signingMessage.getBytes(StandardCharsets.UTF_8), keyPair);

        JSONObject obj = new JSONObject();
        obj.put("type", "TRANSFER");
        obj.put("id", id);
        obj.put("from", from);
        obj.put("to", to);
        obj.put("amount", amount.toPlainString());
        obj.put("fee", fee.toPlainString());
        obj.put("timestamp", timestamp);
        obj.put("memo", memo == null ? "" : memo);
        obj.put("sigR", Numeric.toHexStringNoPrefixZeroPadded(
                new BigInteger(1, signature.getR()), 64));
        obj.put("sigS", Numeric.toHexStringNoPrefixZeroPadded(
                new BigInteger(1, signature.getS()), 64));
        obj.put("sigV", signature.getV()[0] & 0xFF);
        return obj.toString();
    }

    private static String signingMessage(String id, String from, String to,
                                         BigDecimal amount, BigDecimal fee, long timestamp,
                                         String memo) {
        String message = "TRANSFER|" + id + "|" + from + "|" + to + "|"
                + amount.toPlainString() + "|" + fee.toPlainString() + "|" + timestamp;
        return memo == null || memo.isEmpty() ? message : message + "|" + memo;
    }

    private static boolean verifySignature(String expectedAddress, String signingMessage,
                                           JSONObject obj) {
        try {
            String rHex = obj.optString("sigR", "");
            String sHex = obj.optString("sigS", "");
            int v = obj.optInt("sigV", -1);
            if (rHex.isEmpty() || sHex.isEmpty() || v < 0) {
                return false;
            }
            Sign.SignatureData signature = new Sign.SignatureData(
                    (byte) v, hexTo32Bytes(rHex), hexTo32Bytes(sHex));
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(
                    signingMessage.getBytes(StandardCharsets.UTF_8), signature);
            String recovered = normalizeAddress("0x" + Keys.getAddress(publicKey));
            return recovered.equals(expectedAddress);
        } catch (Throwable t) {
            Log.w(TAG, "signature verification failed", t);
            return false;
        }
    }

    private static byte[] hexTo32Bytes(String hex) {
        String value = hex == null ? "" : hex.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        while (value.length() < 64) {
            value = "0" + value;
        }
        if (value.length() > 64) {
            value = value.substring(value.length() - 64);
        }
        return Numeric.hexStringToByteArray(value);
    }

    private static boolean markProcessed(Context context, String id) {
        synchronized (PROCESSED_LOCK) {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> saved = prefs.getStringSet(KEY_PROCESSED_IDS, null);
            Set<String> ids = saved == null ? new HashSet<>() : new HashSet<>(saved);
            if (!ids.add(id)) {
                return false;
            }
            prefs.edit().putStringSet(KEY_PROCESSED_IDS, ids).apply();
            return true;
        }
    }

    private static void notifyBalanceChanged(Context context) {
        try {
            LocalBroadcastManager.getInstance(context.getApplicationContext())
                    .sendBroadcast(new Intent(ACTION_BALANCE_CHANGED));
        } catch (Throwable ignored) {
        }
    }
}
