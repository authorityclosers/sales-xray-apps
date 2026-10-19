#!/usr/bin/env bash
# Proves the accessibility call recorder on an emulator with an emulated SIM call
# (android.yml, job emulator-call-recorder): ring, answer, hang up, then check the
# one tagged recording. Usage: emulator-call-recorder.sh <run number>
set -euo pipefail
run=$1
P=com.authorityclosers.salesxray.dev

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
ls "$ANDROID_AVD_HOME"
echo "emulator: $("$sdk/emulator/emulator" -version 2>/dev/null | head -1)"
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
  ls -la /dev/kvm || true
  tail -60 emulator.log
  exit 1
fi
adb shell getprop ro.build.version.release
adb install "dist/sales-xray-dev-android-0.3.${run}-test.apk"
adb install "dist/sales-xray-dev-android-0.3.${run}-device-tests.apk"
for perm in RECORD_AUDIO READ_PHONE_STATE READ_MEDIA_AUDIO POST_NOTIFICATIONS; do
  adb shell pm grant "$P" "android.permission.$perm"
done
# On the emulator only: the Accessibility switch a person taps on a real phone.
adb shell settings put secure enabled_accessibility_services "$P/com.authorityclosers.salesxray.CallRecorderService"
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell am broadcast -n "$P/com.authorityclosers.salesxray.SimulateCallReceiver" -e state want-on
adb emu gsm call 5551234
sleep 4
adb emu gsm accept 5551234
sleep 10
adb emu gsm cancel 5551234
sleep 6
# An internet call: the phone in "in communication" mode (what WhatsApp, Meet and Telegram set).
adb shell am broadcast -n "$P/com.authorityclosers.salesxray.SimulateCallReceiver" -e state voip-start
sleep 8
adb shell am broadcast -n "$P/com.authorityclosers.salesxray.SimulateCallReceiver" -e state voip-end
sleep 4
# One check after both calls (starting a test stops the app, and with it the recorder).
adb shell am instrument -w -e class "com.authorityclosers.salesxray.DeviceCaptureTest#aCallWasRecordedByTheRecorder,com.authorityclosers.salesxray.DeviceCaptureTest#anInternetCallIsRecorded"   -e expectCall phone,voip "$P.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.txt
adb logcat -d -s SXTEST:I | tail -20
grep -q "^OK (2 tests)" instrument.txt
# Safe areas: a punch-hole cut-out like the owner's phone, then the page must sit clear
# of the status bar, the cut-out and the navigation bar.
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole || adb shell cmd overlay enable com.android.internal.display.cutout.emulation.corner || true
sleep 3
adb exec-out screencap -p > safe-area.png || true
adb shell am instrument -w -e class com.authorityclosers.salesxray.SafeAreaTest "$P.test/androidx.test.runner.AndroidJUnitRunner" | tee safe-area.txt
adb logcat -d -s SXTEST:I | grep "safe area" | tail -2
grep -q "^OK (1 test)" safe-area.txt
