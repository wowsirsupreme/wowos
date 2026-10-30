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
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HomeActivity extends Activity {

    private static final long STATUS_REFRESH_MS = 8000;

    private PackageManager pm;
    private LayoutInflater inflater;
    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout quickRow;
    private LinearLayout inputsRow;
    private View inputsScroll;
    private View inputsSection;
    private LinearLayout favRow;
    private HorizontalScrollView quickScroll;
    private HorizontalScrollView favScroll;
    private TextView favHint;
    private LinearLayout allRow;
    private HorizontalScrollView allScroll;
    private ScrollView page;

    private ImageView netDot;
    private TextView netText;
    private ImageView usbDot;
    private TextView usbText;
    private TextView greetView;
    private TextView messageView;
    private View nextChip;
    private TextView nextText;
    private String lastMessage;
    private static final long MESSAGE_MS = 45_000;
    private static final long IDLE_MS = 90_000;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> byPkg = new HashMap<>();
    private boolean loading;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        pm = getPackageManager();
        inflater = LayoutInflater.from(this);
        prefs = new Prefs(this);

        quickRow = findViewById(R.id.quick_row);
        inputsRow = findViewById(R.id.inputs_row);
        inputsScroll = findViewById(R.id.inputs_scroll);
        inputsSection = findViewById(R.id.sec_inputs);
        favRow = findViewById(R.id.fav_row);
        quickScroll = findViewById(R.id.quick_scroll);
        favScroll = findViewById(R.id.fav_scroll);
        favHint = findViewById(R.id.fav_hint);
        allRow = findViewById(R.id.all_row);
        allScroll = findViewById(R.id.all_scroll);
        page = findViewById(R.id.page);
        netDot = findViewById(R.id.net_dot);
        netText = findViewById(R.id.net_text);
        usbDot = findViewById(R.id.usb_dot);
        usbText = findViewById(R.id.usb_text);
        greetView = findViewById(R.id.greet);
        messageView = findViewById(R.id.message);
        nextChip = findViewById(R.id.next_chip);
        nextText = findViewById(R.id.next_text);

        buildQuickRow();
        buildInputsRow();

        registerReceiver(packageReceiver, packageFilter());
        registerReceiver(statusReceiver, statusFilter());
        IntentFilter media = mediaFilter();
        registerReceiver(statusReceiver, media);

        reloadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimeSync.syncSoon(this);
        refreshStatus();
        ui.postDelayed(statusTick, STATUS_REFRESH_MS);
        showMessage(false);
        ui.postDelayed(messageTick, MESSAGE_MS);
        scheduleIdle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statusTick);
        ui.removeCallbacks(messageTick);
        ui.removeCallbacks(idleRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) { }
    }

    /** Home pressed while already home: go back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        quickScroll.smoothScrollTo(0, 0);
        favScroll.smoothScrollTo(0, 0);
        allScroll.smoothScrollTo(0, 0);
        page.smoothScrollTo(0, 0);
        if (quickRow.getChildCount() > 0) quickRow.getChildAt(0).requestFocus();
    }

    /** A launcher has nowhere to go "back" to. */
    @Override
    public void onBackPressed() {
        if (quickRow.getChildCount() > 0) quickRow.getChildAt(0).requestFocus();
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

    /** Menu opens app options; the App-switch key opens the multitask switcher. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            openRecents();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof AppEntry) {
                showAppOptions((AppEntry) f.getTag(), f.getParent() == favRow);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ apps

    private void reloadApps() {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> list = queryApps();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        allApps.clear();
                        allApps.addAll(list);
                        byPkg.clear();
                        for (AppEntry e : list) byPkg.put(e.pkg, e);
                        prefs.seedIfNeeded(byPkg.keySet());
                        render();
                    }
                });
            }
        }, "wowos-apps").start();
    }

    /** TV (leanback) entries first, then phone launcher entries for apps without one. */
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
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    private void render() {
        Set<String> hidden = prefs.hidden();
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry e : allApps) if (!hidden.contains(e.pkg)) visible.add(e);
        renderAll(visible);
        renderFavs();
    }

    /** All apps as two rows that scroll left to right. */
    private void renderAll(List<AppEntry> visible) {
        View focused = getCurrentFocus();
        boolean hadFocus = focused != null && focused.getParent() != null
                && focused.getParent().getParent() == allRow;

        allRow.removeAllViews();
        int width = dp(168);
        int gap = dp(18);
        LinearLayout column = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % 2 == 0) {
                column = new LinearLayout(this);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setClipChildren(false);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMarginEnd(gap);
                allRow.addView(column, clp);
            }
            View tile = makeAppTile(visible.get(i), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % 2 == 0) lp.bottomMargin = gap;
            column.addView(tile, lp);
        }
        if (hadFocus && allRow.getChildCount() > 0) {
            ((ViewGroup) allRow.getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    private View makeAppTile(final AppEntry e, final boolean inFavRow) {
        View tile = inflater.inflate(R.layout.item_app, allRow, false);
        tile.setFocusable(true);
        tile.setTag(e);
        Tiles.bind(tile, e, pm);
        tile.setOnFocusChangeListener(Tiles.LIFT_ON_FOCUS);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(e); }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showAppOptions(e, inFavRow); return true; }
        });
        return tile;
    }

    private void renderFavs() {
        View focused = getCurrentFocus();
        boolean favHadFocus = focused != null && focused.getParent() == favRow;
        int focusIndex = favHadFocus ? favRow.indexOfChild(focused) : -1;

        favRow.removeAllViews();
        int px = dp(176);
        for (String p : prefs.favs()) {
            AppEntry e = byPkg.get(p);
            if (e == null) continue;
            View tile = makeAppTile(e, true);
            tile.setOnFocusChangeListener(Tiles.dock(favRow));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(18));
            favRow.addView(tile, lp);
        }
        boolean empty = favRow.getChildCount() == 0;
        favScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        favHint.setVisibility(empty ? View.VISIBLE : View.GONE);

        if (favHadFocus && !empty) {
            int i = Math.max(0, Math.min(focusIndex, favRow.getChildCount() - 1));
            favRow.getChildAt(i).requestFocus();
        }
    }

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

    private void showAppOptions(final AppEntry e, final boolean inFavRow) {
        final boolean fav = prefs.isFav(e.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(fav ? "Unpin from favourites" : "Pin to favourites");
        actions.add(new Runnable() { @Override public void run() { prefs.toggleFav(e.pkg); renderFavs(); } });

        if (fav) {
            labels.add("Move left");
            actions.add(new Runnable() { @Override public void run() { prefs.moveFav(e.pkg, -1); renderFavs(); } });
            labels.add("Move right");
            actions.add(new Runnable() { @Override public void run() { prefs.moveFav(e.pkg, 1); renderFavs(); } });
        }

        labels.add("Hide app");
        actions.add(new Runnable() { @Override public void run() {
            prefs.setHidden(e.pkg, true);
            render();
            toast(e.label + " hidden. Open \"Hidden\" in Quick to bring it back.");
        } });

        labels.add("App info");
        actions.add(new Runnable() { @Override public void run() {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            if (!tryStart(i)) toast("App info isn't available on this box");
        } });

        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        actions.get(which).run();
                    }
                })
                .create();
        showGlass(d);
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
                .setTitle("Tap an app to show it again")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        AppEntry e = list.get(which);
                        prefs.setHidden(e.pkg, false);
                        render();
                        toast(e.label + " is back in All apps");
                    }
                })
                .create();
        showGlass(d);
    }

    private void showGlass(AlertDialog d) {
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }

    // ------------------------------------------------------------------ inputs

    private void buildInputsRow() {
        inputsRow.removeAllViews();
        java.util.List<InputSwitch.Input> inputs = InputSwitch.available(this)
                ? InputSwitch.inputs() : java.util.Collections.<InputSwitch.Input>emptyList();
        boolean show = !inputs.isEmpty();
        inputsSection.setVisibility(show ? View.VISIBLE : View.GONE);
        inputsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (final InputSwitch.Input in : inputs) {
            View t = inflater.inflate(R.layout.item_quick, inputsRow, false);
            ((ImageView) t.findViewById(R.id.icon)).setImageResource(in.iconRes);
            ((TextView) t.findViewById(R.id.label)).setText(in.label);
            t.setTag(in.enumName);
            t.setOnFocusChangeListener(Tiles.dock(inputsRow));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String err = InputSwitch.select(HomeActivity.this, in.enumName);
                    if (err != null) toast(err);
                }
            });
            inputsRow.addView(t);
        }
        markCurrentInput();
    }

    /** Dim the input the TV is already showing. */
    private void markCurrentInput() {
        String cur = InputSwitch.current(this);
        for (int i = 0; i < inputsRow.getChildCount(); i++) {
            View t = inputsRow.getChildAt(i);
            boolean isCur = cur != null && cur.equals(t.getTag());
            t.setAlpha(isCur ? 0.55f : 1f);
        }
    }

    // ------------------------------------------------------------------ quick row

    private void openRecents() {
        try { startActivity(new Intent(this, RecentsActivity.class)); } catch (Exception ignored) { }
    }

    private void buildQuickRow() {
        addQuick(R.drawable.ic_settings, "Settings", new Runnable() { @Override public void run() {
            startFirst("Settings aren't available",
                    new Intent().setComponent(new ComponentName("com.android.tv.settings",
                            "com.android.tv.settings.MainSettings")),
                    new Intent(Settings.ACTION_SETTINGS));
        } });
        addQuick(R.drawable.ic_wifi, "Network", new Runnable() { @Override public void run() {
            startFirst("Network settings aren't available",
                    new Intent(Settings.ACTION_WIFI_SETTINGS),
                    new Intent().setComponent(new ComponentName("com.android.tv.settings",
                            "com.android.tv.settings.connectivity.NetworkActivity")),
                    new Intent(Settings.ACTION_SETTINGS));
        } });
        addQuick(R.drawable.ic_folder, "Files", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, FilesActivity.class));
        } });
        addQuick(R.drawable.ic_calendar, "Planner", new Runnable() { @Override public void run() {
            PlannerActivity.open(HomeActivity.this, null);
        } });
        addQuick(R.drawable.ic_alarm, "Alarm", new Runnable() { @Override public void run() {
            PlannerActivity.open(HomeActivity.this, "alarm");
        } });
        addQuick(R.drawable.ic_plug, "Connections", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, ConnectionsActivity.class));
        } });
        addQuick(R.drawable.ic_keyboard, "Keyboard", new Runnable() { @Override public void run() {
            openKeyboardSetup();
        } });
        addQuick(R.drawable.ic_grid, "Recents", new Runnable() { @Override public void run() {
            openRecents();
        } });
        addQuick(R.drawable.ic_cast, "Mirror", new Runnable() { @Override public void run() {
            AppEntry e = byPkg.get("com.wowsir.airwow");
            if (e == null) e = findApp("airwow", "airplay", "airpin", "mirror", "miracast", "eshare", "cast", "screen share");
            if (e != null) launch(e);
            else toast("No screen-mirroring app found");
        } });
        addQuick(R.drawable.ic_store, "Play Store", new Runnable() { @Override public void run() {
            AppEntry e = byPkg.get("com.android.vending");
            if (e != null) launch(e); else toast("Google Play isn't installed");
        } });
        addQuick(R.drawable.ic_globe, "Browser", new Runnable() { @Override public void run() {
            AppEntry e = findApp("browser", "chrome", "firefox", "edge", "puffin");
            if (e != null) launch(e); else toast("No browser installed");
        } });
        addQuick(R.drawable.ic_alerts, "Alerts", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, AlertSettingsActivity.class));
        } });
        addQuick(R.drawable.ic_star, "Personalise", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, PersonaliseActivity.class));
        } });
        addQuick(R.drawable.ic_hidden, "Hidden", new Runnable() { @Override public void run() {
            showHiddenManager();
        } });
    }

    private void addQuick(int iconRes, String label, final Runnable action) {
        View t = inflater.inflate(R.layout.item_quick, quickRow, false);
        ((ImageView) t.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) t.findViewById(R.id.label)).setText(label);
        t.setOnFocusChangeListener(Tiles.dock(quickRow));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        quickRow.addView(t);
    }

    /** First visible app whose name or package contains any keyword. */
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

    // ------------------------------------------------------------------ greeting & next up

    private final Runnable messageTick = new Runnable() {
        @Override public void run() {
            showMessage(true);
            ui.postDelayed(this, MESSAGE_MS);
        }
    };

    private void refreshGreeting() {
        boolean on = Greeting.show(this);
        greetView.setVisibility(on ? View.VISIBLE : View.GONE);
        messageView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) greetView.setText(Greeting.hello(this));
    }

    /** Fade to a new message suited to the time of day. */
    private void showMessage(boolean animate) {
        if (!Greeting.show(this)) return;
        final String next = Greeting.message(this, lastMessage);
        lastMessage = next;
        if (!animate) {
            messageView.setText(next);
            return;
        }
        messageView.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override public void run() {
                messageView.setText(next);
                messageView.animate().alpha(1f).setDuration(600).start();
            }
        }).start();
    }

    private void refreshNextUp() {
        long now = System.currentTimeMillis();
        Plans.Plan p = Plans.nextUp(this, now);
        long at = p == null ? -1 : Plans.nextTrigger(p, now);
        // Only show what's coming in the next 24 hours.
        if (p == null || at - now > 24 * 3_600_000L) {
            nextChip.setVisibility(View.GONE);
            return;
        }
        nextChip.setVisibility(View.VISIBLE);
        nextText.setText(Plans.timeText(this, p.hour, p.minute) + "  " + p.displayTitle());
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
                .setMessage("This box hides keyboard settings. Run these from the Mac terminal:\n\n"
                        + "adb shell ime enable com.wowsir.wowos/.WowKeyboard\n"
                        + "adb shell ime set com.wowsir.wowos/.WowKeyboard")
                .setPositiveButton("OK", null).create();
        showGlass(d);
    }

    // ------------------------------------------------------------------ status chips

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    private void refreshStatus() {
        refreshNetwork();
        refreshUsb();
        refreshGreeting();
        refreshNextUp();
        if (inputsRow != null && inputsRow.getChildCount() > 0) markCurrentInput();
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

    /** Shows mounted / checking / unreadable so a slow USB disk is visible, not a mystery. */
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
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
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
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
