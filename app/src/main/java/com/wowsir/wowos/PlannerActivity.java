package com.wowsir.wowos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Life Planner: alarms and the plan for your day. */
public class PlannerActivity extends Activity {

    static final String EXTRA_ADD = "add";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LayoutInflater inflater;
    private ListView list;
    private TextView empty;
    private TextView nextTitle;
    private TextView nextMeta;
    private LinearLayout today;
    private final PlanAdapter adapter = new PlanAdapter();

    static void open(Context c, String add) {
        Intent i = new Intent(c, PlannerActivity.class);
        if (add != null) i.putExtra(EXTRA_ADD, add);
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_planner);
        inflater = LayoutInflater.from(this);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        nextTitle = findViewById(R.id.next_title);
        nextMeta = findViewById(R.id.next_meta);
        today = findViewById(R.id.today);

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                edit(adapter.getItem(pos), Plans.KIND_ALARM);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                options(adapter.getItem(pos));
                return true;
            }
        });

        bind(R.id.btn_add_alarm, new Runnable() { @Override public void run() { edit(null, Plans.KIND_ALARM); } });
        bind(R.id.btn_add_plan, new Runnable() { @Override public void run() { edit(null, Plans.KIND_REMINDER); } });
        bind(R.id.btn_alert_settings, new Runnable() { @Override public void run() {
            startActivity(new Intent(PlannerActivity.this, AlertSettingsActivity.class));
        } });

        handleAdd(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAdd(intent);
    }

    private void handleAdd(Intent i) {
        String add = i.getStringExtra(EXTRA_ADD);
        if (add == null) return;
        i.removeExtra(EXTRA_ADD);
        edit(null, "reminder".equals(add) ? Plans.KIND_REMINDER : Plans.KIND_ALARM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.postDelayed(tick, 30_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 30_000);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus()) {
            Object o = list.getSelectedItem();
            if (o instanceof Plans.Plan) { options((Plans.Plan) o); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void bind(int id, final Runnable r) {
        View v = findViewById(id);
        v.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { r.run(); }
        });
    }

    // ------------------------------------------------------------------ display

    private void refresh() {
        List<Plans.Plan> all = Plans.load(this);
        adapter.set(all);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);

        long now = System.currentTimeMillis();
        Plans.Plan next = Plans.nextUp(this, now);
        if (next == null) {
            nextTitle.setText("Nothing scheduled");
            nextMeta.setText("Add an alarm or a plan");
        } else {
            long at = Plans.nextTrigger(next, now);
            nextTitle.setText(next.displayTitle());
            nextMeta.setText(dayWord(at) + " " + Plans.timeText(this, next.hour, next.minute) + " · " + Plans.untilText(at, now));
        }
        renderToday(all, now);
    }

    /** Today's timeline: what's done and what's still coming. */
    private void renderToday(List<Plans.Plan> all, long now) {
        today.removeAllViews();
        Calendar cal = Calendar.getInstance();
        int todayIdx = Plans.dayIndex(cal);
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int count = 0;
        for (Plans.Plan p : all) {
            boolean isToday;
            if (p.days == 0) {
                Calendar at = Calendar.getInstance();
                at.setTimeInMillis(p.onceAt);
                isToday = at.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
                        && at.get(Calendar.YEAR) == cal.get(Calendar.YEAR);
            } else {
                isToday = p.enabled && (p.days & (1 << todayIdx)) != 0;
            }
            if (!isToday) continue;
            boolean past = p.hour * 60 + p.minute <= nowMin;
            TextView t = new TextView(this);
            t.setText(Plans.timeText(this, p.hour, p.minute) + "   " + p.displayTitle());
            t.setTextSize(14);
            t.setTextColor(getColor(past ? R.color.text_faint : R.color.text));
            t.setPadding(0, dp(7), 0, dp(7));
            if (past) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            today.addView(t);
            count++;
        }
        if (count == 0) {
            TextView t = new TextView(this);
            t.setText("A free day.");
            t.setTextColor(getColor(R.color.text_faint));
            t.setTextSize(14);
            today.addView(t);
        }
    }

    private String dayWord(long at) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(at);
        Calendar n = Calendar.getInstance();
        int diff = a.get(Calendar.DAY_OF_YEAR) - n.get(Calendar.DAY_OF_YEAR)
                + (a.get(Calendar.YEAR) - n.get(Calendar.YEAR)) * 365;
        if (diff == 0) return "Today";
        if (diff == 1) return "Tomorrow";
        return DateFormat.format("EEE", a).toString();
    }

    // ------------------------------------------------------------------ editing

    private void options(final Plans.Plan p) {
        String[] items = {"Edit", p.enabled ? "Turn off" : "Turn on", "Delete"};
        showGlass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(p.displayTitle())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) edit(p, p.kind);
                        else if (which == 1) { Plans.setEnabled(PlannerActivity.this, p.id, !p.enabled); refresh(); }
                        else { Plans.delete(PlannerActivity.this, p.id); refresh(); }
                    }
                }).create());
    }

    private void edit(final Plans.Plan existing, int defaultKind) {
        final Plans.Plan p = existing != null ? existing : new Plans.Plan();
        if (existing == null) {
            p.kind = defaultKind;
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MINUTE, 30);
            p.hour = c.get(Calendar.HOUR_OF_DAY);
            p.minute = c.get(Calendar.MINUTE) / 5 * 5;
        }
        final int[] days = {p.days};

        View v = inflater.inflate(R.layout.dialog_plan, null);
        final EditText title = v.findViewById(R.id.title);
        final TimePicker time = v.findViewById(R.id.time);
        final Button repeat = v.findViewById(R.id.repeat);
        final RadioGroup kind = v.findViewById(R.id.kind);

        title.setText(p.title);
        time.setIs24HourView(DateFormat.is24HourFormat(this));
        time.setHour(p.hour);
        time.setMinute(p.minute);
        kind.check(p.kind == Plans.KIND_ALARM ? R.id.kind_alarm : R.id.kind_reminder);
        repeat.setText("Repeat: " + Plans.repeatText(p));
        repeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) { pickDays(days, repeat); }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(existing == null ? (p.kind == Plans.KIND_ALARM ? "New alarm" : "New plan") : "Edit")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        p.title = title.getText().toString().trim();
                        p.hour = time.getHour();
                        p.minute = time.getMinute();
                        p.days = days[0];
                        p.kind = kind.getCheckedRadioButtonId() == R.id.kind_reminder ? Plans.KIND_REMINDER : Plans.KIND_ALARM;
                        p.enabled = true;
                        p.snooze = false;
                        Plans.upsert(PlannerActivity.this, p);
                        refresh();
                        long now = System.currentTimeMillis();
                        long at = Plans.nextTrigger(p, now);
                        if (at > 0) {
                            Alerts.info(PlannerActivity.this, p.kind == Plans.KIND_ALARM ? R.drawable.ic_alarm : R.drawable.ic_calendar,
                                    p.displayTitle() + " saved", dayWord(at) + " at " + Plans.timeText(PlannerActivity.this, p.hour, p.minute)
                                            + " · " + Plans.untilText(at, now), 3500);
                        }
                    }
                });
        if (existing != null) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Plans.delete(PlannerActivity.this, p.id);
                    refresh();
                }
            });
        }
        showGlass(b.create());
        if (existing == null) title.requestFocus();
    }

    private void pickDays(final int[] days, final Button repeat) {
        final boolean[] checked = new boolean[7];
        for (int i = 0; i < 7; i++) checked[i] = (days[0] & (1 << i)) != 0;
        String[] names = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
        showGlass(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Repeat on (none = once)")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setNeutralButton("Weekdays", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { setDays(days, Plans.WEEKDAYS, repeat); }
                })
                .setNegativeButton("Every day", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { setDays(days, Plans.EVERY_DAY, repeat); }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int mask = 0;
                        for (int i = 0; i < 7; i++) if (checked[i]) mask |= 1 << i;
                        setDays(days, mask, repeat);
                    }
                }).create());
    }

    private void setDays(int[] days, int mask, Button repeat) {
        days[0] = mask;
        Plans.Plan tmp = new Plans.Plan();
        tmp.days = mask;
        repeat.setText("Repeat: " + Plans.repeatText(tmp));
    }

    private void showGlass(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class PlanAdapter extends BaseAdapter {
        private final List<Plans.Plan> items = new ArrayList<>();

        void set(List<Plans.Plan> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Plans.Plan getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.item_plan, parent, false);
            Plans.Plan p = items.get(pos);
            ((ImageView) v.findViewById(R.id.icon)).setImageResource(p.kind == Plans.KIND_ALARM ? R.drawable.ic_alarm : R.drawable.ic_calendar);
            ((TextView) v.findViewById(R.id.time)).setText(Plans.timeText(PlannerActivity.this, p.hour, p.minute));
            ((TextView) v.findViewById(R.id.name)).setText(p.displayTitle());
            ((TextView) v.findViewById(R.id.meta)).setText(Plans.repeatText(p) + " · " + (p.kind == Plans.KIND_ALARM ? "Alarm" : "Reminder"));
            ((ImageView) v.findViewById(R.id.state_dot)).setImageResource(p.enabled ? R.drawable.dot_on : R.drawable.dot_off);
            ((TextView) v.findViewById(R.id.state)).setText(p.enabled ? "On" : "Off");
            v.setAlpha(p.enabled ? 1f : 0.55f);
            return v;
        }
    }
}
