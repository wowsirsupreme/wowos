package com.wowsir.wowos;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from the GitHub release. Every build is signed with the same key, so an
 * in-place update installs cleanly. We can't install silently (that needs device-owner
 * privileges), so the download is automatic and Android shows a one-tap confirm.
 */
final class Updater {

    private Updater() { }

    private static final String VERSION_URL = "https://github.com/wowsirsupreme/wowos/releases/latest/download/version.json";
    private static final String APK_URL = "https://github.com/wowsirsupreme/wowos/releases/latest/download/WOWOS.apk";

    interface Check { void result(boolean updateAvailable, int code, String name); }

    static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Throwable t) { return 0; }
    }

    static void check(final Context c, final Check cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final int cur = currentCode(c);
                int code = 0; String name = null;
                try {
                    String json = httpGet(VERSION_URL);
                    if (json != null) {
                        JSONObject o = new JSONObject(json);
                        code = o.optInt("versionCode", 0);
                        name = o.optString("versionName", "");
                    }
                } catch (Throwable ignored) { }
                final int fCode = code; final String fName = name;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { cb.result(fCode > cur && fCode > 0, fCode, fName); }
                });
            }
        }, "wowos-update-check").start();
    }

    static void downloadAndInstall(final Context c, final Runnable onFail) {
        new Thread(new Runnable() {
            @Override public void run() {
                File dir = c.getExternalCacheDir();
                if (dir == null) dir = c.getExternalFilesDir(null);
                final boolean ok;
                final File out;
                if (dir == null) { ok = false; out = null; }
                else { out = new File(dir, "wowos-update.apk"); ok = httpDownload(APK_URL, out); }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (ok && out != null) install(c, out); else onFail.run();
                    }
                });
            }
        }, "wowos-update-dl").start();
    }

    private static void install(Context c, File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(FileShare.uriFor(c, apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = open(urlStr);
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) { return null; }
        finally { if (c != null) try { c.disconnect(); } catch (Throwable ignored) { } }
    }

    private static boolean httpDownload(String urlStr, File out) {
        HttpURLConnection c = null;
        try {
            c = open(urlStr);
            InputStream in = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush(); fos.close(); in.close();
            return out.length() > 0;
        } catch (Throwable t) { return false; }
        finally { if (c != null) try { c.disconnect(); } catch (Throwable ignored) { } }
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "WOWOS-Launcher");
        return c;
    }
}
