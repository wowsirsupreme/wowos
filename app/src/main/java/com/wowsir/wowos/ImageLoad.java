package com.wowsir.wowos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Tiny async image loader for remote feed thumbnails: a memory LruCache, a small
 * thread pool, and downsampling to the target tile size so a big image can't exhaust
 * the TV's heap. No external library. Safe for view recycling via the ImageView tag.
 */
final class ImageLoad {

    private ImageLoad() { }

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
            };
    private static final Executor EXE = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    static void into(final ImageView iv, final String url, final int reqW, final int reqH) {
        if (url == null || url.isEmpty()) { iv.setImageDrawable(null); return; }
        iv.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        EXE.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = download(url, reqW, reqH);
                if (b != null) CACHE.put(url, b);
                UI.post(new Runnable() {
                    @Override public void run() { if (b != null && url.equals(iv.getTag())) iv.setImageBitmap(b); }
                });
            }
        });
    }

    private static Bitmap download(String urlStr, int reqW, int reqH) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "WOWOS-Launcher");

            byte[] bytes = readAll(c.getInputStream());
            if (bytes == null) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (reqW > 0 && reqH > 0 && (o.outWidth / sample > reqW * 1.3 || o.outHeight / sample > reqH * 1.3)) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
