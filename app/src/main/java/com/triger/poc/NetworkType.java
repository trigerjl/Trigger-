package com.triger.poc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

final class NetworkType {
    static final String WIFI = "Wi-Fi";
    static final String CELLULAR = "4G";
    static final String ETHERNET = "Ethernet";
    static final String VPN = "VPN";
    static final String OTHER = "other";
    static final String NONE = "none";

    private NetworkType() {
    }

    static String current(Context context) {
        if (context == null) {
            return NONE;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return NONE;
            }
            Network active = cm.getActiveNetwork();
            return forNetwork(cm, active);
        } catch (Throwable t) {
            return NONE;
        }
    }

    static String forNetwork(ConnectivityManager cm, Network network) {
        if (cm == null || network == null) {
            return NONE;
        }
        try {
            return fromCapabilities(cm.getNetworkCapabilities(network));
        } catch (Throwable t) {
            return NONE;
        }
    }

    static String fromCapabilities(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return NONE;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WIFI;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return CELLULAR;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return ETHERNET;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return VPN;
        }
        return OTHER;
    }

    static String displayName(Context context, String type) {
        if (context == null) {
            return type == null ? "" : type;
        }
        if (NONE.equals(type)) {
            return context.getString(R.string.network_none);
        }
        if (OTHER.equals(type)) {
            return context.getString(R.string.network_other);
        }
        if (WIFI.equals(type)) {
            return context.getString(R.string.network_wifi);
        }
        if (CELLULAR.equals(type)) {
            return context.getString(R.string.network_cellular);
        }
        if (ETHERNET.equals(type)) {
            return context.getString(R.string.network_ethernet);
        }
        if (VPN.equals(type)) {
            return context.getString(R.string.network_vpn);
        }
        return type == null ? "" : type;
    }

    static boolean isConnected(String type) {
        return WIFI.equals(type) || CELLULAR.equals(type);
    }
}
