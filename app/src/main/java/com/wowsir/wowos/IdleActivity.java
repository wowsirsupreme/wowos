package com.wowsir.wowos;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen idle screen. Shows one of:
 *   - the built-in crows-and-eyes animation (default),
 *   - a slideshow of the user's own photos, or
 *   - the user's own looping video.
 * Any key or touch dismisses it. Images are downsampled to the screen so a big
 * photo can't exhaust the TV's small heap.
 */
public class IdleActivity extends Activity {

    private static final long SLIDE_MS = 8000;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<String> photos;
    private int photoIndex;
    private ImageView imageA, imageB;
    private boolean showingA = true;
    private VideoView video;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LOW_PROFILE);

        String mode = Greeting.idleMode(this);
        if (Greeting.MODE_PHOTOS.equals(mode) && hasReadablePhotos()) {
            setContentView(buildPhotos());
        } else if (Greeting.MODE_VIDEO.equals(mode) && readable(Greeting.idleVideo(this))) {
            setContentView(buildVideo());
        } else {
            setContentView(new IdleView(this));   // default animation, also the fallback
        }
    }

    // ------------------------------------------------------------------ photos

    private boolean hasReadablePhotos() {
        photos = new ArrayList<>();
        for (String p : Greeting.idlePhotos(this)) if (readable(p)) photos.add(p);
        return !photos.isEmpty();
    }

    private View buildPhotos() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        imageA = fullImage();
        imageB = fullImage();
        imageB.setAlpha(0f);
        root.addView(imageA);
        root.addView(imageB);
        root.addView(clockOverlay());

        photoIndex = 0;
        setImage(imageA, photos.get(0));
        if (photos.size() > 1) ui.postDelayed(slide, SLIDE_MS);
        return root;
    }

    private ImageView fullImage() {
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private final Runnable slide = new Runnable() {
        @Override public void run() {
            photoIndex = (photoIndex + 1) % photos.size();
            final ImageView next = showingA ? imageB : imageA;
            final ImageView cur = showingA ? imageA : imageB;
            setImage(next, photos.get(photoIndex));
            next.animate().alpha(1f).setDuration(900).start();
            cur.animate().alpha(0f).setDuration(900).start();
            showingA = !showingA;
            ui.postDelayed(this, SLIDE_MS);
        }
    };

    private void setImage(ImageView iv, String path) {
        Bitmap bmp = decodeDownsampled(path, screenW(), screenH());
        if (bmp != null) iv.setImageBitmap(bmp);
    }

    /** Decode a file no larger than the screen, so a huge photo can't OOM the TV. */
    private static Bitmap decodeDownsampled(String path, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample > reqW * 1.2 || o.outHeight / sample > reqH * 1.2) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ video

    private View buildVideo() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        video.setLayoutParams(lp);
        video.setVideoURI(Uri.fromFile(new File(Greeting.idleVideo(this))));
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                mp.setLooping(true);
                mp.setVolume(0f, 0f);   // silent wallpaper
                video.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                finish();   // bad file: just drop back to the home screen
                return true;
            }
        });
        root.addView(video);
        return root;
    }

    // ------------------------------------------------------------------ clock overlay

    private View clockOverlay() {
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xF2FFFFFF);
        clock.setTextSize(44);
        clock.setShadowLayer(12f, 0, 2f, 0xCC000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(40), dp(28));
        clock.setLayoutParams(lp);
        return clock;
    }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return getResources().getDisplayMetrics().heightPixels; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean readable(String path) {
        if (path == null || path.isEmpty()) return false;
        File f = new File(path);
        return f.exists() && f.canRead();
    }

    // ------------------------------------------------------------------ dismiss

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(slide);
        if (video != null) try { video.stopPlayback(); } catch (Exception ignored) { }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        finish();
        return true;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        finish();
    }
}
