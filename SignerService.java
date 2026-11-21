package com.triger.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SignerService extends Service {

    private static final String TAG = "TrigerService";
    private static final String CHANNEL_ID = "triger_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_BLOCK_SIGNED = "TRIGER_BLOCK_SIGNED";
    private static final int NETWORK_BONUS = 5;
    private static final String FIELD_VALIDATOR_DEVICE_ID = "validator_device_id";

    private ScheduledExecutorService scheduler;
    private final P2PNode p2pNode = P2PNode.getInstance();
    private final IrohNode irohNode = IrohNode.getInstance();
    private final IrohNode.OnBlockListener irohBlockListener = (from, blockJson) -> {
        Log.d(TAG, "Iroh: block received from " + from);
        handleIncomingIrohBlock(blockJson);
    };
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        CryptoUtils.setupBouncyCastle();
        createNotificationChannel();
        acquireLocks();
        Log.d(TAG, "Locks acquired");
        Log.d(TAG, "Сервис создан");
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
                wakeLock.acquire();
                Log.d(TAG, "PartialWakeLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock failed: " + e.getMessage());
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
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Triger Mining")
                .setContentText("triger: подпись блоков")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Майнинг и обмен блоками идут через Iroh; P2PNode остаётся
        // только для обнаружения устройств в локальной сети.
        irohNode.start(this);
        irohNode.startDiscovery();
        irohNode.addOnBlockListener(irohBlockListener);

        p2pNode.setSelfDeviceId(KeyManager.getOrCreateDeviceId(this));
        p2pNode.startNode(8080);
        p2pNode.setBlockStorage(new BlockStorage(this));

        p2pNode.broadcastBlock("GET_LAST_BLOCK");
        Log.d(TAG, "Broadcast GET_LAST_BLOCK to peers");

        if (scheduler == null || scheduler.isShutdown()) {
            int intervalSeconds = SettingsActivity.getMiningIntervalSeconds(this);
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Проверка слота...");
                    simulateBlockSigning();
                }
            }, 0, intervalSeconds, TimeUnit.SECONDS);
            Log.d(TAG, "Интервал майнинга: " + intervalSeconds + " с");
        }

        return START_STICKY;
    }

    private void simulateBlockSigning() {
        ValidatorManager.ensureInitialized(this);
        BlockStorage storage = new BlockStorage(this);
        int index = storage.getLastBlockIndex() + 1;
        int count = ValidatorManager.getValidatorCount(this);
        int myIndex = ValidatorManager.getLocalValidatorIndex(this);
        boolean canMine = myIndex >= 0 && count > 0 && (index % count == myIndex);
        Log.d(TAG, "Round-robin: my index = " + myIndex + ", block index = "
                + index + ", can mine = " + canMine);
        if (!canMine) {
            if (myIndex < 0) {
                Log.d(TAG, "PoA: not a validator, skipping block creation");
            }
            return;
        }

        String address = KeyManager.getOrCreateAddress(this);
        Log.d(TAG, "PoA: my turn, creating block index=" + index);
        String blockJson = buildAndSaveBlock(address, "p2p_block");
        if (blockJson != null) {
            p2pNode.broadcastBlock(blockJson);
            irohNode.broadcastBlock(blockJson);
        }
        Intent intent = new Intent(ACTION_BLOCK_SIGNED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * Блок, пришедший от соседа по Iroh. Начисление бонуса и запись
     * выполняются один раз: повторные блоки с тем же index игнорируются.
     */
    private void handleIncomingIrohBlock(String blockJson) {
        BlockStorage storage = new BlockStorage(this);
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
            String validatorDeviceId = obj.optString(FIELD_VALIDATOR_DEVICE_ID, "");
            if (!ValidatorManager.isValidator(this, validatorDeviceId)) {
                Log.w(TAG, "Block rejected: validator not trusted");
                return;
            }
            storage.saveBlock(blockJson);
            KeyManager.addLocalBalance(this, NETWORK_BONUS);
            TransactionHistory.addTransaction(this,
                    TransactionHistory.TYPE_NETWORK_BONUS, NETWORK_BONUS);
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(ACTION_BLOCK_SIGNED));
            Log.d(TAG, "Iroh: saved block " + index + " from peer");
        } catch (Exception e) {
            Log.e(TAG, "Iroh: invalid block received", e);
        }
    }

    private String buildAndSaveBlock(String validator, String data) {
        BlockStorage storage = new BlockStorage(this);
        int index = storage.getLastBlockIndex() + 1;
        String prevHash = (index == 0) ? "0x0" : storage.getLastBlockHash();
        long timestamp = System.currentTimeMillis();
        String validatorDeviceId = DeviceIdentity.getOrCreateDeviceId(this);
        String hash = sha256Hex(index + prevHash + timestamp + validator
                + validatorDeviceId + data);

        JSONObject obj = new JSONObject();
        try {
            obj.put("index", index);
            obj.put("prevHash", prevHash);
            obj.put("timestamp", timestamp);
            obj.put("validator", validator);
            obj.put(FIELD_VALIDATOR_DEVICE_ID, validatorDeviceId);
            obj.put("data", data);
            obj.put("hash", hash);
        } catch (Exception ignored) {
        }

        String json = obj.toString();
        storage.saveBlock(json);
        double reward = Tokenomics.getBlockReward(this, index);
        double minted = Tokenomics.mint(this, reward);
        KeyManager.incrementTotalBlocksSigned(this);
        if (minted > 0.0) {
            KeyManager.addLocalBalance(this, minted);
            TransactionHistory.addTransaction(
                    this, TransactionHistory.TYPE_MINING_REWARD, minted);
        }
        Log.d(TAG, "Награда за блок: " + Tokenomics.formatAmount(minted)
                + " TRG (подписано блоков: " + KeyManager.getTotalBlocksSigned(this)
                + ", всего выпущено: " + Tokenomics.formatAmount(Tokenomics.getTotalSupply(this))
                + " TRG)");
        return json;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0x0";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Triger Mining", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Фоновый сервис подписи блоков Triger");
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
