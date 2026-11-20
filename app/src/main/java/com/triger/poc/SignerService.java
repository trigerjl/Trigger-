package com.triger.poc;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;
import org.web3j.crypto.ECKeyPair;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class SignerService extends Service {

    private static final String TAG = "TrigerService";
    private static final String CHANNEL_ID = "triger_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_BLOCK_SIGNED = "TRIGER_BLOCK_SIGNED";
    public static final String KEY_MINING_ACTIVE = "mining_active";
    public static final String KEY_MINING_START_TIME = "mining_start_time";
    public static final String KEY_LAST_BLOCK_TIME = "last_block_time_ms";
    public static final String KEY_NEXT_BLOCK_AT = "next_block_at_ms";
    private static final int MINING_INTERVAL_SECONDS = 300;
    private static final int MAX_RANDOM_DELAY_SECONDS = 30;
    private static final long BLOCK_RECENT_WINDOW_MS = 30_000L;
    private static final int RETRY_DELAY_MIN_SECONDS = 15;
    private static final int RETRY_DELAY_MAX_SECONDS = 30;
    private static final long NETWORK_SCAN_INTERVAL_SEC = 120L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final String FIELD_VALIDATOR_DEVICE_ID = "validator_device_id";
    private static final String FIELD_VALIDATOR_SIM_HASH = "validator_sim_hash";

    /** Запоминает время последнего блока, полученного от любого узла сети. */
    public static void recordBlockReceived(Context context) {
        if (context == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_BLOCK_TIME, System.currentTimeMillis())
                .apply();
    }

    /** Время последнего блока (локального или полученного от пира), мс. */
    public static long getLastBlockTime(Context context) {
        if (context == null) {
            return 0L;
        }
        return context.getApplicationContext()
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_BLOCK_TIME, 0L);
    }

    public static void stopMining(Context context) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MINING_ACTIVE, false)
                .putLong(KEY_MINING_START_TIME, 0L)
                .remove(KEY_NEXT_BLOCK_AT)
                .apply();
        try {
            context.stopService(new Intent(context, SignerService.class));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to stop mining service: " + t);
        }
    }

    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService networkScheduler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final P2PNode p2pNode = P2PNode.getInstance();
    private final IrohNode irohNode = IrohNode.getInstance();
    private final IrohNode.OnBlockListener irohBlockListener = (from, blockJson) -> {
        Log.d(TAG, "Iroh: block received from " + from);
        try {
            org.json.JSONObject obj = new org.json.JSONObject(blockJson);
            int idx = obj.optInt("index", -1);
            Log.d(TAG, "Stability: block received, index = " + idx + ", from = " + from);
        } catch (Exception ignored) {
        }
        handleIncomingIrohBlock(blockJson);
    };
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private boolean observerMode;

    @Override
    public void onCreate() {
        super.onCreate();
        CryptoUtils.setupBouncyCastle();
        createNotificationChannel();
        acquireLocks();
        registerNetworkCallback();
        Log.d(TAG, "Locks acquired");
        Log.d(TAG, "Сервис создан");
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handleNetworkAvailable();
                }

                @Override
                public void onLost(Network network) {
                    irohNode.notifyNetworkChange();
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                  NetworkCapabilities capabilities) {
                    if (capabilities != null
                            && capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        irohNode.notifyNetworkChange();
                    }
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable t) {
            Log.w(TAG, "Network callback registration failed: " + t);
        }
    }

    private void handleNetworkAvailable() {
        irohNode.notifyNetworkChange();
        if (NetworkType.WIFI.equals(NetworkType.current(this))
                && p2pNode.getConnectedPeers() == 0) {
            p2pNode.scanLocalNetwork();
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (callback == null) {
            return;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Network callback unregistration failed: " + t);
        }
    }

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Triger:WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.d(TAG, "WifiLock acquired (HIGH_PERF)");
            }
        } catch (Exception e) {
            Log.w(TAG, "WifiLock failed: " + e.getMessage());
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Triger:PartialWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
                Log.d(TAG, "PartialWakeLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock failed: " + e.getMessage());
        }
    }

    private void renewWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock renew failed: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                Log.d(TAG, "WifiLock released");
            }
        } catch (Exception ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean phoneStateGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasSim = phoneStateGranted && SimIdentity.hasRealSim(this);
        observerMode = !hasSim;
        Log.d("Signer", "onStartCommand: startId=" + startId
                + ", phoneStateGranted=" + phoneStateGranted
                + ", hasSim=" + hasSim
                + ", observerMode=" + observerMode);
        Log.i(TAG, observerMode
                ? "Observer mode enabled: no real SIM, mining disabled"
                : "Mining mode enabled");

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(observerMode
                        ? R.string.observer_mode_notification
                        : R.string.notification_mining_title))
                .setContentText(getString(observerMode
                        ? R.string.observer_mode_desc
                        : R.string.notification_mining_signing))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        if (observerMode) {
            getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_MINING_ACTIVE, false)
                    .putLong(KEY_MINING_START_TIME, 0L)
                    .remove(KEY_NEXT_BLOCK_AT)
                    .apply();
        } else {
            getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_MINING_ACTIVE, true)
                    .apply();
        }

        // Этот foreground-сервис уже держит сокет и процесс, в том числе в
        // режиме наблюдателя, поэтому отдельный ChatService не нужен.
        stopService(new Intent(this, ChatService.class));

        // P2PNode (локальный скан) стартует первым: прямой контакт через mDNS/P2P
        // должен устанавливаться раньше Relay.
        p2pNode.setSelfDeviceId(KeyManager.getOrCreateDeviceId(this));
        p2pNode.setSelfStrongBoxBacked(DeviceIdentity.isStrongBoxBacked(this));
        ReputationManager.applyHardwareStatus(this);
        NetworkFileLogger.init(getApplicationContext());
        p2pNode.setAppContext(this);
        p2pNode.startNode(8080);
        p2pNode.setBlockStorage(new BlockStorage(this));

        // Майнинг и обмен блоками идут через Iroh.
        irohNode.start(this);
        irohNode.startDiscovery();
        irohNode.startHeartbeat();
        irohNode.addOnBlockListener(irohBlockListener);
        irohNode.requestChainFromAllPeers();

        p2pNode.broadcastBlock("GET_LAST_BLOCK");
        Log.d(TAG, "Broadcast GET_LAST_BLOCK to peers");

        if (!observerMode && (scheduler == null || scheduler.isShutdown())) {
            Log.d(TAG, "Mining interval = " + MINING_INTERVAL_SECONDS
                    + "s + random 0-" + MAX_RANDOM_DELAY_SECONDS + "s");
            // Фиксируем начало сессии майнинга только для новой сессии. При
            // перезапуске сервиса/приложения сохраняем исходное время старта.
            android.content.SharedPreferences miningPrefs =
                    getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
            if (miningPrefs.getLong(KEY_MINING_START_TIME, 0L) <= 0L) {
                miningPrefs.edit()
                        .putLong(KEY_MINING_START_TIME, System.currentTimeMillis())
                        .apply();
            }
            initializeLastBlockTime(miningPrefs);
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduleNextMining(nextBlockDelayMs());
            Log.d("Signer", "Mining timer started, next block in "
                    + (MINING_INTERVAL_SECONDS + MAX_RANDOM_DELAY_SECONDS / 2)
                    + "s (approx)");
        } else if (observerMode) {
            Log.d("Signer", "Mining timer not started: observer mode (no real SIM)");
        } else {
            Log.d("Signer", "Mining timer already running");
        }

        startNetworkScanLoop();
        return START_STICKY;
    }

    private void startNetworkScanLoop() {
        if (networkScheduler != null && !networkScheduler.isShutdown()) {
            return;
        }
        networkScheduler = Executors.newSingleThreadScheduledExecutor();
        networkScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (NetworkType.WIFI.equals(NetworkType.current(this))
                        && p2pNode.getConnectedPeers() == 0) {
                    p2pNode.scanLocalNetwork();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Periodic P2P scan failed: " + t);
            }
        }, 10L, NETWORK_SCAN_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void initializeLastBlockTime(android.content.SharedPreferences prefs) {
        if (prefs.getLong(KEY_LAST_BLOCK_TIME, 0L) > 0L) {
            return;
        }
        BlockStorage storage = new BlockStorage(this);
        int lastIndex = storage.getLastBlockIndex();
        if (lastIndex < 0) {
            return;
        }
        String last = storage.getBlock(lastIndex);
        if (last == null) {
            return;
        }
        try {
            long timestamp = new JSONObject(last).optLong("timestamp", 0L);
            if (timestamp > 0L) {
                prefs.edit().putLong(KEY_LAST_BLOCK_TIME, timestamp).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private long nextBlockDelayMs() {
        return randomDelayMs(0, MAX_RANDOM_DELAY_SECONDS)
                + MINING_INTERVAL_SECONDS * 1000L;
    }

    private long randomDelayMs(int minSeconds, int maxSeconds) {
        int min = Math.max(0, minSeconds);
        int max = Math.max(min, maxSeconds);
        int seconds = min + ThreadLocalRandom.current().nextInt(max - min + 1);
        return seconds * 1000L;
    }

    private void scheduleNextMining(long delayMs) {
        ScheduledExecutorService current = scheduler;
        if (current == null || current.isShutdown()) {
            return;
        }
        long nextAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_BLOCK_AT, nextAt)
                .apply();
        try {
            current.schedule(this::runMiningTick, Math.max(0L, delayMs),
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to schedule mining tick: " + t);
        }
    }

    private void runMiningTick() {
        renewWakeLock();
        if (observerMode) {
            Log.d(TAG, "Observer mode: block creation skipped");
            return;
        }
        boolean keepMining = true;
        try {
            long lastBlockTime = getLastBlockTime(this);
            long sinceLastBlockMs = System.currentTimeMillis() - lastBlockTime;
            if (lastBlockTime > 0L && sinceLastBlockMs >= 0L
                    && sinceLastBlockMs < BLOCK_RECENT_WINDOW_MS) {
                long retryMs = randomDelayMs(RETRY_DELAY_MIN_SECONDS,
                        RETRY_DELAY_MAX_SECONDS);
                Log.d(TAG, "Another block was created " + sinceLastBlockMs
                        + " ms ago, retry in " + (retryMs / 1000L) + "s");
                scheduleNextMining(retryMs);
                return;
            }
            keepMining = attemptBlockCreation();
        } catch (Throwable t) {
            Log.e(TAG, "Mining tick failed", t);
        }
        if (keepMining) {
            scheduleNextMining(nextBlockDelayMs());
        }
    }

    private boolean attemptBlockCreation() {
        BlockStorage storage = new BlockStorage(this);
        int index = storage.getLastBlockIndex() + 1;
        List<String> chain = storage.getAllBlocks();
        double reward = Tokenomics.getBlockReward(index);
        boolean canMint = Tokenomics.canMintReward(chain, reward);
        Log.d("Signer", "Mining check: blockIndex=" + index + ", reward="
                + Tokenomics.formatAmount(reward) + ", canMintReward=" + canMint);
        if (!canMint) {
            Log.w(TAG, "Emission limit reached: block " + index + " reward "
                    + Tokenomics.formatAmount(reward) + " TRG would exceed "
                    + Tokenomics.formatAmount(Tokenomics.MAX_SUPPLY) + " TRG");
            stopMiningWithNotification(R.string.emission_limit_reached);
            return false;
        }

        String address = KeyManager.getOrCreateAddress(this);
        Mempool mempool = new Mempool(this);
        List<JSONObject> included = TransferManager.selectValidTransactions(
                this, chain, mempool.getPendingTransactions(), 10);
        String data = TransferManager.packTransactions(included);
        Log.d(TAG, "Independent mining: creating block index=" + index
                + ", transactions=" + included.size());
        String blockJson = buildAndSaveBlock(address, data);
        if (blockJson != null) {
            recordBlockReceived(this);
            p2pNode.broadcastBlock(blockJson);
            irohNode.broadcastBlock(blockJson);
            Intent intent = new Intent(ACTION_BLOCK_SIGNED);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
        return true;
    }

    /**
     * Блок, пришедший от соседа по Iroh. Запись выполняется один раз:
     * повторные блоки с тем же index игнорируются.
     */
    private void handleIncomingIrohBlock(String blockJson) {
        BlockStorage storage = new BlockStorage(this);
        boolean spv = storage.isSpvMode();
        try {
            JSONObject obj = new JSONObject(blockJson);
            int index = obj.optInt("index", -1);
            String prevHash = obj.optString("prevHash", "");
            if (index < 0 || storage.hasBlock(index)) {
                return;
            }
            if (index > 0 && !prevHash.equals(storage.getLastBlockHash())) {
                Log.w(TAG, "Iroh: block " + index + " has unknown prevHash, ignored");
                return;
            }
            String blockHash = obj.optString("hash", "");
            if (!spv && !TransferManager.validateBlockData(
                    this, storage.getAllBlocks(), obj.optString("data", ""),
                    obj.optString("validator", ""))) {
                Log.w(TAG, "Block rejected: invalid transaction in block " + index);
                return;
            }
            if (!storage.saveBlock(blockJson)) {
                Log.w(TAG, "Iroh: duplicate block ignored, index=" + index + ", hash=" + blockHash);
                return;
            }
            recordBlockReceived(this);
            TransferManager.onBlockConfirmed(this, blockJson);
            if (spv) {
                KeyManager.applyBlockToLocalBalance(
                        this, blockJson, KeyManager.getOrCreateAddress(this));
            } else {
                KeyManager.recalculateBalanceFromChain(
                        this, storage, KeyManager.getOrCreateAddress(this));
            }
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(ACTION_BLOCK_SIGNED));
            Log.d(TAG, "Iroh: saved block " + index + " from peer");
        } catch (Exception e) {
            Log.e(TAG, "Iroh: invalid block received", e);
            try {
                Log.e(TAG, "Stability: error = " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    private String buildAndSaveBlock(String validator, String data) {
        if (observerMode) {
            Log.d(TAG, "Observer mode: buildAndSaveBlock skipped");
            return null;
        }
        BlockStorage storage = new BlockStorage(this);
        int index = storage.getLastBlockIndex() + 1;
        List<String> chain = storage.getAllBlocks();
        double reward = Tokenomics.getBlockReward(index);
        if (!Tokenomics.canMintReward(chain, reward)) {
            Log.w(TAG, "Emission limit reached: block " + index + " reward "
                    + Tokenomics.formatAmount(reward) + " TRG would exceed "
                    + Tokenomics.formatAmount(Tokenomics.MAX_SUPPLY) + " TRG");
            stopMiningWithNotification(R.string.emission_limit_reached);
            return null;
        }
        String prevHash = (index == 0) ? "0x0" : storage.getLastBlockHash();
        long timestamp = System.currentTimeMillis();
        String validatorDeviceId = DeviceIdentity.getOrCreateDeviceId(this);
        String validatorSimHash = "";
        try {
            validatorSimHash = SimIdentity.getOrCreateSimHash(this);
        } catch (Throwable ignored) {
        }
        String hash = BlockStorage.computeBlockHash(index, prevHash, timestamp, validator,
                validatorDeviceId, validatorSimHash, data);
        ECKeyPair keyPair = KeyManager.getOrCreateKeyPair(this);
        if (keyPair == null) {
            Log.e(TAG, "Block not created: signing key unavailable");
            return null;
        }
        String signature;
        try {
            signature = BlockStorage.signBlockHash(hash, keyPair);
        } catch (Exception e) {
            Log.e(TAG, "Block not created: failed to sign block hash", e);
            return null;
        }

        JSONObject obj = new JSONObject();
        try {
            obj.put("index", index);
            obj.put("prevHash", prevHash);
            obj.put("timestamp", timestamp);
            obj.put("validator", validator);
            obj.put(FIELD_VALIDATOR_DEVICE_ID, validatorDeviceId);
            if (validatorSimHash != null && !validatorSimHash.trim().isEmpty()) {
                obj.put(FIELD_VALIDATOR_SIM_HASH, validatorSimHash.trim());
            }
            obj.put("data", data);
            obj.put("hash", hash);
            obj.put(BlockStorage.FIELD_SIGNATURE, signature);
        } catch (Exception ignored) {
        }

        String json = obj.toString();
        if (!storage.saveBlock(json)) {
            Log.e(TAG, "Block not saved: duplicate index or hash");
            return null;
        }
        recordBlockReceived(this);
        if (validatorSimHash != null && !validatorSimHash.trim().isEmpty()) {
            ReputationManager.addReputation(this, validatorSimHash.trim(), 1);
        }
        double minted = reward;
        KeyManager.incrementTotalBlocksSigned(this);
        int minedBlocks = KeyManager.getTotalBlocksSigned(this);
        long miningStart = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_MINING_START_TIME, 0L);
        long miningTimeMs = miningStart > 0L
                ? Math.max(0L, System.currentTimeMillis() - miningStart) : 0L;
        for (CosmeticManager.Item item : CosmeticManager.checkUnlocks(
                this, minedBlocks, miningTimeMs)) {
            NotificationHelper.showCosmeticUnlockNotification(
                    this, getString(item.unlockNameRes));
        }
        if (minted > 0.0) {
            TransactionHistory.addTransaction(
                    this, TransactionHistory.TYPE_MINING_REWARD, minted, hash);
            NotificationHelper.showMiningRewardNotification(this, minted);
        }
        TransferManager.onBlockConfirmed(this, json);
        if (storage.isSpvMode()) {
            KeyManager.applyBlockToLocalBalance(this, json, validator);
        } else {
            KeyManager.recalculateBalanceFromChain(this, storage, validator);
        }
        try {
            long uptimeSec = (SystemClock.elapsedRealtime() / 1000L);
            Log.d(TAG, "Stability: block created, index = " + index + ", reward = " + minted + " TRG, uptime = " + uptimeSec);
        } catch (Exception ignored) {
        }
        Log.d(TAG, "Награда за блок: " + Tokenomics.formatAmount(minted)
                + " TRG (подписано блоков: " + KeyManager.getTotalBlocksSigned(this)
                + ", всего выпущено: "
                + Tokenomics.formatAmount(Tokenomics.getCachedTotalSupply(this))
                + " TRG)");
        return json;
    }

    private void stopMiningWithNotification(int messageRes) {
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MINING_ACTIVE, false)
                .putLong(KEY_MINING_START_TIME, 0L)
                .remove(KEY_NEXT_BLOCK_AT)
                .apply();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_mining_title))
                .setContentText(getString(messageRes))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setOngoing(false)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_mining_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_mining_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (networkScheduler != null) {
            networkScheduler.shutdownNow();
            networkScheduler = null;
        }
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_BLOCK_AT)
                .apply();
        unregisterNetworkCallback();
        irohNode.stopHeartbeat();
        irohNode.removeOnBlockListener(irohBlockListener);
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.d(TAG, "Сервис остановлен");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
