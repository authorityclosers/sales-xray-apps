#!/usr/bin/env bash
# The Sales Xray Android app (the published APK, which opens production) on an
# emulator of a given Android version and screen: start it, let the page load, keep
# screenshots of the app, the signed-out page and /apps (owner order, 19 Oct).
#   android-emulator.sh <api level> <device id, e.g. pixel_6 | pixel_tablet | small_phone>
set -euo pipefail
api=$1
device=$2
OUT=${OUT:-shots}
mkdir -p "$OUT"
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
image="system-images;android-${api};google_apis;x86_64"
set +o pipefail
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$image" >/dev/null
set -o pipefail
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
case "$device" in
  small_phone) profile=small_phone ;;
  pixel_tablet) profile=pixel_tablet ;;
  *) profile=$device ;;
esac
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n m -k "$image" -d "$profile" --force -p "$ANDROID_AVD_HOME/m.avd" >/dev/null
"$sdk/emulator/emulator" -avd m -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio >emulator.log 2>&1 &
export PATH="$sdk/platform-tools:$PATH"
for _ in $(seq 1 120); do
  state=$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
  [ "$state" = "1" ] && break
  sleep 5
done
[ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ] || { tail -40 emulator.log; exit 1; }
adb shell wm size
curl -sSfL -o app.apk https://github.com/authorityclosers/sales-xray-apps/releases/latest/download/sales-xray-android.apk
adb install app.apk
adb shell monkey -p com.authorityclosers.salesxray -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 25
tag="android-${api}-${device}"
adb exec-out screencap -p > "$OUT/$tag-app.png"
sleep 10
adb exec-out screencap -p > "$OUT/$tag-app-later.png"
# The app must still be in front (it hasn't crashed).
adb shell dumpsys activity activities | grep -q "topResumedActivity.*com.authorityclosers.salesxray"
# And it must show Sales Xray, not Cloudflare's bot check.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb exec-out cat /sdcard/ui.xml > "$OUT/$tag-screen.xml"
if grep -qiE "Performing security verification|Verify you are human" "$OUT/$tag-screen.xml"; then
  echo "FAIL $tag: Cloudflare's bot check stopped the app (see README.md)"
  exit 1
fi
echo "ok $tag"
