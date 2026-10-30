package com.wowsir.wowos;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

/** One launchable app. Artwork is loaded lazily and cached to keep RAM low. */
final class AppEntry {
    final String pkg;
    final String label;
    final ComponentName component;
    final boolean leanback;
    private final ActivityInfo info;

    private Drawable art;
    private boolean artIsBanner;
    private boolean artLoaded;

    AppEntry(ActivityInfo info, String label, boolean leanback) {
        this.info = info;
        this.pkg = info.packageName;
        this.label = label;
        this.leanback = leanback;
        this.component = new ComponentName(info.packageName, info.name);
    }

    private void loadArt(PackageManager pm) {
        if (artLoaded) return;
        artLoaded = true;
        try {
            Drawable banner = info.loadBanner(pm);
            if (banner != null) {
                art = banner;
                artIsBanner = true;
                return;
            }
        } catch (Exception ignored) { }
        try {
            art = info.loadIcon(pm);
        } catch (Exception ignored) { }
    }

    boolean hasBanner(PackageManager pm) {
        loadArt(pm);
        return artIsBanner;
    }

    /** The app's launcher icon (always the icon, never the banner). */
    android.graphics.drawable.Drawable newIcon(PackageManager pm, Resources res) {
        try {
            android.graphics.drawable.Drawable d = info.loadIcon(pm);
            if (d == null) return null;
            android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
            return cs != null ? cs.newDrawable(res) : d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A fresh drawable instance (so two views can show the same app safely). */
    Drawable newArt(PackageManager pm, Resources res) {
        loadArt(pm);
        if (art == null) return null;
        Drawable.ConstantState cs = art.getConstantState();
        return cs != null ? cs.newDrawable(res) : art;
    }
}
