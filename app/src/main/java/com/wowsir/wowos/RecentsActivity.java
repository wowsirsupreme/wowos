package com.wowsir.wowos;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Multitask switcher: shows the apps the TV has running / recently used and jumps
 * back to the exact task. As a system app (REAL_GET_TASKS) it sees every app; on a
 * normal install Android only reports WOW OS's own task, so the list is short.
 */
public class RecentsActivity extends Activity {

    private PackageManager pm;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        buildUi(collect());
    }

    private static final class Task {
        int id; String pkg; String label; Drawable icon; ComponentName component;
    }

    private List<Task> collect() {
        List<Task> out = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return out;
            List<ActivityManager.RecentTaskInfo> recents = am.getRecentTasks(
                    12, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (ActivityManager.RecentTaskInfo info : recents) {
                ComponentName comp = componentOf(info);
                if (comp == null) continue;
                String pkg = comp.getPackageName();
                if (pkg.equals(getPackageName())) continue;            // skip WOW OS itself
                if (isHome(pkg)) continue;                              // skip launchers
                Task t = new Task();
                t.id = info.id;                                        // -1 if no longer running
                t.pkg = pkg;
                t.component = comp;
                try {
                    t.label = pm.getActivityInfo(comp, 0).loadLabel(pm).toString();
                    t.icon = pm.getActivityInfo(comp, 0).loadIcon(pm);
                } catch (Exception e) {
                    try {
                        t.label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                        t.icon = pm.getApplicationIcon(pkg);
                    } catch (Exception ignored) { continue; }
                }
                out.add(t);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    @SuppressWarnings("deprecation")
    private ComponentName componentOf(ActivityManager.RecentTaskInfo info) {
        if (info.baseIntent != null && info.baseIntent.getComponent() != null) {
            return info.baseIntent.getComponent();
        }
        if (info.origActivity != null) return info.origActivity;
        if (info.topActivity != null) return info.topActivity;
        if (info.baseActivity != null) return info.baseActivity;
        return null;
    }

    private boolean isHome(String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            if (ri.activityInfo.packageName.equals(pkg)) return true;
        }
        return false;
    }

    private void buildUi(List<Task> tasks) {
        Ui.page(this, "Running apps", tasks.isEmpty()
                ? "Nothing else is running right now."
                : "OK resumes an app. Hold OK (or Menu) to end it.");
        LinearLayout content = Ui.content(this);
        if (tasks.isEmpty()) {
            Ui.card(this, content, R.drawable.ic_grid, "Open an app from the home screen to multitask.");
            return;
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        content.addView(scroll, lp);

        LayoutInflater inf = LayoutInflater.from(this);
        for (final Task t : tasks) {
            View card = inf.inflate(R.layout.item_quick, row, false);
            ((ImageView) card.findViewById(R.id.icon)).setImageDrawable(t.icon);
            ((TextView) card.findViewById(R.id.label)).setText(t.label);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 150), Ui.dp(this, 110));
            clp.setMarginEnd(Ui.dp(this, 14));
            card.setLayoutParams(clp);
            card.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(t); }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { options(t); return true; }
            });
            row.addView(card);
        }
        row.post(new Runnable() {
            @Override public void run() { if (row.getChildCount() > 0) row.getChildAt(0).requestFocus(); }
        });
    }

    private void open(Task t) {
        // Resume the actual running task when we can; otherwise start the app fresh.
        if (t.id >= 0) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.moveTaskToFront(t.id, 0);
                    finish();
                    return;
                }
            } catch (Throwable ignored) { }
        }
        Intent i = pm.getLeanbackLaunchIntentForPackage(t.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(t.pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); finish(); return; } catch (Throwable ignored) { }
        }
        Toast.makeText(this, "Couldn't reopen " + t.label, Toast.LENGTH_SHORT).show();
    }

    private void options(final Task t) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(t.label)
                .setItems(new String[]{"Resume", "End app"}, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int which) {
                        if (which == 0) open(t); else end(t);
                    }
                }).create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }

    /** End an app: force-stop it (system app), kill background, and drop its task. */
    private void end(Task t) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            try { am.killBackgroundProcesses(t.pkg); } catch (Throwable ignored) { }
            try { ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, t.pkg); } catch (Throwable ignored) { }
            if (t.id >= 0) {
                try { ActivityManager.class.getMethod("removeTask", int.class).invoke(am, t.id); } catch (Throwable ignored) { }
            }
        }
        Toast.makeText(this, "Ended " + t.label, Toast.LENGTH_SHORT).show();
        // Rebuild the list after a moment so the ended app drops off.
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { buildUi(collect()); }
        }, 400);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            // no-op here; long-press handles options
        }
        return super.onKeyDown(keyCode, event);
    }
}
