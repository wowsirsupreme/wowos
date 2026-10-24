package com.wowsir.wowos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.StatFs;
import android.text.format.Formatter;

/** Turns USB and disk events into glass alerts (works even when WOW OS isn't on screen). */
public class ConnectionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a) || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean storage = d != null && isStorage(d);
            boolean attached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a);
            if (storage) {
                // Disks get their own, more useful alerts from the media events below.
                if (attached && Alerts.on(c, Alerts.K_STORAGE)) {
                    Alerts.info(c, R.drawable.ic_usb, "USB disk connected", "Getting it ready…", 5000);
                }
                return;
            }
            if (!Alerts.on(c, Alerts.K_USB)) return;
            Alerts.info(c, R.drawable.ic_usb, attached ? "USB device connected" : "USB device removed",
                    d == null ? null : name(d), 5000);
            return;
        }

        if (!Alerts.on(c, Alerts.K_STORAGE) || !isRemovable(i.getData())) return;
        switch (a) {
            case Intent.ACTION_MEDIA_CHECKING:
                Alerts.info(c, R.drawable.ic_usb, "Checking USB disk…", "Large disks can take a few minutes", 8000);
                break;
            case Intent.ACTION_MEDIA_MOUNTED:
                Alerts.info(c, R.drawable.ic_folder, "USB disk ready", freeText(c, i.getData()) + " · open it in WOW Files", 6000);
                break;
            case Intent.ACTION_MEDIA_UNMOUNTABLE:
                Alerts.info(c, R.drawable.ic_usb, "Can't read this disk", "Its format isn't supported by this box", 8000);
                break;
            case Intent.ACTION_MEDIA_BAD_REMOVAL:
                Alerts.info(c, R.drawable.ic_usb, "Disk unplugged without ejecting",
                        "Eject first next time to avoid a long check", 8000);
                break;
            case Intent.ACTION_MEDIA_REMOVED:
            case Intent.ACTION_MEDIA_EJECT:
                Alerts.info(c, R.drawable.ic_usb, "USB disk removed", null, 4000);
                break;
            default:
                break;
        }
    }

    /** Internal storage lives under /storage/emulated; anything else under /storage is removable. */
    private static boolean isRemovable(Uri data) {
        String p = data == null ? null : data.getPath();
        return p != null && !p.startsWith("/storage/emulated") && !p.startsWith("/storage/self");
    }

    private static String freeText(Context c, Uri data) {
        try {
            StatFs fs = new StatFs(data.getPath());
            return Formatter.formatShortFileSize(c, fs.getAvailableBytes()) + " free";
        } catch (Exception e) {
            return "Mounted";
        }
    }

    private static boolean isStorage(UsbDevice d) {
        if (d.getDeviceClass() == 8) return true;
        for (int k = 0; k < d.getInterfaceCount(); k++) if (d.getInterface(k).getInterfaceClass() == 8) return true;
        return false;
    }

    private static String name(UsbDevice d) {
        String n = null;
        try { n = d.getProductName(); } catch (Exception ignored) { }
        return n == null || n.trim().isEmpty() ? "Unknown device" : n.trim();
    }
}
