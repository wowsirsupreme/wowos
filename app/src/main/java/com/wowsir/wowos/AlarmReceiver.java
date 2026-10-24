package com.wowsir.wowos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** Fires alarms/reminders and keeps the schedule alive across reboots and clock changes. */
public class AlarmReceiver extends BroadcastReceiver {

    static final String ACTION_FIRE = "com.wowsir.wowos.FIRE";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_FIRE.equals(action)) {
            // Boot, time change, time zone change, app update.
            Plans.reschedule(c);
            return;
        }
        long at = intent.getLongExtra(Plans.EXTRA_AT, System.currentTimeMillis());
        List<Plans.Plan> due = Plans.takeDue(c, at);
        if (due.isEmpty()) return;

        boolean anyAlarm = false;
        for (Plans.Plan p : due) if (p.kind == Plans.KIND_ALARM) anyAlarm = true;
        String style = anyAlarm ? Alerts.alarmStyle(c) : Alerts.reminderStyle(c);
        boolean banner = Alerts.STYLE_BANNER.equals(style) && Alerts.canOverlay(c);

        if (!banner) {
            RingActivity.start(c, due);
        } else if (anyAlarm) {
            Alerts.alarm(c, due);
        } else {
            Plans.Plan first = due.get(0);
            Alerts.info(c, R.drawable.ic_bell, Alerts.joinTitles(due),
                    "Reminder · " + Plans.timeText(c, first.hour, first.minute), 20_000);
        }
    }
}
