package com.wowsir.wowos;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Alarms and planner items: storage, next-occurrence maths and AlarmManager scheduling. */
final class Plans {

    static final int KIND_ALARM = 0;
    static final int KIND_REMINDER = 1;

    static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    static final int EVERY_DAY = 127;
    static final int WEEKDAYS = 31;
    static final int WEEKENDS = 96;

    static final String EXTRA_AT = "at";

    static final class Plan {
        long id;
        String title = "";
        int hour;
        int minute;
        /** Bit 0 = Monday … bit 6 = Sunday. 0 means "once". */
        int days;
        /** For "once" items: the exact time it fires. */
        long onceAt;
        int kind = KIND_ALARM;
        boolean enabled = true;
        /** Snoozes are temporary and removed after they ring. */
        boolean snooze;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("title", title).put("h", hour).put("m", minute).put("days", days)
                    .put("once", onceAt).put("kind", kind).put("on", enabled).put("snooze", snooze);
            return o;
        }

        static Plan fromJson(JSONObject o) {
            Plan p = new Plan();
            p.id = o.optLong("id");
            p.title = o.optString("title");
            p.hour = o.optInt("h");
            p.minute = o.optInt("m");
            p.days = o.optInt("days");
            p.onceAt = o.optLong("once");
            p.kind = o.optInt("kind");
            p.enabled = o.optBoolean("on", true);
            p.snooze = o.optBoolean("snooze");
            return p;
        }

        String displayTitle() {
            if (title != null && !title.trim().isEmpty()) return title.trim();
            return kind == KIND_ALARM ? "Alarm" : "Reminder";
        }
    }

    private Plans() { }

    // ------------------------------------------------------------------ storage

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("plans", Context.MODE_PRIVATE);
    }

    static List<Plan> load(Context c) {
        List<Plan> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Plan.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        Collections.sort(out, new Comparator<Plan>() {
            @Override public int compare(Plan a, Plan b) {
                return (a.hour * 60 + a.minute) - (b.hour * 60 + b.minute);
            }
        });
        return out;
    }

    static void save(Context c, List<Plan> plans) {
        JSONArray a = new JSONArray();
        try {
            for (Plan p : plans) a.put(p.toJson());
        } catch (Exception ignored) { }
        sp(c).edit().putString("items", a.toString()).apply();
        reschedule(c);
    }

    static void upsert(Context c, Plan plan) {
        List<Plan> all = load(c);
        if (plan.id == 0) plan.id = System.currentTimeMillis();
        if (plan.days == 0 && !plan.snooze) plan.onceAt = nextAt(plan.hour, plan.minute, System.currentTimeMillis());
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == plan.id) { all.set(i, plan); replaced = true; break; }
        }
        if (!replaced) all.add(plan);
        save(c, all);
    }

    static void delete(Context c, long id) {
        List<Plan> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == id) all.remove(i);
        save(c, all);
    }

    static void setEnabled(Context c, long id, boolean on) {
        List<Plan> all = load(c);
        for (Plan p : all) {
            if (p.id != id) continue;
            p.enabled = on;
            if (on && p.days == 0) p.onceAt = nextAt(p.hour, p.minute, System.currentTimeMillis());
        }
        save(c, all);
    }

    // ------------------------------------------------------------------ time maths

    static int dayIndex(Calendar cal) {
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /** Next time today or tomorrow at hour:minute, strictly after now. */
    static long nextAt(int hour, int minute, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    /** When this plan next fires after {@code now}, or -1 if it won't. */
    static long nextTrigger(Plan p, long now) {
        if (!p.enabled) return -1;
        if (p.days == 0) return p.onceAt > now ? p.onceAt : -1;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, p.hour);
        cal.set(Calendar.MINUTE, p.minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if ((p.days & (1 << dayIndex(cal))) != 0 && cal.getTimeInMillis() > now) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return -1;
    }

    /** The plan that fires soonest, or null. */
    static Plan nextUp(Context c, long now) {
        Plan best = null;
        long bestAt = Long.MAX_VALUE;
        for (Plan p : load(c)) {
            long at = nextTrigger(p, now);
            if (at > 0 && at < bestAt) { bestAt = at; best = p; }
        }
        return best;
    }

    // ------------------------------------------------------------------ scheduling

    static void reschedule(Context c) {
        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        for (Plan p : load(c)) {
            long at = nextTrigger(p, now);
            if (at > 0 && at < soonest) soonest = at;
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent fire = firePending(c, soonest == Long.MAX_VALUE ? 0 : soonest);
        if (soonest == Long.MAX_VALUE) {
            am.cancel(fire);
            return;
        }
        PendingIntent show = PendingIntent.getActivity(c, 1, new Intent(c, PlannerActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(soonest, show), fire);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, soonest, fire);
        }
    }

    private static PendingIntent firePending(Context c, long at) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_FIRE).putExtra(EXTRA_AT, at);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Collects the plans due at {@code at}, retires one-off items and snoozes,
     * and schedules the next alarm. Returns the due plans.
     */
    static List<Plan> takeDue(Context c, long at) {
        List<Plan> all = load(c);
        List<Plan> due = new ArrayList<>();
        long window = 90_000;
        for (Plan p : all) {
            long t = nextTrigger(p, at - window);
            if (t > 0 && t <= at + 30_000) due.add(p);
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Plan p = all.get(i);
            if (!due.contains(p)) continue;
            if (p.snooze) all.remove(i);
            else if (p.days == 0) p.enabled = false;
        }
        save(c, all);
        return due;
    }

    static void snooze(Context c, Plan from, int minutes) {
        Plan s = new Plan();
        s.title = from.displayTitle();
        s.kind = from.kind;
        s.snooze = true;
        s.onceAt = System.currentTimeMillis() + minutes * 60_000L;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(s.onceAt);
        s.hour = cal.get(Calendar.HOUR_OF_DAY);
        s.minute = cal.get(Calendar.MINUTE);
        upsert(c, s);
    }

    /** A throwaway alarm a few seconds from now, for testing alerts. */
    static void testIn(Context c, int seconds) {
        Plan s = new Plan();
        s.title = "Test alarm";
        s.kind = KIND_ALARM;
        s.snooze = true;
        s.onceAt = System.currentTimeMillis() + seconds * 1000L;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(s.onceAt);
        s.hour = cal.get(Calendar.HOUR_OF_DAY);
        s.minute = cal.get(Calendar.MINUTE);
        upsert(c, s);
    }

    // ------------------------------------------------------------------ text

    static String timeText(Context c, int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        return DateFormat.getTimeFormat(c).format(cal.getTime());
    }

    static String repeatText(Plan p) {
        if (p.snooze) return "Snoozed";
        switch (p.days) {
            case 0: return "Once";
            case EVERY_DAY: return "Every day";
            case WEEKDAYS: return "Weekdays";
            case WEEKENDS: return "Weekends";
            default:
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 7; i++) {
                    if ((p.days & (1 << i)) == 0) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(DAY_NAMES[i]);
                }
                return sb.toString();
        }
    }

    /** "in 2 h 15 min", "in 5 min", "now". */
    static String untilText(long at, long now) {
        long mins = Math.max(0, (at - now + 59_999) / 60_000);
        if (mins == 0) return "now";
        long h = mins / 60;
        long m = mins % 60;
        if (h >= 24) return "in " + (h / 24) + " d " + (h % 24) + " h";
        if (h > 0) return "in " + h + " h" + (m > 0 ? " " + m + " min" : "");
        return "in " + m + " min";
    }
}
