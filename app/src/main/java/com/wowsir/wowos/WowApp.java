package com.wowsir.wowos;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

/** Process-wide setup: keeps alarms scheduled and watches the network for alerts. */
public class WowApp extends Application {

    private static final long QUIET_AFTER_START_MS = 8000;
    private final long started = SystemClock.elapsedRealtime();
    private Boolean online;

    @Override
    public void onCreate() {
        super.onCreate();
        Plans.reschedule(this);
        watchNetwork();
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    changed(true, cm, n);
                }

                @Override
                public void onLost(Network n) {
                    changed(false, cm, n);
                }
            });
        } catch (Exception ignored) { }
    }

    private void changed(boolean nowOnline, ConnectivityManager cm, Network n) {
        Boolean before = online;
        online = nowOnline;
        // Stay quiet on start-up and when nothing actually changed.
        if (before == null || before == nowOnline) return;
        if (SystemClock.elapsedRealtime() - started < QUIET_AFTER_START_MS) return;
        if (!Alerts.on(this, Alerts.K_NETWORK)) return;
        if (nowOnline) {
            String kind = "Wi-Fi";
            try {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) kind = "Ethernet";
            } catch (Exception ignored) { }
            Alerts.info(this, R.drawable.ic_wifi, "Back online", "Connected over " + kind, 4000);
        } else {
            Alerts.info(this, R.drawable.ic_wifi, "Network lost", "Streaming apps will pause until it's back", 6000);
        }
    }
}
