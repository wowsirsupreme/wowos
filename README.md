# WOW OS

A lightweight home screen (launcher) for HiSilicon Android TVs (this one is a Samsung-branded Hi3751 panel, Android 9, 512 MB RAM). The TV mainboard runs Android directly; there is no separate box. Liquid-glass look built from translucent layers, so it needs no blur effects and no extra libraries.

## What it does

Home screen is a PS5-style layout: a row of app tiles along the top, and the focused app's banner art fills the screen with a big title and an Open button. Settings and a menu (Files, mirror, planner, connections, inputs, alerts, personalise, hidden apps, running apps) sit top-right.


- Header with clock, date, network status and USB disk status (shows "USB checking…" while a slow disk is being scanned, then the disk name when it is ready).
- Quick row: Settings, Network, Files, Mirror, Play Store, Browser, Hidden apps.
- Favourites row you can pin, reorder and unpin.
- All apps in two rows that scroll left to right, with TV banners where apps provide them.
- WOW Files (built in, also listed as its own app): internal storage and USB disks with free space, Downloads/Movies/Music/Pictures shortcuts, open videos, music, photos and documents, install APKs, copy, move, rename, delete and new folder. Hold OK or press Menu on a file for options.
- Hold OK (or press Menu) on any app: pin/unpin, move left/right, hide, app info.
- Time-aware greeting with rotating original messages on courage and letting your light shine. Add your own quotes under Personalise; they stay on the box.
- Life Planner: alarms (ring until dismissed, snooze 10 min) and reminders (short chime), once or repeating on chosen days, with a Next up chip on the home screen.
- Alerts over any app: glass banners for reminders, USB devices, USB disks (connected, checking, ready, removed) and network changes. Choose banner or full screen and switch each type on or off under Alerts.
- Inputs: switch between HDMI 1/2, AV and antenna (TV) from the home screen, when installed as a system app (see below). Otherwise the remote's Source button is used.
- Connections: HDMI output mode, inputs, USB devices, storage and network/IP.
- WOW Keyboard: a glass on-screen keyboard made for the remote, with a gliding highlight. Up from the top row closes it.
- Idle screen: after 90s unused, an original night animation (drifting crows and feathers, watching red eyes) with the clock — or your own photos (a slideshow) or a looping video. In WOW Files, hold OK on a photo/video and choose "Use on idle screen"; switch what it shows under Personalise. Any key wakes it.
- Multitask switcher: a Recents tile (and the remote's App-switch key) lists running apps and jumps back to the exact task. Resuming the live task needs the system install.
- Back never leaves the home screen. Pressing Home again jumps to the top.

## Build

Every push to main builds `WOWOS.apk` with GitHub Actions and publishes it as the latest release. Download it on the Mac with:

    curl -L -o WOWOS.apk https://github.com/wowsirsupreme/wowos/releases/latest/download/WOWOS.apk

## Install from the Mac

Put `WOWOS.apk` next to `install-wowos.sh`, then:

    ./install-wowos.sh 172.20.10.3

If the box changes IP, pass the new one. The script also grants storage access, allows alerts over other apps, and switches on WOW Keyboard. If the home screen does not switch, press Home on the remote, pick WOW OS and choose Always.

## Remove the old SmartTV4K skin (optional, reversible)

    adb shell pm disable-user --user 0 com.vstv.pisces.wave.launcher

Undo:

    adb shell pm enable com.vstv.pisces.wave.launcher

## Uninstall WOW OS

    adb uninstall com.wowsir.wowos

The box falls back to its previous launcher. Re-enable the stock one first if you disabled it.

## Switching TV inputs (system install)

The inputs (HDMI 1/2, AV, antenna) are controlled by the TV's own HiSilicon service, which only accepts commands from a system app. Install WOW OS into `/system` once, and it can switch inputs itself:

    curl -L -o WOWOS.apk https://github.com/wowsirsupreme/wowos/releases/latest/download/WOWOS.apk
    curl -L -o install-system.sh https://raw.githubusercontent.com/wowsirsupreme/wowos/main/install-system.sh
    chmod +x install-system.sh && ./install-system.sh 172.20.10.3

This needs `adb root` and a writable `/system` (both work on this TV). The TV reboots once, after which an INPUTS row appears. It is reversible; the script prints the undo command.

A normal install (install-wowos.sh) gives you everything except input switching.

## Note on USB disks

On Android 9, ordinary apps can read USB disks but not change them. WOW Files can open and copy from a USB disk, but copying, renaming or deleting on the disk itself will say it is read-only. Copy files to Internal storage to work with them.
