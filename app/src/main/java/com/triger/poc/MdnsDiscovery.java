package com.triger.poc;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class MdnsDiscovery {
    private static final String TAG = "IrohNode";
    private static final String SERVICE_TYPE = "_triger._tcp.";
    private static final String ATTR_ENDPOINT_ID = "irohId";

    interface Listener {
        void onPeerFound(String endpointId, String address);
    }

    private final Context context;
    private final String endpointId;
    private final int advertisePort;
    private final Listener listener;
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final Set<String> seenServices =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private volatile String registeredName = "";
    private volatile boolean running = false;
    private boolean resolving = false;

    MdnsDiscovery(Context context, String endpointId, int advertisePort, Listener listener) {
        this.context = context == null ? null : context.getApplicationContext();
        this.endpointId = endpointId == null ? "" : endpointId.trim();
        this.advertisePort = advertisePort > 0 && advertisePort <= 65535 ? advertisePort : 8080;
        this.listener = listener;
    }

    void start() {
        if (running || context == null || endpointId.isEmpty()) {
            Log.w(TAG, "mDNS: discovery not started (invalid state)");
            return;
        }
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.w(TAG, "mDNS: NsdManager unavailable");
            return;
        }
        running = true;
        String startMessage = "mDNS: start serviceType=" + SERVICE_TYPE
                + " endpointId=" + endpointId + " port=" + advertisePort;
        Log.i(TAG, startMessage);
        NetworkFileLogger.log(startMessage);
        acquireMulticastLock();
        advertise();
        discover();
    }

    void stop() {
        running = false;
        synchronized (this) {
            resolveQueue.clear();
            resolving = false;
        }
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
                Log.i(TAG, "mDNS: discovery stopped");
            } catch (Throwable t) {
                Log.w(TAG, "mDNS: stop discovery failed: " + t);
            }
        }
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
                Log.i(TAG, "mDNS: service unregistered");
            } catch (Throwable t) {
                Log.w(TAG, "mDNS: unregister failed: " + t);
            }
        }
        discoveryListener = null;
        registrationListener = null;
        releaseMulticastLock();
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            multicastLock = wm.createMulticastLock("Triger:mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "mDNS: multicast lock failed: " + t);
        }
    }

    private void releaseMulticastLock() {
        WifiManager.MulticastLock lock = multicastLock;
        multicastLock = null;
        if (lock != null) {
            try {
                if (lock.isHeld()) {
                    lock.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "mDNS: multicast unlock failed: " + t);
            }
        }
    }

    private void advertise() {
        try {
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(serviceName());
            serviceInfo.setServiceType(SERVICE_TYPE);
            serviceInfo.setPort(advertisePort);
            serviceInfo.setAttribute(ATTR_ENDPOINT_ID, endpointId);

            registrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo info) {
                    registeredName = info.getServiceName();
                    String message = "mDNS: advertised " + registeredName
                            + " type=" + SERVICE_TYPE + " port=" + info.getPort();
                    Log.i(TAG, message);
                    NetworkFileLogger.log(message);
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "mDNS: advertise failed for " + info.getServiceName()
                            + ", error=" + errorCode);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo info) {
                    Log.i(TAG, "mDNS: service unregistered " + info.getServiceName());
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "mDNS: unregister failed for " + info.getServiceName()
                            + ", error=" + errorCode);
                }
            };
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD,
                    registrationListener);
        } catch (Throwable t) {
            Log.w(TAG, "mDNS: advertise failed: " + t);
        }
    }

    private void discover() {
        try {
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                    Log.i(TAG, "mDNS: discovery started for " + serviceType);
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    String name = info.getServiceName();
                    if (registeredName.equals(name)) {
                        return;
                    }
                    if (!seenServices.add(name)) {
                        return;
                    }
                    String message = "mDNS: service found " + name
                            + " type=" + info.getServiceType();
                    Log.i(TAG, message);
                    NetworkFileLogger.log(message);
                    enqueueResolve(info);
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                    seenServices.remove(info.getServiceName());
                    Log.i(TAG, "mDNS: service lost " + info.getServiceName());
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    Log.i(TAG, "mDNS: discovery stopped for " + serviceType);
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.w(TAG, "mDNS: start discovery failed for " + serviceType
                            + ", error=" + errorCode);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    Log.w(TAG, "mDNS: stop discovery failed for " + serviceType
                            + ", error=" + errorCode);
                }
            };
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener);
        } catch (Throwable t) {
            Log.w(TAG, "mDNS: discovery start failed: " + t);
        }
    }

    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (this) {
            resolveQueue.add(info);
        }
        resolveNext();
    }

    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (this) {
            if (!running || resolving || resolveQueue.isEmpty() || nsdManager == null) {
                return;
            }
            next = resolveQueue.poll();
            resolving = true;
        }
        try {
            nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "mDNS: resolve failed for " + serviceInfo.getServiceName()
                            + ", error=" + errorCode);
                    finishResolve();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    try {
                        String peerId = extractEndpointId(serviceInfo);
                        String host = serviceInfo.getHost() == null
                                ? "unknown"
                                : serviceInfo.getHost().getHostAddress();
                        String address = host.contains(":")
                                ? "[" + host + "]:" + serviceInfo.getPort()
                                : host + ":" + serviceInfo.getPort();
                        if (peerId.isEmpty()) {
                            Log.w(TAG, "mDNS: found service " + serviceInfo.getServiceName()
                                    + " addrs=" + address + " without " + ATTR_ENDPOINT_ID);
                        } else {
                            Log.i(TAG, "mDNS: found peer " + peerId + " addrs=" + address);
                            if (listener != null) {
                                listener.onPeerFound(peerId, address);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "mDNS: resolve handling failed: " + t);
                    } finally {
                        finishResolve();
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "mDNS: resolve start failed: " + t);
            finishResolve();
        }
    }

    private void finishResolve() {
        synchronized (this) {
            resolving = false;
        }
        resolveNext();
    }

    private String extractEndpointId(NsdServiceInfo info) {
        byte[] raw = info.getAttributes().get(ATTR_ENDPOINT_ID);
        if (raw == null) {
            return "";
        }
        return new String(raw, StandardCharsets.UTF_8).trim();
    }

    private String serviceName() {
        String clean = endpointId.replaceAll("[^A-Za-z0-9]", "");
        if (clean.length() > 12) {
            clean = clean.substring(0, 12);
        }
        if (clean.isEmpty()) {
            clean = "peer";
        }
        return "Trigger-" + clean;
    }
}
