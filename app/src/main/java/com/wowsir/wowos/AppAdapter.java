package com.wowsir.wowos;

import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.ArrayList;
import java.util.List;

final class AppAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final PackageManager pm;
    private final List<AppEntry> items = new ArrayList<>();

    AppAdapter(LayoutInflater inflater, PackageManager pm) {
        this.inflater = inflater;
        this.pm = pm;
    }

    void setItems(List<AppEntry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public AppEntry getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView : inflater.inflate(R.layout.item_app, parent, false);
        // Recycled views must not keep the "lifted" look of another app.
        v.setSelected(false);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationZ(0f);
        Tiles.bind(v, items.get(position), pm);
        return v;
    }
}
