package com.triger.poc;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.bouncycastle.util.encoders.Hex;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
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
    private static final String ACTION_BLOCK_SIGNED = "TRIGER_BLOCK_SIGNED";
    private static final int NETWORK_BONUS = 5;
    private static final long AUTO_DISCOVERY_FIRST_DELAY_MS = 4000;
    /**
     * Период автосканирования локальной сети. Сканирование 254 адресов по TCP
     * — тяжёлая операция, поэтому ходим по сети вдвое реже (было 60 с).
     */
    private static final long AUTO_DISCOVERY_INTERVAL_MS = 120000;
    private static final String TAG = "MainActivity";
    private static final String FIELD_VALIDATOR_DEVICE_ID = "validator_device_id";
    private static final String PREFS_NAME = SettingsActivity.PREFS_NAME;
    private static final String KEY_CONTRACT_ADDRESS = SettingsActivity.KEY_CONTRACT_ADDRESS;
    private static final String KEY_RPC_URL = SettingsActivity.KEY_RPC_URL;
    private static final String KEY_THEME = SettingsActivity.KEY_THEME;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private String address = "";
    private String rpcUrl = "";
    private String contractAddress = "";
    private String currentBalanceText = "0 TRG";
    private String currentNetworkStatus = "Поиск узлов в глобальной сети...";
    private String lastBlockHash = "—";

    private int minedBlocksCount = 0;
    private boolean serviceRunning = false;
    private boolean darkTheme = false;

    private final P2PNode p2pNode = P2PNode.getInstance();
    private final IrohNode irohNode = IrohNode.getInstance();
    private volatile String myPeerId = "";
    private final IrohNode.OnBlockListener irohBlockListener =
            (fromEndpointId, blockJson) -> handleIncomingBlock(blockJson);
    private BottomNavigationView bottomNav;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_BLOCK_SIGNED.equals(intent.getAction())) {
                BlockStorage storage = new BlockStorage(MainActivity.this);
                minedBlocksCount = storage.getBlockCount();
                lastBlockHash = storage.getLastBlockHash();
                pushMiningStats();
                updateBalance();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        darkTheme = prefs.getBoolean(KEY_THEME, prefs.getBoolean("theme_dark", false));
        setTheme(darkTheme ? R.style.Theme_Triger_Dark : R.style.Theme_Triger_Light);

        super.onCreate(savedInstanceState);
        CryptoUtils.setupBouncyCastle();
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_settings) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return false;
            }
            selectTab(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_wallet);
        }

        address = KeyManager.getOrCreateAddress(this);

        String savedContract = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CONTRACT_ADDRESS, "");
        rpcUrl = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_RPC_URL, "");
        contractAddress = savedContract;

        requestNotificationsPermissionIfNeeded();

        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, new IntentFilter(ACTION_BLOCK_SIGNED));

        p2pNode.setSelfDeviceId(KeyManager.getOrCreateDeviceId(this));
        p2pNode.startNode(8080);
        setupIrohTestButton();
        irohNode.start(this);
        irohNode.startDiscovery();
        irohNode.addOnBlockListener(irohBlockListener);
        BlockStorage blockStorage = new BlockStorage(this);
        p2pNode.setBlockStorage(blockStorage);
        if (blockStorage.getLastBlockIndex() >= 0) {
            lastBlockHash = blockStorage.getLastBlockHash();
        }
        minedBlocksCount = blockStorage.getBlockCount();
        p2pNode.setOnBlockReceivedListener((blockData, validatorAddress) -> handleIncomingBlock(blockData));
        p2pNode.setOnPeerCountChangedListener(count -> runOnUiThread(() -> {
            if (count > 0) {
                setNetworkStatus("Локальная сеть: " + count + " узлов");
            }
        }));
        p2pNode.setOnChainReceivedListener(blocks -> handleIncomingChain(blocks));
        // Локальный P2PNode (WebSocket-скан Wi-Fi) нужен только для первого контакта:
        // из HELLO узнаём Iroh Endpoint ID соседа и сразу подключаемся уже через Iroh.
        p2pNode.setOnPeerDiscoveredListener((endpointId, address) -> {
            String peer = endpointId == null ? "" : endpointId.trim();
            if (peer.isEmpty() || peer.equals(irohNode.getEndpointId())) {
                return;
            }
            Log.d(TAG, "Local peer discovered: " + peer + " at " + address);
            irohNode.connectToPeer(peer);
        });
        // Служебные логи локального сканера не показываем в UI — только в Logcat.
        p2pNode.setOnLogListener(message -> Log.d(TAG, "P2P: " + message));
        // Автопоиск в фоне: пользователю не нужно вручную нажимать «Найти узлы».
        startAutoDiscovery();

        updateBalance();
    }

    /**
     * Временный тестовый блок: кнопка «Запустить Iroh» поднимает Iroh-узел
     * и показывает Toast с endpointId (или с текстом ошибки).
     */
    private void setupIrohTestButton() {
        Button btn = findViewById(R.id.btn_start_iroh);
        if (btn == null) {
            return;
        }
        // Слушатель ставим до старта, чтобы поймать и успех, и ошибку.
        irohNode.setListener(new IrohNode.Listener() {
            @Override
            public void onStarted(String endpointId) {
                p2pNode.setSelfIrohId(endpointId);
                myPeerId = endpointId == null ? "" : endpointId;
                runOnUiThread(() -> {
                    dispatchPeerId(myPeerId);
                    Toast.makeText(MainActivity.this,
                            "Iroh запущен, endpointId = " + endpointId, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onLog(String msg) {
                Log.d(TAG, "Iroh: " + msg);
            }

            @Override
            public void onFailed(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Iroh: ошибка — " + message, Toast.LENGTH_LONG).show());
            }
        });
        btn.setOnClickListener(v -> {
            Toast.makeText(this, "Iroh: запуск...", Toast.LENGTH_SHORT).show();
            irohNode.start(this);
            // Если узел уже поднят (например, SignerService успел раньше) — id известен сразу.
            String id = irohNode.getEndpointId();
            if (id != null && !id.isEmpty()) {
                Toast.makeText(this, "Iroh запущен, endpointId = " + id, Toast.LENGTH_LONG).show();
            }
        });

        Button testBtn = findViewById(R.id.btn_iroh_test);
        if (testBtn != null) {
            testBtn.setOnClickListener(v ->
                    startActivity(new Intent(MainActivity.this, IrohTestActivity.class)));
        }
    }

    private void selectTab(int itemId) {
        Fragment fragment;
        String tag;
        if (itemId == R.id.nav_mining) {
            fragment = new MiningFragment();
            tag = "mining";
        } else if (itemId == R.id.nav_network) {
            fragment = new NetworkFragment();
            tag = "network";
        } else if (itemId == R.id.nav_chat) {
            fragment = new ChatFragment();
            tag = "chat";
        } else {
            fragment = new WalletFragment();
            tag = "wallet";
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    public void openWalletTab() {
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_wallet);
        } else {
            selectTab(R.id.nav_wallet);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean savedDark = prefs.getBoolean(KEY_THEME, darkTheme);
        if (savedDark != darkTheme) {
            darkTheme = savedDark;
            recreate();
            return;
        }
        String savedRpc = prefs.getString(KEY_RPC_URL, "");
        String savedContract = prefs.getString(KEY_CONTRACT_ADDRESS, "");
        if (!savedRpc.equals(rpcUrl) || !savedContract.equals(contractAddress)) {
            rpcUrl = savedRpc;
            contractAddress = savedContract;
            updateBalance();
        }
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

    public String getContractAddress() {
        return contractAddress;
    }

    public String getSavedRpcUrl() {
        return rpcUrl;
    }

    public double getLocalBalance() {
        return KeyManager.getLocalBalance(this);
    }

    public int getMinedBlocksCount() {
        return minedBlocksCount;
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
        showToast("Адрес скопирован");
    }

    public void connectToNetwork(String url) {
        final String target = url == null ? "" : url.trim();
        if (target.isEmpty()) {
            showToast("Введите RPC URL");
            return;
        }
        new Thread(() -> {
            try {
                Web3j web3 = Web3j.build(new HttpService(target));
                final long blockNumber = web3.ethBlockNumber().send().getBlockNumber().longValue();
                runOnUiThread(() -> showToast("Подключено. Блок: #" + blockNumber));
            } catch (Exception e) {
                Log.e(TAG, "connect error", e);
                runOnUiThread(() -> showToast("Ошибка подключения"));
            }
        }).start();
    }

    public void registerInNetwork(String url, String contract) {
        final String rpc = url == null ? "" : url.trim();
        final String contractAddr = contract == null ? "" : contract.trim();
        if (rpc.isEmpty()) {
            showToast("Введите RPC URL");
            return;
        }
        if (contractAddr.isEmpty()) {
            showToast("Введите адрес контракта");
            return;
        }
        new Thread(() -> {
            try {
                Web3j web3 = Web3j.build(new HttpService(rpc));
                String addr = KeyManager.getOrCreateAddress(MainActivity.this);
                String privateKey = KeyManager.getPrivateKeyHex(MainActivity.this);
                Credentials credentials = privateKey == null ? null : Credentials.create(privateKey);
                if (credentials == null) {
                    runOnUiThread(() -> showToast("Не найден приватный ключ"));
                    return;
                }
                String simHash = "sim_" + addr.substring(0, Math.min(8, addr.length()));
                String abiJson = loadAbiFromAssets();
                if (abiJson == null) {
                    runOnUiThread(() -> showToast("Не удалось загрузить ABI"));
                    return;
                }
                Function function = new Function("registerValidator",
                        Arrays.<Type>asList(new Utf8String(simHash)),
                        Collections.<TypeReference<?>>emptyList());
                String encodedFunction = FunctionEncoder.encode(function);
                BigInteger nonce = web3.ethGetTransactionCount(addr, DefaultBlockParameterName.LATEST).send().getTransactionCount();
                RawTransaction rawTransaction = RawTransaction.createTransaction(
                        nonce,
                        BigInteger.valueOf(20000000000L),
                        BigInteger.valueOf(300000L),
                        contractAddr,
                        BigInteger.ZERO,
                        encodedFunction);
                byte[] signed = TransactionEncoder.signMessage(rawTransaction, credentials);
                String hex = Numeric.toHexString(signed);
                EthSendTransaction response = web3.ethSendRawTransaction(hex).send();
                if (response.hasError()) {
                    runOnUiThread(() -> showToast("Ошибка регистрации: " + response.getError().getMessage()));
                    return;
                }
                runOnUiThread(() -> showToast("Регистрация отправлена"));
                updateBalance();
            } catch (Exception e) {
                Log.e(TAG, "register error", e);
                runOnUiThread(() -> showToast("Ошибка регистрации"));
            }
        }).start();
    }

    public void scanForPeers() {
        p2pNode.scanLocalNetwork();
        showToast("Идет поиск узлов...");
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
                p2pNode.scanLocalNetwork();
                handler.postDelayed(this, AUTO_DISCOVERY_INTERVAL_MS);
            }
        };
        // Небольшая задержка, чтобы WebSocket-сервер и Iroh-хост успели подняться.
        handler.postDelayed(discovery, AUTO_DISCOVERY_FIRST_DELAY_MS);
    }

    public void connectToPeerById(String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) {
            showToast("Введите Endpoint ID");
            return;
        }
        String id = peerId.trim();
        irohNode.connectToPeer(id, new IrohNode.ConnectCallback() {
            @Override
            public void onSuccess(String endpointId) {
                runOnUiThread(() -> showToast("Подключено: " + endpointId));
            }

            @Override
            public void onError(String endpointId, String message) {
                runOnUiThread(() -> showToast("Не удалось подключиться: " + message));
            }
        });
        showToast("Подключаемся к узлу " + id + "...");
    }

    /** Краткий статус Iroh для вкладки «Сети». */
    public String getIrohStatusText() {
        String id = irohNode.getEndpointId();
        if (id == null || id.isEmpty()) {
            return "Iroh: запуск...";
        }
        int connected = irohNode.getConnectedPeers().size();
        int known = irohNode.getKnownPeers().size();
        return "Iroh: запущен\nEndpoint ID: " + id
                + "\nПодключено: " + connected + " · известно: " + known;
    }

    public void toggleMiningService() {
        Intent serviceIntent = new Intent(this, SignerService.class);
        if (serviceRunning) {
            stopService(serviceIntent);
            serviceRunning = false;
            showToast("Майнинг остановлен");
        } else {
            requestIgnoreBatteryOptimizations();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            serviceRunning = true;
            showToast("Майнинг запущен");
        }
        pushServiceState();
    }

    public void resetMinedBlocksCounter() {
        minedBlocksCount = 0;
        pushMiningStats();
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
            if (!prevHash.equals(storage.getLastBlockHash())) {
                p2pNode.broadcastBlock("REQUEST_CHAIN");
                irohNode.broadcastBlock("REQUEST_CHAIN");
                runOnUiThread(() -> showToast("Обнаружен разрыв. Синхронизация..."));
                return;
            }
            String validatorDeviceId = obj.optString(FIELD_VALIDATOR_DEVICE_ID, "");
            if (!ValidatorManager.isValidator(this, validatorDeviceId)) {
                Log.w(TAG, "Block rejected: validator not trusted");
                return;
            }
            storage.saveBlock(blockData);
            KeyManager.addLocalBalance(this, NETWORK_BONUS);
            TransactionHistory.addTransaction(this, TransactionHistory.TYPE_NETWORK_BONUS, NETWORK_BONUS);
            minedBlocksCount++;
            lastBlockHash = storage.getLastBlockHash();
            runOnUiThread(() -> {
                pushMiningStats();
                updateBalance();
                showToast("Получен блок от соседа!");
            });
        } catch (Exception e) {
            Log.e(TAG, "Invalid block received", e);
        }
    }

    private void handleIncomingChain(List<String> blocks) {
        BlockStorage storage = new BlockStorage(this);
        int localLast = storage.getLastBlockIndex();
        int remoteLast = -1;
        for (String b : blocks) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(b);
                int idx = o.optInt("index", -1);
                if (idx > remoteLast) {
                    remoteLast = idx;
                }
                String validatorDeviceId = o.optString(FIELD_VALIDATOR_DEVICE_ID, "");
                if (!ValidatorManager.isValidator(this, validatorDeviceId)) {
                    Log.w(TAG, "Block rejected: validator not trusted");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (remoteLast <= localLast) {
            return;
        }
        storage.clearAll();
        for (String b : blocks) {
            storage.saveBlock(b);
        }
        minedBlocksCount = storage.getLastBlockIndex() + 1;
        lastBlockHash = storage.getLastBlockHash();
        runOnUiThread(() -> {
            pushMiningStats();
            updateBalance();
            showToast("Цепочка синхронизирована!");
        });
    }

    private String loadAbiFromAssets() {
        try {
            InputStream is = getAssets().open("triger_abi.json");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "load abi error", e);
            return null;
        }
    }

    public void updateBalance() {
        // Автономная экономика: баланс читается только локально (KeyManager),
        // без запросов к внешнему блокчейну.
        currentBalanceText = Tokenomics.formatAmount(KeyManager.getLocalBalance(this)) + " TRG";
        for (Listener l : listeners) {
            l.onBalance(currentBalanceText);
        }
    }

    private void requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS_PERMISSION);
            }
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

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        irohNode.removeOnBlockListener(irohBlockListener);
        super.onDestroy();
    }
}
