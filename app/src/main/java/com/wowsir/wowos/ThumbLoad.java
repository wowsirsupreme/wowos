package com.wowsir.wowos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Builds thumbnail previews for local files (image downsample, video first-frame),
 * off the main thread, with a memory cache keyed by path+mtime. View-recycle safe via
 * the ImageView tag. Non-previewable files keep their type icon (set by the adapter).
 */
final class ThumbLoad {

    private ThumbLoad() { }

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
            };
    private static final Executor EXE = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Load a preview into iv for image/video files; leaves the type icon otherwise. */
    static void into(final ImageView iv, final File f, final boolean image, final boolean video,
                     final int reqW, final int reqH) {
        if (f == null || (!image && !video)) return;
        final String key = f.getAbsolutePath() + "|" + f.lastModified();
        iv.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) { show(iv, cached); return; }
        EXE.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = make(f, image, reqW, reqH);
                if (b != null) CACHE.put(key, b);
                UI.post(new Runnable() {
                    @Override public void run() { if (b != null && key.equals(iv.getTag())) show(iv, b); }
                });
            }
        });
    }

    private static void show(ImageView iv, Bitmap b) {
        iv.setPadding(0, 0, 0, 0);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
    }

    private static Bitmap make(File f, boolean image, int reqW, int reqH) {
        try {
            if (image) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int sample = 1;
                while (reqW > 0 && (o.outWidth / sample > reqW * 1.3 || o.outHeight / sample > reqH * 1.3)) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            }
            // video first frame
            return ThumbnailUtils.createVideoThumbnail(f.getAbsolutePath(), MediaStore.Images.Thumbnails.MINI_KIND);
        } catch (Throwable t) {
            return null;
        }
    }
}
