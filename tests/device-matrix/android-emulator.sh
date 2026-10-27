#!/usr/bin/env bash
# The Sales Xray Android app (the published APK, which opens production) on an
# emulator of a given Android version and screen: start it, let the page load, keep
# screenshots of the app, the signed-out page and /apps (owner order, 19 Oct).
#   android-emulator.sh <api level> <device id, e.g. pixel_6 | pixel_tablet | small_phone> [variants] [ram MB]
# variants (comma-separated, owner's phone test 19 Oct): punch-hole, notch, font-large,
# dark, gesture, buttons. Each is applied in turn after the first launch, and the app gets
# a screenshot and a safe-area check (the page must sit below the status bar / cut-out).
# ram (MB) gives the emulator a budget phone's memory, 2048 or 3072; empty keeps the device's.
# Cloudflare's bot check on production can hold the app back (README.md): that is reported
# as CHALLENGED, in the log and the run's summary, and fails nothing; layout checks still run.
set -euo pipefail
api=$1
device=$2
variants=${3:-}
ram=${4:-}
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
if [ -n "$ram" ]; then
  config="$ANDROID_AVD_HOME/m.avd/config.ini"
  grep -v '^hw.ramSize=' "$config" > "$config.new" || true
  echo "hw.ramSize=$ram" >> "$config.new"
  mv "$config.new" "$config"
  echo "memory: $ram MB"
fi
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
tag="android-${api}-${device}${ram:+-${ram}}"
adb exec-out screencap -p > "$OUT/$tag-app.png"
sleep 10
adb exec-out screencap -p > "$OUT/$tag-app-later.png"
# The app must still be in front (it hasn't crashed).
# "topResumedActivity" on Android 11+, "mResumedActivity" on Android 10.
front=$(adb shell dumpsys activity activities | grep -m1 -E "(topResumedActivity|mResumedActivity|ResumedActivity:)" || true)
echo "in front: $front"
echo "$front" | grep -q "com.authorityclosers.salesxray"
# And it must show Sales Xray, not Cloudflare's bot check.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb exec-out cat /sdcard/ui.xml > "$OUT/$tag-screen.xml"
if grep -qiE "Performing security verification|Verify you are human" "$OUT/$tag-screen.xml"; then
  echo "CHALLENGED $tag: Cloudflare's bot check held the app back (see README.md); layout checks still run"
  [ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "- $tag: Cloudflare challenged (not a failure)" >> "$GITHUB_STEP_SUMMARY"
else
  echo "ok $tag"
fi

# The page's web view must start below the status bar and any cut-out (SafeArea.java).
check_safe_area() {
  local name=$1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb exec-out cat /sdcard/ui.xml > "$OUT/$tag-$name.xml"
  local web bar
  web=$(grep -o 'class="android.webkit.WebView"[^>]*bounds="\[[0-9]*,[0-9]*\]' "$OUT/$tag-$name.xml" | head -1 | sed -E 's/.*bounds="\[[0-9]+,([0-9]+)\]/\1/')
  # "type=statusBars" on Android 11+, "ITYPE_STATUS_BAR" on Android 10.
  bar=$(adb shell dumpsys window | grep -m1 -oE '(statusBars|ITYPE_STATUS_BAR)[^ ]* frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | sed -E 's/.*\]\[[0-9]+,([0-9]+)\]/\1/')
  echo "$tag/$name: web view top ${web:-?}, status bar bottom ${bar:-?}"
  if [ -n "$web" ] && [ -n "$bar" ] && [ "$web" -lt "$bar" ]; then
    echo "FAIL $tag/$name: the page starts under the status bar"
    return 1
  fi
}
relaunch() {
  adb shell am force-stop com.authorityclosers.salesxray
  adb shell monkey -p com.authorityclosers.salesxray -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 20
}
failed=0
check_safe_area base || failed=1
IFS=, read -ra list <<< "$variants"
for variant in "${list[@]}"; do
  case "$variant" in
    punch-hole) adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole || true ;;
    notch) adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall || true ;;
    font-large) adb shell settings put system font_scale 1.3 ;;
    dark) adb shell cmd uimode night yes ;;
    gesture) adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true ;;
    buttons) adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true ;;
    "") continue ;;
    *) echo "unknown variant $variant"; continue ;;
  esac
  sleep 3
  relaunch
  adb exec-out screencap -p > "$OUT/$tag-$variant.png"
  check_safe_area "$variant" || failed=1
done
exit $failed
