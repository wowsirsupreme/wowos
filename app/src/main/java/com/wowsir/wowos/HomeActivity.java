package com.wowsir.wowos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PS5-style home: a row of app tiles along the top; focusing an app fills the
 * screen with its banner art and shows a big title plus an Open button, with
 * Settings and a menu in the top-right. All the device logic (launching,
 * pinning, hiding, inputs, idle, time, status) is unchanged.
 */
public class HomeActivity extends Activity {

    private static final long STATUS_REFRESH_MS = 8000;
    private static final long IDLE_MS = 90_000;
    private static final long HERO_DELAY_MS = 170;

    private PackageManager pm;
    private Resources res;
    private LayoutInflater inflater;
    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout appRow;
    private android.widget.FrameLayout appScroll;
    private ImageView heroA, heroB, heroIcon;
    private boolean heroShowingA = true;
    private TextView heroTitle, heroTag;
    private View btnOpen, btnOptions, btnSettings, btnMenu;

    private ImageView netDot, usbDot;
    private TextView netText, usbText, nextText;
    private View nextChip;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> byPkg = new HashMap<>();
    private AppEntry current;         // focused app, or null when a tool tile is focused
    private Runnable currentAction;   // focused tool's action, or null for an app
    private boolean loading;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        pm = getPackageManager();
        res = getResources();
        inflater = LayoutInflater.from(this);
        prefs = new Prefs(this);

        appRow = findViewById(R.id.app_row);
        appScroll = findViewById(R.id.app_scroll);
        heroA = findViewById(R.id.hero_a);
        heroB = findViewById(R.id.hero_b);
        heroIcon = findViewById(R.id.hero_icon);
        heroTitle = findViewById(R.id.hero_title);
        heroTag = findViewById(R.id.hero_tag);
        btnOpen = findViewById(R.id.btn_open);
        btnOptions = findViewById(R.id.btn_options);
        btnSettings = findViewById(R.id.btn_settings);
        btnMenu = findViewById(R.id.btn_menu);
        netDot = findViewById(R.id.net_dot);
        netText = findViewById(R.id.net_text);
        usbDot = findViewById(R.id.usb_dot);
        usbText = findViewById(R.id.usb_text);
        nextChip = findViewById(R.id.next_chip);
        nextText = findViewById(R.id.next_text);

        btnOpen.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        btnOptions.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        btnSettings.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        btnMenu.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (currentAction != null) currentAction.run();
                else if (current != null) launch(current);
            }
        });
        btnOptions.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (current != null) showAppOptions(current); }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openRecents(); }
        });

        registerReceiver(packageReceiver, packageFilter());
        registerReceiver(statusReceiver, statusFilter());
        registerReceiver(statusReceiver, mediaFilter());

        reloadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimeSync.syncSoon(this);
        refreshStatus();
        ui.postDelayed(statusTick, STATUS_REFRESH_MS);
        scheduleIdle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statusTick);
        ui.removeCallbacks(idleRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusFirstApp();
    }

    @Override
    public void onBackPressed() {
        focusFirstApp();
    }

    private void focusFirstApp() {
        appRow.animate().translationX(0f).setDuration(200).start();
        if (appRow.getChildCount() > 0) appRow.getChildAt(0).requestFocus();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        scheduleIdle();
    }

    private void scheduleIdle() {
        ui.removeCallbacks(idleRunnable);
        if (Greeting.idleOn(this)) ui.postDelayed(idleRunnable, IDLE_MS);
    }

    private final Runnable idleRunnable = new Runnable() {
        @Override public void run() {
            if (!hasWindowFocus()) return;
            try { startActivity(new Intent(HomeActivity.this, IdleActivity.class)); } catch (Exception ignored) { }
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            openRecents();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && current != null) {
            showAppOptions(current);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ apps

    private void reloadApps() {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<AppEntry> list = queryApps();
                ui.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        allApps.clear();
                        allApps.addAll(list);
                        byPkg.clear();
                        for (AppEntry e : list) byPkg.put(e.pkg, e);
                        prefs.seedIfNeeded(byPkg.keySet());
                        renderRow();
                    }
                });
            }
        }, "wowos-apps").start();
    }

    private List<AppEntry> queryApps() {
        Map<String, AppEntry> map = new HashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> found;
            try {
                found = pm.queryIntentActivities(i, 0);
            } catch (Exception ex) {
                continue;
            }
            for (ResolveInfo ri : found) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || map.containsKey(p)) continue;
                CharSequence l = ri.loadLabel(pm);
                map.put(p, new AppEntry(ri.activityInfo, l == null ? p : l.toString(),
                        Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat)));
            }
        }
        List<AppEntry> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    /** Pinned apps first (in pin order), then everyone else alphabetically; hidden excluded. */
    private List<AppEntry> ordered() {
        Set<String> hidden = prefs.hidden();
        List<AppEntry> out = new ArrayList<>();
        for (String p : prefs.favs()) {
            AppEntry e = byPkg.get(p);
            if (e != null && !hidden.contains(p)) out.add(e);
        }
        for (AppEntry e : allApps) {
            if (!hidden.contains(e.pkg) && !out.contains(e)) out.add(e);
        }
        return out;
    }

    private void renderRow() {
        String keepFocusPkg = current != null ? current.pkg : null;
        appRow.removeAllViews();
        for (AppEntry e : ordered()) addAppTile(e);
        addToolTiles();

        View focusTarget = null;
        if (keepFocusPkg != null) {
            for (int i = 0; i < appRow.getChildCount(); i++) {
                Object t = appRow.getChildAt(i).getTag();
                if (t instanceof AppEntry && ((AppEntry) t).pkg.equals(keepFocusPkg)) {
                    focusTarget = appRow.getChildAt(i); break;
                }
            }
        }
        if (focusTarget == null && appRow.getChildCount() > 0) focusTarget = appRow.getChildAt(0);
        if (focusTarget != null) focusTarget.requestFocus();
    }

    private void addAppTile(final AppEntry e) {
        View tile = inflater.inflate(R.layout.item_ps5_tile, appRow, false);
        ImageView icon = tile.findViewById(R.id.icon);
        try { icon.setImageDrawable(e.newIcon(pm, res)); } catch (Throwable ignored) { }
        tile.setFocusable(true);
        tile.setTag(e);
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                zoom(v, has);
                if (has) setApp(e);
            }
        });
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(e); }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showAppOptions(e); return true; }
        });
        addTile(tile);
    }

    /** WOW OS tools shown as real app-style icon tiles (not hidden behind a menu). */
    private void addToolTiles() {
        addToolTile(R.drawable.sysic_files, "Files", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, FilesActivity.class)); } });
        addToolTile(R.drawable.sysic_planner, "Planner", new Runnable() { @Override public void run() {
            PlannerActivity.open(HomeActivity.this, null); } });
        addToolTile(R.drawable.sysic_connections, "Connections", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, ConnectionsActivity.class)); } });
        addToolTile(R.drawable.sysic_mirror, "Screen mirror", new Runnable() { @Override public void run() {
            AppEntry e = byPkg.get("com.wowsir.airwow");
            if (e == null) e = findApp("airwow", "airplay", "airpin", "mirror", "miracast", "eshare", "cast");
            if (e != null) launch(e); else toast("No screen-mirroring app found"); } });
        addToolTile(R.drawable.sysic_alerts, "Alerts", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, AlertSettingsActivity.class)); } });
        addToolTile(R.drawable.sysic_personalise, "Personalise", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, PersonaliseActivity.class)); } });
        addToolTile(R.drawable.sysic_hidden, "Hidden apps", new Runnable() { @Override public void run() { showHiddenManager(); } });
        if (InputSwitch.available(this)) {
            for (final InputSwitch.Input in : InputSwitch.inputs()) {
                int ic = in.label.toLowerCase(Locale.ROOT).contains("tv") ? R.drawable.sysic_tv : R.drawable.sysic_input;
                addToolTile(ic, in.label, new Runnable() { @Override public void run() {
                    String err = InputSwitch.select(HomeActivity.this, in.enumName);
                    if (err != null) toast(err); } });
            }
        }
    }

    private void addToolTile(final int iconRes, final String title, final Runnable run) {
        View tile = inflater.inflate(R.layout.item_ps5_tile, appRow, false);
        ImageView icon = tile.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));   // colored app-style icon fills the tile
        tile.setFocusable(true);
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                zoom(v, has);
                if (has) setAction(iconRes, title, run);
            }
        });
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        addTile(tile);
    }

    private void addTile(View tile) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), dp(118));
        lp.setMarginEnd(dp(16));
        appRow.addView(tile, lp);
    }

    /** Big, top-anchored zoom so a hovered tile blows up downward into the open area. */
    private void zoom(View v, boolean focused) {
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(0f);                      // anchor the top so it can't be cut; bloom downward
        float s = focused ? 1.45f : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(focused ? 28f : 0f)
                .setDuration(190).setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();
        if (focused) keepClear(v);
    }

    /** Slide the whole row (no scroll container to clip the zoom) so focus sits ~a quarter in. */
    private void keepClear(final View v) {
        appRow.post(new Runnable() {
            @Override public void run() {
                int vw = appScroll.getWidth();
                int rowW = appRow.getWidth();
                if (vw == 0 || rowW == 0) return;
                float desired = vw * 0.25f - v.getLeft();
                float min = Math.min(0f, (float) (vw - rowW));
                float tx = Math.max(min, Math.min(0f, desired));
                appRow.animate().translationX(tx).setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        });
    }

    // ------------------------------------------------------------------ hero

    private void setApp(AppEntry e) {
        current = e;
        currentAction = null;
        btnOptions.setVisibility(View.VISIBLE);
        heroTitle.setText(e.label);
        boolean fav = prefs.isFav(e.pkg);
        heroTag.setVisibility(fav ? View.VISIBLE : View.GONE);
        if (fav) heroTag.setText("PINNED");
        Drawable banner = null;
        try { if (e.hasBanner(pm)) banner = e.newArt(pm, res); } catch (Throwable ignored) { }
        Drawable icon = null;
        try { icon = e.newIcon(pm, res); } catch (Throwable ignored) { }
        scheduleHero(banner, icon);
    }

    private void setAction(int iconRes, String title, Runnable run) {
        current = null;
        currentAction = run;
        btnOptions.setVisibility(View.GONE);
        heroTag.setVisibility(View.VISIBLE);
        heroTag.setText("WOW OS");
        heroTitle.setText(title);
        Drawable icon = null;
        try { icon = res.getDrawable(iconRes); } catch (Throwable ignored) { }
        scheduleHero(null, icon);
    }

    private Drawable pendingBanner, pendingIcon;

    private void scheduleHero(Drawable banner, Drawable icon) {
        pendingBanner = banner;
        pendingIcon = icon;
        ui.removeCallbacks(heroLoad);
        ui.postDelayed(heroLoad, HERO_DELAY_MS);
    }

    private final Runnable heroLoad = new Runnable() {
        @Override public void run() { showHero(pendingBanner, pendingIcon); }
    };

    private void showHero(Drawable banner, Drawable icon) {
        Drawable art = null;
        try { art = scaleHero(banner); } catch (Throwable ignored) { }

        ImageView next = heroShowingA ? heroB : heroA;
        ImageView cur = heroShowingA ? heroA : heroB;
        try { next.setImageDrawable(art); } catch (Throwable t) { next.setImageDrawable(null); }
        next.animate().alpha(art != null ? 1f : 0f).setDuration(350).start();
        cur.animate().alpha(0f).setDuration(350).start();
        heroShowingA = !heroShowingA;

        if (art == null) {
            try { heroIcon.setImageDrawable(icon); } catch (Throwable ignored) { }
            heroIcon.animate().alpha(0.5f).setDuration(350).start();
        } else {
            heroIcon.animate().alpha(0f).setDuration(250).start();
        }
    }

    /** Shrink a big banner bitmap to the screen so the hero can't exhaust the heap. */
    private Drawable scaleHero(Drawable d) {
        if (!(d instanceof BitmapDrawable)) return d;
        try {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b == null) return d;
            int maxW = res.getDisplayMetrics().widthPixels;
            int maxH = res.getDisplayMetrics().heightPixels;
            if (b.getWidth() <= maxW && b.getHeight() <= maxH) return d;
            float scale = Math.min((float) maxW / b.getWidth(), (float) maxH / b.getHeight());
            return new BitmapDrawable(res, Bitmap.createScaledBitmap(b,
                    Math.max(1, (int) (b.getWidth() * scale)), Math.max(1, (int) (b.getHeight() * scale)), true));
        } catch (Throwable t) {
            return d;
        }
    }

    // ------------------------------------------------------------------ launch

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Intent fallback = pm.getLaunchIntentForPackage(e.pkg);
            if (fallback == null) fallback = pm.getLeanbackLaunchIntentForPackage(e.pkg);
            if (fallback != null && tryStart(fallback)) return;
            toast("Couldn't open " + e.label);
        }
    }

    // ------------------------------------------------------------------ options dialog

    private void showAppOptions(final AppEntry e) {
        final boolean fav = prefs.isFav(e.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add("Open");
        actions.add(new Runnable() { @Override public void run() { launch(e); } });

        labels.add(fav ? "Unpin (move right)" : "Pin to front");
        actions.add(new Runnable() { @Override public void run() { prefs.toggleFav(e.pkg); renderRow(); } });

        if (fav) {
            labels.add("Move left");
            actions.add(new Runnable() { @Override public void run() { prefs.moveFav(e.pkg, -1); renderRow(); } });
            labels.add("Move right");
            actions.add(new Runnable() { @Override public void run() { prefs.moveFav(e.pkg, 1); renderRow(); } });
        }

        labels.add("Hide app");
        actions.add(new Runnable() { @Override public void run() {
            prefs.setHidden(e.pkg, true);
            renderRow();
            toast(e.label + " hidden. Menu → Hidden apps to bring it back.");
        } });

        labels.add("App info");
        actions.add(new Runnable() { @Override public void run() {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            if (!tryStart(i)) toast("App info isn't available on this TV");
        } });

        pick(e.label, labels, actions);
    }

    private void showHiddenManager() {
        Set<String> hidden = prefs.hidden();
        final List<AppEntry> list = new ArrayList<>();
        for (AppEntry e : allApps) if (hidden.contains(e.pkg)) list.add(e);
        if (list.isEmpty()) {
            toast("No hidden apps");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).label;
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Show an app again")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.setHidden(list.get(which).pkg, false);
                        renderRow();
                    }
                }).create();
        showGlass(d);
    }

    // ------------------------------------------------------------------ top-right menu

    private void showMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        // Input switching first, when this TV supports it.
        if (InputSwitch.available(this)) {
            for (final InputSwitch.Input in : InputSwitch.inputs()) {
                labels.add("Input: " + in.label);
                actions.add(new Runnable() { @Override public void run() {
                    String err = InputSwitch.select(HomeActivity.this, in.enumName);
                    if (err != null) toast(err);
                } });
            }
        }
        add(labels, actions, "Files", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, FilesActivity.class)); } });
        add(labels, actions, "Screen mirror", new Runnable() { @Override public void run() {
            AppEntry e = byPkg.get("com.wowsir.airwow");
            if (e == null) e = findApp("airwow", "airplay", "airpin", "mirror", "miracast", "eshare", "cast");
            if (e != null) launch(e); else toast("No screen-mirroring app found"); } });
        add(labels, actions, "Planner", new Runnable() { @Override public void run() {
            PlannerActivity.open(HomeActivity.this, null); } });
        add(labels, actions, "New alarm", new Runnable() { @Override public void run() {
            PlannerActivity.open(HomeActivity.this, "alarm"); } });
        add(labels, actions, "Running apps", new Runnable() { @Override public void run() { openRecents(); } });
        add(labels, actions, "Connections", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, ConnectionsActivity.class)); } });
        add(labels, actions, "Network", new Runnable() { @Override public void run() {
            startFirst("Network settings aren't available",
                    new Intent(Settings.ACTION_WIFI_SETTINGS),
                    new Intent(Settings.ACTION_SETTINGS)); } });
        add(labels, actions, "Alerts", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, AlertSettingsActivity.class)); } });
        add(labels, actions, "Personalise", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, PersonaliseActivity.class)); } });
        add(labels, actions, "Keyboard", new Runnable() { @Override public void run() { openKeyboardSetup(); } });
        add(labels, actions, "Hidden apps", new Runnable() { @Override public void run() { showHiddenManager(); } });

        pick("Menu", labels, actions);
    }

    private static void add(List<String> labels, List<Runnable> actions, String label, Runnable r) {
        labels.add(label);
        actions.add(r);
    }

    private void pick(String title, final List<String> labels, final List<Runnable> actions) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { actions.get(which).run(); }
                }).create();
        showGlass(d);
    }

    private void showGlass(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }

    private void openSettings() {
        startFirst("Settings aren't available",
                new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.MainSettings")),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private void openRecents() {
        try { startActivity(new Intent(this, RecentsActivity.class)); } catch (Exception ignored) { }
    }

    private AppEntry findApp(String... keywords) {
        List<String> keys = Arrays.asList(keywords);
        for (AppEntry e : allApps) {
            String hay = (e.label + " " + e.pkg).toLowerCase(Locale.ROOT);
            for (String k : keys) if (hay.contains(k)) return e;
        }
        return null;
    }

    private void startFirst(String failMessage, Intent... intents) {
        for (Intent i : intents) if (tryStart(i)) return;
        toast(failMessage);
    }

    private boolean tryStart(Intent i) {
        try {
            if (i.resolveActivity(pm) == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void openKeyboardSetup() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (imm != null) {
            for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (getPackageName().equals(info.getPackageName())) enabled = true;
            }
        }
        if (enabled && imm != null) {
            imm.showInputMethodPicker();
            return;
        }
        if (tryStart(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))) {
            toast("Turn on WOW Keyboard, then press Keyboard again to switch to it");
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Turn on WOW Keyboard")
                .setMessage("Run these from the Mac terminal:\n\n"
                        + "adb shell ime enable com.wowsir.wowos/.WowKeyboard\n"
                        + "adb shell ime set com.wowsir.wowos/.WowKeyboard")
                .setPositiveButton("OK", null).create();
        showGlass(d);
    }

    // ------------------------------------------------------------------ status

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            ui.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    private void refreshStatus() {
        refreshNetwork();
        refreshUsb();
        refreshNextUp();
    }

    private void refreshNextUp() {
        long now = System.currentTimeMillis();
        Plans.Plan p = Plans.nextUp(this, now);
        long at = p == null ? -1 : Plans.nextTrigger(p, now);
        if (p == null || at - now > 24 * 3_600_000L) {
            nextChip.setVisibility(View.GONE);
            return;
        }
        nextChip.setVisibility(View.VISIBLE);
        nextText.setText(Plans.timeText(this, p.hour, p.minute) + "  " + p.displayTitle());
    }

    @SuppressWarnings("deprecation")
    private void refreshNetwork() {
        String text = "Offline";
        boolean on = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                on = true;
                text = ni.getType() == ConnectivityManager.TYPE_ETHERNET ? "Ethernet" : "Wi-Fi";
            }
        } catch (Exception ignored) { }
        netText.setText(text);
        netDot.setImageResource(on ? R.drawable.dot_on : R.drawable.dot_off);
    }

    private void refreshUsb() {
        String text = "No USB disk";
        int dot = R.drawable.dot_off;
        try {
            StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (!v.isRemovable()) continue;
                    String state = v.getState();
                    String name = v.getDescription(this);
                    if (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                        text = name == null ? "USB ready" : name;
                        dot = R.drawable.dot_on;
                        break;
                    } else if (Environment.MEDIA_CHECKING.equals(state)) {
                        text = "USB checking…";
                        dot = R.drawable.dot_wait;
                    } else if (Environment.MEDIA_UNMOUNTABLE.equals(state)) {
                        text = "USB unreadable";
                        dot = R.drawable.dot_wait;
                    }
                }
            }
        } catch (Exception ignored) { }
        usbText.setText(text);
        usbDot.setImageResource(dot);
    }

    // ------------------------------------------------------------------ receivers

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reloadApps(); }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshStatus(); }
    };

    private static IntentFilter packageFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        return f;
    }

    @SuppressWarnings("deprecation")
    private static IntentFilter statusFilter() {
        return new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    }

    private static IntentFilter mediaFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_CHECKING);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        f.addDataScheme("file");
        return f;
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) {
        return Math.round(v * res.getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
