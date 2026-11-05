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
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
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
    private ScrollView homeScroll;
    private LinearLayout rowsContainer;
    private ImageView heroA, heroB, heroIcon, homeWallpaper;
    private boolean heroShowingA = true;
    private TextView heroTitle, heroTag;
    private View btnOpen, btnOptions, btnSettings, btnMenu;

    private ImageView netDot, usbDot;
    private TextView netText, usbText, nextText;
    private View nextChip;
    private TextView greetingHello, greetingMsg, clock;
    private String lastMsg = "";

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
        homeScroll = findViewById(R.id.home_scroll);
        rowsContainer = findViewById(R.id.rows_container);
        heroA = findViewById(R.id.hero_a);
        heroB = findViewById(R.id.hero_b);
        heroIcon = findViewById(R.id.hero_icon);
        homeWallpaper = findViewById(R.id.home_wallpaper);
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
        greetingHello = findViewById(R.id.greeting_hello);
        greetingMsg = findViewById(R.id.greeting_msg);
        clock = findViewById(R.id.clock);

        // Force clipping off the whole ancestor chain so a focused tile's bloom can
        // never be cut by a ROM that doesn't honour the XML clipChildren flag.
        unclipAncestors(appRow);

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
        applyWallpaper();
        refreshGreeting();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        ui.postDelayed(statusTick, STATUS_REFRESH_MS);
        scheduleIdle();
    }

    /** Clock shows the NTP-corrected time, so it stays right even when the TV's own
     *  system clock is wrong (we can't set the system clock without system privileges). */
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            boolean h24 = android.text.format.DateFormat.is24HourFormat(HomeActivity.this);
            try { clock.setText(android.text.format.DateFormat.format(h24 ? "HH:mm" : "h:mm", TimeSync.nowMillis())); } catch (Throwable ignored) { }
            ui.postDelayed(this, 15_000);
        }
    };

    /** Time-aware greeting and a rotating message, honouring the Personalise toggle. */
    private void refreshGreeting() {
        if (greetingHello == null) return;
        if (!Greeting.show(this)) {
            greetingHello.setVisibility(View.GONE);
            greetingMsg.setVisibility(View.GONE);
            return;
        }
        greetingHello.setVisibility(View.VISIBLE);
        greetingHello.setText(Greeting.hello(this));
        String m = Greeting.message(this, lastMsg);
        lastMsg = m;
        greetingMsg.setText(m);
        greetingMsg.setVisibility(m == null || m.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Show the user's own home wallpaper when they've set one; otherwise fall back
     *  to the built-in background. Reloaded on every resume so a change in WOW Files
     *  takes effect as soon as you return to the home screen. */
    private static final int DEFAULT_ACCENT = 0xFF4F9BFF;
    private int glowColor = 0xFFBFE3FF;       // focus-glow colour, follows the accent
    private String appliedWallpaper = null;
    private void applyWallpaper() {
        String path = Greeting.homeWallpaper(this);
        if (path == null || path.isEmpty() || !new java.io.File(path).canRead()) {
            homeWallpaper.setImageDrawable(null);
            homeWallpaper.setVisibility(View.GONE);
            appliedWallpaper = null;
            applyAccent(DEFAULT_ACCENT);
            return;
        }
        if (path.equals(appliedWallpaper)) return;   // already shown; don't re-decode
        int w = res.getDisplayMetrics().widthPixels;
        int h = res.getDisplayMetrics().heightPixels;
        Bitmap bmp = decodeDownsampled(path, w, h);
        if (bmp == null) {
            homeWallpaper.setVisibility(View.GONE);
            appliedWallpaper = null;
            applyAccent(DEFAULT_ACCENT);
            return;
        }
        homeWallpaper.setImageBitmap(bmp);
        homeWallpaper.setVisibility(View.VISIBLE);
        appliedWallpaper = path;
        applyAccent(ColorExtract.accent(bmp, DEFAULT_ACCENT));
    }

    /** Tint the Open button and the "pinned/WOW OS" tag with the wallpaper accent. */
    private void applyAccent(int color) {
        glowColor = lighten(color, 0.4f);     // a softer, brighter glow than the raw accent
        try {
            btnOpen.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            if (btnOpen instanceof TextView)
                ((TextView) btnOpen).setTextColor(ColorExtract.isLight(color) ? 0xFF0B0E1A : 0xFFFFFFFF);
        } catch (Throwable ignored) { }
        try { heroTag.setTextColor(color); } catch (Throwable ignored) { }
    }

    private static int lighten(int c, float f) {
        int r = (int) (android.graphics.Color.red(c) + (255 - android.graphics.Color.red(c)) * f);
        int g = (int) (android.graphics.Color.green(c) + (255 - android.graphics.Color.green(c)) * f);
        int b = (int) (android.graphics.Color.blue(c) + (255 - android.graphics.Color.blue(c)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Decode a file no larger than the screen so a huge photo can't exhaust the heap. */
    private static Bitmap decodeDownsampled(String path, int reqW, int reqH) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample > reqW * 1.2 || o.outHeight / sample > reqH * 1.2) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statusTick);
        ui.removeCallbacks(clockTick);
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

    private static final String EXTRA_ROW = "wow_extra_row";

    private void renderRow() {
        String keepFocusPkg = current != null ? current.pkg : null;

        appRow.removeAllViews();
        for (int i = rowsContainer.getChildCount() - 1; i >= 0; i--) {
            if (EXTRA_ROW.equals(rowsContainer.getChildAt(i).getTag())) rowsContainer.removeViewAt(i);
        }

        List<AppEntry> apps = ordered();
        for (AppEntry e : apps) addTile(appTile(e, true), appRow, dp(38), dp(118), dp(118));

        // Streaming: WIDE banner tiles using each app's own banner art (relevant per app).
        List<View> streaming = new ArrayList<>();
        for (AppEntry e : streamingApps(apps)) streaming.add(bannerTile(e));
        if (!streaming.isEmpty()) rowsContainer.addView(makeRow("STREAMING", streaming, dp(228), dp(128)));

        // Real content rows from free public feeds (added async; removed if unreachable).
        addContentRow("TRENDING TRAILERS", "trailers",
                new String[]{"https://www.youtube.com/feeds/videos.xml?channel_id=UCi8e0iOVk1fEOogdfu4YgfA"}, 15);
        addContentRow("NEWS", "news",
                new String[]{"https://www.myjoyonline.com/feed/", "https://feeds.bbci.co.uk/news/world/rss.xml"}, 16);
        addContentRow("SPORTS", "sports",
                new String[]{"https://feeds.bbci.co.uk/sport/rss.xml"}, 15);
        addContentRow("TECH", "tech",
                new String[]{"https://feeds.bbci.co.uk/news/technology/rss.xml"}, 15);

        rowsContainer.addView(makeRow("WOW OS", toolTiles(), dp(118), dp(118)));

        if (InputSwitch.available(this)) {
            List<View> inputs = new ArrayList<>();
            for (final InputSwitch.Input in : InputSwitch.inputs()) {
                int ic = in.label.toLowerCase(Locale.ROOT).contains("tv") ? R.drawable.sysic_tv : R.drawable.sysic_input;
                inputs.add(toolTile(ic, in.label, new Runnable() {
                    @Override public void run() { String err = InputSwitch.select(HomeActivity.this, in.enumName); if (err != null) toast(err); }
                }, false));
            }
            if (!inputs.isEmpty()) rowsContainer.addView(makeRow("INPUTS", inputs, dp(118), dp(118)));
        }

        View focusTarget = null;
        if (keepFocusPkg != null) {
            for (int i = 0; i < appRow.getChildCount(); i++) {
                Object t = appRow.getChildAt(i).getTag();
                if (t instanceof AppEntry && ((AppEntry) t).pkg.equals(keepFocusPkg)) { focusTarget = appRow.getChildAt(i); break; }
            }
        }
        if (focusTarget == null && appRow.getChildCount() > 0) focusTarget = appRow.getChildAt(0);
        if (focusTarget != null) focusTarget.requestFocus();
    }

    /** Package/label keywords that put an app in the Streaming row. */
    private static final String[] STREAM_KEYS = {
            "netflix", "youtube", "primevideo", "amazon", "disney", "spotify", "hbo", "max",
            "hulu", "plex", "kodi", "mubi", "crunchyroll", "twitch", "dailymotion", "showmax",
            "viu", "iflix", "tubi", "vlc", "mxplayer", "yupptv", "zee", "tidal", "deezer", "iqiyi"
    };

    private List<AppEntry> streamingApps(List<AppEntry> apps) {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : apps) {
            String hay = (e.pkg + " " + e.label).toLowerCase(Locale.ROOT);
            for (String k : STREAM_KEYS) { if (hay.contains(k)) { out.add(e); break; } }
        }
        return out;
    }

    private List<View> toolTiles() {
        List<View> t = new ArrayList<>();
        t.add(toolTile(R.drawable.sysic_files, "Files", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, FilesActivity.class)); } }, false));
        t.add(toolTile(R.drawable.sysic_planner, "Planner", new Runnable() { @Override public void run() {
            PlannerActivity.open(HomeActivity.this, null); } }, false));
        t.add(toolTile(R.drawable.sysic_connections, "Connections", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, ConnectionsActivity.class)); } }, false));
        t.add(toolTile(R.drawable.sysic_mirror, "Screen mirror", new Runnable() { @Override public void run() {
            AppEntry e = byPkg.get("com.wowsir.airwow");
            if (e == null) e = findApp("airwow", "airplay", "airpin", "mirror", "miracast", "eshare", "cast");
            if (e != null) launch(e); else toast("No screen-mirroring app found"); } }, false));
        t.add(toolTile(R.drawable.sysic_alerts, "Alerts", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, AlertSettingsActivity.class)); } }, false));
        t.add(toolTile(R.drawable.sysic_personalise, "Personalise", new Runnable() { @Override public void run() {
            tryStart(new Intent(HomeActivity.this, PersonaliseActivity.class)); } }, false));
        t.add(toolTile(R.drawable.sysic_hidden, "Hidden apps", new Runnable() { @Override public void run() { showHiddenManager(); } }, false));
        return t;
    }

    private View appTile(final AppEntry e, final boolean sliding) {
        View tile = inflater.inflate(R.layout.item_ps5_tile, appRow, false);
        ImageView icon = tile.findViewById(R.id.icon);
        try { icon.setImageDrawable(e.newIcon(pm, res)); } catch (Throwable ignored) { }
        tile.setFocusable(true);
        tile.setTag(e);
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                doZoom(v, has);
                if (has && sliding) keepClear(v);
                if (has) setApp(e);
            }
        });
        tile.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { launch(e); } });
        tile.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { showAppOptions(e); return true; } });
        return tile;
    }

    private View toolTile(final int iconRes, final String title, final Runnable run, final boolean sliding) {
        View tile = inflater.inflate(R.layout.item_ps5_tile, appRow, false);
        ImageView icon = tile.findViewById(R.id.icon);
        icon.setImageResource(iconRes);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setFocusable(true);
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                doZoom(v, has);
                if (has && sliding) keepClear(v);
                if (has) setAction(iconRes, title, run);
            }
        });
        tile.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { run.run(); } });
        return tile;
    }

    /** A wide banner tile: shows the app's own banner art, falling back to its icon. */
    private View bannerTile(final AppEntry e) {
        View tile = inflater.inflate(R.layout.item_ps5_tile, appRow, false);
        ImageView icon = tile.findViewById(R.id.icon);
        ImageView banner = tile.findViewById(R.id.banner);
        boolean shown = false;
        try {
            if (e.hasBanner(pm)) {
                banner.setImageDrawable(e.newArt(pm, res));
                banner.setVisibility(View.VISIBLE);
                icon.setVisibility(View.GONE);
                shown = true;
            }
        } catch (Throwable ignored) { }
        if (!shown) { try { icon.setImageDrawable(e.newIcon(pm, res)); } catch (Throwable ignored) { } }
        tile.setFocusable(true);
        tile.setTag(e);
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { doZoom(v, has); if (has) setApp(e); }
        });
        tile.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { launch(e); } });
        tile.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { showAppOptions(e); return true; } });
        return tile;
    }

    /** A labelled horizontal row (native scrolling) for a lower section. */
    private View makeRow(String label, List<View> tiles, int tileW, int tileH) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);
        block.setTag(EXTRA_ROW);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(0x99FFFFFF);
        lab.setTextSize(13);
        lab.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labLp.leftMargin = dp(44);
        labLp.topMargin = dp(20);
        block.addView(lab, labLp);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(44), dp(16), dp(44), dp(24));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setClipChildren(false);
        for (View t : tiles) addTile(t, inner, dp(26), tileW, tileH);
        hsv.addView(inner);
        block.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return block;
    }

    /** A content row (news/trailers/etc.) populated asynchronously from a public feed.
     *  Shows a brief loading state, then real thumbnails — or removes itself if the
     *  feed is empty or unreachable (never an empty section). */
    private void addContentRow(final String label, final String cacheKey, final String[] urls, final int cap) {
        final LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);
        block.setTag(EXTRA_ROW);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(0x99FFFFFF);
        lab.setTextSize(13);
        lab.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labLp.leftMargin = dp(44);
        labLp.topMargin = dp(20);
        block.addView(lab, labLp);

        final LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setClipChildren(false);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(44), dp(16), dp(44), dp(24));
        hsv.addView(inner);
        block.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView loading = new TextView(this);
        loading.setText("Loading…");
        loading.setTextColor(0x66FFFFFF);
        loading.setTextSize(13);
        loading.setGravity(Gravity.CENTER_VERTICAL);
        inner.addView(loading, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(128)));

        rowsContainer.addView(block);

        new Thread(new Runnable() {
            @Override public void run() {
                final List<Feeds.Item> items = Feeds.fetchMerged(cacheKey, urls, cap);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (block.getParent() == null) return;             // row was rebuilt meanwhile
                        inner.removeAllViews();
                        if (items == null || items.isEmpty()) { rowsContainer.removeView(block); return; }
                        for (Feeds.Item it : items) addTile(contentTile(it), inner, dp(26), dp(228), dp(128));
                    }
                });
            }
        }, "wowos-feed-" + cacheKey).start();
    }

    /** A wide content tile: feed thumbnail with a title overlay; opens the link on click. */
    private View contentTile(final Feeds.Item it) {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFF161A22);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        f.addView(scrim, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView t = new TextView(this);
        t.setText(it.title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(12);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        tlp.setMargins(dp(10), 0, dp(10), dp(8));
        t.setLayoutParams(tlp);
        f.addView(t);

        f.setFocusable(true);
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { doZoom(v, has); }
        });
        f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openLink(it.link); } });
        ImageLoad.into(img, it.image, dp(228), dp(128));
        return f;
    }

    private void openLink(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Couldn't open that link");
        }
    }

    private static final String GLOW_TAG = "wow_glow";

    private void addTile(View tile, LinearLayout target, int marginEnd, int w, int h) {
        if (tile instanceof FrameLayout) {
            FrameLayout f = (FrameLayout) tile;
            f.setClipChildren(false);
            f.setClipToPadding(false);
            View glow = new View(this);
            glow.setTag(GLOW_TAG);
            int extra = dp(40);
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(w + extra, h + extra);
            glp.gravity = Gravity.CENTER;
            glow.setLayoutParams(glp);
            glow.setAlpha(0f);
            f.addView(glow, 0);
        }
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22)); }
        });
        tile.setClipToOutline(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(marginEnd);
        target.addView(tile, lp);
    }

    /** A soft radial halo in the accent colour, for the focus glow behind a tile. */
    private Drawable makeGlow(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(30));
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(78));
        g.setColors(new int[]{ (color & 0x00FFFFFF) | 0xB3000000, (color & 0x00FFFFFF) });
        return g;
    }

    /** Centre-pivot PS5 bloom: a hovered tile scales evenly in every direction into
     *  the clear space around the row. Pivots are set from the measured size (posted
     *  if the tile hasn't been laid out yet, e.g. the very first auto-focus). */
    private void doZoom(final View v, final boolean focused) {
        final Runnable apply = new Runnable() {
            @Override public void run() {
                v.setPivotX(v.getWidth() / 2f);
                v.setPivotY(v.getHeight() / 2f);     // bloom from the centre, not a rail
                if (v instanceof ViewGroup) {        // the real glow halo behind the tile
                    View glow = ((ViewGroup) v).getChildAt(0);
                    if (glow != null && GLOW_TAG.equals(glow.getTag())) {
                        glow.setBackground(makeGlow(glowColor));
                        glow.animate().alpha(focused ? 1f : 0f).setDuration(200).start();
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {   // tinted shadow too, where the ROM honours it
                    v.setOutlineSpotShadowColor(focused ? glowColor : 0xFF000000);
                    v.setOutlineAmbientShadowColor(focused ? glowColor : 0xFF000000);
                }
                float s = focused ? 1.42f : 1f;
                v.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(16) : 0f)
                        .setDuration(190)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f))
                        .start();
            }
        };
        if (v.getWidth() == 0 || v.getHeight() == 0) v.post(apply); else apply.run();
    }

    /** Walk from the row up to the window root, turning clipping off on every group. */
    private void unclipAncestors(View from) {
        View v = from;
        while (v != null) {
            if (v instanceof ViewGroup) {
                ((ViewGroup) v).setClipChildren(false);
                ((ViewGroup) v).setClipToPadding(false);
            }
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
    }

    /** Slide the row only when the focused tile nears an edge; never past the ends. */
    private void keepClear(final View v) {
        appRow.post(new Runnable() {
            @Override public void run() {
                int vw = appScroll.getWidth();
                if (vw == 0 || appRow.getChildCount() == 0) return;
                // The row is wrap_content inside a full-width frame, so appRow.getWidth()
                // is clamped to the screen and is NOT the true content width. Measure the
                // content from the last child, or scrolling right is impossible (min == 0).
                View last = appRow.getChildAt(appRow.getChildCount() - 1);
                int byEdge = last.getRight() + appRow.getPaddingRight();
                // Independent measure in case getRight() comes back clamped on this ROM.
                int bySum = appRow.getPaddingLeft() + appRow.getPaddingRight();
                for (int i = 0; i < appRow.getChildCount(); i++) {
                    View c = appRow.getChildAt(i);
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
                    bySum += c.getWidth() + mlp.leftMargin + mlp.rightMargin;
                }
                int contentW = Math.max(byEdge, bySum);
                float cur = appRow.getTranslationX();
                int margin = dp(90);                       // keep this much clear of each edge
                int leftOnScreen = (int) (v.getLeft() + cur);
                int rightOnScreen = (int) (v.getRight() + cur);
                float tx = cur;
                if (leftOnScreen < margin) tx = cur + (margin - leftOnScreen);
                else if (rightOnScreen > vw - margin) tx = cur - (rightOnScreen - (vw - margin));
                float min = Math.min(0f, (float) (vw - contentW));   // last tile flush right, no blank beyond
                tx = Math.max(min, Math.min(0f, tx));
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
