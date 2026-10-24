package com.wowsir.wowos;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/** Plays alarm and reminder sounds. TV boxes often lack ringtones, so it falls back to a tone. */
final class AlarmSound {

    private static final long MAX_RING_MS = 10 * 60_000L;

    private static MediaPlayer player;
    private static ToneGenerator tone;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Runnable onTimeout;

    private AlarmSound() { }

    /** Ring until {@link #stop()} (or 10 minutes pass, then {@code timeout} runs). */
    static synchronized void ring(Context c, Runnable timeout) {
        stop();
        onTimeout = timeout;
        if (!play(c, true)) toneLoop();
        ui.postDelayed(timeoutRunnable, MAX_RING_MS);
    }

    /** A short sound for reminders and banners. */
    static synchronized void chime(Context c) {
        stop();
        if (!play(c, false)) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
            } catch (Exception ignored) { }
        }
    }

    static synchronized void stop() {
        ui.removeCallbacks(toneTick);
        ui.removeCallbacks(timeoutRunnable);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    private static final Runnable timeoutRunnable = new Runnable() {
        @Override public void run() {
            stop();
            if (onTimeout != null) onTimeout.run();
        }
    };

    private static boolean play(Context c, boolean loop) {
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(c,
                loop ? RingtoneManager.TYPE_ALARM : RingtoneManager.TYPE_NOTIFICATION);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri == null) return false;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            // On many TV boxes the alarm volume is 0; fall back to the media stream so it is heard.
            boolean alarmMuted = am != null && am.getStreamVolume(AudioManager.STREAM_ALARM) == 0;
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(alarmMuted || !loop ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(c, uri);
            mp.setLooping(loop);
            mp.prepare();
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final Runnable toneTick = new Runnable() {
        @Override public void run() {
            if (tone == null) return;
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
            ui.postDelayed(this, 1800);
        }
    };

    private static void toneLoop() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            ui.post(toneTick);
        } catch (Exception ignored) { }
    }
}
