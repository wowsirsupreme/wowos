package com.wowsir.wowos;

import android.content.pm.PackageManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Fills an item_app tile and gives it the glass focus animation. */
final class Tiles {
    static final float FOCUS_SCALE = 1.06f;

    private Tiles() { }

    static void bind(View tile, AppEntry e, PackageManager pm) {
        ImageView banner = tile.findViewById(R.id.banner);
        ImageView icon = tile.findViewById(R.id.icon);
        TextView label = tile.findViewById(R.id.label);
        View frame = tile.findViewById(R.id.art_frame);
        frame.setClipToOutline(true);

        label.setText(e.label);
        if (e.hasBanner(pm)) {
            banner.setImageDrawable(e.newArt(pm, tile.getResources()));
            banner.setVisibility(View.VISIBLE);
            icon.setImageDrawable(null);
            icon.setVisibility(View.GONE);
        } else {
            icon.setImageDrawable(e.newArt(pm, tile.getResources()));
            icon.setVisibility(View.VISIBLE);
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
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
