#!/bin/bash
# Install WOW OS on the TV box and make it the home screen.
# Usage:  ./install-wowos.sh [box-ip]     (default 172.20.10.3)
set -e
IP="${1:-172.20.10.3}"
DEV="$IP:5555"
APK="${APK:-WOWOS.apk}"

adb connect "$DEV"
adb -s "$DEV" install -r "$APK"
adb -s "$DEV" shell pm grant com.wowsir.wowos android.permission.READ_EXTERNAL_STORAGE || true
adb -s "$DEV" shell pm grant com.wowsir.wowos android.permission.WRITE_EXTERNAL_STORAGE || true
adb -s "$DEV" shell appops set com.wowsir.wowos SYSTEM_ALERT_WINDOW allow || true
adb -s "$DEV" shell ime enable com.wowsir.wowos/.WowKeyboard || true
adb -s "$DEV" shell ime set com.wowsir.wowos/.WowKeyboard || true
adb -s "$DEV" shell cmd package set-home-activity com.wowsir.wowos/.HomeActivity
adb -s "$DEV" shell input keyevent KEYCODE_HOME
echo "WOW OS installed and set as home screen."
