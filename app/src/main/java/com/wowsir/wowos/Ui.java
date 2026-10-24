package com.wowsir.wowos;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small builders for the glass card screens (settings, connections). */
final class Ui {
    private Ui() { }

    static void page(Activity a, String title, String sub) {
        a.setContentView(R.layout.activity_panel);
        ((TextView) a.findViewById(R.id.page_title)).setText(title);
        TextView s = a.findViewById(R.id.page_sub);
        if (sub == null) s.setVisibility(View.GONE); else s.setText(sub);
    }

    static LinearLayout content(Activity a) {
        return a.findViewById(R.id.content);
    }

    /** A glass card with a heading. Returns the card to add rows and lines to. */
    static LinearLayout card(Activity a, ViewGroup parent, int icon, String title) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.glass_panel);
        int p = dp(a, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 14);
        parent.addView(card, lp);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(a, 4), 0, 0, dp(a, 6));
        ImageView iv = new ImageView(a);
        iv.setImageResource(icon);
        head.addView(iv, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(a.getColor(R.color.text));
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(dp(a, 12), 0, 0, 0);
        head.addView(t);
        card.addView(head);
        return card;
    }

    static TextView line(Activity a, ViewGroup card, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(a.getColor(R.color.text_dim));
        t.setPadding(dp(a, 38), dp(a, 4), dp(a, 8), dp(a, 4));
        card.addView(t);
        return t;
    }

    /** A focusable row: icon, name, optional detail, value on the right. */
    static View row(Activity a, ViewGroup parent, int icon, String name, String detail, String value,
                    final Runnable onClick) {
        View r = LayoutInflater.from(a).inflate(R.layout.item_setting, parent, false);
        ((ImageView) r.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) r.findViewById(R.id.name)).setText(name);
        setDetail(r, detail);
        setValue(r, value);
        if (onClick != null) {
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onClick.run(); }
            });
        } else {
            r.setFocusable(false);
        }
        parent.addView(r);
        return r;
    }

    static void setValue(View row, String value) {
        ((TextView) row.findViewById(R.id.value)).setText(value == null ? "" : value);
    }

    static void setDetail(View row, String detail) {
        TextView m = row.findViewById(R.id.meta);
        if (detail == null || detail.isEmpty()) {
            m.setVisibility(View.GONE);
        } else {
            m.setVisibility(View.VISIBLE);
            m.setText(detail);
        }
    }

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
