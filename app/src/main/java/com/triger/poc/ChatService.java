package com.triger.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class ChatService extends Service {

    private static final String TAG = "TrigerChatService";
    private static final String CHANNEL_ID = "triger_chat_service_min";
    private static final String LEGACY_CHANNEL_ID = "triger_chat_service";
    private static final int NOTIFICATION_ID = 2;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9 * 60 * 1000L;

    private final IrohNode irohNode = IrohNode.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private boolean networkStarted;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile String lastNetworkType = "";

    private final Runnable wakeLockRenew = new Runnable() {
        @Override
        public void run() {
            renewWakeLock();
            handler.postDelayed(this, WAKE_LOCK_RENEW_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        CryptoUtils.setupBouncyCastle();
        createNotificationChannel();
        acquireLocks();
        registerNetworkCallback();
        handler.postDelayed(wakeLockRenew, WAKE_LOCK_RENEW_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        boolean miningActive = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SignerService.KEY_MINING_ACTIVE, false);
        if (miningActive) {
            Log.d(TAG, "Mining service is active, chat socket already kept alive");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!networkStarted) {
            networkStarted = true;
            NetworkFileLogger.init(getApplicationContext());
            irohNode.start(this);
            irohNode.startDiscovery();
            irohNode.startHeartbeat();
            Log.d(TAG, "Chat foreground service started");
        }
        return START_STICKY;
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            lastNetworkType = NetworkType.current(getApplicationContext());
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    handleChatNetworkChange(NetworkType.forNetwork(cm, network));
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                  NetworkCapabilities capabilities) {
                    if (capabilities != null
                            && capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        handleChatNetworkChange(NetworkType.fromCapabilities(capabilities));
                    }
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Throwable t) {
            Log.w(TAG, "Chat network callback registration failed: " + t);
        }
    }

    private void handleChatNetworkChange(String type) {
        if (!NetworkType.WIFI.equals(type) && !NetworkType.CELLULAR.equals(type)) {
            return;
        }
        String previous;
        synchronized (this) {
            previous = lastNetworkType;
            if (type.equals(previous)) {
                return;
            }
            lastNetworkType = type;
        }
        if (!NetworkType.WIFI.equals(previous) && !NetworkType.CELLULAR.equals(previous)) {
            return;
        }
        Log.d(TAG, "Chat network switched: " + previous + " -> " + type);
        reconnectChat();
    }

    private void reconnectChat() {
        boolean miningActive = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SignerService.KEY_MINING_ACTIVE, false);
        if (miningActive) {
            return;
        }
        try {
            irohNode.start(getApplicationContext());
            irohNode.startDiscovery();
            irohNode.notifyNetworkChange();
            Log.d(TAG, "Chat reconnect requested after network change");
        } catch (Throwable t) {
            Log.w(TAG, "Chat reconnect failed: " + t);
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (callback == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
            }
        } catch (Throwable ignored) {
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

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Triger:ChatWifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Chat WifiLock failed: " + e.getMessage());
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "Triger:ChatWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "Chat WakeLock failed: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_chat_waiting))
                .setContentText(getString(R.string.notification_chat_waiting_text))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setDefaults(0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_chat_service),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notification_channel_chat_service_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            try {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
            } catch (Throwable ignored) {
            }
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (networkStarted) {
            irohNode.stopHeartbeat();
        }
        unregisterNetworkCallback();
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.d(TAG, "Chat foreground service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
