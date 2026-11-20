package com.triger.poc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager2.widget.ViewPager2;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends AppCompatActivity {

    public interface Listener {
        default void onBalance(String text) {
        }

        default void onAddress(String address) {
        }

        default void onMiningStats(int count, String lastHash) {
        }

        default void onServiceState(boolean running) {
        }

        default void onNetworkStatus(String text) {
        }

        default void onNetworkError(String message) {
        }

        default void onTheme(boolean dark) {
        }

        default void onPeerId(String peerId) {
        }
    }

    private static final int REQUEST_NOTIFICATIONS_PERMISSION = 101;
    private static final int REQUEST_AUTO_UNLOCK = 4401;
    /**
     * Время, после которого возврат из фона требует повторную разблокировку по PIN.
     */
    private static final long AUTO_LOCK_TIMEOUT_MS = 120_000L;
    private static final String ACTION_BLOCK_SIGNED = "TRIGER_BLOCK_SIGNED";
    public static final String ACTION_RESET_COUNTER = "TRIGER_RESET_COUNTER";
    public static final String ACTION_ACCOUNT_RESTORED = "TRIGER_ACCOUNT_RESTORED";
    private static final long AUTO_DISCOVERY_FIRST_DELAY_MS = 4000;
    private static final int NAV_MARKET_POSITION = 2;
    /**
     * Период автосканирования локальной сети. Сканирование 254 адресов по TCP
     * — тяжёлая операция, поэтому ходим по сети вдвое реже (было 60 с).
     */
    private static final long AUTO_DISCOVERY_INTERVAL_MS = 120000;
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = SettingsActivity.PREFS_NAME;
    private static final String KEY_THEME = SettingsActivity.KEY_THEME;
    private static final String KEY_MINED_BLOCKS_BASELINE = "mined_blocks_baseline";

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private String address = "";
    private String currentBalanceText = "";
    private String currentNetworkStatus = "";
    private String lastBlockHash;

    private int minedBlocksCount = 0;
    private boolean serviceRunning = false;
    private boolean darkTheme = false;

    private final P2PNode p2pNode = P2PNode.getInstance();
    private final IrohNode irohNode = IrohNode.getInstance();
    private volatile String myPeerId = "";
    private final IrohNode.OnBlockListener irohBlockListener =
            (fromEndpointId, blockJson) -> handleIncomingBlock(blockJson);
    private final IrohNode.OnChainSyncListener irohChainSyncListener =
            this::handleIncomingChainSync;
    private final IrohNode.OnPeerConnectedListener irohPeerConnectedListener =
            peer -> irohNode.requestChainFromPeer(peer);
    private static final long CHAIN_SYNC_INTERVAL_MS = 30_000L;
    private static final long SYNC_INDICATOR_TIMEOUT_MS = 15_000L;
    private Handler chainSyncHandler;
    private boolean chainSyncLoopRunning;
    private final Runnable chainSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!chainSyncLoopRunning) {
                return;
            }
            irohNode.requestChainFromAllPeers();
            if (chainSyncHandler != null) {
                chainSyncHandler.postDelayed(this, CHAIN_SYNC_INTERVAL_MS);
            }
        }
    };
    private BottomNavigationView bottomNav;
    private ViewPager2 viewPager;
    private View syncIndicator;
    private final Runnable hideSyncIndicator = () -> {
        if (syncIndicator != null) {
            syncIndicator.setVisibility(View.GONE);
        }
    };
    private TextView tvNetworkType;
    private String currentNetworkType = "";
    private ConnectivityManager.NetworkCallback networkUiCallback;

    /**
     * Момент ухода приложения в фон (когда не видно ни одной активности, включая
     * PinActivity и PreviewActivity). Читается в {@link #onResume()} для автозащиты.
     */
    private static volatile long appBackgroundedAt = 0L;

    private int startedActivityCount = 0;
    private boolean awaitingAutoUnlock = false;

    private final Application.ActivityLifecycleCallbacks appLifecycleCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    startedActivityCount++;
                }

                @Override
                public void onActivityStopped(@NonNull Activity activity) {
                    startedActivityCount--;
                    if (startedActivityCount <= 0) {
                        startedActivityCount = 0;
                        appBackgroundedAt = SystemClock.elapsedRealtime();
                    }
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    if (!(activity instanceof MainActivity)) {
                        appBackgroundedAt = 0L;
                    }
                }

                @Override
                public void onActivityCreated(@NonNull Activity activity,
                                               @Nullable Bundle savedInstanceState) {
                }

                @Override
                public void onActivityPaused(@NonNull Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                        @NonNull Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                }
            };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_BLOCK_SIGNED.equals(action)) {
                BlockStorage storage = new BlockStorage(MainActivity.this);
                syncMinedBlocksCount(storage);
                lastBlockHash = storage.getLastBlockHash();
                updateSyncIndicator();
                pushMiningStats();
                updateBalance();
            } else if (TransferManager.ACTION_BALANCE_CHANGED.equals(action)) {
                updateBalance();
            } else if (ACTION_RESET_COUNTER.equals(action)) {
                resetMinedBlocksCounter();
            } else if (ACTION_ACCOUNT_RESTORED.equals(action)) {
                SignerService.stopMining(MainActivity.this);
                serviceRunning = false;
                pushServiceState();
                startBackgroundService();
                address = KeyManager.getOrCreateAddress(MainActivity.this);
                for (Listener listener : listeners) {
                    listener.onAddress(address);
                }
                updateBalance();
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageActivity.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean seedBackupPending = !prefs.getBoolean(
                OnboardingActivity.KEY_SEED_BACKUP_CONFIRMED, false);
        darkTheme = SettingsActivity.isDarkTheme(this);
        serviceRunning = prefs.getBoolean(SignerService.KEY_MINING_ACTIVE, false);
        setTheme(SettingsActivity.themeRes(this));

        super.onCreate(savedInstanceState);
        if (!prefs.getBoolean(LanguageActivity.KEY_LANGUAGE_SELECTED, false)) {
            String deviceLang = java.util.Locale.getDefault().getLanguage();
            String appLang = "ru".equalsIgnoreCase(deviceLang) ? "ru" : "en";
            prefs.edit()
                    .putString(LanguageActivity.KEY_APP_LANGUAGE, appLang)
                    .putBoolean(LanguageActivity.KEY_LANGUAGE_SELECTED, true)
                    .apply();
            LanguageActivity.applyLocale(this, appLang);
            recreate();
            return;
        }
        LanguageActivity.applySavedLocale(this);
        if (PinManager.isPinSet(this) && !PinManager.isUnlocked()) {
            startActivity(new Intent(MainActivity.this, PinActivity.class));
            finish();
            return;
        }
        if (!prefs.getBoolean(OnboardingActivity.KEY_ONBOARDING_COMPLETED, false)) {
            startActivity(new Intent(MainActivity.this, OnboardingActivity.class));
            finish();
            return;
        }

        CryptoUtils.setupBouncyCastle();
        getApplication().registerActivityLifecycleCallbacks(appLifecycleCallbacks);
        setContentView(R.layout.activity_main);
        if (seedBackupPending) {
            showSeedBackupReminder();
        }
        currentNetworkStatus = getString(R.string.network_searching_global);
        currentBalanceText = getString(R.string.amount_zero_aura);
        lastBlockHash = getString(R.string.placeholder_dash);

        setupNetworkTypeIndicator();
        bottomNav = findViewById(R.id.bottom_nav);
        viewPager = findViewById(R.id.view_pager);
        syncIndicator = findViewById(R.id.sync_indicator);
        viewPager.setAdapter(new MainPagerAdapter(this));
        viewPager.setOffscreenPageLimit(1);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                viewPager.setUserInputEnabled(position != NAV_MARKET_POSITION);
                int itemId = navItemForPosition(position);
                if (bottomNav.getSelectedItemId() != itemId) {
                    bottomNav.setSelectedItemId(itemId);
                }
                onTabSelected(position);
            }
        });

        bottomNav.setOnNavigationItemSelectedListener(item -> {
            int position = positionForNavItem(item.getItemId());
            if (position < 0) {
                return false;
            }
            if (viewPager.getCurrentItem() != position) {
                viewPager.setCurrentItem(position, true);
            }
            onTabSelected(position);
            return true;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_wallet);
        }
        viewPager.post(() -> {
            int current = viewPager.getCurrentItem();
            viewPager.setUserInputEnabled(current != NAV_MARKET_POSITION);
            int itemId = navItemForPosition(current);
            if (bottomNav.getSelectedItemId() != itemId) {
                bottomNav.setSelectedItemId(itemId);
            }
        });
        updateChatBadge();

        address = KeyManager.getOrCreateAddress(this);
        for (Listener listener : listeners) {
            listener.onAddress(address);
        }

        requestNotificationsPermissionIfNeeded();

        IntentFilter serviceFilter = new IntentFilter(ACTION_BLOCK_SIGNED);
        serviceFilter.addAction(TransferManager.ACTION_BALANCE_CHANGED);
        serviceFilter.addAction(ACTION_RESET_COUNTER);
        serviceFilter.addAction(ACTION_ACCOUNT_RESTORED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, serviceFilter);
        p2pNode.setSelfDeviceId(KeyManager.getOrCreateDeviceId(this));
        p2pNode.setSelfStrongBoxBacked(DeviceIdentity.isStrongBoxBacked(this));
        ReputationManager.applyHardwareStatus(this);
        NetworkFileLogger.init(getApplicationContext());
        Log.i(TAG, "Starting local P2P node on port 8080");
        p2pNode.startNode(8080);
        setupIrohListener();
        Log.i(TAG, "Requesting Iroh start from MainActivity.onCreate");
        irohNode.start(this);
        irohNode.startDiscovery();
        irohNode.addOnBlockListener(irohBlockListener);
        irohNode.addOnChainSyncListener(irohChainSyncListener);
        irohNode.addOnPeerConnectedListener(irohPeerConnectedListener);
        new Handler(Looper.getMainLooper()).postDelayed(
                irohNode::requestChainFromAllPeers, 3000L);
        startChainSyncLoop();
        BlockStorage blockStorage = new BlockStorage(this);
        p2pNode.setBlockStorage(blockStorage);
        if (blockStorage.getLastBlockIndex() >= 0) {
            lastBlockHash = blockStorage.getLastBlockHash();
        }
        if (!blockStorage.isSpvMode()) {
            KeyManager.recalculateBalanceFromChain(
                    this, blockStorage, KeyManager.getOrCreateAddress(this));
        }
        syncMinedBlocksCount(blockStorage);
        verifyChainIntegrity();
        p2pNode.setOnBlockReceivedListener((blockData, validatorAddress) -> handleIncomingBlock(blockData));
        p2pNode.setOnPeerCountChangedListener(count -> runOnUiThread(() -> {
            if (count > 0) {
                setNetworkStatus(getString(R.string.network_local_format, count));
            }
        }));
        p2pNode.setOnChainReceivedListener(blocks -> handleIncomingChain(blocks));
        // Локальный P2PNode (WebSocket-скан Wi-Fi) нужен только для первого контакта:
        // из HELLO узнаём Iroh Endpoint ID соседа и сразу подключаемся уже через Iroh.
        p2pNode.setOnPeerDiscoveredListener((endpointId, address, irohAddrs) -> {
            String peer = endpointId == null ? "" : endpointId.trim();
            if (peer.isEmpty() || peer.equals(irohNode.getEndpointId())) {
                return;
            }
            Log.d(TAG, "Local peer discovered: " + peer + " at " + address
                    + ", irohAddrs=" + irohAddrs);
            irohNode.rememberPeerFromHello(peer, irohAddrs);
            irohNode.connectToPeer(peer);
        });
        // Служебные логи локального сканера не показываем в UI — только в Logcat.
        p2pNode.setOnLogListener(message -> Log.d(TAG, "P2P: " + message));
        // Автопоиск в фоне: пользователю не нужно вручную нажимать «Найти узлы».
        startAutoDiscovery();
        if (!SimIdentity.hasRealSim(this)) {
            serviceRunning = false;
            prefs.edit()
                    .putBoolean(SignerService.KEY_MINING_ACTIVE, false)
                    .putLong(SignerService.KEY_MINING_START_TIME, 0L)
                    .apply();
            startObserverService();
        } else {
            resumeMiningServiceIfNeeded();
            if (!serviceRunning) {
                startChatService();
            }
        }

        updateSyncIndicator();
        updateBalance();
    }

    private void updateSyncIndicator() {
        if (syncIndicator == null) {
            return;
        }
        boolean chainEmpty = new BlockStorage(this).getBlockCount() == 0;
        syncIndicator.removeCallbacks(hideSyncIndicator);
        if (chainEmpty) {
            syncIndicator.setVisibility(View.VISIBLE);
            syncIndicator.postDelayed(hideSyncIndicator, SYNC_INDICATOR_TIMEOUT_MS);
        } else {
            syncIndicator.setVisibility(View.GONE);
        }
    }

    private void resumeMiningServiceIfNeeded() {
        if (!serviceRunning) {
            return;
        }
        Intent serviceIntent = new Intent(this, SignerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "Mining service resumed from saved state");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to resume mining service: " + t);
        }
    }

    private void startChatService() {
        try {
            Intent intent = new Intent(this, ChatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Log.i(TAG, "Chat foreground service requested");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start chat service: " + t);
        }
    }

    private void startObserverService() {
        Intent intent = new Intent(this, SignerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Log.i(TAG, "Observer foreground service requested (no SIM)");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start observer service: " + t);
        }
    }

    private void startBackgroundService() {
        if (SimIdentity.hasRealSim(this)) {
            startChatService();
        } else {
            startObserverService();
        }
    }

    private void stopChatService() {
        try {
            stopService(new Intent(this, ChatService.class));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to stop chat service: " + t);
        }
    }

    private void setupIrohListener() {
        irohNode.setListener(new IrohNode.Listener() {
            @Override
            public void onStarted(String endpointId) {
                p2pNode.setSelfIrohId(endpointId);
                p2pNode.setSelfIrohAddrs(irohNode.getSelfDirectAddresses());
                myPeerId = endpointId == null ? "" : endpointId;
                runOnUiThread(() -> {
                    dispatchPeerId(myPeerId);
                });
            }

            @Override
            public void onDirectAddressesChanged(List<String> addresses) {
                p2pNode.setSelfIrohAddrs(addresses);
            }

            @Override
            public void onLog(String msg) {
                Log.d(TAG, "Iroh: " + msg);
            }

            @Override
            public void onFailed(String message) {
                Log.e(TAG, "Iroh: ошибка — " + message);
            }
        });
    }

    private void setupNetworkTypeIndicator() {
        tvNetworkType = findViewById(R.id.tv_network_type);
        updateNetworkType(NetworkType.current(this), false);
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            ConnectivityManager.NetworkCallback callback =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            refreshNetworkType();
                            irohNode.notifyNetworkChange();
                        }

                        @Override
                        public void onLost(Network network) {
                            refreshNetworkType();
                            irohNode.notifyNetworkChange();
                        }

                        @Override
                        public void onCapabilitiesChanged(Network network,
                                                          NetworkCapabilities capabilities) {
                            refreshNetworkType();
                            if (capabilities != null
                                    && capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                                irohNode.notifyNetworkChange();
                            }
                        }
                    };
            cm.registerDefaultNetworkCallback(callback);
            networkUiCallback = callback;
        } catch (Throwable t) {
            Log.w(TAG, "Network type callback registration failed: " + t);
        }
    }

    private void refreshNetworkType() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String type = NetworkType.current(this);
        runOnUiThread(() -> updateNetworkType(type, true));
    }

    private void updateNetworkType(String type, boolean showToast) {
        if (tvNetworkType == null) {
            return;
        }
        String next = type == null || type.trim().isEmpty() ? NetworkType.NONE : type.trim();
        boolean changed = !currentNetworkType.isEmpty() && !currentNetworkType.equals(next);
        currentNetworkType = next;
        tvNetworkType.setText(NetworkType.displayName(this, next));
        if (showToast && changed && NetworkType.isConnected(next)) {
            Toast.makeText(this, getString(R.string.network_switched_format,
                    NetworkType.displayName(this, next)), Toast.LENGTH_SHORT).show();
        }
    }

    private void unregisterNetworkTypeIndicator() {
        ConnectivityManager.NetworkCallback callback = networkUiCallback;
        networkUiCallback = null;
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
            Log.w(TAG, "Network type callback unregistration failed: " + t);
        }
    }

    private int positionForNavItem(int itemId) {
        if (itemId == R.id.nav_mining) {
            return 1;
        }
        if (itemId == R.id.nav_market) {
            return 2;
        }
        if (itemId == R.id.nav_profile) {
            return 3;
        }
        if (itemId == R.id.nav_settings) {
            return 4;
        }
        if (itemId == R.id.nav_wallet) {
            return 0;
        }
        return -1;
    }

    private int navItemForPosition(int position) {
        if (position == 1) {
            return R.id.nav_mining;
        }
        if (position == 2) {
            return R.id.nav_market;
        }
        if (position == 3) {
            return R.id.nav_profile;
        }
        if (position == 4) {
            return R.id.nav_settings;
        }
        return R.id.nav_wallet;
    }

    private void onTabSelected(int position) {
        if (position == 3) {
            try {
                UnreadCounter.getInstance(this).clearAll();
                updateChatBadge();
            } catch (Throwable ignored) {
            }
        }
    }

    public void openWalletTab() {
        if (viewPager != null) {
            viewPager.setCurrentItem(0, true);
        }
    }

    public void openMiningTab() {
        if (viewPager != null) {
            viewPager.setCurrentItem(1, true);
        }
    }

    private void showSeedBackupReminder() {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.onboarding_seed_title)
                .setMessage(R.string.onboarding_seed_reminder)
                .setPositiveButton(R.string.onboarding_seed_open,
                        (dialog, which) -> startActivity(
                                new Intent(this, SeedBackupActivity.class)))
                .setNegativeButton(R.string.onboarding_seed_later, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean savedDark = SettingsActivity.isDarkTheme(this);
        if (savedDark != darkTheme) {
            darkTheme = savedDark;
            recreate();
            return;
        }
        maybeAutoLock();
        updateChatBadge();
        updateSyncIndicator();
        updateBalance();
        irohNode.requestChainFromAllPeers();
        EscrowManager.processDueDealsAsync(this);
        if (tvNetworkType != null) {
            updateNetworkType(NetworkType.current(this), false);
        }
    }

    /**
     * Автозащита: если приложение пробыло в фоне дольше {@link #AUTO_LOCK_TIMEOUT_MS},
     * при возврате требуем разблокировку через PinActivity (PIN или биометрия).
     * Открытые сверху PinActivity/PreviewActivity не дают счётчику упасть в ноль,
     * поэтому повторного запроса в этих случаях не возникает.
     */
    private void maybeAutoLock() {
        if (awaitingAutoUnlock || isFinishing() || isDestroyed()) {
            return;
        }
        long backgroundedAt = appBackgroundedAt;
        appBackgroundedAt = 0L;
        if (backgroundedAt <= 0L
                || SystemClock.elapsedRealtime() - backgroundedAt < AUTO_LOCK_TIMEOUT_MS
                || !PinManager.isPinSet(this)) {
            return;
        }
        PinManager.lock();
        awaitingAutoUnlock = true;
        Toast.makeText(this, R.string.app_locked_timeout, Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, PinActivity.class);
        intent.putExtra(PinActivity.EXTRA_VERIFY_ONLY, true);
        startActivityForResult(intent, REQUEST_AUTO_UNLOCK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AUTO_UNLOCK) {
            awaitingAutoUnlock = false;
            if (resultCode != RESULT_OK) {
                moveTaskToBack(true);
            }
        }
    }

    /**
     * Проверка целостности цепочки блоков после разблокировки. При ошибке предлагаем
     * пересинхронизацию с другими узлами сети.
     */
    private void verifyChainIntegrity() {
        new Thread(() -> {
            BlockStorage storage = new BlockStorage(MainActivity.this);
            boolean valid;
            try {
                valid = storage.isChainValid();
            } catch (Throwable t) {
                Log.w(TAG, "Chain integrity check failed", t);
                valid = false;
            }
            if (valid) {
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.chain_integrity_error)
                        .setMessage(R.string.chain_integrity_message)
                        .setPositiveButton(R.string.chain_resync,
                                (dialog, which) -> resyncChain())
                        .setNegativeButton(R.string.onboarding_seed_later, null)
                        .setCancelable(false)
                        .show();
            });
        }, "ChainIntegrityCheck").start();
    }

    private void resyncChain() {
        try {
            IrohNode node = IrohNode.getInstance();
            node.requestAllBlocksFromPeers();
            node.requestChainFromAllPeers();
            Toast.makeText(this, R.string.debug_chain_resync_started,
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.debug_chain_resync_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (viewPager != null && viewPager.getCurrentItem() != 0) {
            viewPager.setCurrentItem(0, true);
            return;
        }
        super.onBackPressed();
    }

    public boolean isDarkTheme() {
        return darkTheme;
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        listener.onBalance(currentBalanceText);
        listener.onAddress(address);
        listener.onMiningStats(minedBlocksCount, lastBlockHash);
        listener.onServiceState(serviceRunning);
        listener.onNetworkStatus(currentNetworkStatus);
        listener.onPeerId(myPeerId);
        listener.onTheme(darkTheme);
    }

    public void updateChatBadge() {
        if (bottomNav == null) {
            return;
        }
        try {
            int total = UnreadCounter.getInstance(this).getTotalUnread();
            if (total > 0) {
                com.google.android.material.badge.BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.nav_profile);
                badge.setNumber(total);
                badge.setVisible(true);
            } else {
                bottomNav.removeBadge(R.id.nav_profile);
            }
        } catch (Throwable ignored) {
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void setNetworkStatus(String text) {
        currentNetworkStatus = text;
        for (Listener l : listeners) {
            l.onNetworkStatus(text);
        }
    }

    private void dispatchPeerId(String peerId) {
        for (Listener l : listeners) {
            l.onPeerId(peerId);
        }
    }

    private void pushMiningStats() {
        for (Listener l : listeners) {
            l.onMiningStats(minedBlocksCount, lastBlockHash);
        }
    }

    private void pushServiceState() {
        for (Listener l : listeners) {
            l.onServiceState(serviceRunning);
        }
    }

    // ---------- Data providers for fragments ----------

    public String getAddress() {
        return address;
    }

    public String getMyPeerId() {
        return myPeerId;
    }

    public double getLocalBalance() {
        return KeyManager.getLocalBalance(this);
    }

    public int getMinedBlocksCount() {
        return minedBlocksCount;
    }

    public void refreshMiningStats() {
        BlockStorage storage = new BlockStorage(this);
        syncMinedBlocksCount(storage);
        lastBlockHash = storage.getLastBlockHash();
        pushMiningStats();
    }

    public boolean isServiceRunning() {
        return serviceRunning;
    }

    public void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------- Actions called by fragments ----------

    public void copyText(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        }
        showToast(getString(R.string.toast_address_copied));
    }

    public void scanForPeers() {
        p2pNode.scanLocalNetwork();
        showToast(getString(R.string.toast_searching_nodes));
    }

    /**
     * Запускает фоновое сканирование локальной Wi-Fi сети. Найденные соседи
     * сообщают свой Iroh Endpoint ID через HELLO, после чего устанавливается
     * соединение уже по Iroh (libp2p не используется).
     */
    private void startAutoDiscovery() {
        final Handler handler = new Handler(Looper.getMainLooper());
        Runnable discovery = new Runnable() {
            @Override
            public void run() {
                // Скан подсети имеет смысл только в Wi-Fi; в LTE не спамим.
                if (NetworkType.WIFI.equals(NetworkType.current(MainActivity.this))) {
                    p2pNode.scanLocalNetwork();
                }
                handler.postDelayed(this, AUTO_DISCOVERY_INTERVAL_MS);
            }
        };
        // Небольшая задержка, чтобы WebSocket-сервер и Iroh-хост успели подняться.
        handler.postDelayed(discovery, AUTO_DISCOVERY_FIRST_DELAY_MS);
    }

    public void connectToPeerById(String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) {
            showToast(getString(R.string.toast_enter_endpoint));
            return;
        }
        String id = peerId.trim();
        irohNode.connectToPeer(id, new IrohNode.ConnectCallback() {
            @Override
            public void onSuccess(String endpointId) {
                runOnUiThread(() -> showToast(getString(R.string.toast_connected_format, endpointId)));
            }

            @Override
            public void onError(String endpointId, String message) {
                runOnUiThread(() -> showToast(getString(R.string.toast_connect_failed_format, message)));
            }
        });
        showToast(getString(R.string.toast_connecting_format, id));
    }

    public void toggleMiningService() {
        Intent serviceIntent = new Intent(this, SignerService.class);
        Log.d("Mining", "toggleMiningService: serviceRunning=" + serviceRunning
                + ", hasRealSim=" + SimIdentity.hasRealSim(this));
        if (serviceRunning) {
            SignerService.stopMining(this);
            serviceRunning = false;
            startBackgroundService();
            showToast(getString(R.string.mining_stopped));
        } else {
            if (!SimIdentity.hasRealSim(this)) {
                Log.d("Mining", "toggleMiningService: start blocked, no real SIM");
                showToast(getString(R.string.sim_missing_mining));
                pushServiceState();
                return;
            }
            requestIgnoreBatteryOptimizations();
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(SignerService.KEY_MINING_ACTIVE, true)
                    .putLong(SignerService.KEY_MINING_START_TIME, 0L)
                    .apply();
            stopChatService();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d("Mining", "Starting SignerService via startForegroundService");
                    startForegroundService(serviceIntent);
                } else {
                    Log.d("Mining", "Starting SignerService via startService");
                    startService(serviceIntent);
                }
                serviceRunning = true;
                showToast(getString(R.string.mining_started));
            } catch (Throwable t) {
                Log.e("Mining", "Failed to start SignerService", t);
                serviceRunning = false;
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(SignerService.KEY_MINING_ACTIVE, false)
                        .putLong(SignerService.KEY_MINING_START_TIME, 0L)
                        .apply();
                showToast(getString(R.string.mining_start_failed));
            }
        }
        pushServiceState();
    }

    public void resetMinedBlocksCounter() {
        int totalBlocks = new BlockStorage(this).getBlockCount();
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MINED_BLOCKS_BASELINE, totalBlocks)
                .apply();
        minedBlocksCount = 0;
        pushMiningStats();
    }

    private void syncMinedBlocksCount(BlockStorage storage) {
        int totalBlocks = storage.getBlockCount();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int baseline = prefs.getInt(KEY_MINED_BLOCKS_BASELINE, 0);
        if (baseline > totalBlocks) {
            baseline = 0;
            prefs.edit().putInt(KEY_MINED_BLOCKS_BASELINE, 0).apply();
        }
        minedBlocksCount = Math.max(0, totalBlocks - baseline);
    }

    // ---------- Block / chain handling ----------

    private void handleIncomingBlock(final String blockData) {
        BlockStorage storage = new BlockStorage(this);
        try {
            org.json.JSONObject obj = new org.json.JSONObject(blockData);
            int index = obj.optInt("index", -1);
            String prevHash = obj.optString("prevHash", "");
            if (index < 0 || storage.hasBlock(index)) {
                return;
            }
            if (!BlockStorage.isBlockValid(obj)) {
                Log.w(TAG, getString(R.string.block_invalid_signature));
                return;
            }
            if (!prevHash.equals(storage.getLastBlockHash())) {
                p2pNode.broadcastBlock("REQUEST_CHAIN");
                irohNode.broadcastBlock("REQUEST_CHAIN");
                runOnUiThread(() -> showToast(getString(R.string.toast_chain_gap)));
                return;
            }
            boolean spv = storage.isSpvMode();
            if (!spv && !TransferManager.validateBlockData(
                    this, storage.getAllBlocks(), obj.optString("data", ""),
                    obj.optString("validator", ""))) {
                Log.w(TAG, getString(R.string.block_invalid_transaction));
                return;
            }
            if (!storage.saveBlock(blockData)) {
                Log.w(TAG, "Duplicate block ignored");
                return;
            }
            TransferManager.onBlockConfirmed(this, blockData);
            if (spv) {
                KeyManager.applyBlockToLocalBalance(
                        this, blockData, KeyManager.getOrCreateAddress(this));
            } else {
                recalculateBalanceFromChain(storage);
            }
            syncMinedBlocksCount(storage);
            lastBlockHash = storage.getLastBlockHash();
            runOnUiThread(() -> {
                updateSyncIndicator();
                pushMiningStats();
                updateBalance();
                showToast(getString(R.string.toast_block_received));
            });
        } catch (Exception e) {
            Log.e(TAG, "Invalid block received", e);
        }
    }

    private void handleIncomingChainSync(String from, int remoteHeight, String remoteLastHash,
                                         List<String> blockJsons) {
        if (blockJsons == null || blockJsons.isEmpty()) {
            return;
        }
        BlockStorage storage = new BlockStorage(this);
        boolean spv = storage.isSpvMode();
        int added = 0;
        for (String blockJson : blockJsons) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(blockJson);
                int index = obj.optInt("index", -1);
                String hash = obj.optString("hash", "");
                if (index < 0) {
                    Log.w(TAG, "Chain sync: invalid index");
                    break;
                }
                if (storage.hasBlock(index) || storage.hasBlockHash(hash)) {
                    continue;
                }
                if (index != storage.getLastBlockIndex() + 1) {
                    Log.w(TAG, "Chain sync: gap before index " + index);
                    break;
                }
                if (!obj.optString("prevHash", "").equals(storage.getLastBlockHash())) {
                    Log.w(TAG, "Chain sync: prevHash mismatch at index " + index);
                    break;
                }
                boolean headerOnly = !obj.has("signature") || !obj.has("data");
                if (headerOnly) {
                    if (!spv || !BlockStorage.verifyHeader(obj)) {
                        Log.w(TAG, "Chain sync: invalid SPV header at index " + index);
                        break;
                    }
                } else {
                    if (!BlockStorage.isBlockValid(obj)) {
                        Log.w(TAG, getString(R.string.block_invalid_signature));
                        break;
                    }
                    if (!spv && !TransferManager.validateBlockData(
                            this, storage.getAllBlocks(), obj.optString("data", ""),
                            obj.optString("validator", ""))) {
                        Log.w(TAG, getString(R.string.block_invalid_transaction));
                        break;
                    }
                }
                if (storage.saveBlock(blockJson)) {
                    TransferManager.onBlockConfirmed(this, blockJson);
                    if (spv) {
                        KeyManager.applyBlockToLocalBalance(
                                this, blockJson, KeyManager.getOrCreateAddress(this));
                    }
                    added++;
                }
            } catch (Exception e) {
                Log.w(TAG, "Chain sync: invalid block JSON", e);
                break;
            }
        }
        if (added <= 0) {
            return;
        }
        SignerService.recordBlockReceived(this);
        if (!spv) {
            recalculateBalanceFromChain(storage);
        }
        syncMinedBlocksCount(storage);
        lastBlockHash = storage.getLastBlockHash();
        Log.i(TAG, "Chain sync from " + from + ": added " + added
                + " blocks, remoteHeight=" + remoteHeight);
        runOnUiThread(() -> {
            updateSyncIndicator();
            pushMiningStats();
            updateBalance();
            showToast(getString(R.string.toast_chain_synced));
        });
    }

    private void recalculateBalanceFromChain(BlockStorage storage) {
        KeyManager.recalculateBalanceFromChain(
                this, storage, KeyManager.getOrCreateAddress(this));
    }

    private void handleIncomingChain(List<String> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        BlockStorage storage = new BlockStorage(this);
        boolean spv = storage.isSpvMode();
        int added = 0;
        for (String b : blocks) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(b);
                int idx = o.optInt("index", -1);
                String hash = o.optString("hash", "");
                if (idx < 0 || storage.hasBlock(idx) || storage.hasBlockHash(hash)) {
                    continue;
                }
                if (idx != storage.getLastBlockIndex() + 1) {
                    continue;
                }
                if (!o.optString("prevHash", "").equals(storage.getLastBlockHash())) {
                    continue;
                }
                if (!BlockStorage.isBlockValid(o)) {
                    Log.w(TAG, getString(R.string.block_invalid_signature));
                    continue;
                }
                if (!spv && !TransferManager.validateBlockData(
                        this, storage.getAllBlocks(), o.optString("data", ""),
                        o.optString("validator", ""))) {
                    Log.w(TAG, getString(R.string.block_invalid_transaction));
                    continue;
                }
                if (storage.saveBlock(b)) {
                    TransferManager.onBlockConfirmed(this, b);
                    if (spv) {
                        KeyManager.applyBlockToLocalBalance(
                                this, b, KeyManager.getOrCreateAddress(this));
                    }
                    added++;
                }
            } catch (Exception ignored) {
            }
        }
        if (added <= 0) {
            return;
        }
        SignerService.recordBlockReceived(this);
        if (!spv) {
            recalculateBalanceFromChain(storage);
        }
        syncMinedBlocksCount(storage);
        lastBlockHash = storage.getLastBlockHash();
        runOnUiThread(() -> {
            updateSyncIndicator();
            pushMiningStats();
            updateBalance();
            showToast(getString(R.string.toast_chain_synced));
        });
    }

    public void updateBalance() {
        // Автономная экономика: баланс читается только локально (KeyManager),
        // без запросов к внешнему блокчейну.
        currentBalanceText = getString(R.string.amount_format,
                Tokenomics.formatAmount(KeyManager.getLocalBalance(this)));
        for (Listener l : listeners) {
            l.onBalance(currentBalanceText);
        }
    }

    private void requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_NOTIFICATIONS_PERMISSION);
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Log.d(TAG, "Requested exemption from battery optimization");
        } catch (Exception e) {
            Log.e(TAG, "Failed to request battery optimization exemption", e);
        }
    }

    private void startChainSyncLoop() {
        if (chainSyncLoopRunning) {
            return;
        }
        chainSyncLoopRunning = true;
        if (chainSyncHandler == null) {
            chainSyncHandler = new Handler(Looper.getMainLooper());
        }
        chainSyncHandler.post(chainSyncRunnable);
    }

    private void stopChainSyncLoop() {
        chainSyncLoopRunning = false;
        if (chainSyncHandler != null) {
            chainSyncHandler.removeCallbacks(chainSyncRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        stopChainSyncLoop();
        unregisterNetworkTypeIndicator();
        getApplication().unregisterActivityLifecycleCallbacks(appLifecycleCallbacks);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        irohNode.removeOnBlockListener(irohBlockListener);
        irohNode.removeOnChainSyncListener(irohChainSyncListener);
        irohNode.removeOnPeerConnectedListener(irohPeerConnectedListener);
        if (syncIndicator != null) {
            syncIndicator.removeCallbacks(hideSyncIndicator);
            syncIndicator = null;
        }
        tvNetworkType = null;
        super.onDestroy();
    }
}
