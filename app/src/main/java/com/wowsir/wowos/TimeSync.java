package com.wowsir.wowos;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Keeps WOW OS's idea of the time correct even when the TV's own clock is wrong.
 *
 * Many of these TV mainboards have no battery-backed clock, so the time resets on
 * every power cut and the greeting ends up wrong. This fetches the real time from
 * the internet (NTP, with an HTTPS fallback) and:
 *   - always exposes the correct time to WOW OS via {@link #nowMillis()}, and
 *   - when WOW OS runs as a system app, also fixes the whole TV's clock.
 *
 * Everything runs off the main thread and never throws into the caller.
 */
final class TimeSync {

    private static final String[] NTP_SERVERS = {
            "time.google.com", "time.cloudflare.com", "pool.ntp.org", "time.windows.com"};
    private static final long NTP_EPOCH_DIFF = 2208988800L; // 1900 -> 1970, seconds
    private static final long SET_CLOCK_THRESHOLD_MS = 10_000;
    private static final long RESYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 hours

    /** offsetMillis = trueTime - System.currentTimeMillis(), measured at last sync. */
    private static volatile long offsetMillis = 0;
    private static volatile boolean haveOffset = false;
    private static volatile long lastSyncElapsed = 0;
    private static volatile boolean syncing = false;

    private TimeSync() { }

    /** The best known wall-clock time, corrected for a wrong device clock. */
    static long nowMillis() {
        return System.currentTimeMillis() + (haveOffset ? offsetMillis : 0);
    }

    static boolean isCorrected() {
        return haveOffset;
    }

    /** Kicks off a background sync if one isn't running and the last was a while ago. */
    static void syncSoon(final Context context) {
        if (syncing) return;
        if (haveOffset && SystemClock.elapsedRealtime() - lastSyncElapsed < RESYNC_INTERVAL_MS) return;
        syncing = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    syncNow(app);
                } finally {
                    syncing = false;
                }
            }
        }, "wow-timesync").start();
    }

    /** Fetches the true time and, if possible, fixes the system clock. Blocking. */
    private static void syncNow(Context context) {
        Long trueTime = fetchNtp();
        if (trueTime == null) trueTime = fetchHttp();
        if (trueTime == null) return;

        offsetMillis = trueTime - System.currentTimeMillis();
        haveOffset = true;
        lastSyncElapsed = SystemClock.elapsedRealtime();

        if (Math.abs(offsetMillis) > SET_CLOCK_THRESHOLD_MS) {
            correctDeviceClock(context, trueTime);
        }
    }

    // ------------------------------------------------------------------ fetching

    private static Long fetchNtp() {
        for (String host : NTP_SERVERS) {
            Long t = queryNtp(host);
            if (t != null) return t;
        }
        return null;
    }

    /** Minimal SNTP client (RFC 4330) over UDP 123. */
    private static Long queryNtp(String host) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(4000);
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // leap=0, version=3, mode=3 (client)
            InetAddress addr = InetAddress.getByName(host);
            long originTicks = SystemClock.elapsedRealtime();
            socket.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            long roundTrip = SystemClock.elapsedRealtime() - originTicks;

            long seconds = readUnsigned32(buf, 40);   // transmit timestamp, seconds
            long fraction = readUnsigned32(buf, 44);   // transmit timestamp, fraction
            if (seconds == 0) return null;
            long ms = (seconds - NTP_EPOCH_DIFF) * 1000L + (fraction * 1000L >>> 32);
            // Add half the round trip so we land near "now", not when the server sent it.
            return ms + roundTrip / 2;
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static long readUnsigned32(byte[] b, int i) {
        return ((long) (b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    /** Fallback: read the Date header from an HTTPS response (works when UDP 123 is blocked). */
    private static Long fetchHttp() {
        String[] urls = {"https://www.google.com/generate_204", "https://cloudflare.com/cdn-cgi/trace"};
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.connect();
                long date = c.getHeaderFieldDate("Date", 0);
                if (date > 0) return date;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ fixing the device clock

    /** Only succeeds on a system install (SET_TIME / WRITE_SECURE_SETTINGS granted). */
    private static void correctDeviceClock(Context context, long trueTime) {
        // Prefer the OS keeping itself in sync from now on.
        ContentResolver cr = context.getContentResolver();
        try {
            Settings.Global.putInt(cr, Settings.Global.AUTO_TIME, 1);
            Settings.Global.putInt(cr, Settings.Global.AUTO_TIME_ZONE, 1);
        } catch (Throwable ignored) { }
        try {
            // Point the OS NTP client at a server we know works here.
            Settings.Global.putString(cr, "ntp_server", "time.google.com");
        } catch (Throwable ignored) { }

        // Set the clock directly as well, so the fix is immediate.
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.setTime(trueTime);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ debug

    static String describe() {
        if (!haveOffset) return "not synced yet";
        long off = offsetMillis;
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        String sign = off >= 0 ? "+" : "-";
        long s = Math.abs(off) / 1000;
        return "now " + f.format(new Date(nowMillis())) + " (clock was off by "
                + sign + s + "s)";
    }
}
