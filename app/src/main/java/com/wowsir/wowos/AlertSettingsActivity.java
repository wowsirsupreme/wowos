package com.wowsir.wowos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;

/** Choose how alerts appear and which connection alerts to show. */
public class AlertSettingsActivity extends Activity {

    private View overlayRow;
    private View alarmRow;
    private View reminderRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.page(this, "Alerts", "Choose how alarms, reminders and connection alerts appear.");
        LinearLayout content = Ui.content(this);

        LinearLayout look = Ui.card(this, content, R.drawable.ic_alerts, "Appearance");
        overlayRow = Ui.row(this, look, R.drawable.ic_tv, "Show alerts over other apps",
                "Needed for banners while you watch something", "", new Runnable() {
                    @Override public void run() { askOverlay(); }
                });
        alarmRow = Ui.row(this, look, R.drawable.ic_alarm, "Alarms appear as", null, "", new Runnable() {
            @Override public void run() { toggleStyle(Alerts.K_ALARM_STYLE, Alerts.STYLE_FULL); }
        });
        reminderRow = Ui.row(this, look, R.drawable.ic_calendar, "Reminders appear as", null, "", new Runnable() {
            @Override public void run() { toggleStyle(Alerts.K_REMINDER_STYLE, Alerts.STYLE_BANNER); }
        });
        toggleRow(look, R.drawable.ic_bell, "Sound for banners", Alerts.K_SOUND);

        LinearLayout conn = Ui.card(this, content, R.drawable.ic_plug, "Connection alerts");
        toggleRow(conn, R.drawable.ic_usb, "USB devices (keyboards, dongles…)", Alerts.K_USB);
        toggleRow(conn, R.drawable.ic_folder, "USB disks and pendrives", Alerts.K_STORAGE);
        toggleRow(conn, R.drawable.ic_wifi, "Network connected / lost", Alerts.K_NETWORK);

        LinearLayout test = Ui.card(this, content, R.drawable.ic_star, "Try it");
        Ui.row(this, test, R.drawable.ic_alerts, "Show a test banner", null, null, new Runnable() {
            @Override public void run() {
                Alerts.info(AlertSettingsActivity.this, R.drawable.ic_star, "This is a WOW OS alert",
                        Alerts.canOverlay(AlertSettingsActivity.this) ? "It shows over any app" : "Allow \"over other apps\" for banners", 5000);
            }
        });
        Ui.row(this, test, R.drawable.ic_alarm, "Test alarm in 15 seconds", "Open another app to see it appear over it", null, new Runnable() {
            @Override public void run() {
                Plans.testIn(AlertSettingsActivity.this, 15);
                Alerts.info(AlertSettingsActivity.this, R.drawable.ic_alarm, "Test alarm set", "Rings in 15 seconds", 3000);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        Ui.setValue(overlayRow, Alerts.canOverlay(this) ? "Allowed" : "Not allowed");
        Ui.setValue(alarmRow, label(Alerts.alarmStyle(this)));
        Ui.setValue(reminderRow, label(Alerts.reminderStyle(this)));
        boolean needsOverlay = !Alerts.canOverlay(this)
                && (Alerts.STYLE_BANNER.equals(Alerts.alarmStyle(this)) || Alerts.STYLE_BANNER.equals(Alerts.reminderStyle(this)));
        Ui.setDetail(alarmRow, needsOverlay ? "Banners need \"over other apps\"; full screen is used until allowed" : null);
    }

    private static String label(String style) {
        return Alerts.STYLE_BANNER.equals(style) ? "Banner over apps" : "Full screen";
    }

    private void toggleStyle(String key, String def) {
        String cur = Alerts.prefs(this).getString(key, def);
        String next = Alerts.STYLE_BANNER.equals(cur) ? Alerts.STYLE_FULL : Alerts.STYLE_BANNER;
        Alerts.prefs(this).edit().putString(key, next).apply();
        refresh();
    }

    private void toggleRow(LinearLayout parent, int icon, String name, final String key) {
        final View[] row = new View[1];
        row[0] = Ui.row(this, parent, icon, name, null, Alerts.on(this, key) ? "On" : "Off", new Runnable() {
            @Override public void run() {
                boolean v = !Alerts.on(AlertSettingsActivity.this, key);
                Alerts.prefs(AlertSettingsActivity.this).edit().putBoolean(key, v).apply();
                Ui.setValue(row[0], v ? "On" : "Off");
            }
        });
    }

    private void askOverlay() {
        if (Alerts.canOverlay(this)) {
            Alerts.info(this, R.drawable.ic_star, "Already allowed", "Banners will show over apps", 3000);
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try {
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception ignored) { }
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Allow from your Mac")
                .setMessage("This box hides the setting. Run this once from the Mac terminal:\n\n"
                        + "adb shell appops set com.wowsir.wowos SYSTEM_ALERT_WINDOW allow")
                .setPositiveButton("OK", null).create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawableResource(R.drawable.dialog_glass);
    }
}
