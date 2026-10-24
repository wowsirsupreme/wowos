# WOW OS

A lightweight home screen (launcher) for Android TV boxes, made for the HiSilicon Hi3751 box (Android 9, 512 MB RAM). Liquid-glass look built from translucent layers, so it needs no blur effects and no extra libraries.

## What it does

- Header with clock, date, network status and USB disk status (shows "USB checking…" while a slow disk is being scanned, then the disk name when it is ready).
- Quick row: Settings, Network, Files, Mirror, Play Store, Browser, Hidden apps.
- Favourites row you can pin, reorder and unpin.
- All apps in two rows that scroll left to right, with TV banners where apps provide them.
- WOW Files (built in, also listed as its own app): internal storage and USB disks with free space, Downloads/Movies/Music/Pictures shortcuts, open videos, music, photos and documents, install APKs, copy, move, rename, delete and new folder. Hold OK or press Menu on a file for options.
- Hold OK (or press Menu) on any app: pin/unpin, move left/right, hide, app info.
- Back never leaves the home screen. Pressing Home again jumps to the top.

## Build

Every push to main builds `WOWOS.apk` with GitHub Actions and publishes it as the latest release. Download it on the Mac with:

    curl -L -o WOWOS.apk https://github.com/wowsirsupreme/wowos/releases/latest/download/WOWOS.apk

## Install from the Mac

Put `WOWOS.apk` next to `install-wowos.sh`, then:

    ./install-wowos.sh 172.20.10.3

If the box changes IP, pass the new one. The script also grants storage access so WOW Files works straight away. If the home screen does not switch, press Home on the remote, pick WOW OS and choose Always.

## Remove the old SmartTV4K skin (optional, reversible)

    adb shell pm disable-user --user 0 com.vstv.pisces.wave.launcher

Undo:

    adb shell pm enable com.vstv.pisces.wave.launcher

## Uninstall WOW OS

    adb uninstall com.wowsir.wowos

The box falls back to its previous launcher. Re-enable the stock one first if you disabled it.

## Note on USB disks

On Android 9, ordinary apps can read USB disks but not change them. WOW Files can open and copy from a USB disk, but copying, renaming or deleting on the disk itself will say it is read-only. Copy files to Internal storage to work with them.
