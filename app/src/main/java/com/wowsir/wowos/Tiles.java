package com.wowsir.wowos;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Fills an item_app tile and gives it the glass focus animation. */
final class Tiles {
    static final float FOCUS_SCALE = 1.06f;

    /** Cap artwork size so a wall of tiles can't exhaust the TV's small heap. */
    private static final int MAX_ART_PX = 256;

    private Tiles() { }

    static void bind(View tile, AppEntry e, PackageManager pm) {
        ImageView banner = tile.findViewById(R.id.banner);
        ImageView icon = tile.findViewById(R.id.icon);
        TextView label = tile.findViewById(R.id.label);
        View frame = tile.findViewById(R.id.art_frame);
        frame.setClipToOutline(true);

        label.setText(e.label);
        // Art loading must never crash the launcher — bad icons or low memory just fall back to text.
        try {
            if (e.hasBanner(pm)) {
                banner.setImageDrawable(downsample(e.newArt(pm, tile.getResources()), tile.getResources()));
                banner.setVisibility(View.VISIBLE);
                icon.setImageDrawable(null);
                icon.setVisibility(View.GONE);
            } else {
                icon.setImageDrawable(downsample(e.newArt(pm, tile.getResources()), tile.getResources()));
                icon.setVisibility(View.VISIBLE);
                banner.setImageDrawable(null);
                banner.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(null);
            icon.setVisibility(View.GONE);
        }
    }

    /** Scale an oversized bitmap drawable down to the cap; leave vectors and small art alone. */
    private static Drawable downsample(Drawable d, Resources res) {
        if (!(d instanceof BitmapDrawable)) return d;
        try {
            Bitmap bmp = ((BitmapDrawable) d).getBitmap();
            if (bmp == null) return d;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int max = Math.max(w, h);
            if (max <= MAX_ART_PX) return d;
            float scale = (float) MAX_ART_PX / max;
            Bitmap small = Bitmap.createScaledBitmap(bmp, Math.max(1, (int) (w * scale)),
                    Math.max(1, (int) (h * scale)), true);
            return new BitmapDrawable(res, small);
        } catch (Throwable t) {
            return d;
        }
    }

    static void lift(View v, boolean up) {
        float s = up ? FOCUS_SCALE : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(up ? 12f : 0f).setDuration(140).start();
    }

    static final View.OnFocusChangeListener LIFT_ON_FOCUS = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            lift(v, hasFocus);
        }
    };

    // ---- macOS Dock-style magnification for a horizontal row --------------------
    // The focused tile swells most and its neighbours bulge less, falling off with
    // distance, so focus moves along the row like a wave under a Dock.

    // Focused tile blows up big (PS5-style); the two nearest neighbours bulge a little.
    private static final float[] DOCK_SCALE = {1.32f, 1.10f, 1.04f};

    /** A focus listener that magnifies the whole row around whatever tile has focus. */
    static View.OnFocusChangeListener dock(final android.widget.LinearLayout row) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                row.post(new Runnable() { @Override public void run() { magnify(row); } });
            }
        };
    }

    private static void magnify(android.widget.LinearLayout row) {
        View focused = row.findFocus();
        int fi = -1;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (contains(row.getChildAt(i), focused)) { fi = i; break; }
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            float scale = 1f, z = 0f;
            if (fi >= 0) {
                int dist = Math.abs(i - fi);
                scale = dist < DOCK_SCALE.length ? DOCK_SCALE[dist] : 1f;
                z = dist == 0 ? 30f : (dist == 1 ? 8f : 0f);
            }
            // Elevation makes the blown-up tile draw ABOVE its neighbours, not behind them.
            c.setElevation(z);
            c.animate().scaleX(scale).scaleY(scale).translationZ(z)
                    .setDuration(190).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                    .start();
        }
    }

    private static boolean contains(View parent, View v) {
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }
}
