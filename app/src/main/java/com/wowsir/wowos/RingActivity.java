package com.wowsir.wowos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;

/** Full-screen alarm / reminder. */
public class RingActivity extends Activity {

    private static final String X_TITLE = "title";
    private static final String X_ALARM = "alarm";
    private static final String X_H = "h";
    private static final String X_M = "m";
    private static final String X_KIND = "kind";
    private static final long REMINDER_SHOW_MS = 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    static void start(Context c, List<Plans.Plan> due) {
        boolean anyAlarm = false;
        for (Plans.Plan p : due) if (p.kind == Plans.KIND_ALARM) anyAlarm = true;
        Plans.Plan first = due.get(0);
        Intent i = new Intent(c, RingActivity.class)
                .putExtra(X_TITLE, Alerts.joinTitles(due))
                .putExtra(X_ALARM, anyAlarm)
                .putExtra(X_H, first.hour)
                .putExtra(X_M, first.minute)
                .putExtra(X_KIND, first.kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        c.startActivity(i);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_ring);

        final Intent in = getIntent();
        final boolean alarm = in.getBooleanExtra(X_ALARM, true);
        final String title = in.getStringExtra(X_TITLE);
        ((TextView) findViewById(R.id.title)).setText(title);
        ((TextView) findViewById(R.id.body)).setText(
                Plans.timeText(this, in.getIntExtra(X_H, 0), in.getIntExtra(X_M, 0)) + " · " + (alarm ? "Alarm" : "Reminder"));

        View dismiss = findViewById(R.id.btn_dismiss);
        View snooze = findViewById(R.id.btn_snooze);
        dismiss.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        snooze.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        dismiss.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        snooze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Plans.Plan p = new Plans.Plan();
                p.title = title;
                p.kind = in.getIntExtra(X_KIND, Plans.KIND_ALARM);
                Plans.snooze(RingActivity.this, p, 10);
                Alerts.info(RingActivity.this, R.drawable.ic_bell, "Snoozed for 10 minutes", title, 4000);
                finish();
            }
        });
        dismiss.requestFocus();

        if (alarm) {
            AlarmSound.ring(this, new Runnable() {
                @Override public void run() { finish(); }
            });
        } else {
            AlarmSound.chime(this);
            ui.postDelayed(new Runnable() {
                @Override public void run() { finish(); }
            }, REMINDER_SHOW_MS);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        AlarmSound.stop();
    }
}
