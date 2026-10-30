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
}
