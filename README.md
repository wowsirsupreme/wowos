# WOW OS

A lightweight home screen (launcher) for Android TV boxes, made for the HiSilicon Hi3751 box (Android 9, 512 MB RAM). Liquid-glass look built from translucent layers, so it needs no blur effects and no extra libraries.

## What it does

- Header with clock, date, network status and USB disk status (shows "USB checking…" while a slow disk is being scanned, then the disk name when it is ready).
- Quick row: Settings, Network, Files, Mirror, Play Store, Browser, Hidden apps.
- Favourites row you can pin, reorder and unpin.
- All apps grid with TV banners where apps provide them.
- Hold OK (or press Menu) on any app: pin/unpin, move left/right, hide, app info.
- Back never leaves the home screen. Pressing Home again jumps to the top.

## Build

Push this folder to a GitHub repo. GitHub Actions builds `WOWOS.apk` automatically; download it from the run's Artifacts. You can also open the folder in Android Studio and build there.

## Install from the Mac

Put `WOWOS.apk` next to `install-wowos.sh`, then:

    ./install-wowos.sh 172.20.10.3

If the box changes IP, pass the new one. If the home screen does not switch, press Home on the remote, pick WOW OS and choose Always.

## Remove the old SmartTV4K skin (optional, reversible)

    adb shell pm disable-user --user 0 com.vstv.pisces.wave.launcher

Undo:

    adb shell pm enable com.vstv.pisces.wave.launcher

## Uninstall WOW OS

    adb uninstall com.wowsir.wowos

The box falls back to its previous launcher. Re-enable the stock one first if you disabled it.
