package com.triger.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import computer.iroh.BiStream;
import computer.iroh.Connection;
import computer.iroh.Endpoint;
import computer.iroh.EndpointAddr;
import computer.iroh.EndpointBuilder;
import computer.iroh.EndpointId;
import computer.iroh.GossipNode;
import computer.iroh.HomeRelayCallback;
import computer.iroh.Incoming;
import computer.iroh.IrohAndroid;
import computer.iroh.IrohException;
import computer.iroh.NetworkChangeCallback;
import computer.iroh.PathSnapshot;
import computer.iroh.RecvStream;
import computer.iroh.RelayMode;
import computer.iroh.SecretKey;
import computer.iroh.TopicDiscoveryOptions;
import computer.iroh.TopicEvent;
import computer.iroh.TopicSubscription;
import computer.iroh.WatchHandle;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;

/**
 * Минимальный Iroh-узел: поднимает Endpoint, умеет отдать свой EndpointId и подключиться к пиру.
 *
 * ВАЖНО: до вызова {@link #start(Context)} нативной стороне должен быть передан Android-контекст —
 * это делает {@link IrohAndroid#installAndroidContext(Context)} (JNI-символ лежит в libiroh_ffi.so).
 * Без него n0/DNS-резолвер на Android не работает.
 */
public class IrohNode {
    private static final String TAG = "IrohNode";
    private static volatile IrohNode instance;

    private final AtomicBoolean started = new AtomicBoolean(false);
    /**
     * Флаг «старт уже в процессе». Нужен, потому что MainActivity и SignerService
     * вызывают start() почти одновременно: флаг started выставляется только после bind(),
     * и без starting второй вызов создал бы второй Endpoint (и упал бы с address in use).
     */
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile Endpoint endpoint;
    private volatile String endpointId = "";
    private volatile GossipNode gossipNode;
    private volatile TopicSubscription topicSubscription;
    private volatile Thread topicEventThread;
    private static final String TOPIC_GLOBAL = "triger-global";

    public interface Listener {
        void onStarted(String endpointId);
        void onLog(String msg);

        /** Вызывается, если запуск упал; message содержит текст ошибки. */
        default void onFailed(String message) {}

        default void onDirectAddressesChanged(List<String> addresses) {}
    }

    public interface OnMessageListener {
        void onMessage(String fromEndpointId, String text);
    }

    public interface OnTypingListener {
        void onTypingStart(String fromEndpointId);

        void onTypingStop(String fromEndpointId);
    }

    /** Собеседник открыл чат и прочитал наши сообщения (до момента upToTimestamp). */
    public interface OnReadReceiptListener {
        void onPeerRead(String fromEndpointId, long upToTimestamp);
    }

    public interface OnBlockListener {
        void onBlock(String fromEndpointId, String blockJson);
    }

    public interface OnChainSyncListener {
        void onChainResponse(String fromEndpointId, int remoteHeight, String remoteLastHash,
                             List<String> blockJsons);
    }

    public interface OnPeerConnectedListener {
        void onPeerConnected(String endpointId);
    }

    public interface OnPeerDiscoveredListener {
        void onPeerDiscovered(String endpointId);
    }

    /** Результат глобального поиска контакта по нику через Peer Exchange. */
    public interface OnPeerSearchListener {
        void onPeerSearchResult(String query, String endpointId, String username);
    }

    public interface ConnectCallback {
        void onSuccess(String endpointId);
        void onError(String endpointId, String message);
    }

    /** Подтверждение отправки сообщения после записи в поток. */
    public interface SendCallback {
        void onSent(String endpointId);
        void onError(String endpointId, String message);
    }

    /** ALPN протокола чата. Должен совпадать на обеих сторонах. */
    public static final String ALPN = "triger/1";

    /**
     * Публичный Relay n0.computer. Используется через RelayMode.Custom,
     * настройка не требуется.
     */
    private static final String HARDCODED_SEED_RELAY_URL =
            "https://euc1-1.relay.n0.iroh.link";

    /** Настроенный relay URL или пустая строка. */
    private static String configuredSeedRelayUrl() {
        return HARDCODED_SEED_RELAY_URL == null
                ? "" : HARDCODED_SEED_RELAY_URL.trim();
    }

    private static String normalizeRelayUrl(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean sameRelayUrl(String first, String second) {
        String a = normalizeRelayUrl(first);
        String b = normalizeRelayUrl(second);
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    /**
     * Служебный префикс peer exchange. Такие сообщения не попадают в чат.
     * n0 DNS в iroh умеет находить адрес только по уже известному Endpoint ID,
     * поэтому список известных пиров распространяется между подключёнными узлами.
     */
    private static final String PEX_PREFIX = "__triger_pex__";
    /** Служебный префикс профиля узла (device_id + endpointId), не попадает в чат. */
    private static final String PROFILE_PREFIX = "__triger_profile__";
    /** Запрос глобального поиска контакта по нику. */
    private static final String SEARCH_REQUEST_PREFIX = "__triger_search_req__";
    /** Ответ на запрос глобального поиска контакта по нику. */
    private static final String SEARCH_RESPONSE_PREFIX = "__triger_search_res__";
    /** Пользовательское сообщение с id для дедупликации доставки. */
    private static final String CHAT_MSG_PREFIX = "__triger_msg__";

    private static final String READ_RECEIPT_PREFIX = "__triger_read_receipt__";
    /** Служебный пакет «собеседник печатает». */
    private static final String TYPING_START_PREFIX = "__triger_typing_start__";
    /** Служебный пакет «собеседник перестал печатать». */
    private static final String TYPING_STOP_PREFIX = "__triger_typing_stop__";
    /** Лёгкий пакет проверки жизни соединения (без полезной нагрузки). */
    private static final String PING_PREFIX = "__triger_ping__";
    /** Ответ на PING (без полезной нагрузки). */
    private static final String PONG_PREFIX = "__triger_pong__";
    /** Запрос высоты и последнего хеша цепочки. */
    private static final String GET_CHAIN_REQ_PREFIX = "__triger_get_chain_req__";
    /** Ответ с высотой, последним хешем, списком блоков и списком валидаторов. */
    private static final String CHAIN_RESP_PREFIX = "__triger_chain_resp__";
    /** SPV: запрос полного блока по индексу. */
    private static final String GET_BLOCK_REQ_PREFIX = "__triger_get_block_req__";
    /** SPV: ответ с полным блоком. */
    private static final String BLOCK_RESP_PREFIX = "__triger_block_resp__";
    /** Объявление маркета. */
    private static final String MARKET_ITEM_PREFIX = "MARKET_ITEM:";
    /** Подписанная новость, защищённая платой за публикацию. */
    public static final String NEWS_BROADCAST_PREFIX = "NEWS_BROADCAST:";

    public static final String MARKET_MAKER_REQ_PREFIX = "MARKET_MAKER_REQ:";

    private static final String MARKET_MAKER_RESP_PREFIX = "MARKET_MAKER_RESP:";
    /** Объявление P2P-обменника. */
    private static final String P2P_EXCHANGE_PREFIX = "P2P_EXCHANGE:";
    /** Заявка на обмен P2P. */
    private static final String P2P_EXCHANGE_REQUEST_PREFIX = "P2P_EXCHANGE_REQUEST:";
    /** Статус заявки P2P-обмена. */
    private static final String P2P_EXCHANGE_STATUS_PREFIX = "P2P_EXCHANGE_STATUS:";
    /** Снятие объявления P2P-обменника. */
    private static final String P2P_EXCHANGE_REMOVE_PREFIX = "P2P_EXCHANGE_REMOVE:";
    /** P2P-ордер на покупку или продажу TRG. */
    private static final String P2P_ORDER_PREFIX = "P2P_ORDER:";
    /** Устаревший префикс P2P-ордера, поддерживаем для совместимости. */
    private static final String P2P_EXCHANGE_ORDER_PREFIX = "P2P_EXCHANGE_ORDER:";
    /** Статус P2P-ордера (подтверждение фиата, расчёт). */
    private static final String P2P_EXCHANGE_ORDER_STATUS_PREFIX = "P2P_EXCHANGE_ORDER_STATUS:";
    /** Спор P2P-обменника и список назначенных арбитров. */
    private static final String P2P_DISPUTE_PREFIX = "P2P_DISPUTE:";
    /** Голос арбитра по спору. */
    private static final String P2P_DISPUTE_VOTE_PREFIX = "P2P_DISPUTE_VOTE:";
    /** Итог спора после подсчёта голосов. */
    private static final String P2P_DISPUTE_STATUS_PREFIX = "P2P_DISPUTE_STATUS:";
    /** Обновление цены TRG (VWAP) и сделка для синхронизации между узлами. */
    private static final String PRICE_UPDATE_PREFIX = "PRICE_UPDATE:";
    private static final long BLOCK_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_CHAIN_SYNC_BLOCKS = 100;
    private static final long VALIDATOR_RESYNC_INTERVAL_MS = 5000L;
    /** Сколько помним id входящих сообщений, чтобы не показывать дубль из P2P и Relay. */
    private static final long INCOMING_DEDUP_TTL_MS = 5L * 60L * 1000L;
    private static final int INCOMING_DEDUP_MAX = 512;
    /** Базовый PEX: объявление узла и запрос списка известных пиров. */
    private static final String PEX_TYPE_ANNOUNCE = "PEX_ANNOUNCE";
    private static final String PEX_TYPE_QUERY = "PEX_QUERY";
    /** Максимальный возраст записи в кэше пиров. */
    private static final long PEX_CACHE_TTL_MS = 60L * 60L * 1000L;
    private static final int PROFILE_VERSION = 1;
    private static final String DISCOVERY_PREFS = "triger_iroh_discovery";
    private static final String KEY_KNOWN_PEERS = "known_peers";
    private static final String KEY_PEER_USERNAMES = "peer_usernames";
    /** Endpoint ID -> адрес кошелька TRG из профиля пира. */
    private static final String KEY_PEER_WALLETS = "peer_wallets";
    /** Endpoint ID -> применяемая рамка профиля из профиля пира. */
    private static final String KEY_PEER_FRAMES = "peer_frames";
    /** Кэш адресов пиров (relay + direct IP:port) для fallback без DNS. */
    private static final String KEY_KNOWN_ADDRS = "known_addrs";
    /** Кэш распределённого PEX: Endpoint ID -> {username, timestamp}. */
    private static final String KEY_PEX_CACHE = "pex_cache";
    private static final int MAX_USERNAME_LENGTH = 32;
    /** Постоянный ключ идентичности узла: один и тот же endpointId между запусками. */
    private static final String IDENTITY_PREFS = "triger_iroh";
    private static final String KEY_SECRET = "secret_key";
    private static final int MAX_KNOWN_PEERS = 500;
    private static final int MAX_PEX_PEERS = 64;
    private static final long DISCOVERY_INTERVAL_MS = 15000L;

    /** Таймаут Relay/DNS handshake при установке соединения с пиром. */
    private static final long RELAY_CONNECT_TIMEOUT_MS = 15000L;
    /** Таймаут одной попытки установки соединения с пиром. */
    private static final long CONNECT_TIMEOUT_MS = RELAY_CONNECT_TIMEOUT_MS;
    /** Быстрый таймаут первой попытки по известному прямому IP. */
    private static final long DIRECT_CONNECT_TIMEOUT_MS = 5000L;
    /** Период таймера автоматического переподключения после потери связи. */
    private static final long RECONNECT_INTERVAL_MS = 10000L;
    /** Сколько пиров пробовать переподключить за один тик таймера. */
    private static final int RECONNECT_MAX_PER_TICK = 5;
    /** Heartbeat в Wi-Fi: NAT и так держит соединение дольше. */
    private static final long HEARTBEAT_WIFI_INTERVAL_MS = 30000L;
    /** Heartbeat через 4G/Relay: NAT закрывает порты быстрее. */
    private static final long HEARTBEAT_CELLULAR_INTERVAL_MS = 20000L;

    private static byte[] alpnBytes() {
        return ALPN.getBytes(StandardCharsets.UTF_8);
    }

    private static String describeAlpn(byte[] alpn) {
        return alpn == null ? "" : new String(alpn, StandardCharsets.UTF_8);
    }

    private Listener listener;
    private volatile OnMessageListener messageListener;
    private volatile OnTypingListener typingListener;
    private volatile OnReadReceiptListener readReceiptListener;
    private final CopyOnWriteArrayList<OnBlockListener> blockListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OnChainSyncListener> chainSyncListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OnPeerConnectedListener> peerConnectedListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OnPeerDiscoveredListener> peerDiscoveredListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OnPeerSearchListener> searchListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    /** id уже доставленных сообщений: защита от дубля P2P + Relay. */
    private final Map<String, Long> incomingMessageIds = new ConcurrentHashMap<>();
    private final Map<String, KnownAddr> knownAddrs = new ConcurrentHashMap<>();
    private final AtomicBoolean knownAddrsLoaded = new AtomicBoolean(false);
    private final Map<String, String> peerUsernames = new ConcurrentHashMap<>();
    private final Map<String, String> peerWalletAddresses = new ConcurrentHashMap<>();
    /** Endpoint ID -> id купленной и применённой рамки профиля. */
    private final Map<String, String> peerFrames = new ConcurrentHashMap<>();
    private final Map<String, PexEntry> pexCache = new ConcurrentHashMap<>();
    private final Map<Integer, CachedBlock> blockCache = new ConcurrentHashMap<>();
    private final Set<String> knownPeers = ConcurrentHashMap.newKeySet();
    private final Set<String> connectingPeers = ConcurrentHashMap.newKeySet();
    /** Пиры, через которые приложение ходит как через relay-мост. */
    private final Set<String> relayPeers = ConcurrentHashMap.newKeySet();
    private final Object connectionLock = new Object();
    private final ExecutorService ioExec = Executors.newCachedThreadPool();
    private final Set<String> reconnectPeers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService reconnectExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "iroh-reconnect");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> reconnectTask;
    private final ScheduledExecutorService heartbeatExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "iroh-heartbeat");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> heartbeatTask;
    /** Запрошен ли heartbeat сервисом (чата или майнинга). */
    private volatile boolean heartbeatRequested = false;
    private volatile boolean heartbeatRunning = false;
    /** Устройство в Doze: heartbeat выключен для экономии батареи. */
    private volatile boolean dozeActive = false;
    private volatile BroadcastReceiver dozeReceiver;
    private volatile Thread acceptThread;
    private volatile Thread discoveryThread;
    private final AtomicBoolean discoveryRunning = new AtomicBoolean(false);
    private final AtomicBoolean n0OnlineRequested = new AtomicBoolean(false);
    /** Успешно ли применён n0-пресет (relays + Pkarr/DNS discovery + crypto). */
    private volatile boolean n0PresetApplied = false;
    /** Получен ли home relay через endpoint.online() (нужен для диагностики relay). */
    private volatile boolean n0Online = false;
    /** Бюджет Pkarr/DNS lookup при поиске по Endpoint ID. */
    private static final long PKARR_LOOKUP_TIMEOUT_MS = 15000L;
    private static final long RESOLVE_POLL_MS = 400L;
    private volatile Context appContext;
    private volatile HomeRelayCallback homeRelayCallback;
    private volatile String homeRelayUrl = "";
    /** Активен ли собственный seed-relay (подключён и зарегистрирован). */
    private volatile boolean seedRelayActive = false;
    private volatile NetworkChangeCallback nativeNetworkChangeCallback;
    private volatile WatchHandle homeRelayWatchHandle;
    private volatile WatchHandle nativeNetworkWatchHandle;
    private volatile ConnectivityManager.NetworkCallback androidNetworkCallback;
    private volatile String lastNetworkType = "";
    private final AtomicLong lastNetworkRestartMs = new AtomicLong(0L);
    private static final long NETWORK_RESTART_DEBOUNCE_MS = 2000L;
    private volatile MdnsDiscovery mdnsDiscovery;
    private volatile List<String> selfDirectAddresses = Collections.emptyList();
    private final AtomicLong lastNetworkChangeMs = new AtomicLong(0L);
    private final AtomicBoolean processingNetworkChange = new AtomicBoolean(false);
    private volatile String lastNotifiedNetworkType = "";
    private volatile ScheduledFuture<?> n0OnlineTask;
    private volatile ScheduledFuture<?> idleReconnectTask;
    private static final long IDLE_RECONNECT_INTERVAL_MS = 2L * 60L * 1000L;
    private static final long N0_ONLINE_INTERVAL_MS = 5L * 60L * 1000L;
    private final AtomicLong lastValidatorResyncMs = new AtomicLong(0L);
    private static final long NETWORK_CHANGE_DEBOUNCE_MS = 500L;
    private volatile String deviceId = "";
    private static final String TIMING_TAG = "TIMING";
    private volatile long irohStartRequestedAtMs = 0L;
    private final AtomicBoolean firstMdnsTimingLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstOutgoingMessageTimingLogged = new AtomicBoolean(false);
    private final AtomicBoolean firstIncomingMessageTimingLogged = new AtomicBoolean(false);

    private IrohNode() {}

    public static IrohNode getInstance() {
        if (instance == null) {
            synchronized (IrohNode.class) {
                if (instance == null) {
                    instance = new IrohNode();
                }
            }
        }
        return instance;
    }

    /**
     * Загружает 32-байтовый секретный ключ из SharedPreferences или генерирует
     * новый при первом запуске. Один и тот же ключ даёт неизменный endpointId.
     */
    private byte[] loadOrCreateSecretKey(Context ctx) {
        if (ctx != null) {
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE);
            String stored = prefs.getString(KEY_SECRET, null);
            if (stored != null && !stored.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(stored, Base64.NO_WRAP);
                    if (bytes != null && bytes.length == 32) {
                        Log.i(TAG, "Iroh: loaded secret key from SharedPreferences");
                        return bytes;
                    }
                    Log.w(TAG, "Iroh: stored secret key has invalid length, regenerating");
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Iroh: stored secret key is corrupted, regenerating", e);
                }
            }
        } else {
            Log.w(TAG, "Iroh: no context, secret key will not be persisted");
        }

        SecretKey key = SecretKey.Companion.generate();
        byte[] bytes;
        try {
            bytes = key.toBytes();
        } finally {
            try {
                key.close();
            } catch (Throwable ignored) {
            }
        }

        if (ctx != null) {
            ctx.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SECRET, Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .apply();
            Log.i(TAG, "Iroh: generated and saved new secret key");
        }
        return bytes;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setOnMessageListener(OnMessageListener l) {
        this.messageListener = l;
    }

    public void setOnTypingListener(OnTypingListener l) {
        this.typingListener = l;
    }

    public void setOnReadReceiptListener(OnReadReceiptListener l) {
        this.readReceiptListener = l;
    }

    public void addOnBlockListener(OnBlockListener l) {
        if (l != null) {
            blockListeners.addIfAbsent(l);
        }
    }

    public void removeOnBlockListener(OnBlockListener l) {
        blockListeners.remove(l);
    }

    public void addOnChainSyncListener(OnChainSyncListener l) {
        if (l != null) {
            chainSyncListeners.addIfAbsent(l);
        }
    }

    public void removeOnChainSyncListener(OnChainSyncListener l) {
        chainSyncListeners.remove(l);
    }

    public void addOnPeerConnectedListener(OnPeerConnectedListener l) {
        if (l != null) {
            peerConnectedListeners.addIfAbsent(l);
        }
    }

    public void removeOnPeerConnectedListener(OnPeerConnectedListener l) {
        peerConnectedListeners.remove(l);
    }

    public void addOnPeerSearchListener(OnPeerSearchListener l) {
        if (l != null) {
            searchListeners.addIfAbsent(l);
        }
    }

    public void removeOnPeerSearchListener(OnPeerSearchListener l) {
        searchListeners.remove(l);
    }

    public void addOnPeerDiscoveredListener(OnPeerDiscoveredListener l) {
        if (l != null) {
            peerDiscoveredListeners.addIfAbsent(l);
        }
    }

    public void removeOnPeerDiscoveredListener(OnPeerDiscoveredListener l) {
        peerDiscoveredListeners.remove(l);
    }

    /** Запуск без Android-контекста: работоспособно только там, где контекст не требуется. */
    public void start() {
        start(null);
    }

    public synchronized void start(Context ctx) {
        if (started.get()) {
            Log.i(TAG, "Iroh: already started, id = " + endpointId);
            notifyStarted(endpointId);
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            // Старт уже идёт — второй Endpoint не поднимаем, дождёмся текущего.
            Log.i(TAG, "Iroh: start already in progress");
            return;
        }
        if (exec.isShutdown()) {
            exec = Executors.newSingleThreadExecutor();
        }
        irohStartRequestedAtMs = System.currentTimeMillis();
        firstMdnsTimingLogged.set(false);
        firstOutgoingMessageTimingLogged.set(false);
        firstIncomingMessageTimingLogged.set(false);
        timing("Iroh start requested");
        final Context appCtx = ctx != null ? ctx.getApplicationContext() : null;
        if (appCtx != null) {
            appContext = appCtx;
            lastNotifiedNetworkType = NetworkType.current(appCtx);
            MarketMakerAPI.initialize(appCtx);
            NetworkFileLogger.init(appCtx);
            registerDozeReceiver(appCtx);
            try {
                deviceId = KeyManager.getOrCreateDeviceId(appCtx);
                if (deviceId != null && !deviceId.isEmpty()) {
                    Log.i(TAG, "Iroh: device_id loaded");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to load device_id", t);
                deviceId = "";
            }
        }
        exec.submit(() -> {
            System.setProperty("uniffi.component.iroh_ffi.libraryOverride", "iroh_ffi");
            // Локальный P2P-узел (WebSocket-скан Wi-Fi) поднимаем первым: он даёт
            // быстрый прямой контакт по IP до запуска Relay/DNS.
            try {
                P2PNode.getInstance().startNode(8080);
                Log.i(TAG, "P2P: local node started on port 8080");
            } catch (Throwable t) {
                Log.w(TAG, "P2P: local node start failed: " + t);
            }
            // Этап 1: загрузка нативной библиотеки libiroh_ffi.so.
            Log.i(TAG, "Iroh: loading library");
            try {
                // Инициализация класса тянет за собой System.loadLibrary("iroh_ffi")
                // (static-блок IrohAndroid) — так библиотека грузится до первого FFI-вызова.
                Class.forName("computer.iroh.IrohAndroid", true, IrohNode.class.getClassLoader());
                Class.forName("computer.iroh.Endpoint", true, IrohNode.class.getClassLoader());
                Log.i(TAG, "Iroh: library loaded");
            } catch (Throwable t) {
                failStart("loading library", t);
                return;
            }

            // Этап 2: проброс Android-контекста в нативную сторону.
            Log.i(TAG, "Iroh: installing Android context");
            if (appCtx != null) {
                try {
                    IrohAndroid.installAndroidContext(appCtx);
                    Log.i(TAG, "Iroh: Android context installed");
                } catch (Throwable t) {
                    failStart("installing Android context", t);
                    return;
                }
            } else {
                Log.w(TAG, "Iroh: no Android context passed, DNS resolution may fail");
            }

            // Этап 3: сборка и биндинг Endpoint.
            Log.i(TAG, "Iroh: building endpoint");
            EndpointBuilder builder;
            try {
                builder = new EndpointBuilder();
            } catch (Throwable t) {
                failStart("building endpoint", t);
                return;
            }
            n0PresetApplied = enableDhtDiscovery(builder);
            if (!n0PresetApplied) {
                Log.w(TAG, "Iroh: DHT discovery unavailable, continuing with mDNS/PEX only");
            }
            // Публичный Relay n0.computer: Iroh регистрируется на нём по HTTPS
            // при bind через RelayMode.Custom.
            String relayUrl = configuredSeedRelayUrl();
            if (!relayUrl.isEmpty()) {
                Log.i(TAG, "Relay: using public n0 relay = " + relayUrl);
                try {
                    builder.relayMode(RelayMode.Companion.customFromUrls(
                            Collections.singletonList(relayUrl)));
                    Log.i(TAG, "Relay: custom relay configured = " + relayUrl);
                    NetworkFileLogger.log("Relay configured: " + relayUrl);
                } catch (Throwable t) {
                    Log.w(TAG, "Relay: failed to configure custom relay: " + t);
                    NetworkFileLogger.log("Relay fail: custom config: " + t);
                }
            }
            // Постоянный секретный ключ: endpointId не меняется между запусками.
            try {
                byte[] secretKey = loadOrCreateSecretKey(appCtx);
                builder.secretKey(secretKey);
                Log.i(TAG, "Iroh: persistent secret key applied");
            } catch (Throwable t) {
                failStart("applying secret key", t);
                return;
            }
            // Регистрируем ALPN в TLS-конфигурации endpoint. Без этого peer
            // отвечает "peer doesn't support any known protocol".
            try {
                builder.alpns(Collections.singletonList(alpnBytes()));
                Log.i(TAG, "Iroh: registered ALPN = " + ALPN);
            } catch (Throwable t) {
                failStart("registering ALPN", t);
                return;
            }
            Log.i(TAG, "Iroh: endpoint discovery = "
                    + (n0PresetApplied ? "DHT (n0 Pkarr/DNS) + relays + mDNS" : "mDNS + PEX only"));
            try {
                Object res = BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> {
                    try {
                        return builder.bind(cont);
                    } catch (IrohException e) {
                        throw new RuntimeException(e);
                    }
                });
                if (res instanceof Endpoint) {
                    endpoint = (Endpoint) res;
                } else {
                    failStart("building endpoint", new IllegalStateException("bind returned: " + res));
                    try {
                        Log.e(TAG, "Stability: connection error = bind returned: " + res);
                    } catch (Exception ignored) {
                    }
                    return;
                }
            } catch (Throwable t) {
                failStart("building endpoint", t);
                try {
                    Log.e(TAG, "Stability: connection error = " + (t.getMessage() == null ? t.toString() : t.getMessage()));
                } catch (Exception ignored) {
                }
                return;
            }
            try {
                endpoint.setAlpns(Collections.singletonList(alpnBytes()));
                Log.i(TAG, "Iroh: ALPN active = " + ALPN);
            } catch (Throwable t) {
                failStart("activating ALPN", t);
                return;
            }

            // Этап 4: получаем собственный EndpointId.
            try {
                EndpointId id = endpoint.id();
                if (id != null) {
                    endpointId = id.toString();
                }
            } catch (Throwable t) {
                failStart("reading endpoint id", t);
                return;
            }
            n0OnlineRequested.set(false);
            ensureDefaultUsername(appCtx);
            loadKnownPeers();
            loadPeerUsernames();
            loadPeerWallets();
            loadPeerFrames();
            loadKnownAddrs();
            loadPexCache();
            connections.clear();
            started.set(true);
            refreshSelfDirectAddresses(endpoint);
            startAcceptLoop(endpoint);
            startEndpointWatchers(endpoint);
            scheduleN0OnlineTimer(endpoint);
            scheduleIdleReconnectTimer();
            registerAndroidNetworkCallback(appCtx);
            starting.set(false);
            Log.i(TAG, "Iroh: host started, endpointId = " + endpointId);
            Log.i(TAG, "Iroh: endpoint started, id = " + endpointId);
            if (deviceId != null && !deviceId.isEmpty()) {
                Log.i(TAG, "Iroh: device_id attached to profile = " + deviceId);
            }
            try {
                Log.i(TAG, "Iroh: host address = " + describeAddr(endpoint.addr()));
                Log.i(TAG, "Iroh: bound sockets = " + endpoint.boundSockets());
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to read host address: " + t);
            }
            notifyStarted(endpointId);
            timing("Iroh started: id=" + endpointId);
            startDiscovery();
            startTopicDiscovery();
            startNetworkStrategy(endpoint);
            if (heartbeatRequested) {
                applyHeartbeatState();
            }
            ioExec.submit(this::connectAllKnownPeers);
        });
    }

    /**
     * Wi-Fi: mDNS + локальный P2P-скан, Relay не трогаем, пока P2P находит пира.
     * LTE: скан не запускаем, Relay — основной путь.
     * Ethernet/VPN/unknown: Relay как отложенный fallback.
     */
    private void startNetworkStrategy(Endpoint ep) {
        String type = NetworkType.current(appContext);
        Log.i(TAG, "Network: startup type = " + type);
        timing("network strategy: " + type);

        if (NetworkType.WIFI.equals(type)) {
            timing("Wi-Fi: mDNS + P2P scan start");
            startMdnsDiscovery();
            // ioExec.submit(() -> P2PNode.getInstance().scanLocalNetwork());
            ioExec.submit(() -> {
                // Relay — только страховка: если за 15 секунд P2P никого не нашёл.
                sleepQuietly(15000L);
                if (hasActiveDirectP2PConnection()) {
                    timing("Wi-Fi: relay fallback skipped, direct P2P connection present");
                    Log.i(TAG, "Network: Wi-Fi relay fallback skipped, direct P2P connection present");
                    return;
                }
                timing("Wi-Fi: relay fallback start");
                connectConfiguredRelay(ep);
            });
            return;
        }
        if (NetworkType.CELLULAR.equals(type)) {
            // В LTE локального P2P нет: Relay нужен сразу как единственный путь.
            timing("LTE: relay start");
            Log.i(TAG, "Network: LTE -> P2P subnet scan skipped, relay start");
            ioExec.submit(() -> connectConfiguredRelay(ep));
            return;
        }
        // Ethernet/VPN/unknown: локальный скан не запускаем, Relay — fallback.
        Log.i(TAG, "Network: " + type + " -> P2P subnet scan skipped, relay fallback");
        ioExec.submit(() -> {
            sleepQuietly(15000L);
            timing("network fallback: relay start");
            connectConfiguredRelay(ep);
        });
    }

    /**
     * Есть ли активный прямой P2P-канал: локальный WebSocket-пир из Wi-Fi-скана
     * или живой IP-путь в Iroh-соединении (не relay). В этом случае Relay не нужен.
     */
    public boolean hasActiveDirectP2PConnection() {
        if (P2PNode.getInstance().getConnectedPeers() > 0) {
            return true;
        }
        for (Connection connection : connections.values()) {
            if (!isConnectionAlive(connection)) {
                continue;
            }
            try {
                for (PathSnapshot path : connection.paths()) {
                    if (path.isIp()) {
                        return true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Network: failed to read connection paths: " + t);
            }
        }
        return false;
    }

    /** Установлено ли с пиром прямое IP-соединение (не через Relay). */
    public boolean isDirectPeer(String peerEndpointId) {
        if (peerEndpointId == null) {
            return false;
        }
        Connection connection = connections.get(peerEndpointId.trim());
        if (!isConnectionAlive(connection)) {
            return false;
        }
        try {
            for (PathSnapshot path : connection.paths()) {
                if (path.isIp()) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Network: failed to read peer paths: " + t);
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** При успешном подключении объявляет себя и запрашивает список пиров у собеседника. */
    private void sendInitialPex(String peer) {
        if (peer == null || peer.trim().isEmpty() || peer.equals(endpointId)) {
            return;
        }
        sendPexAnnounceTo(peer.trim());
        sendPexListQuery(peer.trim());
    }

    /** PEX_QUERY без ника: собеседник отвечает списком известных ему пиров. */
    private void sendPexListQuery(String peer) {
        if (peer == null || peer.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("type", PEX_TYPE_QUERY);
            body.put("query", "");
            sendMessage(peer.trim(), PEX_PREFIX + body);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to build PEX list query: " + t);
        }
    }

    private void startEndpointWatchers(Endpoint ep) {
        try {
            HomeRelayCallback relayCallback = new HomeRelayCallback() {
                @Override
                public Object onChange(List<String> relayUrls,
                                       Continuation<? super Unit> continuation) {
                    try {
                        String configuredRelay = configuredSeedRelayUrl();
                        boolean seedRelay = false;
                        if (relayUrls == null || relayUrls.isEmpty()) {
                            homeRelayUrl = "";
                            seedRelayActive = false;
                            Log.i(TAG, "Relay: no home relay connection, will retry");
                            NetworkFileLogger.log("Relay fail: no home relay connection");
                            if (n0Online) {
                                n0Online = false;
                                n0OnlineRequested.set(false);
                            }
                        } else {
                            homeRelayUrl = relayUrls.get(0);
                            for (String url : relayUrls) {
                                Log.i(TAG, "Relay: reserved slot on " + url);
                                if (sameRelayUrl(url, configuredRelay)) {
                                    seedRelay = true;
                                }
                            }
                            seedRelayActive = seedRelay;
                            Log.i(TAG, "Relay: fallback relay = " + homeRelayUrl
                                    + ", seed relay active = " + seedRelayActive);
                            NetworkFileLogger.log("Relay connected: " + homeRelayUrl
                                    + ", seed=" + seedRelayActive);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Relay: callback failed: " + t);
                        NetworkFileLogger.log("Relay fail: callback: " + t);
                    }
                    return Unit.INSTANCE;
                }
            };
            homeRelayCallback = relayCallback;
            homeRelayWatchHandle = ep.watchHomeRelay(relayCallback);
            Log.i(TAG, "Iroh: relay watcher registered");
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: relay watcher registration failed: " + t);
            NetworkFileLogger.log("Relay fail: watcher registration: " + t);
        }

        try {
            NetworkChangeCallback callback = new NetworkChangeCallback() {
                @Override
                public Object onChange(Continuation<? super Unit> continuation) {
                    try {
                        notifyNetworkChange();
                    } catch (Throwable t) {
                        Log.w(TAG, "Iroh: native network change handling failed: " + t);
                    }
                    return Unit.INSTANCE;
                }
            };
            nativeNetworkChangeCallback = callback;
            nativeNetworkWatchHandle = ep.watchNetworkChange(callback);
            Log.i(TAG, "Iroh: native network watcher registered");
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: native network watcher registration failed: " + t);
        }
    }

    private void stopEndpointWatchers() {
        WatchHandle relayHandle = homeRelayWatchHandle;
        homeRelayWatchHandle = null;
        if (relayHandle != null) {
            try {
                relayHandle.destroy();
            } catch (Throwable ignored) {
            }
        }
        WatchHandle networkHandle = nativeNetworkWatchHandle;
        nativeNetworkWatchHandle = null;
        if (networkHandle != null) {
            try {
                networkHandle.destroy();
            } catch (Throwable ignored) {
            }
        }
        homeRelayCallback = null;
        nativeNetworkChangeCallback = null;
    }

    private void startMdnsDiscovery() {
        Context ctx = appContext;
        String id = endpointId;
        if (ctx == null || id == null || id.isEmpty()) {
            Log.w(TAG, "mDNS: discovery not started (no context or endpointId)");
            return;
        }
        stopMdnsDiscovery();
        List<String> direct = selfDirectAddresses;
        int port = direct.isEmpty() ? 8080 : portFromAddress(direct.get(0));
        if (port <= 0) {
            port = 8080;
        }
        final int mdnsPort = port;
        MdnsDiscovery discovery = new MdnsDiscovery(ctx, id, mdnsPort, (peerId, address) -> {
            if (peerId == null || peerId.trim().isEmpty()
                    || peerId.trim().equals(endpointId)) {
                return;
            }
            String peer = peerId.trim();
            Log.i(TAG, "mDNS: found peer " + peer + " addrs=" + address);
            NetworkFileLogger.log("Peer found (mDNS): " + peer + " addrs=" + address);
            P2PNode.getInstance().rememberPeerFromMdns(peer, address);
            if (firstMdnsTimingLogged.compareAndSet(false, true)) {
                timing("first mDNS hit: peer=" + peer + ", addrs=" + address);
            }
            // mDNS даёт прямой IP:port пира. Сохраняем его всегда, даже если
            // собственные direct-адреса ещё не получены: это единственный
            // адрес для fallback, когда DNS/Pkarr заблокирован.
            rememberPeerAddress(peer, address);
            if (addKnownPeer(peer)) {
                persistKnownPeers();
            }
            connectToPeer(peer);
        });
        mdnsDiscovery = discovery;
        discovery.start();
    }

    private void stopMdnsDiscovery() {
        MdnsDiscovery discovery = mdnsDiscovery;
        mdnsDiscovery = null;
        if (discovery != null) {
            discovery.stop();
        }
    }

    private void startAcceptLoop(Endpoint ep) {
        Thread t = new Thread(() -> {
            Log.i(TAG, "Iroh: accept loop started");
            while (ep == endpoint) {
                try {
                    Incoming incoming = IrohIo.acceptNext(ep);
                    if (incoming == null) {
                        break;
                    }
                    ioExec.submit(() -> handleIncoming(incoming));
                } catch (Throwable e) {
                    if (ep == endpoint) {
                        Log.e(TAG, "Iroh: accept loop failed", e);
                        try {
                            Log.e(TAG, "Stability: error = "
                                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                }
            }
            Log.i(TAG, "Iroh: accept loop stopped");
        }, "iroh-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
    }

    private void handleIncoming(Incoming incoming) {
        try {
            Connection conn = IrohIo.acceptIncoming(incoming);
            if (conn == null) {
                return;
            }
            String remote = conn.remoteId().toString();
            connections.put(remote, conn);
            NetworkFileLogger.log("P2P connect (incoming): " + remote);
            if (addKnownPeer(remote)) {
                persistKnownPeers();
            }
            sendProfile(remote);
            sendInitialPex(remote);
            requestChainFromPeer(remote);
            sendMarketItemsTo(remote);
            sendExchangeOffersTo(remote);
            sendPriceUpdatesTo(remote);
            notifyPeerConnected(remote);
            String dev = deviceId;
            if (dev != null && !dev.isEmpty()) {
                log("Iroh: incoming connection from " + remote + ", ALPN = " + describeAlpn(conn.alpn())
                        + " (device_id=" + dev + ")");
            } else {
                log("Iroh: incoming connection from " + remote + ", ALPN = " + describeAlpn(conn.alpn()));
            }
            try {
                Log.i(TAG, "Stability: connected to " + remote);
            } catch (Exception ignored) {
            }
            readLoop(conn, remote);
        } catch (Throwable t) {
            Log.e(TAG, "Iroh: incoming connection failed", t);
            try {
                Log.e(TAG, "Stability: connection error = " + (t.getMessage() == null ? t.toString() : t.getMessage()));
            } catch (Exception ignored) {
            }
        }
    }

    private void readLoop(Connection conn, String remote) {
        while (connections.get(remote) == conn) {
            try {
                BiStream bi = IrohIo.acceptBi(conn);
                if (bi == null) {
                    return;
                }
                try {
                    RecvStream recv = bi.recv();
                    String text = IrohIo.receiveText(recv);
                    if (text == null || (!text.startsWith(PING_PREFIX)
                            && !text.startsWith(PONG_PREFIX))) {
                        Log.i(TAG, "Received raw: " + text);
                    }
                    if (text != null && !text.isEmpty()) {
                        if (text.startsWith(PING_PREFIX)) {
                            sendMessage(remote, PONG_PREFIX);
                            Log.d("Heartbeat", "Pong sent to " + remote);
                        } else if (text.startsWith(PONG_PREFIX)) {
                            Log.d("Heartbeat", "Pong received from " + remote);
                        } else if (text.startsWith(PROFILE_PREFIX)) {
                            handleProfile(remote, text.substring(PROFILE_PREFIX.length()));
                        } else if (text.startsWith(PEX_PREFIX)) {
                            handlePeerExchange(remote, text.substring(PEX_PREFIX.length()));
                        } else if (text.startsWith(SEARCH_REQUEST_PREFIX)) {
                            handleSearchRequest(remote,
                                    text.substring(SEARCH_REQUEST_PREFIX.length()));
                        } else if (text.startsWith(SEARCH_RESPONSE_PREFIX)) {
                            handleSearchResponse(text.substring(SEARCH_RESPONSE_PREFIX.length()));
                        } else if (text.startsWith(CHAT_MSG_PREFIX)) {
                            handleChatMessage(remote,
                                    text.substring(CHAT_MSG_PREFIX.length()));
                        } else if (text.startsWith(TYPING_START_PREFIX)) {
                            notifyTypingStart(remote);
                        } else if (text.startsWith(TYPING_STOP_PREFIX)) {
                            notifyTypingStop(remote);
                        } else if (text.startsWith(READ_RECEIPT_PREFIX)) {
                            handleReadReceipt(remote,
                                    text.substring(READ_RECEIPT_PREFIX.length()));
                        } else if (text.startsWith(TransferManager.TRANSFER_PREFIX)) {
                            TransferManager.handleIncomingTransfer(appContext, remote,
                                    text.substring(TransferManager.TRANSFER_PREFIX.length()));
                        } else if (text.startsWith(MARKET_ITEM_PREFIX)) {
                            handleIncomingMarketItem(remote,
                                    text.substring(MARKET_ITEM_PREFIX.length()));
                        } else if (text.startsWith(MARKET_MAKER_REQ_PREFIX)) {
                            handleIncomingMarketMakerRequest(remote,
                                    text.substring(MARKET_MAKER_REQ_PREFIX.length()));
                        } else if (text.startsWith(P2P_DISPUTE_VOTE_PREFIX)) {
                            handleIncomingDisputeVote(remote,
                                    text.substring(P2P_DISPUTE_VOTE_PREFIX.length()));
                        } else if (text.startsWith(P2P_DISPUTE_STATUS_PREFIX)) {
                            handleIncomingDisputeStatus(remote,
                                    text.substring(P2P_DISPUTE_STATUS_PREFIX.length()));
                        } else if (text.startsWith(P2P_DISPUTE_PREFIX)) {
                            handleIncomingDispute(remote,
                                    text.substring(P2P_DISPUTE_PREFIX.length()));
                        } else if (text.startsWith(P2P_EXCHANGE_REQUEST_PREFIX)) {
                            handleIncomingExchangeRequest(remote,
                                    text.substring(P2P_EXCHANGE_REQUEST_PREFIX.length()));
                        } else if (text.startsWith(P2P_EXCHANGE_ORDER_STATUS_PREFIX)) {
                            handleIncomingExchangeOrderStatus(remote,
                                    text.substring(P2P_EXCHANGE_ORDER_STATUS_PREFIX.length()));
                        } else if (text.startsWith(P2P_ORDER_PREFIX)) {
                            handleIncomingExchangeOrder(remote,
                                    text.substring(P2P_ORDER_PREFIX.length()));
                        } else if (text.startsWith(P2P_EXCHANGE_ORDER_PREFIX)) {
                            handleIncomingExchangeOrder(remote,
                                    text.substring(P2P_EXCHANGE_ORDER_PREFIX.length()));
                        } else if (text.startsWith(P2P_EXCHANGE_STATUS_PREFIX)) {
                            handleIncomingExchangeStatus(remote,
                                    text.substring(P2P_EXCHANGE_STATUS_PREFIX.length()));
                        } else if (text.startsWith(P2P_EXCHANGE_REMOVE_PREFIX)) {
                            handleIncomingExchangeRemove(remote,
                                    text.substring(P2P_EXCHANGE_REMOVE_PREFIX.length()));
                        } else if (text.startsWith(P2P_EXCHANGE_PREFIX)) {
                            handleIncomingExchangeOffer(remote,
                                    text.substring(P2P_EXCHANGE_PREFIX.length()));
                        } else if (text.startsWith(PRICE_UPDATE_PREFIX)) {
                            handleIncomingPriceUpdate(remote,
                                    text.substring(PRICE_UPDATE_PREFIX.length()));
                        } else if (text.startsWith(GET_BLOCK_REQ_PREFIX)) {
                            handleBlockRequest(remote,
                                    text.substring(GET_BLOCK_REQ_PREFIX.length()));
                        } else if (text.startsWith(BLOCK_RESP_PREFIX)) {
                            handleBlockResponse(remote,
                                    text.substring(BLOCK_RESP_PREFIX.length()));
                        } else if (text.startsWith(GET_CHAIN_REQ_PREFIX)) {
                            handleChainRequest(remote,
                                    text.substring(GET_CHAIN_REQ_PREFIX.length()));
                        } else if (text.startsWith(CHAIN_RESP_PREFIX)) {
                            handleChainResponse(remote,
                                    text.substring(CHAIN_RESP_PREFIX.length()));
                        } else if (text.startsWith(NEWS_BROADCAST_PREFIX)) {
                            handleIncomingNews(remote,
                                    text.substring(NEWS_BROADCAST_PREFIX.length()));
                        } else if (isBlockJson(text)) {
                            if (validateBlock(remote, text)) {
                                notifyBlock(remote, text);
                            }
                        } else {
                            if (TransferManager.isTechnicalTransferPayload(text)) {
                                Log.i(TAG, "Iroh: routing raw transfer payload from "
                                        + remote + " to TransferManager");
                                TransferManager.handleIncomingTransfer(
                                        appContext, remote, text);
                                continue;
                            }
                            if (firstIncomingMessageTimingLogged.compareAndSet(false, true)) {
                                timing("first message received: peer=" + remote
                                        + ", bytes=" + text.length());
                            }
                            notifyMessage(remote, text);
                        }
                    }
                } finally {
                    bi.close();
                }
            } catch (Throwable t) {
                if (connections.get(remote) == conn) {
                    Log.w(TAG, "Iroh: receive loop ended for " + remote + ": " + t);
                    try {
                        Log.e(TAG, "Stability: error = "
                                + (t.getMessage() == null ? t.toString() : t.getMessage()));
                        Log.i(TAG, "Stability: disconnected from " + remote);
                    } catch (Exception ignored) {
                    }
                }
                return;
            }
        }
    }

    private void handleChatMessage(String from, String payload) {
        String messageId = "";
        String text = payload == null ? "" : payload;
        long replyToId = 0;
        String replyPreview = "";
        String messageType = "";
        String audioBase64 = "";
        if (TransferManager.isTechnicalTransferPayload(text)) {
            Log.i(TAG, "Iroh: routing chat-wrapped transfer from "
                    + from + " to TransferManager");
            TransferManager.handleIncomingTransfer(appContext, from, text);
            return;
        }
        try {
            JSONObject body = new JSONObject(text);
            messageId = body.optString("id", "").trim();
            text = body.optString("text", "");
            messageType = body.optString("type", ChatDatabase.TYPE_TEXT);
            audioBase64 = body.optString("audio", "");
            replyToId = Math.max(0, body.optLong("replyTo", 0));
            replyPreview = body.optString("replyPreview", "");
            if (replyPreview.length() > 1000) {
                replyPreview = replyPreview.substring(0, 1000);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid chat message envelope: " + t);
        }
        if (TransferManager.isTechnicalTransferPayload(text)) {
            Log.i(TAG, "Iroh: routing transfer text from "
                    + from + " to TransferManager");
            TransferManager.handleIncomingTransfer(appContext, from, text);
            return;
        }
        boolean fresh = messageId.isEmpty() || firstIncomingDelivery(messageId);
        if (!fresh) {
            return;
        }
        if (ChatDatabase.TYPE_AUDIO.equals(messageType) && !audioBase64.isEmpty()) {
            String audioPath = saveIncomingAudioFile(messageId, audioBase64);
            if (audioPath != null) {
                notifyAudioMessage(from, audioPath, replyToId, replyPreview);
            }
            return;
        }
        if (!text.isEmpty()) {
            notifyMessage(from, text, replyToId, replyPreview);
        }
    }

    /** Декодирует входящее аудио и сохраняет в файл; null при ошибке. */
    private String saveIncomingAudioFile(String messageId, String audioBase64) {
        if (appContext == null) {
            return null;
        }
        try {
            byte[] audio = Base64.decode(audioBase64, Base64.NO_WRAP);
            if (audio == null || audio.length == 0) {
                return null;
            }
            File dir = new File(appContext.getExternalFilesDir(null), "voice");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String name = messageId.isEmpty()
                    ? "voice_" + UUID.randomUUID().toString() : messageId;
            File file = new File(dir, "voice_" + name.replaceAll("[^A-Za-z0-9_-]", "")
                    + ".aac");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(audio);
                out.flush();
            }
            return file.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to save incoming voice message", t);
            return null;
        }
    }

    /** Обработка read receipt: собеседник прочитал наши сообщения до момента upTo. */
    private void handleReadReceipt(String from, String payload) {
        long upTo = 0L;
        try {
            JSONObject body = new JSONObject(payload == null ? "" : payload);
            upTo = Math.max(0L, body.optLong("upTo", 0L));
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid read receipt: " + t);
        }
        if (upTo <= 0L) {
            return;
        }
        int updated = 0;
        if (appContext != null) {
            try {
                updated = ChatDatabase.getInstance(appContext)
                        .markOutgoingMessagesRead(from == null ? "" : from, upTo);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to apply read receipt", t);
            }
        }
        if (updated > 0) {
            OnReadReceiptListener l = readReceiptListener;
            if (l != null) {
                try {
                    l.onPeerRead(from == null ? "" : from, upTo);
                } catch (Throwable t) {
                    Log.w(TAG, "Iroh: read receipt listener failed", t);
                }
            }
        }
    }

    /**
     * Возвращает true при первом получении сообщения с таким id.
     * Копия, пришедшая вторым каналом (P2P + Relay), не показывается повторно.
     */
    private boolean firstIncomingDelivery(String messageId) {
        long now = System.currentTimeMillis();
        Long previous = incomingMessageIds.putIfAbsent(messageId, now);
        if (previous != null && now - previous < INCOMING_DEDUP_TTL_MS) {
            return false;
        }
        if (previous != null) {
            incomingMessageIds.put(messageId, now);
        }
        if (incomingMessageIds.size() > INCOMING_DEDUP_MAX) {
            for (Map.Entry<String, Long> entry : incomingMessageIds.entrySet()) {
                if (now - entry.getValue() >= INCOMING_DEDUP_TTL_MS) {
                    incomingMessageIds.remove(entry.getKey(), entry.getValue());
                }
            }
        }
        return true;
    }

    private void notifyTypingStart(String from) {
        OnTypingListener l = typingListener;
        if (l == null) {
            return;
        }
        try {
            l.onTypingStart(from == null ? "" : from);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: typing listener failed", t);
        }
    }

    private void notifyTypingStop(String from) {
        OnTypingListener l = typingListener;
        if (l == null) {
            return;
        }
        try {
            l.onTypingStop(from == null ? "" : from);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: typing listener failed", t);
        }
    }

    private void notifyMessage(String from, String text) {
        notifyMessage(from, text, 0, "");
    }

    private void notifyMessage(String from, String text, long replyToId,
                               String replyPreview) {
        String peer = from == null ? "" : from;
        String content = text == null ? "" : text;
        if (TransferManager.isTechnicalTransferPayload(content)) {
            Log.i(TAG, "Iroh: technical transfer payload suppressed from chat");
            TransferManager.handleIncomingTransfer(appContext, peer, content);
            return;
        }
        if (content.startsWith(PING_PREFIX) || content.startsWith(PONG_PREFIX)) {
            Log.d("Heartbeat", "Heartbeat packet suppressed from chat");
            return;
        }
        if (ChatActivity.isTechnicalChatText(content)) {
            Log.i(TAG, "Iroh: technical chain payload suppressed from chat");
            return;
        }
        Log.i(TAG, "Msg received: from=" + peer + ", text=" + content
                + ", time=" + System.currentTimeMillis());
        if (appContext != null) {
            try {
                ChatDatabase.getInstance(appContext)
                        .saveIncomingMessage(peer, peer, content, replyToId, replyPreview);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to persist incoming message", t);
            }
            try {
                UnreadCounter.getInstance(appContext).increment(peer);
            } catch (Throwable ignored) {
            }
            if (!ChatActivity.isActiveFor(peer)) {
                NotificationHelper.showMessageNotification(
                        appContext, peer, getPeerDisplayName(peer), content);
            }
        }

        OnMessageListener l = messageListener;
        if (l != null) {
            try {
                l.onMessage(peer, content);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: message listener failed", t);
            }
        }
    }

    /** Доставляет входящее голосовое сообщение: сохраняет в БД и уведомляет UI. */
    private void notifyAudioMessage(String from, String audioPath,
                                    long replyToId, String replyPreview) {
        String peer = from == null ? "" : from;
        String path = audioPath == null ? "" : audioPath;
        Log.i(TAG, "Voice message received: from=" + peer
                + ", file=" + path + ", time=" + System.currentTimeMillis());
        String label = "";
        if (appContext != null) {
            try {
                label = appContext.getString(R.string.chat_voice_message);
            } catch (Throwable ignored) {
            }
            try {
                ChatDatabase.getInstance(appContext)
                        .saveIncomingAudioMessage(peer, peer, path, replyToId, replyPreview);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to persist incoming voice message", t);
            }
            try {
                UnreadCounter.getInstance(appContext).increment(peer);
            } catch (Throwable ignored) {
            }
            if (!ChatActivity.isActiveFor(peer)) {
                NotificationHelper.showMessageNotification(
                        appContext, peer, getPeerDisplayName(peer), label);
            }
        }
        OnMessageListener l = messageListener;
        if (l != null) {
            try {
                l.onMessage(peer, label);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: message listener failed", t);
            }
        }
    }

    /**
     * Запускает фоновое обнаружение пиров.
     *
     * n0-пресет (applyN0) включает DNS/relay discovery: по Endpoint ID он находит
     * актуальные адреса, но не умеет перечислять все узлы сети. Поэтому
     * обнаружение строится на базовом peer exchange: при подключении пиры
     * обмениваются PEX_ANNOUNCE / PEX_QUERY и автоматически соединяются.
     */
    public void startDiscovery() {
        if (!discoveryRunning.compareAndSet(false, true)) {
            return;
        }
        loadKnownPeers();
        Thread t = new Thread(() -> {
            log("Iroh: discovery started (n0 DNS + peer exchange)");
            while (discoveryRunning.get()) {
                try {
                    Endpoint ep = endpoint;
                    if (!started.get() || ep == null) {
                        Thread.sleep(1000L);
                        continue;
                    }
                    syncKnownFromConnections();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable e) {
                    Log.w(TAG, "Iroh: discovery iteration failed: " + e);
                }
                try {
                    Thread.sleep(DISCOVERY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            discoveryRunning.set(false);
            log("Iroh: discovery stopped");
        }, "iroh-discovery");
        t.setDaemon(true);
        discoveryThread = t;
        t.start();
    }

    private void startTopicDiscovery() {
        Log.e("Radar", "=== startTopicDiscovery() CALLED ===");
        Endpoint ep = endpoint;
        if (ep == null || !started.get()) {
            Log.e("Radar", "Radar ABORTED: ep=" + (ep != null) + ", started=" + started.get());
            return;
        }
        stopTopicDiscovery();
        ioExec.submit(() -> {
            GossipNode gn = null;
            TopicSubscription sub = null;
            try {
                gn = IrohIo.spawnGossipNode(ep);
                TopicDiscoveryOptions opts = IrohIo.defaultTopicDiscoveryOptions();
                sub = IrohIo.subscribeWithDiscovery(
                        gn, TOPIC_GLOBAL.getBytes(StandardCharsets.UTF_8),
                        Collections.emptyList(), opts);
                Log.e("Radar", "=== RADAR SUBSCRIBED SUCCESSFULLY to " + TOPIC_GLOBAL + " ===");
                gossipNode = gn;
                topicSubscription = sub;
                Log.i(TAG, "TopicDiscovery: subscribed to " + TOPIC_GLOBAL);
                startTopicEventLoop(sub);
            } catch (Throwable t) {
                Log.w(TAG, "TopicDiscovery: start failed: " + t);
                safeCloseTopicSubscription(sub);
                safeCloseGossipNode(gn);
                if (gossipNode == gn) {
                    gossipNode = null;
                }
                if (topicSubscription == sub) {
                    topicSubscription = null;
                }
            }
        });
    }

    private void startTopicEventLoop(TopicSubscription sub) {
        Thread old = topicEventThread;
        if (old != null) {
            old.interrupt();
        }
        Thread t = new Thread(() -> {
            Log.i(TAG, "TopicDiscovery: event loop started");
            while (started.get() && topicSubscription == sub) {
                try {
                    TopicEvent ev = IrohIo.nextTopicEvent(sub);
                    if (ev == null) {
                        Log.i(TAG, "TopicDiscovery: subscription closed");
                        break;
                    }
                    handleTopicEvent(ev);
                } catch (Throwable tEx) {
                    if (tEx instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (started.get() && topicSubscription == sub) {
                        Log.w(TAG, "TopicDiscovery: event loop error: " + tEx);
                    }
                    sleepQuietly(500L);
                }
            }
            Log.i(TAG, "TopicDiscovery: event loop stopped");
        }, "iroh-topic-discovery");
        t.setDaemon(true);
        topicEventThread = t;
        t.start();
    }

    private void handleTopicEvent(TopicEvent ev) {
        if (ev instanceof TopicEvent.NeighborUp) {
            String peer = ((TopicEvent.NeighborUp) ev).getEndpointId();
            if (peer == null || peer.trim().isEmpty()) return;
            peer = peer.trim();
            if (peer.equals(endpointId) || !isValidEndpointId(peer)) return;
            Log.i(TAG, "TopicDiscovery: NeighborUp " + peer);
            if (addKnownPeer(peer)) {
                persistKnownPeers();
            }
            notifyPeerDiscovered(peer);
            notifyPeerConnected(peer);
            connectToPeer(peer);
        } else if (ev instanceof TopicEvent.NeighborDown) {
            String peer = ((TopicEvent.NeighborDown) ev).getEndpointId();
            if (peer != null) {
                Log.i(TAG, "TopicDiscovery: NeighborDown " + peer.trim());
            }
        } else if (ev instanceof TopicEvent.Lagged) {
            Log.w(TAG, "TopicDiscovery: event stream lagged");
        }
    }

    private void stopTopicDiscovery() {
        Thread t = topicEventThread;
        topicEventThread = null;
        if (t != null) {
            t.interrupt();
        }
        TopicSubscription sub = topicSubscription;
        topicSubscription = null;
        if (sub != null) {
            try {
                sub.stopDiscovery();
            } catch (Throwable ignored) {
            }
            safeCloseTopicSubscription(sub);
        }
        GossipNode gn = gossipNode;
        gossipNode = null;
        safeCloseGossipNode(gn);
    }

    private static void safeCloseTopicSubscription(TopicSubscription sub) {
        if (sub == null) return;
        try {
            sub.close();
        } catch (Throwable ignored) {
        }
    }

    private static void safeCloseGossipNode(GossipNode gn) {
        if (gn == null) return;
        try {
            gn.close();
        } catch (Throwable ignored) {
        }
    }

    private void scheduleN0OnlineTimer(Endpoint ep) {
        ScheduledFuture<?> old = n0OnlineTask;
        if (old != null) {
            old.cancel(false);
        }
        n0OnlineTask = reconnectExec.scheduleAtFixedRate(() -> {
            if (endpoint == ep && started.get() && n0PresetApplied) {
                requestN0Online(ep);
            }
        }, N0_ONLINE_INTERVAL_MS, N0_ONLINE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelN0OnlineTimer() {
        ScheduledFuture<?> task = n0OnlineTask;
        n0OnlineTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void scheduleIdleReconnectTimer() {
        ScheduledFuture<?> old = idleReconnectTask;
        if (old != null) {
            old.cancel(false);
        }
        idleReconnectTask = reconnectExec.scheduleAtFixedRate(() -> {
            if (!started.get() || endpoint == null) {
                return;
            }
            if (!getConnectedPeers().isEmpty()
                    || P2PNode.getInstance().getConnectedPeers() > 0) {
                return;
            }
            ioExec.submit(this::connectKnownPeers);
        }, IDLE_RECONNECT_INTERVAL_MS, IDLE_RECONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelIdleReconnectTimer() {
        ScheduledFuture<?> task = idleReconnectTask;
        idleReconnectTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Ждёт подключения к relay/DNS в фоне, чтобы n0 discovery был прогрето. */
    private void requestN0Online(Endpoint ep) {
        if (!n0OnlineRequested.compareAndSet(false, true)) {
            return;
        }
        ioExec.submit(() -> {
            try {
                BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> ep.online(cont));
                n0Online = true;
                Log.i(TAG, "Pkarr: record published for " + endpointId);
                Log.i(TAG, "Relay: home relay = "
                        + (homeRelayUrl == null || homeRelayUrl.isEmpty()
                        ? "none/pending" : homeRelayUrl));
                log("Iroh: n0 discovery online (Pkarr/DNS record published automatically)");
                refreshSelfDirectAddresses(ep);
            } catch (Throwable t) {
                n0Online = false;
                n0OnlineRequested.set(false);
                Log.w(TAG, "Iroh: n0 discovery offline: " + t
                        + " (will retry in " + (DISCOVERY_INTERVAL_MS / 1000) + "s)");
            }
        });
    }

    private void syncKnownFromConnections() {
        boolean changed = false;
        for (String peer : getConnectedPeers()) {
            changed |= addKnownPeer(peer);
        }
        if (changed) {
            persistKnownPeers();
        }
    }

    private void sendPexAnnounceTo(String peer) {
        if (peer == null || peer.isEmpty() || peer.equals(endpointId)) {
            return;
        }
        try {
            JSONObject entry = new JSONObject();
            entry.put("endpointId", endpointId);
            String username = getLocalUsername();
            if (!username.isEmpty()) {
                entry.put("username", username);
            }
            entry.put("timestamp", System.currentTimeMillis());

            JSONObject body = new JSONObject();
            body.put("type", PEX_TYPE_ANNOUNCE);
            body.put("entry", entry);
            sendMessage(peer, PEX_PREFIX + body);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to build PEX_ANNOUNCE: " + t);
        }
    }

    private void handlePeerExchange(String from, String payload) {
        String raw = payload == null ? "" : payload.trim();
        try {
            if (raw.startsWith("[")) {
                // Совместимость со старым форматом: массив Endpoint ID без ников.
                boolean changed = false;
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    changed |= registerPeerExchangeEntry(array.optString(i, ""), "");
                }
                if (changed) {
                    persistKnownPeers();
                    persistPeerUsernames();
                    connectKnownPeers();
                }
                return;
            }

            JSONObject body = new JSONObject(raw);
            String type = body.optString("type", "");
            if (type.isEmpty()) {
                // Старый PEX-список известных пиров.
                boolean changed = false;
                JSONArray array = body.optJSONArray("peers");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject entry = array.optJSONObject(i);
                        if (entry != null) {
                            changed |= registerPeerExchangeEntry(
                                    entry.optString("id", ""),
                                    entry.optString("username", ""));
                        } else {
                            changed |= registerPeerExchangeEntry(array.optString(i, ""), "");
                        }
                    }
                }
                if (changed) {
                    persistKnownPeers();
                    persistPeerUsernames();
                    connectKnownPeers();
                }
                return;
            }

            if (PEX_TYPE_ANNOUNCE.equals(type)) {
                handlePexAnnounce(from, body);
            } else if (PEX_TYPE_QUERY.equals(type)) {
                handlePexQuery(from, body);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid peer exchange payload: " + t);
        }
    }

    private void handlePexAnnounce(String from, JSONObject body) {
        JSONObject entry = body.optJSONObject("entry");
        if (entry == null) {
            return;
        }
        String peer = entry.optString("endpointId", "").trim();
        if (peer.isEmpty() || peer.equals(endpointId)) {
            return;
        }
        String username = normalizeUsername(entry.optString("username", ""));
        long timestamp = entry.optLong("timestamp", System.currentTimeMillis());
        if (storePexEntry(peer, username, timestamp)) {
            persistPexCache();
            persistPeerUsernames();
            persistKnownPeers();
            connectKnownPeers();
        }
    }

    private void handlePexQuery(String from, JSONObject body) {
        String query = normalizeSearchQuery(body.optString("query", ""));
        AddressBook.Contact contact = query.isEmpty() ? null : findPexContact(query);
        sendPexPeerList(from, contact);
    }

    /** Отправляет пиру текущий список известных узлов (или один найденный контакт). */
    private void sendPexPeerList(String from, AddressBook.Contact only) {
        String target = from == null ? "" : from.trim();
        if (target.isEmpty() || target.equals(endpointId)) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            if (only != null && !only.endpointId.equals(endpointId)
                    && !only.endpointId.equals(target)) {
                JSONObject entry = new JSONObject();
                entry.put("id", only.endpointId);
                if (!only.username.isEmpty()) {
                    entry.put("username", only.username);
                }
                array.put(entry);
            } else {
                int count = 0;
                for (String peer : getKnownPeers()) {
                    if (peer.equals(endpointId) || peer.equals(target)) {
                        continue;
                    }
                    JSONObject entry = new JSONObject();
                    entry.put("id", peer);
                    String username = getPeerUsername(peer);
                    if (!username.isEmpty()) {
                        entry.put("username", username);
                    }
                    array.put(entry);
                    if (++count >= MAX_PEX_PEERS) {
                        break;
                    }
                }
            }
            JSONObject body = new JSONObject();
            body.put("peers", array);
            sendMessage(target, PEX_PREFIX + body);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to send PEX peer list: " + t);
        }
    }

    private AddressBook.Contact findPexContact(String query) {
        String id = query == null ? "" : query.trim();
        if (isValidEndpointId(id)) {
            if (id.equals(endpointId)) {
                String ownName = getLocalUsername();
                return new AddressBook.Contact(
                        ownName.isEmpty() ? id : ownName, id);
            }
            if (pexCache.containsKey(id) || getKnownPeers().contains(id)
                    || getConnectedPeers().contains(id)) {
                String knownName = getPeerUsername(id);
                if (knownName.isEmpty()) {
                    knownName = AddressBook.getUsername(appContext, id);
                }
                return new AddressBook.Contact(knownName, id);
            }
            return null;
        }
        AddressBook.Contact local = AddressBook.findByUsername(appContext, query);
        if (local != null) {
            return local;
        }
        for (Map.Entry<String, PexEntry> entry : pexCache.entrySet()) {
            PexEntry value = entry.getValue();
            if (value != null && !value.username.isEmpty()
                    && value.username.equalsIgnoreCase(query)) {
                return new AddressBook.Contact(value.username, entry.getKey());
            }
        }
        for (String peer : getKnownPeers()) {
            String username = getPeerUsername(peer);
            if (!username.isEmpty() && username.equalsIgnoreCase(query)) {
                return new AddressBook.Contact(username, peer);
            }
        }
        String own = getLocalUsername();
        if (!own.isEmpty() && own.equalsIgnoreCase(query)) {
            return new AddressBook.Contact(own, endpointId);
        }
        return null;
    }

    private boolean storePexEntry(String rawId, String rawUsername, long timestamp) {
        String peer = rawId == null ? "" : rawId.trim();
        if (peer.isEmpty() || peer.equals(endpointId)) {
            return false;
        }
        String username = normalizeUsername(rawUsername);
        PexEntry previous = pexCache.get(peer);
        if (previous != null && previous.timestamp > timestamp) {
            return false;
        }
        boolean changed = previous == null
                || previous.timestamp != timestamp
                || !previous.username.equals(username);
        if (changed) {
            pexCache.put(peer, new PexEntry(username, timestamp));
        }
        if (!username.isEmpty()) {
            String oldName = peerUsernames.put(peer, username);
            if (!username.equals(oldName)) {
                changed = true;
            }
            AddressBook.saveContact(appContext, username, peer);
        }
        if (addKnownPeer(peer)) {
            changed = true;
        }
        return changed;
    }

    /** Обрабатывает одну запись PEX: запоминает Endpoint ID и ник в адресной книге. */
    private boolean registerPeerExchangeEntry(String rawId, String rawUsername) {
        String peer = rawId == null ? "" : rawId.trim();
        if (peer.isEmpty() || peer.equals(endpointId)) {
            return false;
        }
        boolean changed = addKnownPeer(peer);
        if (changed) {
            NetworkFileLogger.log("Peer found (PEX): " + peer);
        }
        String username = normalizeUsername(rawUsername);
        if (!username.isEmpty()) {
            String previous = peerUsernames.put(peer, username);
            if (!username.equals(previous)) {
                changed = true;
            }
            // Ник, полученный по цепочке пиров, сразу становится глобально
            // доступным для локального поиска.
            AddressBook.saveContact(appContext, username, peer);
        }
        return changed;
    }

    /**
     * Отправляет всем подключённым пирам запрос поиска контакта по нику.
     *
     * @return число пиров, которым отправлен запрос (0, если подключений нет).
     */
    public int requestPeerByUsername(String username) {
        String query = normalizeUsername(username);
        if (query.isEmpty()) {
            return 0;
        }
        return requestPeerQuery(query);
    }

    /** Отправляет PEX_QUERY с полным Endpoint ID всем подключённым пирам. */
    public int requestPeerByEndpointId(String endpointId) {
        String query = endpointId == null ? "" : endpointId.trim();
        if (!isValidEndpointId(query) || query.equals(this.endpointId)) {
            return 0;
        }
        return requestPeerQuery(query);
    }

    private int requestPeerQuery(String query) {
        List<String> peers = getConnectedPeers();
        if (peers.isEmpty()) {
            Log.i(TAG, "Iroh: global search '" + query + "' skipped, no connected peers");
            return 0;
        }
        try {
            JSONObject request = new JSONObject();
            request.put("type", PEX_TYPE_QUERY);
            request.put("query", query);
            String payload = PEX_PREFIX + request;
            Log.i(TAG, "Iroh: PEX_QUERY '" + query + "' -> " + peers.size() + " peers");
            for (String peer : peers) {
                sendMessage(peer, payload);
            }
            return peers.size();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to send PEX_QUERY: " + t);
            return 0;
        }
    }

    /** Пир просит найти контакт по нику: ищем локально и отвечаем, если нашли. */
    private void handleSearchRequest(String from, String payload) {
        try {
            JSONObject request = new JSONObject(payload);
            String query = normalizeUsername(request.optString("query", ""));
            if (query.isEmpty()) {
                return;
            }
            AddressBook.Contact contact = AddressBook.findByUsername(appContext, query);
            if (contact == null) {
                for (String peer : getKnownPeers()) {
                    String username = getPeerUsername(peer);
                    if (!username.isEmpty() && username.equalsIgnoreCase(query)) {
                        contact = new AddressBook.Contact(username, peer);
                        break;
                    }
                }
            }
            if (contact == null) {
                Log.i(TAG, "Iroh: search request '" + query + "' from " + from + " not found");
                return;
            }
            JSONObject response = new JSONObject();
            response.put("query", query);
            response.put("id", contact.endpointId);
            response.put("username", contact.username);
            sendMessage(from, SEARCH_RESPONSE_PREFIX + response);
            Log.i(TAG, "Iroh: search request '" + query + "' from " + from
                    + " -> " + contact.endpointId);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid search request payload: " + t);
        }
    }

    /** Пир ответил на глобальный поиск: сохраняем контакт и уведомляем UI. */
    private void handleSearchResponse(String payload) {
        try {
            JSONObject response = new JSONObject(payload);
            String query = normalizeSearchQuery(response.optString("query", ""));
            String peerId = response.optString("id", "").trim();
            String username = normalizeUsername(response.optString("username", ""));
            boolean idQuery = isValidEndpointId(query);
            if (peerId.isEmpty()) {
                return;
            }
            if (username.isEmpty() && !idQuery) {
                username = query;
            }
            if (peerId.equals(endpointId)) {
                return;
            }
            if (!username.isEmpty()) {
                AddressBook.saveContact(appContext, username, peerId);
                peerUsernames.put(peerId, username);
                persistPeerUsernames();
            }
            if (addKnownPeer(peerId)) {
                persistKnownPeers();
            }
            Log.i(TAG, "Iroh: global search found "
                    + (username.isEmpty() ? peerId : username) + " -> " + peerId);
            notifyPeerSearchResult(query.isEmpty() ? username : query, peerId, username);
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid search response payload: " + t);
        }
    }

    private void notifyPeerSearchResult(String query, String peerId, String username) {
        for (OnPeerSearchListener l : searchListeners) {
            try {
                l.onPeerSearchResult(query, peerId, username);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: search listener failed", t);
            }
        }
    }

    /** Формирует служебное hello-сообщение профиля узла с device_id. */
    private String buildProfilePayload() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "HELLO");
            obj.put("v", PROFILE_VERSION);
            if (endpointId != null && !endpointId.isEmpty()) {
                obj.put("endpointId", endpointId);
            }
            if (deviceId != null && !deviceId.isEmpty()) {
                obj.put("deviceId", deviceId);
            }
            obj.put("strongBox", DeviceIdentity.isStrongBoxBacked(appContext));
            String username = getLocalUsername();
            if (!username.isEmpty()) {
                obj.put("username", username);
            }
            try {
                String wallet = KeyManager.getOrCreateAddress(appContext);
                if (wallet != null && !wallet.isEmpty()) {
                    obj.put("walletAddress", wallet);
                }
            } catch (Throwable ignored) {
            }
            try {
                String frame = CosmeticManager.getEquippedFrame(appContext);
                if (!frame.isEmpty()) {
                    obj.put("frame", frame);
                }
            } catch (Throwable ignored) {
            }
            return PROFILE_PREFIX + obj;
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to build profile payload: " + t);
            return null;
        }
    }

    /** Отправляет пиру свой профиль (device_id) служебным сообщением. */
    private void sendProfile(String peer) {
        if (peer == null || peer.isEmpty()) {
            return;
        }
        String payload = buildProfilePayload();
        if (payload != null) {
            sendMessage(peer, payload);
        }
        sendPexAnnounceTo(peer);
    }

    /**
     * Обрабатывает профиль пира. Одинаковый device_id у разных Endpoint ID
     * (или совпадение с нашим) помечается как подозрительный, но не блокируется.
     */
    private void handleProfile(String remote, String payload) {
        try {
            JSONObject obj = new JSONObject(payload);
            String claimedEndpoint = obj.optString("endpointId", "").trim();
            if (!claimedEndpoint.isEmpty() && !claimedEndpoint.equals(remote)) {
                DeviceIdentity.markSuspicious(remote);
                log("Iroh: SUSPICIOUS profile from " + remote
                        + " claims endpointId " + claimedEndpoint + " (not blocked)");
            }

            String peerUsername = normalizeUsername(obj.optString("username", ""));
            if (!peerUsername.isEmpty()) {
                String previous = peerUsernames.put(remote, peerUsername);
                if (!peerUsername.equals(previous)) {
                    persistPeerUsernames();
                }
                AddressBook.saveContact(appContext, peerUsername, remote);
                log("Iroh: profile from " + remote + " username=" + peerUsername);
            }

            String peerWallet = normalizeWalletAddress(obj.optString("walletAddress", ""));
            if (!peerWallet.isEmpty()) {
                String previousWallet = peerWalletAddresses.put(remote, peerWallet);
                if (!peerWallet.equals(previousWallet)) {
                    persistPeerWallets();
                }
            }

            String peerFrame = normalizeFrameId(obj.optString("frame", ""));
            String previousFrame = peerFrame.isEmpty()
                    ? peerFrames.remove(remote)
                    : peerFrames.put(remote, peerFrame);
            if (!peerFrame.equals(previousFrame == null ? "" : previousFrame)) {
                persistPeerFrames();
            }

            String peerDeviceId = obj.optString("deviceId", "").trim();
            boolean peerStrongBox = obj.optBoolean("strongBox", false);
            DeviceIdentity.registerPeerStrongBox(remote, peerDeviceId, peerStrongBox);
            if (peerDeviceId.isEmpty()) {
                return;
            }
            String shortId = DeviceIdentity.shortId(peerDeviceId);
            boolean sameAsLocal = deviceId != null && !deviceId.isEmpty()
                    && peerDeviceId.equals(deviceId);
            List<String> conflicts = DeviceIdentity.registerPeerDeviceId(remote, peerDeviceId);

            if (sameAsLocal) {
                DeviceIdentity.markSuspicious(remote);
                log("Iroh: SUSPICIOUS peer " + remote + " has the same device_id as this"
                        + " installation (possible clone, not blocked)");
            } else if (!conflicts.isEmpty()) {
                log("Iroh: SUSPICIOUS duplicate device_id " + shortId + " on " + remote
                        + " and " + conflicts + " (not blocked)");
            } else {
                log("Iroh: profile from " + remote + " device_id=" + shortId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid profile payload: " + t);
        }
    }

    /**
     * При запуске подключается ко всем известным пирам (knownPeers + knownAddrs)
     * в фоновом режиме, не дожидаясь первого тика discovery.
     */
    private void connectAllKnownPeers() {
        if (endpoint == null) {
            return;
        }
        Set<String> targets = new HashSet<>(knownPeers);
        targets.addAll(knownAddrs.keySet());
        Log.i(TAG, "Bootstrap: auto-connecting to " + targets.size() + " known peer(s)");
        boolean changed = false;
        for (String raw : targets) {
            String peer = raw == null ? "" : raw.trim();
            if (peer.isEmpty() || peer.equals(endpointId)) {
                continue;
            }
            changed |= addKnownPeer(peer);
            Connection existing = connections.get(peer);
            if (existing != null && isConnectionAlive(existing)) {
                continue;
            }
            if (!connectingPeers.add(peer)) {
                continue;
            }
            ioExec.submit(() -> {
                try {
                    getOrConnect(peer);
                } catch (Throwable t) {
                    Log.d(TAG, "Bootstrap: auto-connect failed for " + peer + ": " + t);
                } finally {
                    connectingPeers.remove(peer);
                }
            });
        }
        if (changed) {
            persistKnownPeers();
        }
    }

    /**
     * Публичный Relay n0 настраивается через relayMode при bind; отдельное
     * подключение к seed-узлу не требуется.
     */
    private void connectConfiguredRelay(Endpoint ep) {
        String relayUrl = configuredSeedRelayUrl();
        if (ep == null || relayUrl.isEmpty()) {
            return;
        }
        Log.i(TAG, "Relay: using public n0 relay = " + relayUrl);
        Log.i(TAG, "Relay: endpoint registration is handled by relayMode");
        timing("relay configured: " + relayUrl);
    }

    private void connectKnownPeers() {
        if (!started.get() || endpoint == null) {
            return;
        }
        int attempts = 0;
        for (String peer : getKnownPeers()) {
            if (attempts >= 5) {
                break;
            }
            if (peer.equals(endpointId)) {
                continue;
            }
            Connection existing = connections.get(peer);
            if (existing != null && isConnectionAlive(existing)) {
                continue;
            }
            if (!connectingPeers.add(peer)) {
                continue;
            }
            attempts++;
            ioExec.submit(() -> {
                try {
                    getOrConnect(peer);
                } catch (Throwable t) {
                    Log.d(TAG, "Iroh: discovery connect failed for " + peer + ": " + t);
                } finally {
                    connectingPeers.remove(peer);
                }
            });
        }
    }

    /**
     * Ставит пира в очередь на автоматическое переподключение.
     * Повторные вызовы для одного и того же пира не создают дублей.
     */
    private void scheduleReconnect(String peerEndpointId) {
        if (peerEndpointId == null) {
            return;
        }
        String peer = peerEndpointId.trim();
        if (peer.isEmpty() || peer.equals(endpointId) || !started.get()) {
            return;
        }
        if (reconnectPeers.add(peer)) {
            Log.i(TAG, "Reconnect: trying to reconnect to " + peer);
        }
        ensureReconnectLoop();
    }

    /** Запускает периодический таймер переподключения, если он ещё не работает. */
    private synchronized void ensureReconnectLoop() {
        if (!started.get() || reconnectExec.isShutdown()) {
            return;
        }
        ScheduledFuture<?> existing = reconnectTask;
        if (existing != null && !existing.isDone() && !existing.isCancelled()) {
            return;
        }
        Log.i(TAG, "Iroh: reconnect loop started (every " + RECONNECT_INTERVAL_MS + " ms)");
        reconnectTask = reconnectExec.scheduleWithFixedDelay(
                this::reconnectTick, RECONNECT_INTERVAL_MS, RECONNECT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /** Один тик таймера: пробует переподключить не более RECONNECT_MAX_PER_TICK пиров. */
    private void reconnectTick() {
        if (!started.get() || endpoint == null) {
            stopReconnectLoop();
            return;
        }
        List<String> batch = new ArrayList<>(RECONNECT_MAX_PER_TICK);
        for (String peer : reconnectPeers) {
            if (batch.size() >= RECONNECT_MAX_PER_TICK) {
                break;
            }
            batch.add(peer);
        }
        if (batch.isEmpty()) {
            // Очередь пуста — таймер больше не нужен до следующего scheduleReconnect().
            stopReconnectLoop();
            return;
        }
        for (String peer : batch) {
            Connection existing = connections.get(peer);
            if (existing != null && isConnectionAlive(existing)) {
                reconnectPeers.remove(peer);
                continue;
            }
            if (!connectingPeers.add(peer)) {
                continue;
            }
            ioExec.submit(() -> {
                try {
                    getOrConnect(peer, true);
                    reconnectPeers.remove(peer);
                    Log.i(TAG, "Iroh: reconnected to " + peer);
                } catch (Throwable t) {
                    Log.d(TAG, "Iroh: reconnect failed for " + peer + ": " + t);
                } finally {
                    connectingPeers.remove(peer);
                }
            });
        }
    }

    /** Останавливает таймер переподключения и очищает очередь пиров. */
    private synchronized void stopReconnectLoop() {
        ScheduledFuture<?> task = reconnectTask;
        reconnectTask = null;
        if (task != null) {
            task.cancel(false);
            Log.i(TAG, "Iroh: reconnect loop stopped");
        }
        reconnectPeers.clear();
    }

    /**
     * Реальный Endpoint ID iroh — 32-байтовый ed25519 ключ в base32
     * (52 символа) либо hex (64 символа). Заглушки и прочие невалидные
     * строки не попадают в knownPeers/UI.
     */
    public static boolean isValidEndpointId(String value) {
        if (value == null) {
            return false;
        }
        String id = value.trim();
        if (id.length() == 52) {
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '2' && c <= '7'))) {
                    return false;
                }
            }
            return true;
        }
        if (id.length() == 64) {
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F'))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean addKnownPeer(String endpointIdValue) {
        if (endpointIdValue == null) {
            return false;
        }
        String peer = endpointIdValue.trim();
        if (!isValidEndpointId(peer) || peer.equals(endpointId)
                || knownPeers.size() >= MAX_KNOWN_PEERS) {
            return false;
        }
        return knownPeers.add(peer);
    }

    private void loadKnownPeers() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            Set<String> saved = ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY_KNOWN_PEERS, null);
            if (saved != null) {
                int skipped = 0;
                for (String peer : saved) {
                    if (!addKnownPeer(peer)) {
                        skipped++;
                    }
                }
                if (skipped > 0) {
                    Log.i(TAG, "Iroh: dropped " + skipped + " invalid saved peer id(s)");
                    persistKnownPeers();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: load known peers failed: " + t);
        }
    }

    private void persistKnownPeers() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_KNOWN_PEERS, new HashSet<>(knownPeers))
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: persist known peers failed: " + t);
        }
    }

    /** Загружает сохранённые адреса пиров, чтобы fallback работал после холодного старта. */
    private void loadKnownAddrs() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            String raw = ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_KNOWN_ADDRS, "");
            if (raw != null && !raw.isEmpty()) {
                JSONObject root = new JSONObject(raw);
                java.util.Iterator<String> keys = root.keys();
                int loaded = 0;
                while (keys.hasNext()) {
                    String peer = keys.next();
                    if (!isValidEndpointId(peer)) {
                        continue;
                    }
                    JSONObject entry = root.optJSONObject(peer);
                    if (entry == null) {
                        continue;
                    }
                    String relay = entry.optString("relay", "").trim();
                    List<String> direct = new ArrayList<>();
                    JSONArray array = entry.optJSONArray("direct");
                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            String address = array.optString(i, "").trim();
                            if (!address.isEmpty() && address.contains(":")
                                    && !direct.contains(address)) {
                                direct.add(address);
                            }
                        }
                    }
                    if (relay.isEmpty() && direct.isEmpty()) {
                        continue;
                    }
                    knownAddrs.put(peer.trim(), new KnownAddr(relay, direct));
                    loaded++;
                }
                Log.i(TAG, "Addr cache: loaded " + loaded + " peer(s) from prefs");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: load known addrs failed: " + t);
        } finally {
            knownAddrsLoaded.set(true);
        }
    }

    /** Сохраняет knownAddrs (relay + direct) для fallback после перезапуска приложения. */
    private void persistKnownAddrs() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, KnownAddr> entry : knownAddrs.entrySet()) {
                KnownAddr known = entry.getValue();
                if (entry.getKey() == null || known == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                if (!known.relayUrl.isEmpty()) {
                    item.put("relay", known.relayUrl);
                }
                if (!known.directAddresses.isEmpty()) {
                    JSONArray array = new JSONArray();
                    for (String address : known.directAddresses) {
                        array.put(address);
                    }
                    item.put("direct", array);
                }
                root.put(entry.getKey(), item);
            }
            ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_KNOWN_ADDRS, root.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: persist known addrs failed: " + t);
        }
    }

    /** Если ник ещё не задан, сохраняем сгенерированное имя от Endpoint ID. */
    private void ensureDefaultUsername(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
            String stored = prefs.getString(SettingsActivity.KEY_USERNAME, "");
            if (normalizeUsername(stored).isEmpty()) {
                String generated = PeerNameGenerator.generateName(ctx, endpointId);
                prefs.edit().putString(SettingsActivity.KEY_USERNAME, generated).apply();
                Log.i(TAG, "Iroh: default username generated = " + generated);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to prepare default username: " + t);
        }
    }

    /** Собственный ник: сохранённый пользователем или сгенерированный из Endpoint ID. */
    public String getLocalUsername() {
        Context ctx = appContext;
        if (ctx != null) {
            try {
                String stored = ctx.getSharedPreferences(
                                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(SettingsActivity.KEY_USERNAME, "");
                String normalized = normalizeUsername(stored);
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to read local username: " + t);
            }
        }
        return PeerNameGenerator.generateName(appContext, endpointId);
    }

    /** Ник, полученный от пира (пусто, если профиль ещё не приходил). */
    public String getPeerUsername(String peerEndpointId) {
        if (peerEndpointId == null) {
            return "";
        }
        String peer = peerEndpointId.trim();
        if (peer.isEmpty()) {
            return "";
        }
        String username = peerUsernames.get(peer);
        if (username == null || username.isEmpty()) {
            username = AddressBook.getUsername(appContext, peer);
            if (!username.isEmpty()) {
                peerUsernames.put(peer, username);
            }
        }
        return username == null ? "" : username;
    }

    /** Имя для UI: ник пира, если известен, иначе сгенерированное из Endpoint ID. */
    public String getPeerDisplayName(String peerEndpointId) {
        String username = getPeerUsername(peerEndpointId);
        return username.isEmpty()
                ? PeerNameGenerator.generateName(appContext, peerEndpointId) : username;
    }

    /** Id применённой рамки профиля пира (пусто, если рамки нет). */
    public String getPeerFrame(String peerEndpointId) {
        if (peerEndpointId == null) {
            return "";
        }
        String peer = peerEndpointId.trim();
        if (peer.isEmpty()) {
            return "";
        }
        String frame = peerFrames.get(peer);
        return frame == null ? "" : frame;
    }

    /** Отправляет обновлённый профиль (ник) всем подключённым пирам. */
    public void broadcastProfile() {
        for (String peer : getConnectedPeers()) {
            sendProfile(peer);
        }
    }

    private static String normalizeSearchQuery(String raw) {
        String value = raw == null ? "" : raw.trim().replace('\n', ' ').replace('\r', ' ');
        return isValidEndpointId(value) ? value : normalizeUsername(value);
    }

    private static String normalizeUsername(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().replace('\n', ' ').replace('\r', ' ');
        if (value.length() > MAX_USERNAME_LENGTH) {
            value = value.substring(0, MAX_USERNAME_LENGTH).trim();
        }
        return value;
    }

    private static String normalizeWalletAddress(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.matches("(?i)0x[0-9a-f]{40}")) {
            return value.toLowerCase(java.util.Locale.US);
        }
        return "";
    }

    private static String normalizeFrameId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(java.util.Locale.US);
        return value.matches("[a-z0-9_]{1,32}") ? value : "";
    }

    private void loadPeerUsernames() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            String raw = ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PEER_USERNAMES, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            JSONObject obj = new JSONObject(raw);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String peer = keys.next();
                String username = normalizeUsername(obj.optString(peer, ""));
                if (!peer.isEmpty() && !username.isEmpty()) {
                    peerUsernames.put(peer, username);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: load peer usernames failed: " + t);
        }
    }

    private void persistPeerUsernames() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> entry : peerUsernames.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PEER_USERNAMES, obj.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: persist peer usernames failed: " + t);
        }
    }

    private void loadPeerWallets() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            String raw = ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PEER_WALLETS, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            JSONObject obj = new JSONObject(raw);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String peer = keys.next();
                String wallet = normalizeWalletAddress(obj.optString(peer, ""));
                if (!peer.isEmpty() && !wallet.isEmpty()) {
                    peerWalletAddresses.put(peer, wallet);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: load peer wallets failed: " + t);
        }
    }

    private void persistPeerWallets() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> entry : peerWalletAddresses.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PEER_WALLETS, obj.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: persist peer wallets failed: " + t);
        }
    }

    private void loadPeerFrames() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            String raw = ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PEER_FRAMES, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            JSONObject obj = new JSONObject(raw);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String peer = keys.next();
                String frame = normalizeFrameId(obj.optString(peer, ""));
                if (!peer.isEmpty() && !frame.isEmpty()) {
                    peerFrames.put(peer, frame);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: load peer frames failed: " + t);
        }
    }

    private void persistPeerFrames() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> entry : peerFrames.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PEER_FRAMES, obj.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: persist peer frames failed: " + t);
        }
    }

    private void loadPexCache() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            String raw = ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PEX_CACHE, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            JSONObject root = new JSONObject(raw);
            long cutoff = System.currentTimeMillis() - PEX_CACHE_TTL_MS;
            int expired = 0;
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String peer = keys.next();
                JSONObject item = root.optJSONObject(peer);
                if (peer == null || peer.trim().isEmpty() || item == null) {
                    continue;
                }
                String id = peer.trim();
                if (id.equals(endpointId)) {
                    continue;
                }
                long timestamp = item.optLong("timestamp", 0L);
                if (timestamp <= 0L || timestamp < cutoff) {
                    expired++;
                    continue;
                }
                String username = normalizeUsername(item.optString("username", ""));
                pexCache.put(id, new PexEntry(username, timestamp));
                if (!username.isEmpty()) {
                    peerUsernames.putIfAbsent(id, username);
                }
                addKnownPeer(id);
            }
            if (expired > 0) {
                persistPexCache();
                Log.i(TAG, "PEX cache: removed " + expired
                        + " expired peer(s) older than 1 hour");
            }
            Log.i(TAG, "PEX cache: loaded " + pexCache.size() + " peer(s) from prefs");
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: load PEX cache failed: " + t);
        }
    }

    private void persistPexCache() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, PexEntry> entry : pexCache.entrySet()) {
                PexEntry value = entry.getValue();
                if (entry.getKey() == null || value == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                if (!value.username.isEmpty()) {
                    item.put("username", value.username);
                }
                item.put("timestamp", value.timestamp);
                root.put(entry.getKey(), item);
            }
            ctx.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PEX_CACHE, root.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: persist PEX cache failed: " + t);
        }
    }

    private static boolean isBlockJson(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.contains("\"index\"")
                || !trimmed.contains("\"hash\"")) {
            return false;
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            return obj.has("index") && obj.has("hash");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Запрашивает у конкретного пира высоту и последний хеш цепочки. */
    public void requestChainFromPeer(String peerId) {
        final String peer = peerId == null ? "" : peerId.trim();
        Context ctx = appContext;
        if (peer.isEmpty() || ctx == null) {
            return;
        }
        BlockStorage storage = new BlockStorage(ctx);
        int height = storage.getBlockCount();
        String lastHash = storage.getLastBlockHash();
        try {
            JSONObject request = new JSONObject();
            request.put("height", height);
            request.put("lastHash", lastHash);
            sendMessage(peer, GET_CHAIN_REQ_PREFIX + request.toString());
            log("Chain: GET_CHAIN_REQ sent to " + peer + ", height=" + height);
        } catch (Throwable t) {
            Log.w(TAG, "Chain: failed to build request for " + peer, t);
        }
    }

    /** Запрашивает цепочку у всех активных пиров. */
    public void requestChainFromAllPeers() {
        for (String peer : getConnectedPeers()) {
            requestChainFromPeer(peer);
        }
    }

    /**
     * Включает heartbeat: лёгкие PING активным пирам, на PING отвечаем PONG.
     * Интервал зависит от сети: 30 c в Wi-Fi, 20 c через 4G/Relay.
     */
    public void startHeartbeat() {
        heartbeatRequested = true;
        applyHeartbeatState();
    }

    /** Выключает heartbeat при остановке сервиса. */
    public void stopHeartbeat() {
        heartbeatRequested = false;
        cancelHeartbeatTask();
    }

    private synchronized void applyHeartbeatState() {
        cancelHeartbeatTask();
        if (!heartbeatRequested || dozeActive) {
            Log.d("Heartbeat", "Heartbeat paused (requested=" + heartbeatRequested
                    + ", doze=" + dozeActive + ")");
            return;
        }
        long interval = NetworkType.CELLULAR.equals(NetworkType.current(appContext))
                ? HEARTBEAT_CELLULAR_INTERVAL_MS : HEARTBEAT_WIFI_INTERVAL_MS;
        heartbeatTask = heartbeatExec.scheduleWithFixedDelay(this::sendHeartbeatPings,
                interval, interval, TimeUnit.MILLISECONDS);
        heartbeatRunning = true;
        Log.i("Heartbeat", "Heartbeat started, interval=" + interval + "ms");
    }

    private void cancelHeartbeatTask() {
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        heartbeatRunning = false;
        if (task != null) {
            task.cancel(false);
            Log.d("Heartbeat", "Heartbeat stopped");
        }
    }

    private void sendHeartbeatPings() {
        try {
            for (String peerId : getConnectedPeers()) {
                if (peerId == null || peerId.isEmpty() || peerId.equals(endpointId)) {
                    continue;
                }
                sendMessage(peerId, PING_PREFIX);
                Log.d("Heartbeat", "Ping sent to " + peerId);
            }
        } catch (Throwable t) {
            Log.w("Heartbeat", "Ping round failed: " + t);
        }
    }

    private void registerDozeReceiver(Context ctx) {
        if (ctx == null || dozeReceiver != null) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            dozeActive = pm.isDeviceIdleMode();
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean idle;
                    try {
                        PowerManager power = (PowerManager) context.getSystemService(
                                Context.POWER_SERVICE);
                        idle = power != null && power.isDeviceIdleMode();
                    } catch (Throwable t) {
                        idle = false;
                    }
                    onDozeModeChanged(idle);
                }
            };
            ctx.registerReceiver(receiver,
                    new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
            dozeReceiver = receiver;
            Log.i(TAG, "Heartbeat: Doze receiver registered, idle=" + dozeActive);
            if (dozeActive) {
                applyHeartbeatState();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Heartbeat: Doze receiver registration failed: " + t);
        }
    }

    private void unregisterDozeReceiver() {
        BroadcastReceiver receiver = dozeReceiver;
        dozeReceiver = null;
        dozeActive = false;
        if (receiver == null) {
            return;
        }
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            ctx.unregisterReceiver(receiver);
            Log.i(TAG, "Heartbeat: Doze receiver unregistered");
        } catch (Throwable ignored) {
        }
    }

    private synchronized void onDozeModeChanged(boolean idle) {
        if (dozeActive == idle) {
            return;
        }
        dozeActive = idle;
        Log.i("Heartbeat", idle ? "Device entered Doze, heartbeat stopped"
                : "Device left Doze, heartbeat resumed");
        if (idle) {
            cancelHeartbeatTask();
        } else {
            applyHeartbeatState();
            requestChainFromAllPeers();
        }
    }

    /** SPV: запрашивает полный блок по индексу у конкретного пира. */
    public void requestBlock(int index, String peerId) {
        final String peer = peerId == null ? "" : peerId.trim();
        if (index < 0 || peer.isEmpty()) {
            return;
        }
        try {
            JSONObject request = new JSONObject();
            request.put("index", index);
            sendMessage(peer, GET_BLOCK_REQ_PREFIX + request.toString());
            log("SPV: GET_BLOCK_REQ sent to " + peer + ", index=" + index);
        } catch (Throwable t) {
            Log.w(TAG, "SPV: failed to request block " + index, t);
        }
    }

    /** SPV: запрашивает полные блоки для всех сохранённых заголовков. */
    public void requestAllBlocksFromPeers() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        List<String> peers = getConnectedPeers();
        if (peers.isEmpty()) {
            return;
        }
        SpvStorage spvStorage = new SpvStorage(ctx);
        List<JSONObject> headers = spvStorage.getHeaders();
        for (int i = 0; i < headers.size(); i++) {
            int index = headers.get(i).optInt("index", -1);
            if (index >= 0) {
                requestBlock(index, peers.get(i % peers.size()));
            }
        }
    }

    /** SPV: полный блок из 24-часового кэша (или null). */
    public String getCachedBlock(int index) {
        CachedBlock cached = blockCache.get(index);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt <= System.currentTimeMillis()) {
            blockCache.remove(index, cached);
            return null;
        }
        return cached.json;
    }

    private void handleBlockRequest(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            int index = new JSONObject(payload).optInt("index", -1);
            BlockStorage storage = new BlockStorage(ctx);
            String block = index < 0 ? null : storage.getStoredBlock(index);
            JSONObject response = new JSONObject();
            response.put("index", index);
            response.put("found", block != null);
            if (block != null) {
                response.put("block", new JSONObject(block));
            }
            sendMessage(from, BLOCK_RESP_PREFIX + response.toString());
            log("SPV: BLOCK_RESP sent to " + from + ", index=" + index
                    + ", found=" + (block != null));
        } catch (Throwable t) {
            Log.w(TAG, "SPV: failed to handle block request from " + from, t);
        }
    }

    private void handleBlockResponse(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject response = new JSONObject(payload);
            JSONObject block = response.optJSONObject("block");
            if (block == null) {
                return;
            }
            int index = block.optInt("index", -1);
            String blockJson = block.toString();
            if (!validateFetchedBlock(from, blockJson)) {
                return;
            }
            blockCache.put(index, new CachedBlock(blockJson,
                    System.currentTimeMillis() + BLOCK_CACHE_TTL_MS));
            BlockStorage storage = new BlockStorage(ctx);
            if (!storage.isSpvMode() && !storage.hasBlock(index)) {
                storage.saveBlock(blockJson);
            }
            notifyBlock(from, blockJson);
            log("SPV: full block cached, index=" + index);
        } catch (Throwable t) {
            Log.w(TAG, "SPV: failed to handle block response from " + from, t);
        }
    }

    private boolean validateFetchedBlock(String from, String blockJson) {
        try {
            JSONObject obj = new JSONObject(blockJson);
            if (!BlockStorage.verifyBlockHash(obj)) {
                log(blockErrorMessage(R.string.block_invalid_hash, from));
                return false;
            }
            if (!BlockStorage.verifyBlockSignature(obj)) {
                log(blockErrorMessage(R.string.block_invalid_signature, from));
                return false;
            }
            for (JSONObject tx : TransferManager.extractTransactions(
                    obj.optString("data", ""))) {
                if (!TransferManager.isValidTransaction(tx)) {
                    log(blockErrorMessage(R.string.block_invalid_transaction, from));
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "SPV: failed to validate fetched block from " + from, t);
            return false;
        }
    }

    private void handleChainRequest(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            BlockStorage storage = new BlockStorage(ctx);
            int last = storage.getLastBlockIndex();
            int height = Math.max(0, last + 1);
            int remoteHeight = 0;
            try {
                remoteHeight = new JSONObject(payload).optInt("height", 0);
            } catch (Throwable ignored) {
            }
            int start = Math.max(0, remoteHeight);
            int end = Math.min(last, start + MAX_CHAIN_SYNC_BLOCKS - 1);
            JSONArray blocks = new JSONArray();
            for (int i = start; i <= end; i++) {
                String block = storage.getBlock(i);
                if (block != null) {
                    blocks.put(block);
                }
            }
            JSONArray validators = new JSONArray();
            for (String validator : ValidatorManager.getValidators(ctx)) {
                validators.put(validator);
            }
            JSONObject response = new JSONObject();
            response.put("height", height);
            response.put("lastHash", storage.getLastBlockHash());
            response.put("blocks", blocks);
            response.put("validators", validators);
            sendMessage(from, CHAIN_RESP_PREFIX + response.toString());
            log("Chain: CHAIN_RESP sent to " + from + ", height=" + height
                    + ", blocks=" + blocks.length() + ", validators=" + validators.length());
        } catch (Throwable t) {
            Log.w(TAG, "Chain: failed to handle request from " + from, t);
        }
    }

    private void handleChainResponse(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject response = new JSONObject(payload);
            int remoteHeight = response.optInt("height", 0);
            String remoteLastHash = response.optString("lastHash", "");
            if (response.has("validators")) {
                List<String> remoteValidators = new ArrayList<>();
                JSONArray validatorsArray = response.optJSONArray("validators");
                if (validatorsArray != null) {
                    for (int i = 0; i < validatorsArray.length(); i++) {
                        String validator = validatorsArray.optString(i, "").trim();
                        if (!validator.isEmpty()) {
                            remoteValidators.add(validator);
                        }
                    }
                }
                boolean adopted = ValidatorManager.voteForValidators(
                        ctx, from, remoteValidators);
                if (adopted) {
                    log("Chain: validator list updated by consensus, size="
                            + ValidatorManager.getValidatorCount(ctx));
                }
                List<String> localValidators = ValidatorManager.getValidators(ctx);
                List<String> remoteSorted = new ArrayList<>(remoteValidators);
                Collections.sort(remoteSorted);
                if (!remoteValidators.isEmpty()) {
                    ValidatorManager.markSyncedWithPeers();
                }
                Log.d(TAG, "Chain: validators local=" + localValidators
                        + ", remote=" + remoteSorted);
                if (!remoteSorted.isEmpty() && !localValidators.containsAll(remoteSorted)) {
                    long now = System.currentTimeMillis();
                    long last = lastValidatorResyncMs.get();
                    if (now - last >= VALIDATOR_RESYNC_INTERVAL_MS
                            && lastValidatorResyncMs.compareAndSet(last, now)) {
                        log("Chain: validator lists differ, requesting chain from all peers");
                        requestChainFromAllPeers();
                    }
                }
            }
            if (remoteHeight <= 0) {
                return;
            }
            BlockStorage storage = new BlockStorage(ctx);
            int before = storage.getBlockCount();
            if (remoteHeight <= before) {
                log("Chain: peer " + from + " is not ahead (" + remoteHeight + " <= " + before + ")");
                return;
            }
            JSONArray array = response.optJSONArray("blocks");
            List<String> blocks = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    String block = array.optString(i, null);
                    if (block != null && !block.trim().isEmpty()) {
                        blocks.add(block);
                    }
                }
            }
            if (blocks.isEmpty()) {
                log("Chain: empty CHAIN_RESP from " + from + ", height=" + remoteHeight);
                return;
            }
            log("Chain: CHAIN_RESP from " + from + ", remoteHeight=" + remoteHeight
                    + ", blocks=" + blocks.size());
            notifyChainSync(from, remoteHeight, remoteLastHash, blocks);
            int after = storage.getBlockCount();
            if (after > before && after < remoteHeight) {
                requestChainFromPeer(from);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Chain: failed to handle response from " + from, t);
        }
    }

    private void notifyPeerConnected(String peerEndpointId) {
        for (OnPeerConnectedListener l : peerConnectedListeners) {
            try {
                l.onPeerConnected(peerEndpointId);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: peer connected listener failed", t);
            }
        }
    }

    private void notifyPeerDiscovered(String peerEndpointId) {
        for (OnPeerDiscoveredListener l : peerDiscoveredListeners) {
            try {
                l.onPeerDiscovered(peerEndpointId);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: peer discovered listener failed", t);
            }
        }
    }

    private void notifyChainSync(String from, int remoteHeight, String remoteLastHash,
                                 List<String> blockJsons) {
        for (OnChainSyncListener l : chainSyncListeners) {
            try {
                l.onChainResponse(from, remoteHeight, remoteLastHash, blockJsons);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: chain sync listener failed", t);
            }
        }
    }

    private boolean validateBlock(String from, String blockJson) {
        try {
            JSONObject obj = new JSONObject(blockJson);
            Context ctx = appContext;
            BlockStorage storage = ctx == null ? null : new BlockStorage(ctx);
            boolean spv = storage != null && storage.isSpvMode();
            String signature = obj.optString(BlockStorage.FIELD_SIGNATURE, "").trim();
            if (signature.isEmpty()) {
                if (spv && BlockStorage.verifyHeader(obj)) {
                    return true;
                }
                log(blockErrorMessage(R.string.block_missing_signature, from));
                return false;
            }
            if (!BlockStorage.verifyBlockHash(obj)) {
                log(blockErrorMessage(R.string.block_invalid_hash, from));
                return false;
            }
            if (!BlockStorage.verifyBlockSignature(obj)) {
                log(blockErrorMessage(R.string.block_invalid_signature, from));
                return false;
            }
            if (ctx != null) {
                if (spv) {
                    for (JSONObject tx : TransferManager.extractTransactions(
                            obj.optString("data", ""))) {
                        if (!TransferManager.isValidTransaction(tx)) {
                            log(blockErrorMessage(R.string.block_invalid_transaction, from));
                            return false;
                        }
                    }
                } else if (!TransferManager.validateBlockData(
                        ctx, storage.getAllBlocks(), obj.optString("data", ""),
                        obj.optString("validator", ""))) {
                    log(blockErrorMessage(R.string.block_invalid_transaction, from));
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to validate block from " + from, t);
            log(blockErrorMessage(R.string.block_invalid_signature, from));
            return false;
        }
    }

    private String blockErrorMessage(int messageRes, String from) {
        Context ctx = appContext;
        String message = ctx == null ? "" : ctx.getString(messageRes);
        return from == null || from.isEmpty() ? message : message + " (" + from + ")";
    }

    private void notifyBlock(String from, String blockJson) {
        SignerService.recordBlockReceived(appContext);
        for (OnBlockListener l : blockListeners) {
            try {
                l.onBlock(from, blockJson);
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: block listener failed", t);
            }
        }
    }

    private static boolean isConnectionAlive(Connection conn) {
        try {
            return conn != null && conn.closeReason() == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Логирует ошибку запуска с полным стектрейсом и уведомляет listener. */
    private void failStart(String stage, Throwable t) {
        starting.set(false);
        String msg = "Iroh: start failed: " + t + " (stage: " + stage + ")";
        Log.e(TAG, msg, t);
        Listener l = listener;
        if (l != null) {
            try {
                l.onFailed(msg);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyStarted(String id) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onStarted(id);
            } catch (Throwable ignored) {}
        }
    }

    /** Останавливает Endpoint и освобождает ресурсы. Узел можно запустить снова. */
    public synchronized void stop() {
        started.set(false);
        starting.set(false);
        discoveryRunning.set(false);
        stopTopicDiscovery();
        stopReconnectLoop();
        cancelN0OnlineTimer();
        cancelIdleReconnectTimer();
        cancelHeartbeatTask();
        unregisterDozeReceiver();
        stopMdnsDiscovery();
        stopEndpointWatchers();
        unregisterAndroidNetworkCallback();
        Thread discovery = discoveryThread;
        discoveryThread = null;
        if (discovery != null) {
            discovery.interrupt();
        }
        connectingPeers.clear();
        n0OnlineRequested.set(false);
        n0Online = false;
        seedRelayActive = false;
        for (Connection c : connections.values()) {
            try {
                c.close();
            } catch (Throwable ignored) {}
        }
        connections.clear();
        incomingMessageIds.clear();
        Thread accept = acceptThread;
        acceptThread = null;
        if (accept != null) {
            accept.interrupt();
        }
        Endpoint ep = endpoint;
        endpoint = null;
        if (ep != null) {
            try {
                ep.close();
                Log.i(TAG, "Iroh: stopped");
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: stop err: " + t);
            }
        }
        endpointId = "";
        try {
            exec.shutdown();
        } catch (Throwable ignored) {}
    }

    /** Публичный ключ узла (EndpointId) в виде hex-строки; пустая строка, если узел не запущен. */
    public String getEndpointId() {
        return endpointId;
    }

    /** Endpoint ID текущего узла. */
    public String getSelfEndpointId() {
        return endpointId;
    }

    /** true, если событие пришло от собственного endpoint (самоподключение). */
    private boolean isSelfEndpoint(String from) {
        String value = from == null ? "" : from.trim();
        return value.isEmpty() || value.equals(endpointId);
    }

    public List<String> getSelfDirectAddresses() {
        return new ArrayList<>(selfDirectAddresses);
    }

    public void rememberPeerAddresses(String peerEndpointId, List<String> addresses) {
        String peer = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (!isValidEndpointId(peer) || addresses == null || addresses.isEmpty()) {
            return;
        }
        KnownAddr known = knownAddrs.get(peer);
        String relay = known == null ? "" : known.relayUrl;
        List<String> direct = known == null
                ? new ArrayList<>() : new ArrayList<>(known.directAddresses);
        boolean changed = false;
        for (String raw : addresses) {
            String address = raw == null ? "" : raw.trim();
            if (address.isEmpty() || !address.contains(":") || direct.contains(address)) {
                continue;
            }
            if (direct.size() >= 8) {
                break;
            }
            direct.add(address);
            changed = true;
        }
        if (!changed) {
            return;
        }
        knownAddrs.put(peer, new KnownAddr(relay, direct));
        persistKnownAddrs();
        Log.i(TAG, "Addr cache: peer=" + peer + " direct=" + direct);
    }

    public void rememberPeerAddress(String peerEndpointId, String address) {
        rememberPeerAddresses(peerEndpointId, Collections.singletonList(address));
    }

    /**
     * HELLO, полученный локальным P2P-каналом: запоминаем Endpoint ID пира и его
     * адреса. Это позволяет сразу подключиться к пиру через Iroh, не ожидая DNS.
     */
    public void rememberPeerFromHello(String peerEndpointId, List<String> addresses) {
        String peer = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (!isValidEndpointId(peer) || peer.equals(endpointId)) {
            return;
        }
        if (addresses != null && !addresses.isEmpty()) {
            rememberPeerAddresses(peer, addresses);
        }
        if (addKnownPeer(peer)) {
            persistKnownPeers();
        }
        Log.i(TAG, "HELLO: learned peer endpoint id = " + peer
                + ", addrs = " + (addresses == null ? "[]" : addresses));
    }

    /** Device ID из Android Keystore (для профиля узла). */
    public String getDeviceId() {
        return deviceId == null ? "" : deviceId;
    }

    /** device_id, полученный от пира в hello-профиле (пусто, если ещё неизвестен). */
    public String getPeerDeviceId(String peerEndpointId) {
        return DeviceIdentity.getPeerDeviceId(peerEndpointId);
    }

    /** Помечен ли пир подозрительным (дубликат device_id / несовпадение профиля). */
    public boolean isPeerSuspicious(String peerEndpointId) {
        return DeviceIdentity.isSuspicious(peerEndpointId);
    }

    /** Все пиры, помеченные подозрительными в текущей сессии. */
    public List<String> getSuspiciousPeers() {
        return DeviceIdentity.getSuspiciousPeers();
    }

    /**
     * Endpoint ID пиров, с которыми установлено активное соединение.
     * Список отсортирован; мёртвые соединения пропускаются.
     */
    public List<String> getConnectedPeers() {
        List<String> peers = new ArrayList<>();
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            if (isConnectionAlive(entry.getValue())) {
                peers.add(entry.getKey());
            }
        }
        Collections.sort(peers);
        return peers;
    }

    /**
     * Все найденные пиры: известные из peer exchange/прошлых запусков и
     * подключённые сейчас. Используется во вкладке «Чат».
     */
    public List<String> getKnownPeers() {
        TreeSet<String> peers = new TreeSet<>();
        for (String peer : knownPeers) {
            if (isValidEndpointId(peer)) {
                peers.add(peer);
            }
        }
        for (String peer : getConnectedPeers()) {
            if (isValidEndpointId(peer)) {
                peers.add(peer);
            }
        }
        if (endpointId != null && !endpointId.isEmpty()) {
            peers.remove(endpointId);
        }
        return new ArrayList<>(peers);
    }

    /** Пробует подключиться к известным пирам (без широковещательного PEX). */
    public void refreshKnownPeersViaPex(Runnable onComplete) {
        try {
            ioExec.submit(() -> {
                try {
                    connectKnownPeers();
                } catch (Throwable t) {
                    Log.w(TAG, "PEX refresh failed: " + t);
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "PEX refresh submit failed: " + t);
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /** Подключён ли пир и используется ли он как relay-мост. */
    public boolean isRelayPeer(String endpointId) {
        if (endpointId == null) {
            return false;
        }
        return relayPeers.contains(endpointId.trim());
    }

    /** Задан ли реальный HTTPS URL Relay-сервера. */
    public boolean isRelayConfigured() {
        return !configuredSeedRelayUrl().isEmpty();
    }

    /** Используется ли публичный Relay n0.computer. */
    public boolean isPublicN0Relay() {
        return configuredSeedRelayUrl().contains("relay.n0.iroh.link");
    }

    /** Подключён ли endpoint к relay и зарегистрирован на нём. */
    public boolean isRelayConnected() {
        return seedRelayActive || n0Online;
    }

    /** URL собственного relay или пустая строка, если задана заглушка. */
    public String getRelayUrl() {
        return configuredSeedRelayUrl();
    }

    /** Рассылает блок всем подключённым пирам через Iroh. */
    public void broadcastBlock(String blockJson) {
        if (blockJson == null || blockJson.isEmpty()) {
            return;
        }
        List<String> peers = getConnectedPeers();
        Log.i(TAG, "Iroh: broadcasting block to " + peers.size() + " peers");
        for (String peer : peers) {
            sendMessage(peer, blockJson);
        }
    }

    /** Рассылает объявление маркета всем подключённым пирам. */
    public void broadcastMarketItem(JSONObject item) {
        if (item == null) {
            return;
        }
        String payload = MARKET_ITEM_PREFIX + item.toString();
        List<String> peers = getConnectedPeers();
        Log.i(TAG, "Iroh: broadcasting market item to " + peers.size() + " peers");
        for (String peer : peers) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает подписанную новость всем подключённым пирам. */
    public void broadcastNews(JSONObject item) {
        broadcastNewsExcept("", item);
    }

    private void broadcastNewsExcept(String excludedPeer, JSONObject item) {
        if (item == null) {
            return;
        }
        String payload = NEWS_BROADCAST_PREFIX + item.toString();
        List<String> peers = getConnectedPeers();
        Log.i(TAG, "Iroh: broadcasting news to " + peers.size() + " peers");
        for (String peer : peers) {
            if (!peer.equals(excludedPeer)) {
                sendMessage(peer, payload);
            }
        }
    }

    private void handleIncomingNews(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject item = new JSONObject(payload);
            boolean accepted = new NewsManager(ctx).handleIncomingNews(item);
            Log.d("News", "News from " + from + (accepted ? " accepted" : " ignored"));
            if (accepted) {
                broadcastNewsExcept(from == null ? "" : from, item);
            }
        } catch (Throwable t) {
            Log.w(TAG, "News: invalid broadcast from " + from, t);
        }
    }

    /** Отправляет сохранённые объявления новому пиру. */
    private void sendMarketItemsTo(String peer) {
        Context ctx = appContext;
        String target = peer == null ? "" : peer.trim();
        if (ctx == null || target.isEmpty()) {
            return;
        }
        for (MarketItem item : new MarketManager(ctx).getItems()) {
            sendMessage(target, MARKET_ITEM_PREFIX + item.toJson().toString());
        }
    }

    private void handleIncomingMarketItem(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject item = new JSONObject(payload);
            boolean added = new MarketManager(ctx).handleIncomingItem(item);
            log("Market: item from " + from + (added ? " accepted" : " ignored"));
        } catch (Throwable t) {
            Log.w(TAG, "Market: invalid item from " + from, t);
        }
    }

    private void handleIncomingMarketMakerRequest(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null || isSelfEndpoint(from)) {
            return;
        }
        try {
            JSONObject request = new JSONObject(payload);
            String action = request.optString("action", "").trim()
                    .toLowerCase(java.util.Locale.US);
            if (action.isEmpty()) {
                action = request.has("orderType") || request.has("isBuy")
                        ? "place" : "orders";
            }
            JSONObject response = new JSONObject();
            response.put("type", "MARKET_MAKER_RESP");
            response.put("action", action);
            response.put("endpointId", from);
            if ("register".equals(action)) {
                response.put("success", MarketMakerAPI.registerMarketMaker(from));
            } else if (!MarketMakerAPI.isRegistered(from)) {
                response.put("success", false);
                response.put("error", "not_registered");
            } else if ("place".equals(action)) {
                String type = request.optString("orderType",
                        request.optString("type", ""));
                boolean isBuy = request.has("isBuy")
                        ? request.optBoolean("isBuy", true)
                        : !"SELL".equalsIgnoreCase(type);
                P2PExchangeManager.Result result = MarketMakerAPI.placeOrder(from,
                        isBuy,
                        request.optDouble("amountTRG", 0.0),
                        request.optDouble("priceFiat", 0.0),
                        request.optString("currency", ""));
                response.put("success", result.success);
                response.put("message", result.message);
                if (result.order != null) {
                    response.put("orderId", result.order.getId());
                    response.put("order", result.order.toJson());
                }
            } else if ("cancel".equals(action)) {
                P2PExchangeManager.Result result = MarketMakerAPI.cancelOrder(from,
                        request.optString("orderId", ""));
                response.put("success", result.success);
                response.put("message", result.message);
            } else if ("orders".equals(action)) {
                JSONArray orders = new JSONArray();
                for (P2PExchangeManager.Order order : MarketMakerAPI.getOpenOrders()) {
                    orders.put(order.toJson());
                }
                response.put("success", true);
                response.put("orders", orders);
            } else {
                response.put("success", false);
                response.put("error", "unknown_action");
            }
            sendMessage(from, MARKET_MAKER_RESP_PREFIX + response.toString());
        } catch (Throwable t) {
            Log.w(TAG, "Market maker: invalid request from " + from, t);
        }
    }

    /** Рассылает объявление P2P-обменника всем подключённым пирам. */
    public void broadcastExchangeOffer(JSONObject offer) {
        if (offer == null) {
            return;
        }
        String payload = P2P_EXCHANGE_PREFIX + offer.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает заявку на P2P-обмен всем подключённым пирам. */
    public void broadcastExchangeRequest(JSONObject request) {
        if (request == null) {
            return;
        }
        String payload = P2P_EXCHANGE_REQUEST_PREFIX + request.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает статус P2P-обмена. */
    public void broadcastExchangeStatus(JSONObject status) {
        if (status == null) {
            return;
        }
        String payload = P2P_EXCHANGE_STATUS_PREFIX + status.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает P2P-ордер всем подключённым пирам. */
    public void broadcastOrder(JSONObject order) {
        if (order == null) {
            return;
        }
        String payload = P2P_ORDER_PREFIX + order.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Совместимый алиас для рассылки P2P-ордера. */
    public void broadcastExchangeOrder(JSONObject order) {
        broadcastOrder(order);
    }

    /** Рассылает статус P2P-ордера. */
    public void broadcastExchangeOrderStatus(JSONObject status) {
        if (status == null) {
            return;
        }
        String payload = P2P_EXCHANGE_ORDER_STATUS_PREFIX + status.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает обновление цены TRG (VWAP + сделка) всем подключённым пирам. */
    public void broadcastPriceUpdate(JSONObject update) {
        if (update == null) {
            return;
        }
        String payload = PRICE_UPDATE_PREFIX + update.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает спор с назначенными арбитрами. */
    public void broadcastDispute(JSONObject dispute) {
        if (dispute == null) {
            return;
        }
        String payload = P2P_DISPUTE_PREFIX + dispute.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает голос арбитра по спору. */
    public void broadcastDisputeVote(JSONObject vote) {
        if (vote == null) {
            return;
        }
        String payload = P2P_DISPUTE_VOTE_PREFIX + vote.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Рассылает итог разрешения спора. */
    public void broadcastDisputeStatus(JSONObject status) {
        if (status == null) {
            return;
        }
        String payload = P2P_DISPUTE_STATUS_PREFIX + status.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Сообщает пирам, что объявление P2P-обмена снято. */
    public void broadcastExchangeOfferRemoved(String offerId) {
        if (offerId == null || offerId.trim().isEmpty()) {
            return;
        }
        JSONObject object = new JSONObject();
        try {
            object.put("offerId", offerId);
        } catch (Throwable ignored) {
            return;
        }
        String payload = P2P_EXCHANGE_REMOVE_PREFIX + object.toString();
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    /** Отправляет новому пиру сделки за 24 часа для синхронизации VWAP. */
    private void sendPriceUpdatesTo(String peer) {
        Context ctx = appContext;
        String target = peer == null ? "" : peer.trim();
        if (ctx == null || target.isEmpty()) {
            return;
        }
        PriceOracle oracle = new PriceOracle(ctx);
        for (JSONObject update : oracle.getRecentTradeUpdates()) {
            sendMessage(target, PRICE_UPDATE_PREFIX + update.toString());
        }
    }

    /** Отправляет сохранённые объявления обменника новому пиру. */
    private void sendExchangeOffersTo(String peer) {
        Context ctx = appContext;
        String target = peer == null ? "" : peer.trim();
        if (ctx == null || target.isEmpty()) {
            return;
        }
        P2PExchangeManager manager = new P2PExchangeManager(ctx);
        for (ExchangeOffer offer : manager.getOffers()) {
            sendMessage(target, P2P_EXCHANGE_PREFIX + offer.toJson().toString());
        }
        for (JSONObject request : manager.getPendingRequestPayloads()) {
            sendMessage(target, P2P_EXCHANGE_REQUEST_PREFIX + request.toString());
        }
        for (P2PExchangeManager.Order order : manager.getOrders()) {
            sendMessage(target, P2P_ORDER_PREFIX + order.toJson().toString());
        }
        DisputeManager disputeManager = new DisputeManager(ctx);
        for (DisputeManager.Dispute dispute : disputeManager.getDisputes()) {
            if (dispute.isOpen()) {
                sendMessage(target, P2P_DISPUTE_PREFIX + dispute.toJson().toString());
            }
        }
    }

    private void handleIncomingDispute(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            new DisputeManager(ctx).handleIncomingDispute(new JSONObject(payload));
        } catch (Throwable t) {
            Log.w(TAG, "Dispute: invalid dispute from " + from, t);
        }
    }

    private void handleIncomingDisputeVote(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            new DisputeManager(ctx).handleIncomingDisputeVote(new JSONObject(payload));
        } catch (Throwable t) {
            Log.w(TAG, "Dispute: invalid vote from " + from, t);
        }
    }

    private void handleIncomingDisputeStatus(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            new DisputeManager(ctx).handleIncomingDisputeStatus(new JSONObject(payload));
        } catch (Throwable t) {
            Log.w(TAG, "Dispute: invalid status from " + from, t);
        }
    }

    private void handleIncomingExchangeOffer(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject(payload);
            boolean added = new P2PExchangeManager(ctx).handleIncomingOffer(object);
            log("Exchange: offer from " + from + (added ? " accepted" : " ignored"));
        } catch (Throwable t) {
            Log.w(TAG, "Exchange: invalid offer from " + from, t);
        }
    }

    private void handleIncomingExchangeRequest(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject(payload);
            boolean added = new P2PExchangeManager(ctx).handleIncomingRequest(object);
            log("Exchange: request from " + from + (added ? " accepted" : " ignored"));
        } catch (Throwable t) {
            Log.w(TAG, "Exchange: invalid request from " + from, t);
        }
    }

    private void handleIncomingExchangeStatus(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null || isSelfEndpoint(from)) {
            return;
        }
        try {
            new P2PExchangeManager(ctx).handleIncomingStatus(new JSONObject(payload));
        } catch (Throwable t) {
            Log.w(TAG, "Exchange: invalid status from " + from, t);
        }
    }

    private void handleIncomingExchangeOrder(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            JSONObject object = new JSONObject(payload);
            boolean added = new P2PExchangeManager(ctx).handleIncomingOrder(object);
            log("Exchange: order from " + from + (added ? " accepted" : " ignored"));
        } catch (Throwable t) {
            Log.w(TAG, "Exchange: invalid order from " + from, t);
        }
    }

    private void handleIncomingPriceUpdate(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null || isSelfEndpoint(from)) {
            return;
        }
        try {
            JSONObject update = new JSONObject(payload);
            String senderDeviceId = update.optString("deviceId", "").trim();
            String localDeviceId = deviceId == null ? "" : deviceId.trim();
            if (!senderDeviceId.isEmpty() && !localDeviceId.isEmpty()
                    && senderDeviceId.equals(localDeviceId)) {
                return;
            }
            boolean added = new PriceOracle(ctx).applyPriceUpdate(update);
            if (added) {
                PriceOracle.notifyPriceChanged();
                broadcastPriceUpdate(update);
                log("Price: update from " + from + " accepted");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Price: invalid update from " + from, t);
        }
    }

    private void handleIncomingExchangeOrderStatus(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null || isSelfEndpoint(from)) {
            Log.d(TAG, "Exchange: ignoring own order status from " + from);
            return;
        }
        try {
            new P2PExchangeManager(ctx).handleIncomingOrderStatus(
                    new JSONObject(payload), from);
        } catch (Throwable t) {
            Log.w(TAG, "Exchange: invalid order status from " + from, t);
        }
    }

    private void handleIncomingExchangeRemove(String from, String payload) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            new P2PExchangeManager(ctx).handleIncomingOfferRemoved(new JSONObject(payload));
        } catch (Throwable t) {
            Log.w(TAG, "Exchange: invalid remove from " + from, t);
        }
    }

    /** Адрес кошелька TRG, полученный из профиля пира (пустая строка, если неизвестен). */
    public String getPeerWalletAddress(String peerEndpointId) {
        String peer = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (peer.isEmpty()) {
            return "";
        }
        String wallet = peerWalletAddresses.get(peer);
        return wallet == null ? "" : wallet;
    }

    /** Endpoint ID пира, который в профиле указал этот адрес кошелька. */
    public String findEndpointByWalletAddress(String walletAddress) {
        String target = normalizeWalletAddress(walletAddress);
        if (target.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String> entry : peerWalletAddresses.entrySet()) {
            if (target.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return "";
    }

    /** Есть ли кому доставить перевод: получатель на связи или подключённые пиры. */
    public boolean canSendTransfer(String walletAddress) {
        List<String> peers = getConnectedPeers();
        String target = findEndpointByWalletAddress(walletAddress);
        if (!target.isEmpty() && !target.equals(endpointId)) {
            return peers.contains(target);
        }
        return !peers.isEmpty();
    }

    /**
     * Отправляет TRANSFER получателю по адресу кошелька. Если адрес известен
     * из профиля пира, сообщение идёт напрямую; иначе — всем подключённым пирам,
     * а получатель отфильтрует его по своему адресу.
     */
    public int sendTransferPayload(String walletAddress, String payload) {
        if (payload == null || payload.isEmpty()) {
            return 0;
        }
        String wirePayload = payload.startsWith(TransferManager.TRANSFER_PREFIX)
                ? payload : TransferManager.TRANSFER_PREFIX + payload;
        String target = findEndpointByWalletAddress(walletAddress);
        if (!target.isEmpty() && !target.equals(endpointId)) {
            sendMessage(target, wirePayload);
            return 1;
        }
        int sent = 0;
        for (String peer : getConnectedPeers()) {
            if (peer == null || peer.isEmpty() || peer.equals(endpointId)) {
                continue;
            }
            sendMessage(peer, wirePayload);
            sent++;
        }
        return sent;
    }

    /** @deprecated используйте {@link #getEndpointId()} */
    @Deprecated
    private void registerAndroidNetworkCallback(Context ctx) {
        if (ctx == null) {
            return;
        }
        unregisterAndroidNetworkCallback();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            lastNetworkType = NetworkType.current(ctx);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            ConnectivityManager.NetworkCallback callback =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            handleAndroidNetworkChange(
                                    NetworkType.forNetwork(cm, network));
                        }

                        @Override
                        public void onCapabilitiesChanged(Network network,
                                                          NetworkCapabilities capabilities) {
                            if (capabilities != null
                                    && capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                handleAndroidNetworkChange(
                                        NetworkType.fromCapabilities(capabilities));
                            }
                        }

                        @Override
                        public void onLost(Network network) {
                            String current = NetworkType.current(appContext);
                            if (NetworkType.NONE.equals(current)) {
                                handleAndroidNetworkChange(current);
                            }
                        }
                    };
            androidNetworkCallback = callback;
            cm.registerNetworkCallback(request, callback);
            Log.i(TAG, "Network: Android callback registered, current = "
                    + lastNetworkType);
        } catch (Throwable t) {
            Log.w(TAG, "Network: Android callback registration failed: " + t);
        }
    }

    private void unregisterAndroidNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = androidNetworkCallback;
        androidNetworkCallback = null;
        if (callback == null) {
            return;
        }
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
                Log.i(TAG, "Network: Android callback unregistered");
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleAndroidNetworkChange(String networkType) {
        String type = networkType == null || networkType.trim().isEmpty()
                ? NetworkType.NONE : networkType.trim();
        String previous;
        synchronized (this) {
            previous = lastNetworkType;
            if (type.equals(previous)) {
                return;
            }
            lastNetworkType = type;
        }
        if (heartbeatRunning) {
            applyHeartbeatState();
        }
        Log.i(TAG, "Network: Android change " + previous + " -> " + type);
        if (previous == null || previous.isEmpty()) {
            return;
        }
        if (NetworkType.NONE.equals(type)) {
            // Сеть пропала: ждём onAvailable, перезапуск сделаем при появлении.
            return;
        }
        scheduleNetworkRestart(previous, type);
    }

    private void scheduleNetworkRestart(String from, String to) {
        if (!started.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastNetworkRestartMs.get();
        if (now - last < NETWORK_RESTART_DEBOUNCE_MS) {
            Log.d(TAG, "Network: restart debounced (" + from + " -> " + to + ")");
            return;
        }
        if (!lastNetworkRestartMs.compareAndSet(last, now)) {
            return;
        }
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Log.i(TAG, "Network: restarting Iroh (" + from + " -> " + to + ")");
                stop();
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                start(ctx);
                P2PNode.getInstance().onNetworkChanged();
                Log.i(TAG, "Network: Iroh restarted after network change");
            } catch (Throwable t) {
                Log.w(TAG, "Network: restart failed: " + t);
            }
        }, "iroh-network-restart");
        thread.setDaemon(true);
        thread.start();
    }

    public String getSelfId() {
        return getEndpointId();
    }

    public boolean isStarted() {
        return started.get();
    }

    /**
     * Сообщает Iroh о смене сети (Wi-Fi ↔ 4G).
     *
     * В текущих uniffi-биндингах iroh_ffi (и в libiroh_ffi.so) метода
     * {@code Endpoint.network_change()} нет: доступен только
     * {@code watchNetworkChange(callback)} — обратный вызов из Iroh в приложение.
     * Переподключение тут не запускаем: оно доступно только при старте, по кнопке
     * «Обновить» и по таймеру, когда нет активных соединений.
     */
    private static boolean isActiveNetworkType(String type) {
        return NetworkType.WIFI.equals(type) || NetworkType.CELLULAR.equals(type)
                || NetworkType.ETHERNET.equals(type) || NetworkType.VPN.equals(type);
    }

    public void notifyNetworkChange() {
        notifyNetworkChange(NetworkType.current(appContext));
    }

    private void notifyNetworkChange(String networkType) {
        String type = networkType == null || networkType.trim().isEmpty()
                ? NetworkType.NONE : networkType.trim();
        Endpoint ep = endpoint;
        if (ep == null || !started.get()) {
            return;
        }
        String previous;
        synchronized (this) {
            previous = lastNotifiedNetworkType;
            if (type.equals(previous) || !isActiveNetworkType(type)) {
                return;
            }
            lastNotifiedNetworkType = type;
        }
        long now = System.currentTimeMillis();
        long last = lastNetworkChangeMs.get();
        if (now - last < NETWORK_CHANGE_DEBOUNCE_MS) {
            return;
        }
        if (!lastNetworkChangeMs.compareAndSet(last, now)) {
            return;
        }
        if (!processingNetworkChange.compareAndSet(false, true)) {
            return;
        }
        try {
            Log.i(TAG, "Network change: " + previous + " -> " + type);
            if (heartbeatRunning) {
                applyHeartbeatState();
            }
            refreshSelfDirectAddresses(ep);
        } finally {
            processingNetworkChange.set(false);
        }
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    /**
     * Разрешает Endpoint ID: сначала использует известный прямой IP (mDNS/P2P),
     * затем n0 DNS/Pkarr, PEX и Relay как fallback.
     *
     * <p>n0 discovery сам публикует нашу Pkarr-запись, когда endpoint онлайн, и
     * наполняет кэш адресов пиров. Если прямого адреса нет, читаем кэш
     * ({@link Endpoint#remoteAddr}). Если записи ещё нет, недолго ждём её
     * появления, а затем принудительно запускаем discovery через
     * {@code connect(EndpointAddr(id, null, []))} — в текущих биндингах это
     * единственный способ инициировать DNS-запрос (отдельного lookup API нет).
     * Если и это не удалось, пир считается не найденным в DNS.</p>
     */
    public EndpointAddr resolvePeerByEndpointId(String endpointId) throws Exception {
        String target = endpointId == null ? "" : endpointId.trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("empty endpointId");
        }
        Log.i(TAG, "resolve: start for " + target);
        Endpoint ep = endpoint;
        if (ep == null || !started.get()) {
            throw new IllegalStateException("Iroh endpoint not started");
        }
        final EndpointId peerId;
        try {
            peerId = EndpointId.Companion.fromString(target);
        } catch (Throwable t) {
            Context ctx = appContext;
            String message = ctx == null
                    ? "Invalid Endpoint ID: " + target
                    : ctx.getString(R.string.error_invalid_endpoint_id_format, target);
            throw new IllegalArgumentException(message, t);
        }

        // 0) Прямой адрес из mDNS/P2P — без ожидания DNS/Relay.
        EndpointAddr knownDirect = buildDirectFallback(peerId);
        if (knownDirect != null) {
            Log.i(TAG, "resolve: found via known direct " + describeAddr(knownDirect));
            return knownDirect;
        }

        requestN0Online(ep);
        Log.i(TAG, "resolve: search start for " + target);

        // 1) Pkarr/DNS — fallback, если прямого IP нет.
        long pkarrDeadline = System.currentTimeMillis() + PKARR_LOOKUP_TIMEOUT_MS;
        while (System.currentTimeMillis() < pkarrDeadline) {
            if (endpoint != ep || !started.get()) {
                break;
            }
            EndpointAddr a = lookupRemoteAddr(ep, peerId);
            if (hasAddressing(a)) {
                rememberAddr(a);
                Log.i(TAG, "resolve: found via Pkarr/DNS " + describeAddr(a));
                return a;
            }
            try {
                Thread.sleep(RESOLVE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        Log.i(TAG, "resolve: Pkarr/DNS timeout for " + target);

        // 2) PEX
        try {
            requestPeerByEndpointId(target);
        } catch (Throwable t) {
            Log.w(TAG, "resolve: PEX query failed for " + target + ": " + t);
        }
        long pexDeadline = System.currentTimeMillis() + PKARR_LOOKUP_TIMEOUT_MS;
        while (System.currentTimeMillis() < pexDeadline) {
            if (hasKnownDirectAddress(target)) {
                EndpointAddr a = buildDirectFallback(peerId);
                if (a != null) {
                    rememberAddr(a);
                    Log.i(TAG, "resolve: found via PEX " + describeAddr(a));
                    return a;
                }
            }
            try {
                Thread.sleep(RESOLVE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        Log.i(TAG, "resolve: PEX timeout for " + target);

        // 3) Relay
        EndpointAddr relayAddr = buildRelayFallback(peerId);
        if (relayAddr != null) {
            try {
                getOrConnect(target, true, false, RELAY_CONNECT_TIMEOUT_MS);
                rememberAddr(relayAddr);
                Log.i(TAG, "resolve: found via Relay " + describeAddr(relayAddr));
                return relayAddr;
            } catch (Throwable t) {
                Log.w(TAG, "resolve: Relay failed for " + target + ": " + t);
            }
        }

        Log.i(TAG, "resolve: result = not found");
        throw new PeerNotFoundException(appContext, target);
    }

    private EndpointAddr lookupRemoteAddr(Endpoint ep, EndpointId peerId) {
        try {
            Object r = BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> {
                return ep.remoteAddr(peerId, cont);
            });
            return r instanceof EndpointAddr ? (EndpointAddr) r : null;
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: remoteAddr lookup failed: " + t);
            return null;
        }
    }

    /** Человекочитаемое описание адреса: id + relay + direct-адреса (или null). */
    private static String describeAddr(EndpointAddr addr) {
        if (addr == null) {
            return "null";
        }
        try {
            String relay = addr.relayUrl();
            List<String> direct = addr.directAddresses();
            return addr + " [relay=" + (relay == null || relay.isEmpty() ? "none" : relay)
                    + ", direct=" + direct + "]";
        } catch (Throwable t) {
            return String.valueOf(addr);
        }
    }

    private void refreshSelfDirectAddresses(Endpoint ep) {
        List<String> addresses = new ArrayList<>();
        try {
            EndpointAddr self = ep == null ? null : ep.addr();
            if (self != null) {
                List<String> direct = self.directAddresses();
                if (direct != null) {
                    for (String raw : direct) {
                        String address = raw == null ? "" : raw.trim();
                        if (!address.isEmpty() && !addresses.contains(address)) {
                            addresses.add(address);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to read own direct addresses: " + t);
        }
        if (addresses.isEmpty() && ep != null) {
            List<Integer> ports = new ArrayList<>();
            try {
                List<String> sockets = ep.boundSockets();
                if (sockets != null) {
                    for (String socket : sockets) {
                        int port = portFromAddress(socket);
                        if (port > 0 && !ports.contains(port)) {
                            ports.add(port);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: failed to read bound sockets: " + t);
            }
            List<String> localIps = localIpv4Addresses();
            for (int port : ports) {
                for (String ip : localIps) {
                    String address = ip + ":" + port;
                    if (!addresses.contains(address)) {
                        addresses.add(address);
                    }
                }
            }
        }
        selfDirectAddresses = Collections.unmodifiableList(addresses);
        Log.i(TAG, "Iroh: direct addrs = " + selfDirectAddresses);
        notifyDirectAddressesChanged();
    }

    private void notifyDirectAddressesChanged() {
        Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onDirectAddressesChanged(getSelfDirectAddresses());
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: direct address listener failed: " + t);
        }
    }

    private static int portFromAddress(String address) {
        if (address == null) {
            return 0;
        }
        int colon = address.lastIndexOf(':');
        if (colon < 0 || colon == address.length() - 1) {
            return 0;
        }
        try {
            int port = Integer.parseInt(address.substring(colon + 1));
            return port > 0 && port <= 65535 ? port : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            java.util.Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && address.isSiteLocalAddress()
                            && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (ip != null && !result.contains(ip)) {
                            result.add(ip);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to enumerate local IPv4 addresses: " + t);
        }
        return result;
    }

    private void rememberAddr(EndpointAddr addr) {
        if (addr == null) {
            return;
        }
        try {
            EndpointId id = addr.id();
            String key = id == null ? "" : id.toString();
            String relay = addr.relayUrl();
            List<String> direct = addr.directAddresses();
            if (key.isEmpty()
                    || ((relay == null || relay.isEmpty())
                    && (direct == null || direct.isEmpty()))) {
                return;
            }
            KnownAddr previous = knownAddrs.get(key);
            String mergedRelay = relay == null || relay.isEmpty()
                    ? (previous == null ? "" : previous.relayUrl)
                    : relay;
            List<String> directCopy = new ArrayList<>();
            if (direct != null) {
                for (String raw : direct) {
                    String address = raw == null ? "" : raw.trim();
                    if (!address.isEmpty() && address.contains(":")
                            && !directCopy.contains(address)) {
                        directCopy.add(address);
                    }
                }
            }
            // Не теряем прямой IP, полученный через mDNS/P2P, если DNS вернул
            // только relay: он нужен для fallback при блокировке DNS.
            if (previous != null) {
                for (String address : previous.directAddresses) {
                    if (!directCopy.contains(address) && directCopy.size() < 8) {
                        directCopy.add(address);
                    }
                }
            }
            knownAddrs.put(key, new KnownAddr(mergedRelay, directCopy));
            persistKnownAddrs();
            Log.i(TAG, "Addr cache: " + key + " relay="
                    + (mergedRelay.isEmpty() ? "none" : mergedRelay)
                    + ", direct=" + directCopy);
        } catch (Throwable t) {
            Log.w(TAG, "Addr cache: failed to remember address: " + t);
        }
    }

    private String firstKnownDirectAddress(String peer) {
        if (peer == null || peer.isEmpty()) {
            return "";
        }
        KnownAddr known = knownAddrs.get(peer);
        if ((known == null || known.directAddresses.isEmpty()) && !knownAddrsLoaded.get()) {
            loadKnownAddrs();
            known = knownAddrs.get(peer);
        }
        if (known == null) {
            return "";
        }
        for (String raw : known.directAddresses) {
            String address = raw == null ? "" : raw.trim();
            if (!address.isEmpty() && address.contains(":")) {
                return address;
            }
        }
        return "";
    }

    private boolean hasKnownDirectAddress(String peer) {
        return !firstKnownDirectAddress(peer).isEmpty();
    }

    /**
     * Прямой адрес пира из кэша (mDNS/P2P/DNS). Используется, когда DNS/Pkarr
     * недоступен: connect() получает EndpointId + IP:port без TXT-резолва.
     */
    private EndpointAddr buildDirectFallback(EndpointId peerId) {
        if (peerId == null) {
            return null;
        }
        String peer = peerId.toString();
        KnownAddr known = knownAddrs.get(peer);
        if ((known == null || known.directAddresses.isEmpty()) && !knownAddrsLoaded.get()) {
            // Страховка на случай, если fallback запрошен до загрузки кэша.
            loadKnownAddrs();
            known = knownAddrs.get(peer);
        }
        if (known == null || known.directAddresses.isEmpty()) {
            Log.i(TAG, "Fallback: no known direct address for " + peer);
            return null;
        }
        List<String> direct = new ArrayList<>();
        for (String raw : known.directAddresses) {
            String address = raw == null ? "" : raw.trim();
            if (!address.isEmpty() && address.contains(":") && !direct.contains(address)) {
                direct.add(address);
            }
        }
        if (direct.isEmpty()) {
            Log.i(TAG, "Fallback: no valid direct address for " + peer);
            return null;
        }
        Log.i(TAG, "Fallback: connecting directly to " + direct.get(0));
        if (direct.size() > 1) {
            Log.i(TAG, "Fallback: direct candidates = " + direct);
        }
        return new EndpointAddr(peerId, null, direct);
    }

    private EndpointAddr buildRelayFallback(EndpointId peerId) {
        String peer = peerId == null ? "" : peerId.toString();
        KnownAddr known = knownAddrs.get(peer);
        // Seed relay имеет приоритет: через него проходим NAT, когда DNS
        // недоступен. URL задаётся отдельно от Endpoint ID (Circuit Relay).
        String seedRelay = configuredSeedRelayUrl();
        if (!seedRelay.isEmpty()) {
            List<String> direct = known == null
                    ? Collections.emptyList() : known.directAddresses;
            Log.i(TAG, "Fallback: using seed relay for " + peer
                    + " (" + seedRelay + ", direct=" + direct + ")");
            return new EndpointAddr(peerId, seedRelay, direct);
        }
        if (known != null) {
            Log.i(TAG, "Fallback: using known address for " + peer
                    + " (relay=" + (known.relayUrl.isEmpty() ? "none" : known.relayUrl)
                    + ", direct=" + known.directAddresses + ")");
            return new EndpointAddr(peerId, known.relayUrl.isEmpty() ? null : known.relayUrl,
                    known.directAddresses);
        }
        String relay = homeRelayUrl;
        if (relay != null && !relay.isEmpty()) {
            Log.i(TAG, "Fallback: using home relay for " + peer + " (" + relay + ")");
            return new EndpointAddr(peerId, relay, Collections.emptyList());
        }
        Log.w(TAG, "Fallback: no known relay for " + peer);
        return null;
    }

    /**
     * Добавляет публичный relay к адресу пира, если relay ещё не указан.
     * Это страхует от пустых Pkarr-записей: даже без direct-адресов
     * подключение пойдёт через уже настроенный relay.
     */
    private EndpointAddr ensureConfiguredRelay(EndpointId peerId, EndpointAddr addr) {
        String configured = configuredSeedRelayUrl();
        if (peerId == null || configured.isEmpty()) {
            return addr;
        }
        String relay = "";
        List<String> direct = Collections.emptyList();
        if (addr != null) {
            try {
                String currentRelay = addr.relayUrl();
                if (currentRelay != null) {
                    relay = currentRelay.trim();
                }
                List<String> currentDirect = addr.directAddresses();
                if (currentDirect != null) {
                    direct = currentDirect;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Fallback: failed to read cached address: " + t);
            }
        }
        if (!relay.isEmpty()) {
            return addr;
        }
        Log.i(TAG, "Fallback: adding configured relay " + configured
                + " for " + peerId + " (direct=" + direct + ")");
        return new EndpointAddr(peerId, configured, direct);
    }

    private static boolean hasAddressing(EndpointAddr addr) {
        if (addr == null) {
            return false;
        }
        try {
            String relay = addr.relayUrl();
            List<String> direct = addr.directAddresses();
            return (relay != null && !relay.isEmpty())
                    || (direct != null && !direct.isEmpty());
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class KnownAddr {
        final String relayUrl;
        final List<String> directAddresses;

        KnownAddr(String relayUrl, List<String> directAddresses) {
            this.relayUrl = relayUrl;
            this.directAddresses = Collections.unmodifiableList(
                    new ArrayList<>(directAddresses));
        }
    }

    private static final class PexEntry {
        final String username;
        final long timestamp;

        PexEntry(String username, long timestamp) {
            this.username = username == null ? "" : username;
            this.timestamp = timestamp;
        }
    }

    /** Проверяет, является ли ошибка таймаутом (timed out / timeout / deadline). */
    private static boolean isTimeout(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < 8) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("timed out") || lower.contains("timeout")
                        || lower.contains("deadline")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean enableDhtDiscovery(EndpointBuilder builder) {
        try {
            builder.applyN0();
            Log.i(TAG, "Iroh: DHT discovery initialized via n0 preset (Pkarr/DNS + relays)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: applyN0 failed: " + t, t);
        }
        if (tryEnableDhtByReflection(builder)) {
            Log.i(TAG, "Iroh: DHT discovery initialized via reflection");
            return true;
        }
        return false;
    }

    private boolean tryEnableDhtByReflection(EndpointBuilder builder) {
        String[] names = {"enableDht", "dht", "dhtDiscovery", "mainlineAddressLookup", "discovery"};
        for (String name : names) {
            try {
                builder.getClass().getMethod(name).invoke(builder);
                return true;
            } catch (Throwable ignored) {
            }
            try {
                builder.getClass().getMethod(name, boolean.class).invoke(builder, true);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Пир не разрешается через n0 DNS/Pkarr. */
    public static class PeerNotFoundException extends Exception {
        private final String endpointId;

        public PeerNotFoundException(Context context, String endpointId) {
            super(context == null
                    ? "Peer not found in DNS"
                    : context.getString(R.string.error_peer_not_found_dns));
            this.endpointId = endpointId;
        }

        public String getEndpointId() {
            return endpointId;
        }
    }

    public void connectToPeer(String peerEndpointId) {
        connectToPeer(peerEndpointId, null);
    }

    public void connectToPeer(String peerEndpointId, ConnectCallback callback) {
        final String target = peerEndpointId == null ? "" : peerEndpointId.trim();
        Log.i(TAG, "connect: start to " + target);
        if (target.isEmpty()) {
            Log.i(TAG, "connect: result = error (empty endpointId)");
            if (callback != null) {
                callback.onError(target, "empty endpointId");
            }
            return;
        }
        if (isChannelOpen(target)) {
            Log.i(TAG, "connect: already connected to " + target
                    + ", reusing existing connection");
            if (callback != null) {
                callback.onSuccess(target);
            }
            return;
        }
        ioExec.submit(() -> {
            Throwable lastError = null;
            boolean cellular = NetworkType.CELLULAR.equals(NetworkType.current(appContext));
            String directAddress = cellular ? "" : firstKnownDirectAddress(target);

            // 1) Прямое подключение по известному IP (mDNS/P2P).
            if (!directAddress.isEmpty()) {
                Log.i(TAG, "Connect: direct to " + directAddress);
                timing("direct connect start: peer=" + target + ", ip=" + directAddress);
                try {
                    getOrConnect(target, false, true, DIRECT_CONNECT_TIMEOUT_MS);
                    Log.i(TAG, "connect: result = success (direct) to " + target);
                    NetworkFileLogger.log("P2P connect (direct): " + target
                            + " via " + directAddress);
                    timing("direct connect ok: peer=" + target + ", ip=" + directAddress);
                    if (callback != null) {
                        callback.onSuccess(target);
                    }
                    return;
                } catch (Throwable t) {
                    lastError = t;
                    timing("direct connect failed: peer=" + target + ", ip=" + directAddress);
                    Log.w(TAG, "Connect: direct failed, falling back to Relay: " + t);
                    NetworkFileLogger.log("P2P connect fail (direct): " + target
                            + " via " + directAddress + ": " + t);
                }
            } else if (cellular) {
                timing("LTE: direct skipped, relay first: peer=" + target);
            }

            // 2) Relay/Pkarr — только если прямое подключение не удалось.
            Log.i(TAG, "Connect: relay to " + target);
            timing("relay connect start: peer=" + target);
            try {
                getOrConnect(target, true, false, RELAY_CONNECT_TIMEOUT_MS);
                Log.i(TAG, "connect: result = success (relay) to " + target);
                NetworkFileLogger.log("P2P connect (relay): " + target);
                timing("relay connect ok: peer=" + target);
                if (callback != null) {
                    callback.onSuccess(target);
                }
                return;
            } catch (Throwable t) {
                lastError = t;
                timing("relay connect failed: peer=" + target);
                Log.w(TAG, "Connect: relay failed to " + target + ": " + t);
                NetworkFileLogger.log("Relay fail: connect to " + target + ": " + t);
            }

            Throwable t = lastError == null
                    ? new IllegalStateException("connect failed") : lastError;
            boolean timedOut = isTimeout(t);
            Log.e(TAG, "connect: result = error to " + target
                    + (timedOut ? " (timed out)" : "") + ": " + t, t);
            Log.e(TAG, "Iroh: connectToPeer failed: " + target, t);
            if (callback != null) {
                callback.onError(target, t.getMessage() == null ? t.toString() : t.getMessage());
            }
            try {
                Log.e(TAG, "Stability: connection error = " + (t.getMessage() == null ? t.toString() : t.getMessage()));
            } catch (Exception ignored) {
            }
        });
    }

    public void sendMessage(String endpointId, String text) {
        sendMessage(endpointId, text, null);
    }

    public void sendTypingStart(String peerEndpointId) {
        sendTyping(peerEndpointId, TYPING_START_PREFIX);
    }

    public void sendTypingStop(String peerEndpointId) {
        sendTyping(peerEndpointId, TYPING_STOP_PREFIX);
    }

    /**
     * Отправляет подтверждение прочтения: собеседник узнает, что его сообщения
     * (до момента upToTimestamp включительно) прочитаны.
     */
    public void sendReadReceipt(String peerEndpointId, long upToTimestamp) {
        final String peer = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (peer.isEmpty() || upToTimestamp <= 0L) {
            return;
        }
        final String payload;
        try {
            JSONObject body = new JSONObject();
            body.put("upTo", upToTimestamp);
            payload = READ_RECEIPT_PREFIX + body;
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to build read receipt", t);
            return;
        }
        ioExec.submit(() -> {
            try {
                sendDirect(peer, payload);
            } catch (Throwable t) {
                Log.d(TAG, "Read receipt skipped: "
                        + (t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        });
    }

    private void sendTyping(String peerEndpointId, String prefix) {
        final String peer = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (peer.isEmpty()) {
            return;
        }
        final String self = endpointId == null ? "" : endpointId;
        final String payload = prefix + self;
        ioExec.submit(() -> {
            try {
                sendDirect(peer, payload);
            } catch (Throwable t) {
                Log.d(TAG, "Typing packet skipped: "
                        + (t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        });
    }

    /**
     * Отправка сообщения. Если передан callback (пользовательский чат), сообщение
     * помечается id для дедупликации у получателя; callback сообщает о результате
     * записи в поток, а не об отсутствии прямого IP.
     */
    public void sendMessage(String endpointId, String text, SendCallback callback) {
        sendMessage(endpointId, text, 0, "", callback);
    }

    public void sendMessage(String endpointId, String text, long replyToId,
                            String replyPreview, SendCallback callback) {
        final String peer = endpointId == null ? "" : endpointId.trim();
        final String payload = text == null ? "" : text;
        if (peer.isEmpty() || payload.isEmpty()) {
            if (callback != null) {
                callback.onError(peer, "empty endpointId or text");
            }
            return;
        }
        if (callback == null) {
            ioExec.submit(() -> {
                try {
                    sendDirect(peer, payload);
                    if (firstOutgoingMessageTimingLogged.compareAndSet(false, true)) {
                        timing("first message sent: peer=" + peer
                                + ", bytes=" + payload.length());
                    }
                } catch (Throwable t) {
                    String error = t.getMessage() == null ? t.toString() : t.getMessage();
                    Log.e(TAG, "Send failed: " + error, t);
                    try {
                        Log.e(TAG, "Stability: error = " + error);
                    } catch (Exception ignored) {
                    }
                }
            });
            return;
        }

        final String messageId = UUID.randomUUID().toString();
        final String wirePayload;
        try {
            JSONObject body = new JSONObject();
            body.put("id", messageId);
            body.put("text", payload);
            if (replyToId > 0) {
                body.put("replyTo", replyToId);
                body.put("replyPreview", replyPreview == null ? "" : replyPreview);
            }
            wirePayload = CHAT_MSG_PREFIX + body;
        } catch (Throwable t) {
            callback.onError(peer, t.getMessage() == null ? t.toString() : t.getMessage());
            return;
        }

        ioExec.submit(() -> {
            try {
                sendDirect(peer, wirePayload);
                logFirstOutgoingMessage(peer, payload);
                timing("message sent: peer=" + peer + ", id=" + messageId);
                try {
                    callback.onSent(peer);
                } catch (Throwable t) {
                    Log.w(TAG, "Send callback failed on success: " + t);
                }
            } catch (Throwable t) {
                String error = t.getMessage() == null ? t.toString() : t.getMessage();
                Log.e(TAG, "Send failed: " + error, t);
                try {
                    Log.e(TAG, "Stability: error = " + error);
                } catch (Exception ignored) {
                }
                try {
                    callback.onError(peer, error);
                } catch (Throwable callbackError) {
                    Log.w(TAG, "Send callback failed on error: " + callbackError);
                }
            }
        });
    }

    /** Отправляет голосовое сообщение: аудио передаётся внутри конверта (base64). */
    public void sendAudioMessage(String endpointId, String audioBase64, SendCallback callback) {
        sendAudioMessage(endpointId, audioBase64, 0, "", callback);
    }

    public void sendAudioMessage(String endpointId, String audioBase64,
                                 long replyToId, String replyPreview, SendCallback callback) {
        final String peer = endpointId == null ? "" : endpointId.trim();
        final String audio = audioBase64 == null ? "" : audioBase64;
        if (peer.isEmpty() || audio.isEmpty()) {
            if (callback != null) {
                callback.onError(peer, "empty endpointId or audio");
            }
            return;
        }
        final String messageId = UUID.randomUUID().toString();
        final String wirePayload;
        try {
            JSONObject body = new JSONObject();
            body.put("id", messageId);
            body.put("text", "");
            body.put("type", ChatDatabase.TYPE_AUDIO);
            body.put("audio", audio);
            if (replyToId > 0) {
                body.put("replyTo", replyToId);
                body.put("replyPreview", replyPreview == null ? "" : replyPreview);
            }
            wirePayload = CHAT_MSG_PREFIX + body;
        } catch (Throwable t) {
            if (callback != null) {
                callback.onError(peer, t.getMessage() == null ? t.toString() : t.getMessage());
            }
            return;
        }
        ioExec.submit(() -> {
            try {
                sendDirect(peer, wirePayload);
                timing("voice message sent: peer=" + peer
                        + ", id=" + messageId + ", bytes=" + audio.length());
                if (callback != null) {
                    callback.onSent(peer);
                }
            } catch (Throwable t) {
                String error = t.getMessage() == null ? t.toString() : t.getMessage();
                Log.e(TAG, "Voice message send failed: " + error, t);
                if (callback != null) {
                    try {
                        callback.onError(peer, error);
                    } catch (Throwable callbackError) {
                        Log.w(TAG, "Send callback failed on error: " + callbackError);
                    }
                }
            }
        });
    }

    private void logFirstOutgoingMessage(String peer, String payload) {
        if (firstOutgoingMessageTimingLogged.compareAndSet(false, true)) {
            timing("first message sent: peer=" + peer
                    + ", bytes=" + payload.length());
        }
    }

    private void sendOnConnection(String peer, String text, Connection conn, String path)
            throws Exception {
        BiStream bi = IrohIo.openBi(conn);
        try {
            Log.i(TAG, path + " send: to=" + peer + ", text=" + text
                    + ", time=" + System.currentTimeMillis());
            IrohIo.sendText(bi.send(), text);
        } finally {
            bi.close();
        }
    }

    private void sendDirect(String peer, String text) throws Exception {
        boolean channelOpen = isChannelOpen(peer);
        boolean cellular = NetworkType.CELLULAR.equals(NetworkType.current(appContext));
        Connection conn;
        if (!channelOpen && !cellular && hasKnownDirectAddress(peer)) {
            // Приоритет прямого соединения: известный IP из mDNS/P2P, без Relay.
            try {
                conn = getOrConnect(peer, false, true, DIRECT_CONNECT_TIMEOUT_MS);
            } catch (Throwable directError) {
                Log.w(TAG, "Send: direct connect failed, falling back to relay/DNS: "
                        + directError);
                conn = getOrConnect(peer, true);
            }
        } else {
            // LTE: не ждём локальный P2P-скан, идём через Relay/DNS.
            conn = getOrConnect(peer, cellular);
        }
        sendOnConnection(peer, text, conn, "Msg");
    }

    /** Есть ли живое соединение с пиром (DataChannel). */
    private boolean isChannelOpen(String peer) {
        Connection conn = connections.get(peer);
        if (conn == null) {
            return false;
        }
        try {
            return conn.closeReason() == null;
        } catch (Throwable t) {
            return false;
        }
    }

    private Connection getOrConnect(String peerEndpointId) throws Exception {
        return getOrConnect(peerEndpointId, false);
    }

    private Connection getOrConnect(String peerEndpointId, boolean relayFallback) throws Exception {
        return getOrConnect(peerEndpointId, relayFallback, false, CONNECT_TIMEOUT_MS);
    }

    private Connection getOrConnect(String peerEndpointId, boolean relayFallback,
                                    boolean preferDirect, long timeoutMs) throws Exception {
        synchronized (connectionLock) {
            Connection existing = connections.get(peerEndpointId);
            if (existing != null) {
                String reason = null;
                try {
                    reason = existing.closeReason();
                } catch (Throwable ignored) {}
                if (reason == null) {
                    return existing;
                }
                connections.remove(peerEndpointId);
            }
            Endpoint ep = endpoint;
            if (ep == null) {
                throw new IllegalStateException("Iroh endpoint not started");
            }
            EndpointId peerId = EndpointId.Companion.fromString(peerEndpointId);
            EndpointAddr addr = null;
            if (preferDirect) {
                // Сначала пробуем известный прямой IP без Relay и DNS-ожидания.
                addr = buildDirectFallback(peerId);
                if (addr != null) {
                    Log.i(TAG, "Dial: direct-first known IP for " + peerEndpointId
                            + " (" + describeAddr(addr) + ")");
                }
            }
            if (addr == null) {
                try {
                    Object r = BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> {
                        return ep.remoteAddr(peerId, cont);
                    });
                    if (r instanceof EndpointAddr) {
                        addr = (EndpointAddr) r;
                    }
                } catch (Throwable ignored) {}
                rememberAddr(addr);
                // DNS/Pkarr не дал адреса (например, на 4G) — используем knownAddrs:
                // relay + прямой IP из mDNS/P2P. Relay важен при переходе Wi-Fi → 4G,
                // когда старый прямой IP уже недоступен.
                if (!hasAddressing(addr)) {
                    EndpointAddr knownFallback = buildRelayFallback(peerId);
                    if (knownFallback != null) {
                        addr = knownFallback;
                        Log.i(TAG, "Fallback: using knownAddrs (relay + direct) for "
                                + peerEndpointId);
                    } else {
                        EndpointAddr directFallback = buildDirectFallback(peerId);
                        if (directFallback != null) {
                            addr = directFallback;
                        }
                    }
                } else if (relayFallback) {
                    Log.i(TAG, "Fallback: using cached address for " + peerEndpointId
                            + ", DNS retry skipped");
                }
                addr = ensureConfiguredRelay(peerId, addr);
            }
            if (addr == null) {
                addr = new EndpointAddr(peerId, null, Collections.emptyList());
            }
            final EndpointAddr target = addr;
            Log.i(TAG, "Dial: trying to connect to " + peerEndpointId
                    + " (" + describeAddr(target) + ")");
            Log.i(TAG, "Iroh: connecting with ALPN = " + ALPN);
            byte[] alpn = alpnBytes();
            Object connObj;
            try {
                connObj = IrohIo.connectWithTimeout(ep, target, alpn,
                        timeoutMs > 0 ? timeoutMs : CONNECT_TIMEOUT_MS);
            } catch (Throwable t) {
                Log.e(TAG, "Dial failed: " + peerEndpointId + " -> " + t);
                throw t;
            }
            if (!(connObj instanceof Connection)) {
                IllegalStateException error =
                        new IllegalStateException("connect returned: " + connObj);
                Log.e(TAG, "Dial failed: " + peerEndpointId + " -> " + error);
                throw error;
            }
            Connection conn = (Connection) connObj;
            connections.put(peerEndpointId, conn);
            if (addKnownPeer(peerEndpointId)) {
                persistKnownPeers();
            }
            ioExec.submit(() -> readLoop(conn, peerEndpointId));
            sendProfile(peerEndpointId);
            sendInitialPex(peerEndpointId);
            requestChainFromPeer(peerEndpointId);
            sendMarketItemsTo(peerEndpointId);
            sendExchangeOffersTo(peerEndpointId);
            sendPriceUpdatesTo(peerEndpointId);
            notifyPeerConnected(peerEndpointId);
            String dev = deviceId;
            if (dev != null && !dev.isEmpty()) {
                Log.i(TAG, "Iroh: connected to " + peerEndpointId + ", ALPN = " + describeAlpn(conn.alpn())
                        + " (device_id=" + dev + ")");
            } else {
                Log.i(TAG, "Iroh: connected to " + peerEndpointId + ", ALPN = " + describeAlpn(conn.alpn()));
            }
            NetworkFileLogger.log("P2P connect (iroh): " + peerEndpointId);
            try {
                Log.i(TAG, "Stability: connected to " + peerEndpointId);
            } catch (Exception ignored) {
            }
            return conn;
        }
    }

    private static final class CachedBlock {
        final String json;
        final long expiresAt;

        CachedBlock(String json, long expiresAt) {
            this.json = json;
            this.expiresAt = expiresAt;
        }
    }

    private void timing(String message) {
        TimingLogger.log(message);
        long started = irohStartRequestedAtMs;
        long elapsed = started > 0 ? System.currentTimeMillis() - started : 0L;
        Log.d(TIMING_TAG, message + " (+" + elapsed + "ms)");
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        Listener l = listener;
        if (l != null) {
            try {
                l.onLog(msg);
            } catch (Throwable ignored) {}
        }
    }
}
