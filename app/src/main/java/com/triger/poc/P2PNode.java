package com.triger.poc;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class P2PNode {
    private static final String TAG = "P2PNode";
    /** Интервал повторной отправки HELLO соединениям, ожидающим наш Iroh Endpoint ID. */
    private static final long HELLO_RETRY_SEC = 5;

    public interface OnBlockReceivedListener {
        void onBlockReceived(String blockData, String validatorAddress);
    }

    public interface OnPeerCountChangedListener {
        void onPeerCountChanged(int count);
    }

    public interface OnChainReceivedListener {
        void onChainReceived(List<String> blocks);
    }

    public interface OnPeerDiscoveredListener {
        void onPeerDiscovered(String peerId, String address, List<String> irohAddrs);
    }

    /** События локального WebSocket-узла для вывода на экран (диагностика цепочки HELLO). */
    public interface OnLogListener {
        void onLog(String message);
    }

    /** Снимок локально обнаруженного пира для UI. */
    public static final class PeerInfo {
        public final String endpointId;
        public final String address;
        public final String deviceId;
        public final boolean connected;
        public final boolean selfConnection;

        PeerInfo(String endpointId, String address, String deviceId, boolean connected,
                 boolean selfConnection) {
            this.endpointId = endpointId == null ? "" : endpointId;
            this.address = address == null ? "" : address;
            this.deviceId = deviceId == null ? "" : deviceId;
            this.connected = connected;
            this.selfConnection = selfConnection;
        }
    }

    private static P2PNode instance;

    private WebSocketServer server;
    private final Set<WebSocketClient> clients = Collections.newSetFromMap(new ConcurrentHashMap<WebSocketClient, Boolean>());
    /** Все открытые WebSocket-соединения: входящие (сервер) и исходящие (клиент). */
    private final Set<org.java_websocket.WebSocket> connections =
            Collections.newSetFromMap(new ConcurrentHashMap<org.java_websocket.WebSocket, Boolean>());
    /** Соединения, которым мы ещё НЕ отправили HELLO (selfIrohId был пуст в момент onOpen). */
    private final Set<org.java_websocket.WebSocket> pendingHello =
            Collections.newSetFromMap(new ConcurrentHashMap<org.java_websocket.WebSocket, Boolean>());
    /** Соединения, которым мы уже отправили свой HELLO (защита от HELLO-ping-pong). */
    private final Set<org.java_websocket.WebSocket> helloSent =
            Collections.newSetFromMap(new ConcurrentHashMap<org.java_websocket.WebSocket, Boolean>());
    /** Планировщик повторной отправки HELLO (гарантия доставки приветствия). */
    private final ScheduledExecutorService helloRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "p2p-hello-retry");
                    t.setDaemon(true);
                    return t;
                }
            });
    private volatile boolean helloRetryScheduled = false;
    /** Сколько адресов проверяем одновременно при скане локальной сети. */
    private static final int SCAN_PARALLELISM = 64;
    /** Таймаут TCP-проверки одного адреса при локальном сканировании. */
    private static final int SCAN_CONNECT_TIMEOUT_MS = 3000;
    /** Защита от параллельных сканов (иначе накапливаются потоки и сокеты). */
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private OnBlockReceivedListener blockListener;
    private OnPeerCountChangedListener peerCountListener;
    private OnChainReceivedListener chainListener;
    private OnPeerDiscoveredListener peerDiscoveredListener;
    private volatile OnLogListener logListener;
    private BlockStorage blockStorage;
    private volatile Context appContext;
    private final Map<String, PeerInfo> discoveredPeers = new ConcurrentHashMap<>();
    private int connectedPeers = 0;
    /** Наш Iroh Endpoint ID для обмена по локальному WebSocket-handshake. */
    private volatile String selfIrohId = "";
    /** Наши прямые Iroh-адреса (IP:port) для HELLO и fallback-подключения. */
    private volatile List<String> selfIrohAddrs = Collections.emptyList();
    /** Наш device_id из Android Keystore: передаётся в HELLO для анти-клон проверки. */
    private volatile String selfDeviceId = "";
    /** Аппаратная защита нашего ключа (StrongBox): передаётся в HELLO. */
    private volatile boolean selfStrongBoxBacked = false;
    private P2PNode() {
    }

    public void setSelfStrongBoxBacked(boolean strongBoxBacked) {
        boolean changed = strongBoxBacked != this.selfStrongBoxBacked;
        this.selfStrongBoxBacked = strongBoxBacked;
        if (changed && !selfDeviceId.isEmpty()) {
            broadcastHello(true);
        }
    }

    public boolean isSelfStrongBoxBacked() {
        return selfStrongBoxBacked;
    }

    public void setSelfDeviceId(String deviceId) {
        String value = deviceId == null ? "" : deviceId.trim();
        boolean changed = !value.isEmpty() && !value.equals(this.selfDeviceId);
        this.selfDeviceId = value;
        if (value.isEmpty()) {
            uiLog("Self device ID is empty (keystore unavailable)");
            return;
        }
        uiLog("Self device ID set: " + DeviceIdentity.shortId(value));
        if (changed) {
            broadcastHello(true);
        }
    }

    public String getSelfDeviceId() {
        return selfDeviceId;
    }

    public void setSelfIrohId(String endpointId) {
        String value = endpointId == null ? "" : endpointId.trim();
        boolean changed = !value.isEmpty() && !value.equals(this.selfIrohId);
        this.selfIrohId = value;
        if (value.isEmpty()) {
            uiLog("Self Iroh ID is empty (endpoint not started yet)");
            return;
        }
        uiLog("Self Iroh ID set: " + value + (changed ? "" : " (unchanged)"));
        // Пиры, подключившиеся до старта Iroh, получили HELLO без Endpoint ID.
        // Повторно рассылаем HELLO, чтобы они могли подключиться через Iroh.
        broadcastHello(true);
    }

    public String getSelfIrohId() {
        return selfIrohId;
    }

    public void setSelfIrohAddrs(List<String> addresses) {
        List<String> value = new ArrayList<>();
        if (addresses != null) {
            for (String raw : addresses) {
                String address = raw == null ? "" : raw.trim();
                if (!address.isEmpty() && address.contains(":") && !value.contains(address)) {
                    value.add(address);
                }
            }
        }
        boolean changed = !value.equals(this.selfIrohAddrs);
        this.selfIrohAddrs = Collections.unmodifiableList(value);
        uiLog("Self Iroh addrs set: " + value);
        if (changed) {
            broadcastHello(true);
        }
    }

    public List<String> getSelfIrohAddrs() {
        return new ArrayList<>(selfIrohAddrs);
    }

    public static synchronized P2PNode getInstance() {
        if (instance == null) {
            instance = new P2PNode();
        }
        return instance;
    }

    public void setOnBlockReceivedListener(OnBlockReceivedListener listener) {
        this.blockListener = listener;
    }

    public void setBlockStorage(BlockStorage storage) {
        this.blockStorage = storage;
    }

    public void setAppContext(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    public void setOnPeerCountChangedListener(OnPeerCountChangedListener listener) {
        this.peerCountListener = listener;
    }

    public void setOnChainReceivedListener(OnChainReceivedListener listener) {
        this.chainListener = listener;
    }

    public void setOnPeerDiscoveredListener(OnPeerDiscoveredListener listener) {
        this.peerDiscoveredListener = listener;
    }

    public void setOnLogListener(OnLogListener listener) {
        this.logListener = listener;
    }

    /** Пишет событие в Logcat и в UI-канал (ChatFragment). */
    private void uiLog(String message) {
        Log.d(TAG, message);
        OnLogListener l = logListener;
        if (l != null) {
            l.onLog(message);
        }
    }

    private void notifyPeerDiscovered(String peerId, String address, List<String> irohAddrs) {
        if (peerDiscoveredListener != null) {
            peerDiscoveredListener.onPeerDiscovered(peerId, address, irohAddrs);
        }
    }

    private static String hostOf(InetSocketAddress addr) {
        return addr == null || addr.getAddress() == null
                ? "" : addr.getAddress().getHostAddress();
    }

    private void notifyPeerCountChanged() {
        if (peerCountListener != null) {
            peerCountListener.onPeerCountChanged(connectedPeers);
        }
    }

    public int getConnectedPeers() {
        return connectedPeers;
    }

    /** Пиры, найденные локальным сканом или через HELLO, с их IP и статусом. */
    public List<PeerInfo> getDiscoveredPeers() {
        List<PeerInfo> result = new ArrayList<>(discoveredPeers.values());
        Collections.sort(result, (a, b) -> a.address.compareToIgnoreCase(b.address));
        return result;
    }

    /** Только реальные пиры: самоподключения отфильтрованы. */
    public List<PeerInfo> getRealDiscoveredPeers() {
        List<PeerInfo> result = new ArrayList<>();
        for (PeerInfo peer : discoveredPeers.values()) {
            if (!peer.selfConnection) {
                result.add(peer);
            }
        }
        Collections.sort(result, (a, b) -> a.address.compareToIgnoreCase(b.address));
        return result;
    }

    /** Регистрирует пира, найденного через mDNS/Iroh (для диагностики в DebugActivity). */
    public void rememberPeerFromMdns(String endpointId, String address) {
        rememberDiscoveredPeer(address, endpointId, "", null);
    }

    private void rememberDiscoveredPeer(String address, String endpointId, String deviceId,
                                        Boolean connected) {
        if (address == null) {
            return;
        }
        String addr = address.trim();
        if (addr.isEmpty() || "unknown".equals(addr)) {
            return;
        }
        PeerInfo previous = discoveredPeers.get(addr);
        String id = endpointId == null ? "" : endpointId.trim();
        if (id.isEmpty() && previous != null) {
            id = previous.endpointId;
        }
        String dev = deviceId == null ? "" : deviceId.trim();
        if (dev.isEmpty() && previous != null) {
            dev = previous.deviceId;
        }
        boolean self = isSelfDeviceId(dev) || isLocalAddress(addr)
                || (previous != null && previous.selfConnection);
        boolean isConnected = connected != null
                ? connected
                : previous != null && previous.connected;
        discoveredPeers.put(addr, new PeerInfo(id, addr, dev, isConnected, self));
        if (!self && !id.isEmpty() && (previous == null || !id.equals(previous.endpointId))) {
            NetworkFileLogger.log("Peer found: " + id + " at " + addr);
        }
    }

    /** Совпадает ли адрес с одним из локальных IPv4-адресов устройства. */
    private static boolean isLocalAddress(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String target = host.trim();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && target.equals(address.getHostAddress())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check local address " + target, e);
        }
        return false;
    }

    /** Есть ли уже открытое WebSocket-соединение с этим IP. */
    private boolean isHostConnected(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (org.java_websocket.WebSocket conn : connections) {
            if (host.equals(hostOf(conn))) {
                return true;
            }
        }
        return false;
    }

    public void startNode(int port) {
        if (server != null) {
            return;
        }
        server = new WebSocketServer(new InetSocketAddress(port)) {
            @Override
            public void onOpen(org.java_websocket.WebSocket conn, ClientHandshake handshake) {
                Log.d(TAG, "Peer connected: " + conn.getRemoteSocketAddress());
                NetworkFileLogger.log("P2P connect (inbound): "
                        + conn.getRemoteSocketAddress());
                connectedPeers++;
                connections.add(conn);
                String host = hostOf(conn.getRemoteSocketAddress());
                rememberDiscoveredPeer(host, null, "", true);
                // HELLO сразу; если selfIrohId ещё пуст — он уйдёт позже (setSelfIrohId).
                sendHello(conn);
                if (!host.isEmpty()) {
                    uiLog("WebSocket peer connected: " + host
                            + " (ждём HELLO с Iroh Endpoint ID)");
                }
                notifyPeerCountChanged();
            }

            @Override
            public void onClose(org.java_websocket.WebSocket conn, int code, String reason, boolean remote) {
                Log.d(TAG, "Peer disconnected: " + conn.getRemoteSocketAddress());
                NetworkFileLogger.log("P2P disconnect (inbound): "
                        + conn.getRemoteSocketAddress() + ", code=" + code
                        + ", reason=" + reason);
                rememberDiscoveredPeer(hostOf(conn), null, "", false);
                boolean tracked = connections.remove(conn);
                helloSent.remove(conn);
                pendingHello.remove(conn);
                if (tracked && connectedPeers > 0) {
                    connectedPeers--;
                }
                notifyPeerCountChanged();
            }

            @Override
            public void onMessage(org.java_websocket.WebSocket conn, String message) {
                Log.d(TAG, "Received: " + message);
                String host = hostOf(conn.getRemoteSocketAddress());
                handleIncomingMessage(conn, message, host);
            }

            @Override
            public void onError(org.java_websocket.WebSocket conn, Exception ex) {
                Log.e(TAG, "Server error", ex);
                NetworkFileLogger.log("P2P error (inbound): " + ex);
            }

            @Override
            public void onStart() {
                Log.i(TAG, "P2P server started on port " + port);
            }
        };
        try {
            server.start();
            Log.i(TAG, "P2P server start requested on port " + port);
        } catch (Throwable t) {
            Log.e(TAG, "P2P server start failed on port " + port, t);
        }
    }

    /** Отправляет приветствие с нашим Iroh Endpoint ID для локального знакомства. */
    private void sendHello(org.java_websocket.WebSocket conn) {
        sendHello(conn, false);
    }

    /**
     * Отправляет приветствие с нашим Iroh Endpoint ID, чтобы сосед мог
     * подключиться к нам через Iroh.
     *
     * @param force переотправить HELLO, даже если этому пиру он уже уходил
     *              (нужно, когда изменился наш Endpoint ID).
     */
    private void sendHello(org.java_websocket.WebSocket conn, boolean force) {
        if (conn == null) {
            return;
        }
        String irohId = selfIrohId == null ? "" : selfIrohId;
        if (irohId.isEmpty()) {
            // Iroh endpoint ещё не поднят: свой ID нам неизвестен.
            // Сохраняем соединение и отправим HELLO позже — из setSelfIrohId().
            if (pendingHello.add(conn)) {
                uiLog("HELLO deferred to " + remoteOf(conn)
                        + ": self Iroh ID is empty (wait for setSelfIrohId)");
            }
            scheduleHelloRetry();
            return;
        }
        boolean alreadySent = !helloSent.add(conn);
        if (alreadySent && !force) {
            // Свой HELLO этому пиру уже отправляли: повторный ответ вызвал бы
            // бесконечный обмен HELLO между двумя телефонами.
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "HELLO");
            obj.put("irohId", irohId);
            List<String> addrs = selfIrohAddrs;
            if (addrs != null && !addrs.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String address : addrs) {
                    arr.put(address);
                }
                obj.put("irohAddrs", arr);
            }
            String dev = selfDeviceId == null ? "" : selfDeviceId;
            if (!dev.isEmpty()) {
                obj.put("deviceId", dev);
            }
            obj.put("strongBox", selfStrongBoxBacked);
            conn.send(obj.toString());
            pendingHello.remove(conn);
            uiLog("HELLO sent to " + hostOf(conn) + " (irohId=" + irohId + ")");
        } catch (Exception e) {
            Log.e(TAG, "sendHello failed", e);
            helloSent.remove(conn);
            // Соединение могло закрыться в момент отправки — вернём его в очередь,
            // повторная попытка будет по таймеру.
            pendingHello.add(conn);
            uiLog("HELLO send failed to " + remoteOf(conn) + ": " + e);
            scheduleHelloRetry();
        }
    }

    /**
     * Рассылает HELLO всем текущим WebSocket-соединениям.
     * Вызывается из setSelfIrohId(): пиры, подключившиеся раньше, получают
     * наш Iroh Endpoint ID и могут подключиться через Iroh.
     *
     * @param force переотправить HELLO всем, даже если он уже уходил
     *              (наш Endpoint ID изменился — соседу нужен актуальный).
     */
    private void broadcastHello(boolean force) {
        int n = connections.size();
        if (n == 0) {
            return;
        }
        uiLog("HELLO broadcast to " + n + " connections (force=" + force + ")");
        for (org.java_websocket.WebSocket conn : connections) {
            sendHello(conn, force);
        }
    }

    /**
     * Повторяет отправку HELLO соединениям, которые ждут наш Iroh Endpoint ID
     * (страховка от «ID не пришёл» и от гонки с закрытием сокета).
     */
    private synchronized void scheduleHelloRetry() {
        if (helloRetryScheduled || pendingHello.isEmpty()) {
            return;
        }
        helloRetryScheduled = true;
        try {
            helloRetryExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (P2PNode.this) {
                        helloRetryScheduled = false;
                    }
                    if (pendingHello.isEmpty()) {
                        return;
                    }
                    String irohId = selfIrohId == null ? "" : selfIrohId;
                    if (irohId.isEmpty()) {
                        // Endpoint ID всё ещё неизвестен — ждём setSelfIrohId().
                        return;
                    }
                    uiLog("HELLO retry for " + pendingHello.size() + " pending connection(s)");
                    for (org.java_websocket.WebSocket conn : pendingHello) {
                        sendHello(conn, false);
                    }
                    if (!pendingHello.isEmpty()) {
                        scheduleHelloRetry();
                    }
                }
            }, HELLO_RETRY_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            helloRetryScheduled = false;
            Log.e(TAG, "Failed to schedule HELLO retry", e);
        }
    }

    /** IP-адрес удалённой стороны (без порта) для логов. */
    private static String hostOf(org.java_websocket.WebSocket conn) {
        try {
            return hostOf(conn.getRemoteSocketAddress());
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Человекочитаемый адрес удалённой стороны для логов. */
    private static String remoteOf(org.java_websocket.WebSocket conn) {
        try {
            InetSocketAddress addr = conn.getRemoteSocketAddress();
            return addr == null ? "unknown" : addr.getAddress().getHostAddress() + ":" + addr.getPort();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Сколько соединений ждут нашего HELLO (диагностика). */
    public int getPendingHelloCount() {
        return pendingHello.size();
    }

    public void broadcastBlock(String blockData) {
        if (server != null) {
            for (org.java_websocket.WebSocket conn : server.getConnections()) {
                conn.send(blockData);
            }
        }
        for (WebSocketClient client : clients) {
            try {
                client.send(blockData);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send to client", e);
            }
        }
    }

    public void broadcastChain(List<String> blocks) {
        JSONArray arr = new JSONArray();
        for (String b : blocks) {
            arr.put(b);
        }
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", "CHAIN");
            obj.put("blocks", arr);
        } catch (Exception ignored) {
        }
        broadcastBlock(obj.toString());
    }

    public void connectToPeer(final String ip, final int port) {
        try {
            String uriStr = "ws://" + ip + ":" + port;
            final WebSocketClient client = new WebSocketClient(new URI(uriStr)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WebSocket connected: " + uriStr);
                    NetworkFileLogger.log("P2P connect (outbound): " + uriStr);
                    clients.add(this);
                    connections.add(this);
                    connectedPeers++;
                    rememberDiscoveredPeer(ip, null, "", true);
                    uiLog("WebSocket connected to peer: " + uriStr);
                    // HELLO сразу; если selfIrohId ещё пуст — он уйдёт позже (setSelfIrohId).
                    sendHello(this);
                    notifyPeerCountChanged();
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Peer message: " + message);
                    handleIncomingMessage(this, message, ip);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "Disconnected from peer: " + uriStr);
                    NetworkFileLogger.log("P2P disconnect (outbound): " + uriStr
                            + ", code=" + code + ", reason=" + reason);
                    clients.remove(this);
                    boolean tracked = connections.remove(this);
                    helloSent.remove(this);
                    pendingHello.remove(this);
                    rememberDiscoveredPeer(ip, null, "", false);
                    if (tracked && connectedPeers > 0) {
                        connectedPeers--;
                    }
                    notifyPeerCountChanged();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Client error", ex);
                    NetworkFileLogger.log("P2P error (outbound) " + uriStr + ": " + ex);
                }
            };
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to peer", e);
        }
    }

    /** Останавливает WebSocket-сервер и закрывает все локальные соединения. */
    public synchronized void stopNode() {
        WebSocketServer current = server;
        server = null;
        if (current != null) {
            try {
                current.stop(1000);
                Log.i(TAG, "P2P: local node stopped");
            } catch (Throwable t) {
                Log.w(TAG, "P2P: local node stop failed: " + t);
            }
        }
        for (WebSocketClient client : clients) {
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
        for (org.java_websocket.WebSocket conn : connections) {
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        }
        clients.clear();
        connections.clear();
        pendingHello.clear();
        helloSent.clear();
        connectedPeers = 0;
        notifyPeerCountChanged();
    }

    /** Перезапускает локальный P2P-узел после смены сети (Wi-Fi ↔ 4G). */
    public void onNetworkChanged() {
        Log.i(TAG, "P2P: network changed, restarting local discovery");
        stopNode();
        try {
            startNode(8080);
        } catch (Throwable t) {
            Log.w(TAG, "P2P: local node restart failed: " + t);
        }
        scanLocalNetwork();
    }

    public void scanLocalNetwork() {
        if (!scanInProgress.compareAndSet(false, true)) {
            // Скан уже идёт — новый не запускаем, иначе копятся потоки и сокеты.
            Log.d("TrigerP2P", "Scan already in progress, skip");
            return;
        }
        Thread driver = new Thread(new Runnable() {
            @Override
            public void run() {
                final ExecutorService pool = Executors.newFixedThreadPool(SCAN_PARALLELISM,
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "p2p-scan");
                                t.setDaemon(true);
                                return t;
                            }
                        });
                final CountDownLatch startGate = new CountDownLatch(1);
                final List<Future<?>> tasks = new ArrayList<>();
                final AtomicBoolean firstRealPeer = new AtomicBoolean(false);
                final AtomicInteger found = new AtomicInteger();
                final long startedAt = System.currentTimeMillis();
                try {
                    String prefix = getLocalSubnetPrefix();
                    Log.i(TAG, "P2P scan: subnet " + prefix + "1-254 port 8080, threads="
                            + SCAN_PARALLELISM + ", timeout=" + SCAN_CONNECT_TIMEOUT_MS + "ms");
                    Log.d("TIMING", "P2P scan start: subnet=" + prefix);
                    TimingLogger.log("P2P scan start: subnet=" + prefix);
                    for (int i = 1; i <= 254; i++) {
                        final String ip = prefix + i;
                        tasks.add(pool.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    startGate.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                // try-with-resources: сокет закрывается на любой ветке.
                                try (Socket socket = new Socket()) {
                                    socket.connect(new InetSocketAddress(ip, 8080),
                                            SCAN_CONNECT_TIMEOUT_MS);
                                    if (!socket.isConnected()) {
                                        return;
                                    }
                                    found.incrementAndGet();
                                    boolean self = isLocalAddress(ip);
                                    String message = "P2P scan: host up at " + ip + ":8080"
                                            + (self ? " (self, skipped)" : "");
                                    Log.i(TAG, message);
                                    NetworkFileLogger.log(message);
                                    if (self) {
                                        return;
                                    }
                                    if (isHostConnected(ip)) {
                                        Log.d(TAG, "P2P scan: already connected to " + ip + ":8080");
                                        return;
                                    }
                                    rememberDiscoveredPeer(ip, null, "", null);
                                    Log.i(TAG, "P2P scan: peer found at " + ip
                                            + ":8080, connecting");
                                    NetworkFileLogger.log("Peer found: " + ip + ":8080");
                                    if (firstRealPeer.compareAndSet(false, true)) {
                                        Log.d("TIMING", "P2P scan: first peer " + ip
                                                + ":8080 in "
                                                + (System.currentTimeMillis() - startedAt) + "ms");
                                        TimingLogger.log("P2P scan first peer: " + ip + ":8080");
                                    }
                                    // HELLO уйдёт сразу из WebSocketClient.onOpen().
                                    connectToPeer(ip, 8080);
                                } catch (Exception e) {
                                    Log.d(TAG, "P2P scan: " + ip + ":8080 closed/unreachable");
                                }
                            }
                        }));
                    }
                    // Все 254 задачи поставлены в пул — только теперь стартуем пробы.
                    startGate.countDown();
                    for (Future<?> task : tasks) {
                        try {
                            task.get();
                        } catch (Exception ignored) {
                        }
                    }
                    Log.i(TAG, "P2P scan complete: found=" + found.get()
                            + ", prefix=" + prefix);
                    Log.d("TIMING", "P2P scan end: found=" + found.get()
                            + ", duration=" + (System.currentTimeMillis() - startedAt) + "ms");
                    TimingLogger.log("P2P scan end: found=" + found.get());
                } finally {
                    startGate.countDown();
                    pool.shutdownNow();
                    scanInProgress.set(false);
                }
            }
        }, "p2p-scan-driver");
        driver.setDaemon(true);
        driver.start();
    }

    private String getLocalSubnetPrefix() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address)
                            || addr.isLoopbackAddress()
                            || addr.isLinkLocalAddress()
                            || !addr.isSiteLocalAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    int lastDot = host == null ? -1 : host.lastIndexOf('.');
                    if (lastDot > 0) {
                        String prefix = host.substring(0, lastDot + 1);
                        Log.i(TAG, "P2P scan: local address " + host
                                + " on " + nif.getName() + ", prefix=" + prefix);
                        return prefix;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "P2P scan: failed to detect local subnet", e);
        }
        Log.w(TAG, "P2P scan: no private IPv4 address found, fallback 192.168.1.");
        return "192.168.1.";
    }

    private boolean isSelfDeviceId(String peerDeviceId) {
        String dev = peerDeviceId == null ? "" : peerDeviceId.trim();
        return !dev.isEmpty() && !selfDeviceId.isEmpty() && dev.equals(selfDeviceId);
    }

    /** Игнорирует соединение с собственным device_id, не считая его активным пиром. */
    private void ignoreSelfConnection(org.java_websocket.WebSocket conn) {
        uiLog("Self-connection ignored (own device_id)");
        // Не держим самоподключение в списке реальных пиров DebugActivity.
        String selfHost = hostOf(conn);
        if (!selfHost.isEmpty()) {
            discoveredPeers.remove(selfHost);
        }
        boolean tracked = connections.remove(conn);
        helloSent.remove(conn);
        pendingHello.remove(conn);
        if (conn instanceof WebSocketClient) {
            clients.remove((WebSocketClient) conn);
        }
        if (tracked && connectedPeers > 0) {
            connectedPeers--;
        }
        notifyPeerCountChanged();
        try {
            conn.close(1000, "self-connection");
        } catch (Throwable ignored) {
        }
    }

    /**
     * Проверяет device_id из HELLO. Совпадение с локальным обрабатывается
     * отдельно как self-connection; дубли между другими пирами логируются.
     */
    private void checkPeerDeviceId(String peerId, String peerDeviceId, boolean strongBox) {
        String dev = peerDeviceId == null ? "" : peerDeviceId.trim();
        DeviceIdentity.registerPeerStrongBox(peerId, dev, strongBox);
        if (dev.isEmpty()) {
            return;
        }
        if (isSelfDeviceId(dev)) {
            uiLog("Self-connection ignored (own device_id)");
            return;
        }
        List<String> conflicts = DeviceIdentity.registerPeerDeviceId(peerId, dev);
        String shortId = DeviceIdentity.shortId(dev);
        if (!conflicts.isEmpty()) {
            uiLog("SUSPICIOUS duplicate device_id " + shortId + " on " + peerId
                    + " and " + conflicts + " (not blocked)");
        } else {
            uiLog("Peer " + peerId + " device_id=" + shortId);
        }
    }

    private void handleIncomingMessage(org.java_websocket.WebSocket conn, String message, String remoteHost) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (message.equals("REQUEST_CHAIN")) {
            if (blockStorage != null) {
                int last = blockStorage.getLastBlockIndex();
                int start = Math.max(0, last - 4);
                List<String> chain = new ArrayList<>();
                for (int i = start; i <= last; i++) {
                    String b = blockStorage.getStoredBlock(i);
                    if (b != null) {
                        chain.add(b);
                    }
                }
                if (!chain.isEmpty()) {
                    broadcastChain(chain);
                    Log.d(TAG, "Sent last " + chain.size() + " blocks in response to REQUEST_CHAIN");
                }
            }
            return;
        }
        try {
            JSONObject obj = new JSONObject(message);
            String type = obj.optString("type", "");
            if ("HELLO".equals(type)) {
                String peerDeviceId = obj.optString("deviceId", "");
                if (isSelfDeviceId(peerDeviceId)) {
                    ignoreSelfConnection(conn);
                    return;
                }
                String dev = peerDeviceId == null ? "" : peerDeviceId.trim();
                String realPeer = "Real peer found: " + remoteHost
                        + ", device_id=" + (dev.isEmpty() ? "unknown" : dev);
                uiLog(realPeer);
                NetworkFileLogger.log(realPeer);
                String irohId = obj.optString("irohId", "");
                if (!irohId.isEmpty()) {
                    List<String> irohAddrs = new ArrayList<>();
                    JSONArray arr = obj.optJSONArray("irohAddrs");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String address = arr.optString(i, "").trim();
                            if (!address.isEmpty() && address.contains(":")
                                    && !irohAddrs.contains(address)) {
                                irohAddrs.add(address);
                            }
                        }
                    }
                    uiLog("HELLO received from " + irohId + " addr=" + remoteHost
                            + " irohAddrs=" + irohAddrs);
                    rememberDiscoveredPeer(remoteHost, irohId, dev, true);
                    checkPeerDeviceId(irohId, dev,
                            obj.optBoolean("strongBox", false));
                    // Отвечаем своим HELLO: сосед должен узнать наш Endpoint ID,
                    // даже если он подключился раньше, чем поднялся Iroh.
                    sendHello(conn);
                    notifyPeerDiscovered(irohId, remoteHost, irohAddrs);
                } else {
                    uiLog("HELLO received from " + remoteHost + " without Iroh ID — ignored");
                    rememberDiscoveredPeer(remoteHost, "", dev, true);
                    // Сосед ещё не знает наш ID — шлём HELLO ещё раз (принудительно).
                    sendHello(conn, true);
                }
                return;
            }
            if ("CHAIN".equals(type)) {
                JSONArray arr = obj.optJSONArray("blocks");
                if (arr != null && chainListener != null) {
                    List<String> chain = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String b = arr.optString(i, null);
                        if (b != null) {
                            chain.add(b);
                        }
                    }
                    chainListener.onChainReceived(chain);
                }
                return;
            }
            String data = obj.optString("data", "");
            String validator = obj.optString("validator", "");
            if (!obj.has("hash")) {
                return;
            }
            int index = obj.optInt("index", -1);
            if (blockStorage != null && index >= 0 && blockStorage.hasBlock(index)) {
                Log.d(TAG, "Block already exists at index " + index + ", skipping");
                return;
            }
            if (data.isEmpty()) {
                return;
            }
            SignerService.recordBlockReceived(appContext);
            if (blockListener != null) {
                blockListener.onBlockReceived(message, validator);
            }
        } catch (Exception e) {
            // не JSON — считаем как есть (PoC)
            if (blockListener != null) {
                blockListener.onBlockReceived(message, "");
            }
        }
    }
}
