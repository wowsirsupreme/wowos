#!/bin/bash
# Install WOW OS as a SYSTEM app so it can switch TV inputs (HDMI/AV/antenna).
# This TV is a HiSilicon Android TV; /system must be writable (adb root + remount).
# Usage:  ./install-system.sh [tv-ip]       (default 172.20.10.3)
#
# Reversible: see "Undo" at the bottom.
set -e
IP="${1:-172.20.10.3}"
DEV="$IP:5555"
APK="${APK:-WOWOS.apk}"
PRIV="/system/priv-app/WOWOS"

[ -f "$APK" ] || { echo "Put $APK next to this script first."; exit 1; }

adb connect "$DEV"
echo "Requesting root and a writable /system..."
adb -s "$DEV" root ; sleep 2
adb connect "$DEV"
adb -s "$DEV" remount

# Remove any normal-user install first so the system copy is the only one.
adb -s "$DEV" uninstall com.wowsir.wowos >/dev/null 2>&1 || true

echo "Copying WOW OS into $PRIV ..."
adb -s "$DEV" shell "mkdir -p $PRIV"
adb -s "$DEV" push "$APK" "$PRIV/WOWOS.apk"
adb -s "$DEV" shell "chmod 644 $PRIV/WOWOS.apk"
adb -s "$DEV" shell "chmod 755 $PRIV"

echo "Rebooting so the TV grants system permissions..."
adb -s "$DEV" reboot
echo
echo "Done. The TV is rebooting (takes ~30-60s)."
echo "After it comes back:"
echo "  - WOW OS will have an INPUTS row (TV, HDMI 1, HDMI 2, AV)."
echo "  - Set it as home:  adb connect $DEV && adb shell cmd package set-home-activity com.wowsir.wowos/.HomeActivity"
echo
echo "Undo (remove the system app):"
echo "  adb root && adb remount && adb shell rm -rf $PRIV && adb reboot"
