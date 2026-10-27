#!/usr/bin/env bash
# The web app inside the Android app, on an emulator (android.yml, job emulator-web): install the live
# test build (debuggable, with the bundled static export), open it, then run the web self-test in its
# WebView over DevTools (webview-selftest.mjs). Usage: emulator-web-selftest.sh <run number>
set -euo pipefail
run=$1
P=com.authorityclosers.salesxray
mkdir -p selftest

# An Android 13 emulator from the runner's own SDK (only GitHub-owned actions are allowed here).
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
image="system-images;android-33;google_apis;x86_64"
# "yes" ends on a closed pipe (SIGPIPE) once the licences are accepted: not a failure.
set +o pipefail
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$image" >/dev/null
set -o pipefail
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n sx -k "$image" -d pixel_6 --force -p "$ANDROID_AVD_HOME/sx.avd"
"$sdk/emulator/emulator" -avd sx -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none >emulator.log 2>&1 &
export PATH="$sdk/platform-tools:$PATH"
booted=0
for _ in $(seq 1 100); do
  state=$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
  if [ "$state" = "1" ]; then booted=1; break; fi
  sleep 5
done
if [ "$booted" != 1 ]; then
  echo "The emulator did not boot in time."
  tail -60 emulator.log
  exit 1
fi
echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r'), WebView $(adb shell dumpsys package com.google.android.webview | grep -m1 versionName | tr -d ' \r')"
adb install "dist/sales-xray-android-0.3.${run}-test.apk"
adb shell pm grant "$P" android.permission.POST_NOTIFICATIONS || true
adb shell monkey -p "$P" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 30
adb exec-out screencap -p > selftest/android-bundled.png || true
socket=$(adb shell cat /proc/net/unix | grep -a -o 'webview_devtools_remote_[0-9]*' | head -1)
test -n "$socket" || { echo "No WebView DevTools socket: is this a debug build?"; exit 1; }
adb forward tcp:9222 "localabstract:$socket"
node scripts/webview-selftest.mjs http://127.0.0.1:9222/json selftest/android-selftest.json
adb exec-out screencap -p > selftest/android-after-selftest.png || true
