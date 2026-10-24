package com.wowsir.wowos;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Display;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Everything plugged into (or coming out of) the box, in one place. */
public class ConnectionsActivity extends Activity {

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IntentFilter usb = new IntentFilter();
        usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(changed, usb);
        IntentFilter media = new IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_REMOVED);
        media.addDataScheme("file");
        registerReceiver(changed, media);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(changed); } catch (Exception ignored) { }
    }

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            content.postDelayed(new Runnable() { @Override public void run() { build(); } }, 500);
        }
    };

    private void build() {
        Ui.page(this, "Connections", "Ports, inputs and devices on this box.");
        content = Ui.content(this);
        hdmiOut();
        tvInputs();
        usbDevices();
        storage();
        network();
        bluetooth();
    }

    // ------------------------------------------------------------------ sections

    private void hdmiOut() {
        LinearLayout c = Ui.card(this, content, R.drawable.ic_hdmi, "HDMI output");
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode m = d.getMode();
        Ui.line(this, c, "Sending " + m.getPhysicalWidth() + " × " + m.getPhysicalHeight()
                + " at " + Math.round(m.getRefreshRate()) + " Hz to your TV");
        Display.HdrCapabilities hdr = d.getHdrCapabilities();
        int[] types = hdr == null ? null : hdr.getSupportedHdrTypes();
        Ui.line(this, c, types == null || types.length == 0 ? "HDR: not supported by this TV/box pair" : "HDR: supported");
    }

    private void tvInputs() {
        LinearLayout c = Ui.card(this, content, R.drawable.ic_tv, "TV inputs");
        List<TvInputInfo> inputs = Collections.emptyList();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) inputs = tim.getTvInputList();
        } catch (Exception ignored) { }

        if (inputs.isEmpty()) {
            Ui.line(this, c, "This box has no HDMI-in or antenna input.");
            Ui.line(this, c, "For antenna TV or another HDMI device, press Source on the Samsung remote.");
            return;
        }
        for (final TvInputInfo in : inputs) {
            String label = String.valueOf(in.loadLabel(this));
            Ui.row(this, c, iconFor(in.getType()), label, typeName(in.getType()), "Open", new Runnable() {
                @Override public void run() { openInput(in); }
            });
        }
        Ui.line(this, c, "Inputs come from the TV firmware; if one shows a blank screen, use the Samsung's Source button.");
    }

    private void openInput(TvInputInfo in) {
        android.net.Uri uri = in.isPassthroughInput()
                ? TvContract.buildChannelUriForPassthroughInput(in.getId())
                : TvContract.buildChannelsUriForInput(in.getId());
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception ignored) { }
        Toast.makeText(this, "No TV app on this box can open that input", Toast.LENGTH_LONG).show();
    }

    private void usbDevices() {
        LinearLayout c = Ui.card(this, content, R.drawable.ic_usb, "USB devices");
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        java.util.Map<String, UsbDevice> devices = um == null ? null : um.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            Ui.line(this, c, "Nothing plugged in");
            return;
        }
        for (UsbDevice dev : devices.values()) {
            Ui.row(this, c, R.drawable.ic_usb, usbName(dev), usbKind(dev),
                    String.format(Locale.ROOT, "%04X:%04X", dev.getVendorId(), dev.getProductId()), null);
        }
    }

    private void storage() {
        LinearLayout c = Ui.card(this, content, R.drawable.ic_folder, "Storage");
        File internal = Environment.getExternalStorageDirectory();
        storageRow(c, "Internal storage", internal, R.drawable.ic_phone_storage);
        boolean any = false;
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!v.isRemovable()) continue;
                any = true;
                String st = v.getState();
                if (Environment.MEDIA_MOUNTED.equals(st) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(st)) {
                    File dir = volumeDir(v);
                    if (dir != null) storageRow(c, v.getDescription(this), dir, R.drawable.ic_usb);
                } else {
                    Ui.row(this, c, R.drawable.ic_usb, v.getDescription(this), stateText(st), null, null);
                }
            }
        }
        if (!any) Ui.line(this, c, "No USB disk or pendrive connected");
    }

    private void storageRow(LinearLayout c, String name, File dir, int icon) {
        String detail;
        try {
            StatFs fs = new StatFs(dir.getPath());
            detail = Formatter.formatShortFileSize(this, fs.getAvailableBytes()) + " free of "
                    + Formatter.formatShortFileSize(this, fs.getTotalBytes());
        } catch (Exception e) {
            detail = dir.getPath();
        }
        Ui.row(this, c, icon, name, detail, "Open", new Runnable() {
            @Override public void run() {
                startActivity(new Intent(ConnectionsActivity.this, FilesActivity.class));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void network() {
        LinearLayout c = Ui.card(this, content, R.drawable.ic_net, "Network");
        String state = "Offline";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            state = ni.getType() == ConnectivityManager.TYPE_ETHERNET ? "Ethernet (cable)" : "Wi-Fi";
        }
        Ui.row(this, c, R.drawable.ic_wifi, "Connection", localIps(), state, new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception e) {
                    Toast.makeText(ConnectionsActivity.this, "Network settings aren't available", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void bluetooth() {
        LinearLayout c = Ui.card(this, content, R.drawable.ic_bt, "Bluetooth");
        BluetoothAdapter bt = null;
        try { bt = BluetoothAdapter.getDefaultAdapter(); } catch (Exception ignored) { }
        Ui.line(this, c, bt == null
                ? "Not available on this box. A 2.4 GHz keyboard or mouse with a USB receiver works instead."
                : "Bluetooth adapter found");
    }

    // ------------------------------------------------------------------ helpers

    private static String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (sb.length() > 0) sb.append("  ·  ");
                    sb.append(ni.getName()).append(" ").append(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return sb.length() == 0 ? "No IP address" : "IP " + sb;
    }

    private static String usbName(UsbDevice d) {
        String name = null;
        try { name = d.getProductName(); } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = "USB device";
        String maker = null;
        try { maker = d.getManufacturerName(); } catch (Exception ignored) { }
        return maker == null || maker.trim().isEmpty() ? name.trim() : name.trim() + " (" + maker.trim() + ")";
    }

    private static String usbKind(UsbDevice d) {
        int cls = d.getDeviceClass();
        if (cls == 0 && d.getInterfaceCount() > 0) cls = d.getInterface(0).getInterfaceClass();
        switch (cls) {
            case 1: return "Audio";
            case 3: return "Keyboard, mouse or remote";
            case 8: return "Storage (disk or pendrive)";
            case 9: return "USB hub";
            case 14: return "Camera";
            case 0xE0: return "Wireless adapter";
            default: return "Other device";
        }
    }

    private static String stateText(String st) {
        if (Environment.MEDIA_CHECKING.equals(st)) return "Checking… (large disks can take a few minutes)";
        if (Environment.MEDIA_UNMOUNTABLE.equals(st)) return "Can't read this disk's format";
        if (Environment.MEDIA_UNMOUNTED.equals(st)) return "Not mounted";
        return st;
    }

    private static int iconFor(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI:
            case TvInputInfo.TYPE_DVI:
            case TvInputInfo.TYPE_DISPLAY_PORT:
                return R.drawable.ic_hdmi;
            case TvInputInfo.TYPE_TUNER:
                return R.drawable.ic_tv;
            default:
                return R.drawable.ic_plug;
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case TvInputInfo.TYPE_TUNER: return "Antenna / tuner";
            case TvInputInfo.TYPE_HDMI: return "HDMI";
            case TvInputInfo.TYPE_COMPOSITE: return "AV (yellow/red/white)";
            case TvInputInfo.TYPE_COMPONENT: return "Component";
            case TvInputInfo.TYPE_VGA: return "VGA";
            case TvInputInfo.TYPE_SCART: return "SCART";
            case TvInputInfo.TYPE_SVIDEO: return "S-Video";
            case TvInputInfo.TYPE_DVI: return "DVI";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "DisplayPort";
            default: return "Other input";
        }
    }

    private static File volumeDir(StorageVolume v) {
        if (android.os.Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            return (File) StorageVolume.class.getMethod("getPathFile").invoke(v);
        } catch (Exception ignored) { }
        String uuid = v.getUuid();
        return uuid == null ? null : new File("/storage/" + uuid);
    }
}
