package com.wowsir.wowos;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Favourites (ordered) and hidden apps, stored by package name. */
final class Prefs {
    private static final String FILE = "wowos";
    private static final String KEY_FAVS = "favs";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_SEEDED = "seeded";

    /** Pinned on first run if installed. */
    private static final String[] DEFAULT_FAVS = {
            "com.google.android.youtube.tv", "com.google.android.youtube",
            "com.netflix.ninja", "com.netflix.mediaclient",
            "com.android.vending", "com.spotify.tv.android", "com.spotify.music",
            "com.wowsir.airwow"
    };

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    List<String> favs() {
        String raw = sp.getString(KEY_FAVS, "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    void setFavs(List<String> favs) {
        sp.edit().putString(KEY_FAVS, TextUtils.join(",", favs)).apply();
    }

    boolean isFav(String pkg) {
        return favs().contains(pkg);
    }

    void toggleFav(String pkg) {
        List<String> f = favs();
        if (f.contains(pkg)) f.remove(pkg); else f.add(pkg);
        setFavs(f);
    }

    void moveFav(String pkg, int delta) {
        List<String> f = favs();
        int i = f.indexOf(pkg);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= f.size()) return;
        f.remove(i);
        f.add(j, pkg);
        setFavs(f);
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet(KEY_HIDDEN, new HashSet<String>()));
    }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(pkg); else h.remove(pkg);
        sp.edit().putStringSet(KEY_HIDDEN, h).apply();
        if (hide) {
            List<String> f = favs();
            if (f.remove(pkg)) setFavs(f);
        }
    }

    /** First run only: pin common apps that are actually installed. */
    void seedIfNeeded(Set<String> installed) {
        if (sp.getBoolean(KEY_SEEDED, false)) return;
        List<String> f = new ArrayList<>();
        for (String p : DEFAULT_FAVS) if (installed.contains(p)) f.add(p);
        setFavs(f);
        sp.edit().putBoolean(KEY_SEEDED, true).apply();
    }
}
