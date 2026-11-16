package com.triger.poc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import computer.iroh.BiStream;
import computer.iroh.Connection;
import computer.iroh.Endpoint;
import computer.iroh.EndpointAddr;
import computer.iroh.EndpointBuilder;
import computer.iroh.EndpointId;
import computer.iroh.Incoming;
import computer.iroh.IrohAndroid;
import computer.iroh.IrohException;
import computer.iroh.RecvStream;
import computer.iroh.SecretKey;

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

    public interface Listener {
        void onStarted(String endpointId);
        void onLog(String msg);

        /** Вызывается, если запуск упал; message содержит текст ошибки. */
        default void onFailed(String message) {}
    }

    public interface OnMessageListener {
        void onMessage(String fromEndpointId, String text);
    }

    public interface OnBlockListener {
        void onBlock(String fromEndpointId, String blockJson);
    }

    public interface ConnectCallback {
        void onSuccess(String endpointId);
        void onError(String endpointId, String message);
    }

    /** ALPN протокола чата. Должен совпадать на обеих сторонах. */
    public static final String ALPN = "triger/1";

    /**
     * Служебный префикс peer exchange. Такие сообщения не попадают в чат.
     * n0 DNS в iroh умеет находить адрес только по уже известному Endpoint ID,
     * поэтому список известных пиров распространяется между подключёнными узлами.
     */
    private static final String PEX_PREFIX = "__triger_pex__";
    /** Служебный префикс профиля узла (device_id + endpointId), не попадает в чат. */
    private static final String PROFILE_PREFIX = "__triger_profile__";
    private static final int PROFILE_VERSION = 1;
    private static final String DISCOVERY_PREFS = "triger_iroh_discovery";
    private static final String KEY_KNOWN_PEERS = "known_peers";
    /** Постоянный ключ идентичности узла: один и тот же endpointId между запусками. */
    private static final String IDENTITY_PREFS = "triger_iroh";
    private static final String KEY_SECRET = "secret_key";
    private static final int MAX_KNOWN_PEERS = 500;
    private static final int MAX_PEX_PEERS = 64;
    private static final long DISCOVERY_INTERVAL_MS = 15000L;

    private static byte[] alpnBytes() {
        return ALPN.getBytes(StandardCharsets.UTF_8);
    }

    private static String describeAlpn(byte[] alpn) {
        return alpn == null ? "" : new String(alpn, StandardCharsets.UTF_8);
    }

    private Listener listener;
    private volatile OnMessageListener messageListener;
    private final CopyOnWriteArrayList<OnBlockListener> blockListeners = new CopyOnWriteArrayList<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Set<String> knownPeers = ConcurrentHashMap.newKeySet();
    private final Set<String> connectingPeers = ConcurrentHashMap.newKeySet();
    private final Object connectionLock = new Object();
    private final ExecutorService ioExec = Executors.newCachedThreadPool();
    private volatile Thread acceptThread;
    private volatile Thread discoveryThread;
    private final AtomicBoolean discoveryRunning = new AtomicBoolean(false);
    private final AtomicBoolean n0OnlineRequested = new AtomicBoolean(false);
    private volatile Context appContext;
    private volatile ConnectivityManager.NetworkCallback networkCallback;
    private final AtomicLong lastNetworkChangeMs = new AtomicLong(0L);
    private static final long NETWORK_CHANGE_DEBOUNCE_MS = 500L;
    private volatile String deviceId = "";

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
                    .commit();
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

    public void addOnBlockListener(OnBlockListener l) {
        if (l != null) {
            blockListeners.addIfAbsent(l);
        }
    }

    public void removeOnBlockListener(OnBlockListener l) {
        blockListeners.remove(l);
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
        final Context appCtx = ctx != null ? ctx.getApplicationContext() : null;
        if (appCtx != null) {
            appContext = appCtx;
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
            try {
                builder.applyN0();
                Log.i(TAG, "Iroh: n0 preset applied");
            } catch (Throwable t) {
                // applyN0 необязателен: без него узел поднимется, но без relay.
                Log.w(TAG, "Iroh: applyN0 failed (continuing without n0): " + t, t);
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
                    return;
                }
            } catch (Throwable t) {
                failStart("building endpoint", t);
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
            loadKnownPeers();
            connections.clear();
            started.set(true);
            registerNetworkCallback(appCtx);
            startAcceptLoop(endpoint);
            starting.set(false);
            Log.i(TAG, "Iroh: endpoint started, id = " + endpointId);
            Log.i(TAG, "Iroh: started, endpointId = " + endpointId);
            if (deviceId != null && !deviceId.isEmpty()) {
                Log.i(TAG, "Iroh: device_id attached to profile = " + deviceId);
            }
            notifyStarted(endpointId);
            startDiscovery();
        });
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
            if (addKnownPeer(remote)) {
                persistKnownPeers();
            }
            sendProfile(remote);
            String dev = deviceId;
            if (dev != null && !dev.isEmpty()) {
                log("Iroh: incoming connection from " + remote + ", ALPN = " + describeAlpn(conn.alpn())
                        + " (device_id=" + dev + ")");
            } else {
                log("Iroh: incoming connection from " + remote + ", ALPN = " + describeAlpn(conn.alpn()));
            }
            readLoop(conn, remote);
        } catch (Throwable t) {
            Log.e(TAG, "Iroh: incoming connection failed", t);
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
                    if (text != null && !text.isEmpty()) {
                        if (text.startsWith(PROFILE_PREFIX)) {
                            handleProfile(remote, text.substring(PROFILE_PREFIX.length()));
                        } else if (text.startsWith(PEX_PREFIX)) {
                            handlePeerExchange(text.substring(PEX_PREFIX.length()));
                        } else if (isBlockJson(text)) {
                            notifyBlock(remote, text);
                        } else {
                            notifyMessage(remote, text);
                        }
                    }
                } finally {
                    bi.close();
                }
            } catch (Throwable t) {
                if (connections.get(remote) == conn) {
                    Log.w(TAG, "Iroh: receive loop ended for " + remote + ": " + t);
                }
                return;
            }
        }
    }

    private void notifyMessage(String from, String text) {
        OnMessageListener l = messageListener;
        if (l != null) {
            try {
                l.onMessage(from, text);
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
     * обнаружение строится на peer exchange: подключённые пиры обмениваются
     * списками известных Endpoint ID, после чего узлы автоматически соединяются.
     */
    public void startDiscovery() {
        if (!discoveryRunning.compareAndSet(false, true)) {
            return;
        }
        loadKnownPeers();
        Thread t = new Thread(() -> {
            log("Iroh: discovery started (n0 DNS + peer exchange)");
            boolean requestedOnline = false;
            while (discoveryRunning.get()) {
                try {
                    Endpoint ep = endpoint;
                    if (!started.get() || ep == null) {
                        Thread.sleep(1000L);
                        continue;
                    }
                    if (!requestedOnline) {
                        requestedOnline = true;
                        requestN0Online(ep);
                    }
                    syncKnownFromConnections();
                    broadcastPeerExchange();
                    connectKnownPeers();
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

    /** Ждёт подключения к relay/DNS в фоне, чтобы n0 discovery был прогрето. */
    private void requestN0Online(Endpoint ep) {
        if (!n0OnlineRequested.compareAndSet(false, true)) {
            return;
        }
        ioExec.submit(() -> {
            try {
                BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> ep.online(cont));
                log("Iroh: n0 discovery online");
            } catch (Throwable t) {
                Log.w(TAG, "Iroh: n0 discovery offline: " + t);
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

    private void broadcastPeerExchange() {
        List<String> peers = getKnownPeers();
        if (peers.isEmpty()) {
            return;
        }
        JSONArray array = new JSONArray();
        int count = 0;
        for (String peer : peers) {
            if (peer.equals(endpointId)) {
                continue;
            }
            array.put(peer);
            if (++count >= MAX_PEX_PEERS) {
                break;
            }
        }
        if (array.length() == 0) {
            return;
        }
        String payload = PEX_PREFIX + array;
        for (String peer : getConnectedPeers()) {
            sendMessage(peer, payload);
        }
    }

    private void handlePeerExchange(String payload) {
        try {
            JSONArray array = new JSONArray(payload);
            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                changed |= addKnownPeer(array.optString(i, ""));
            }
            if (changed) {
                persistKnownPeers();
                connectKnownPeers();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: invalid peer exchange payload: " + t);
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

            String peerDeviceId = obj.optString("deviceId", "").trim();
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

    private boolean addKnownPeer(String endpointIdValue) {
        if (endpointIdValue == null) {
            return false;
        }
        String peer = endpointIdValue.trim();
        if (peer.isEmpty() || peer.equals(endpointId) || knownPeers.size() >= MAX_KNOWN_PEERS) {
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
                for (String peer : saved) {
                    addKnownPeer(peer);
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

    private void notifyBlock(String from, String blockJson) {
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
        unregisterNetworkCallback();
        Thread discovery = discoveryThread;
        discoveryThread = null;
        if (discovery != null) {
            discovery.interrupt();
        }
        connectingPeers.clear();
        n0OnlineRequested.set(false);
        for (Connection c : connections.values()) {
            try {
                c.close();
            } catch (Throwable ignored) {}
        }
        connections.clear();
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
        TreeSet<String> peers = new TreeSet<>(knownPeers);
        peers.addAll(getConnectedPeers());
        if (endpointId != null && !endpointId.isEmpty()) {
            peers.remove(endpointId);
        }
        return new ArrayList<>(peers);
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

    /** @deprecated используйте {@link #getEndpointId()} */
    @Deprecated
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
     * Поэтому как best-effort заново запрашиваем relay/DNS online и
     * переподключаем известных пиров через актуальный сетевой маршрут.
     */
    public void notifyNetworkChange() {
        Endpoint ep = endpoint;
        if (ep == null || !started.get()) {
            Log.w(TAG, "Iroh: network change ignored, endpoint not started");
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastNetworkChangeMs.get();
        if (now - last < NETWORK_CHANGE_DEBOUNCE_MS) {
            return;
        }
        if (!lastNetworkChangeMs.compareAndSet(last, now)) {
            return;
        }

        Log.i(TAG, "Iroh: network change notified");
        Log.w(TAG, "Iroh: endpoint.network_change() is not exposed by current bindings; "
                + "re-requesting relay/DNS online and reconnecting known peers");
        n0OnlineRequested.set(false);
        requestN0Online(ep);
        ioExec.submit(this::connectKnownPeers);
    }

    /** Регистрирует Android-колбэк смены сети и уведомляет Iroh через {@link #notifyNetworkChange()}. */
    private void registerNetworkCallback(Context ctx) {
        if (ctx == null || networkCallback != null) {
            return;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                Log.w(TAG, "Iroh: ConnectivityManager unavailable, network changes not tracked");
                return;
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            ConnectivityManager.NetworkCallback callback =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            Log.i(TAG, "Iroh: network available: " + network);
                            notifyNetworkChange();
                        }

                        @Override
                        public void onLost(Network network) {
                            Log.i(TAG, "Iroh: network lost: " + network);
                            notifyNetworkChange();
                        }
                    };
            cm.registerNetworkCallback(request, callback);
            networkCallback = callback;
            Log.i(TAG, "Iroh: network callback registered");
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to register network callback: " + t);
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (callback == null) {
            return;
        }
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
                Log.i(TAG, "Iroh: network callback unregistered");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Iroh: failed to unregister network callback: " + t);
        }
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void connectToPeer(String peerEndpointId) {
        connectToPeer(peerEndpointId, null);
    }

    public void connectToPeer(String peerEndpointId, ConnectCallback callback) {
        final String target = peerEndpointId == null ? "" : peerEndpointId.trim();
        if (target.isEmpty()) {
            if (callback != null) {
                callback.onError(target, "empty endpointId");
            }
            return;
        }
        ioExec.submit(() -> {
            try {
                getOrConnect(target);
                if (callback != null) {
                    callback.onSuccess(target);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Iroh: connectToPeer failed: " + target, t);
                if (callback != null) {
                    callback.onError(target, t.getMessage() == null ? t.toString() : t.getMessage());
                }
            }
        });
    }

    public void sendMessage(String endpointId, String text) {
        final String peer = endpointId == null ? "" : endpointId.trim();
        final String payload = text == null ? "" : text;
        if (peer.isEmpty() || payload.isEmpty()) {
            return;
        }
        ioExec.submit(() -> {
            try {
                sendOnce(peer, payload);
            } catch (Throwable first) {
                Log.w(TAG, "Iroh: send failed, reconnecting to " + peer + ": " + first);
                synchronized (connectionLock) {
                    connections.remove(peer);
                }
                try {
                    sendOnce(peer, payload);
                } catch (Throwable second) {
                    Log.e(TAG, "Iroh: send failed to " + peer, second);
                }
            }
        });
    }

    private void sendOnce(String peer, String text) throws Exception {
        Connection conn = getOrConnect(peer);
        BiStream bi = IrohIo.openBi(conn);
        try {
            IrohIo.sendText(bi.send(), text);
        } finally {
            bi.close();
        }
    }

    private Connection getOrConnect(String peerEndpointId) throws Exception {
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
            try {
                Object r = BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> {
                    return ep.remoteAddr(peerId, cont);
                });
                if (r instanceof EndpointAddr) {
                    addr = (EndpointAddr) r;
                }
            } catch (Throwable ignored) {}
            if (addr == null) {
                addr = new EndpointAddr(peerId, null, Collections.emptyList());
            }
            final EndpointAddr target = addr;
            Log.i(TAG, "Iroh: connecting with ALPN = " + ALPN);
            byte[] alpn = alpnBytes();
            Object connObj = BuildersKt.runBlocking(Dispatchers.getIO(), (scope, cont) -> {
                try {
                    return ep.connect(target, alpn, cont);
                } catch (IrohException e) {
                    throw new RuntimeException(e);
                }
            });
            if (!(connObj instanceof Connection)) {
                throw new IllegalStateException("connect returned: " + connObj);
            }
            Connection conn = (Connection) connObj;
            connections.put(peerEndpointId, conn);
            if (addKnownPeer(peerEndpointId)) {
                persistKnownPeers();
            }
            ioExec.submit(() -> readLoop(conn, peerEndpointId));
            sendProfile(peerEndpointId);
            String dev = deviceId;
            if (dev != null && !dev.isEmpty()) {
                Log.i(TAG, "Iroh: connected to " + peerEndpointId + ", ALPN = " + describeAlpn(conn.alpn())
                        + " (device_id=" + dev + ")");
            } else {
                Log.i(TAG, "Iroh: connected to " + peerEndpointId + ", ALPN = " + describeAlpn(conn.alpn()));
            }
            return conn;
        }
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
