package com.triger.poc;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        void onPeerDiscovered(String peerId, String address);
    }

    /** События локального WebSocket-узла для вывода на экран (диагностика цепочки HELLO). */
    public interface OnLogListener {
        void onLog(String message);
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
    private static final int SCAN_PARALLELISM = 32;
    /** Защита от параллельных сканов (иначе накапливаются потоки и сокеты). */
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    /** Пул потоков для проб адресов локальной сети. */
    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(SCAN_PARALLELISM,
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "p2p-scan");
                    t.setDaemon(true);
                    return t;
                }
            });
    private OnBlockReceivedListener blockListener;
    private OnPeerCountChangedListener peerCountListener;
    private OnChainReceivedListener chainListener;
    private OnPeerDiscoveredListener peerDiscoveredListener;
    private volatile OnLogListener logListener;
    private BlockStorage blockStorage;
    private int connectedPeers = 0;
    /** Наш Iroh Endpoint ID для обмена по локальному WebSocket-handshake. */
    private volatile String selfIrohId = "";
    /** Наш device_id из Android Keystore: передаётся в HELLO для анти-клон проверки. */
    private volatile String selfDeviceId = "";
    private P2PNode() {
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

    /** Пишет событие в Logcat и в UI-канал (NetworkFragment / ChatFragment). */
    private void uiLog(String message) {
        Log.d(TAG, message);
        OnLogListener l = logListener;
        if (l != null) {
            l.onLog(message);
        }
    }

    private void notifyPeerDiscovered(String peerId, String address) {
        if (peerDiscoveredListener != null) {
            peerDiscoveredListener.onPeerDiscovered(peerId, address);
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

    public void startNode(int port) {
        if (server != null) {
            return;
        }
        server = new WebSocketServer(new InetSocketAddress(port)) {
            @Override
            public void onOpen(org.java_websocket.WebSocket conn, ClientHandshake handshake) {
                Log.d(TAG, "Peer connected: " + conn.getRemoteSocketAddress());
                connectedPeers++;
                connections.add(conn);
                String host = hostOf(conn.getRemoteSocketAddress());
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
                connections.remove(conn);
                helloSent.remove(conn);
                pendingHello.remove(conn);
                if (connectedPeers > 0) {
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
            }

            @Override
            public void onStart() {
                Log.d(TAG, "P2P server started on port " + port);
            }
        };
        server.start();
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
            String dev = selfDeviceId == null ? "" : selfDeviceId;
            if (!dev.isEmpty()) {
                obj.put("deviceId", dev);
            }
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
            uiLog("HELLO broadcast to 0 connections (no peers connected)");
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
                    clients.add(this);
                    connections.add(this);
                    connectedPeers++;
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
                    clients.remove(this);
                    connections.remove(this);
                    helloSent.remove(this);
                    pendingHello.remove(this);
                    if (connectedPeers > 0) {
                        connectedPeers--;
                    }
                    notifyPeerCountChanged();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Client error", ex);
                }
            };
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to peer", e);
        }
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
                try {
                    String prefix = getLocalSubnetPrefix();
                    Log.d("TrigerP2P", "Scanning subnet: " + prefix + "x");
                    final AtomicInteger found = new AtomicInteger();
                    final AtomicInteger connected = new AtomicInteger();
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int i = 1; i <= 254; i++) {
                        final String ip = prefix + i;
                        tasks.add(scanExecutor.submit(new Runnable() {
                            @Override
                            public void run() {
                                // try-with-resources: раньше сокет на неудачном connect
                                // не закрывался — дескрипторы утекали до зависания.
                                try (Socket socket = new Socket()) {
                                    socket.connect(new InetSocketAddress(ip, 8080), 300);
                                    if (socket.isConnected()) {
                                        found.incrementAndGet();
                                        connectToPeer(ip, 8080);
                                        connected.incrementAndGet();
                                        Log.d("TrigerP2P", "Found peer: " + ip + " port 8080 open, connecting");
                                    }
                                } catch (Exception e) {
                                    // порт закрыт / хост недоступен — пропускаем
                                }
                            }
                        }));
                    }
                    for (Future<?> task : tasks) {
                        try {
                            task.get();
                        } catch (Exception ignored) {
                        }
                    }
                    Log.d("TrigerP2P", "Scan complete. Found: " + found.get()
                            + ", Connected: " + connected.get());
                } finally {
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
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String host = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && host != null && host.indexOf(':') < 0 && host.startsWith("192.168.")) {
                        int lastDot = host.lastIndexOf('.');
                        if (lastDot > 0) {
                            return host.substring(0, lastDot + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to detect local subnet", e);
        }
        return "192.168.1.";
    }

    /**
     * Проверяет device_id из HELLO. Совпадение с локальным или с другим пиром
     * помечает узлы подозрительными, но соединение не блокируется.
     */
    private void checkPeerDeviceId(String peerId, String peerDeviceId) {
        String dev = peerDeviceId == null ? "" : peerDeviceId.trim();
        if (dev.isEmpty()) {
            return;
        }
        boolean sameAsLocal = !selfDeviceId.isEmpty() && dev.equals(selfDeviceId);
        List<String> conflicts = DeviceIdentity.registerPeerDeviceId(peerId, dev);
        String shortId = DeviceIdentity.shortId(dev);
        if (sameAsLocal) {
            DeviceIdentity.markSuspicious(peerId);
            uiLog("SUSPICIOUS peer " + peerId + " has the same device_id as this installation"
                    + " (possible clone, not blocked)");
        } else if (!conflicts.isEmpty()) {
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
                    String b = blockStorage.getBlock(i);
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
                String irohId = obj.optString("irohId", "");
                if (!irohId.isEmpty()) {
                    uiLog("HELLO received from " + irohId + " addr=" + remoteHost);
                    checkPeerDeviceId(irohId, obj.optString("deviceId", ""));
                    // Отвечаем своим HELLO: сосед должен узнать наш Endpoint ID,
                    // даже если он подключился раньше, чем поднялся Iroh.
                    sendHello(conn);
                    notifyPeerDiscovered(irohId, remoteHost);
                } else {
                    uiLog("HELLO received from " + remoteHost + " without Iroh ID — ignored");
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
