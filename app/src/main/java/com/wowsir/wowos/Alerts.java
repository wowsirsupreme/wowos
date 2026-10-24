package com.wowsir.wowos;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Glass alerts that appear over any app, plus the user's alert settings.
 * Without the "display over other apps" permission, info alerts fall back to a toast
 * and alarms fall back to the full-screen ring screen.
 */
final class Alerts {

    static final String STYLE_FULL = "full";
    static final String STYLE_BANNER = "banner";

    static final String K_ALARM_STYLE = "alarm_style";
    static final String K_REMINDER_STYLE = "reminder_style";
    static final String K_USB = "conn_usb";
    static final String K_STORAGE = "conn_storage";
    static final String K_NETWORK = "conn_network";
    static final String K_SOUND = "banner_sound";

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static View banner;
    private static View alarmView;
    private static final Runnable hideBanner = new Runnable() {
        @Override public void run() { removeBanner(true); }
    };

    private Alerts() { }

    // ------------------------------------------------------------------ settings

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("alerts", Context.MODE_PRIVATE);
    }

    static String alarmStyle(Context c) { return prefs(c).getString(K_ALARM_STYLE, STYLE_FULL); }
    static String reminderStyle(Context c) { return prefs(c).getString(K_REMINDER_STYLE, STYLE_BANNER); }
    static boolean on(Context c, String key) { return prefs(c).getBoolean(key, true); }

    static boolean canOverlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    // ------------------------------------------------------------------ info banner

    /** A banner in the top-right corner that disappears on its own. Safe to call from any thread. */
    static void info(final Context ctx, final int icon, final String title, final String body, final long showMs) {
        final Context c = ctx.getApplicationContext();
        ui.post(new Runnable() {
            @Override public void run() {
                if (!canOverlay(c)) {
                    Toast.makeText(c, title + (body == null ? "" : "\n" + body), Toast.LENGTH_LONG).show();
                    return;
                }
                removeBanner(false);
                View v = LayoutInflater.from(c).inflate(R.layout.alert_banner, null);
                ((ImageView) v.findViewById(R.id.icon)).setImageResource(icon);
                ((TextView) v.findViewById(R.id.title)).setText(title);
                TextView b = v.findViewById(R.id.body);
                if (body == null || body.isEmpty()) b.setVisibility(View.GONE); else b.setText(body);
                if (!add(c, v, false)) {
                    Toast.makeText(c, title, Toast.LENGTH_LONG).show();
                    return;
                }
                banner = v;
                v.setAlpha(0f);
                v.setTranslationX(dp(c, 48));
                v.animate().alpha(1f).translationX(0f).setDuration(260).start();
                if (on(c, K_SOUND) && alarmView == null) AlarmSound.chime(c);
                ui.removeCallbacks(hideBanner);
                ui.postDelayed(hideBanner, showMs);
            }
        });
    }

    private static void removeBanner(boolean animate) {
        final View v = banner;
        banner = null;
        if (v == null) return;
        final WindowManager wm = (WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE);
        if (!animate) {
            try { wm.removeView(v); } catch (Exception ignored) { }
            return;
        }
        v.animate().alpha(0f).translationX(dp(v.getContext(), 48)).setDuration(220).withEndAction(new Runnable() {
            @Override public void run() {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }
        }).start();
    }

    // ------------------------------------------------------------------ alarm banner

    /** A focusable banner with Dismiss / Snooze that rings until answered. */
    static void alarm(final Context ctx, final List<Plans.Plan> due) {
        final Context c = ctx.getApplicationContext();
        ui.post(new Runnable() {
            @Override public void run() {
                dismissAlarm(c);
                final Plans.Plan first = due.get(0);
                FrameLayout root = new FrameLayout(c) {
                    @Override public boolean dispatchKeyEvent(KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                            dismissAlarm(c);
                            return true;
                        }
                        return super.dispatchKeyEvent(e);
                    }
                };
                LayoutInflater.from(c).inflate(R.layout.alert_alarm, root, true);
                ((TextView) root.findViewById(R.id.title)).setText(joinTitles(due));
                ((TextView) root.findViewById(R.id.body)).setText(
                        Plans.timeText(c, first.hour, first.minute) + " · " + (first.kind == Plans.KIND_ALARM ? "Alarm" : "Reminder"));
                View dismiss = root.findViewById(R.id.btn_dismiss);
                View snooze = root.findViewById(R.id.btn_snooze);
                dismiss.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { dismissAlarm(c); }
                });
                snooze.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Plans.snooze(c, first, 10);
                        dismissAlarm(c);
                        info(c, R.drawable.ic_bell, "Snoozed for 10 minutes", first.displayTitle(), 4000);
                    }
                });
                dismiss.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
                snooze.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);

                if (!add(c, root, true)) {
                    RingActivity.start(c, due);
                    return;
                }
                alarmView = root;
                root.setAlpha(0f);
                root.animate().alpha(1f).setDuration(250).start();
                dismiss.requestFocus();
                AlarmSound.ring(c, new Runnable() {
                    @Override public void run() { dismissAlarm(c); }
                });
            }
        });
    }

    static void dismissAlarm(Context c) {
        AlarmSound.stop();
        View v = alarmView;
        alarmView = null;
        if (v == null) return;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    static String joinTitles(List<Plans.Plan> due) {
        StringBuilder sb = new StringBuilder();
        for (Plans.Plan p : due) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.displayTitle());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ window helpers

    @SuppressWarnings("deprecation")
    private static boolean add(Context c, View v, boolean focusable) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return false;
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!focusable) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(c, 36);
        lp.y = dp(c, 30);
        try {
            wm.addView(v, lp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
