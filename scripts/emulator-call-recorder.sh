#!/usr/bin/env bash
# Proves the accessibility call recorder on an emulator with an emulated SIM call
# (android.yml, job emulator-call-recorder): ring, answer, hang up, then check the
# one tagged recording. Usage: emulator-call-recorder.sh <run number>
set -euo pipefail
run=$1
P=com.authorityclosers.salesxray.dev
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
adb shell am instrument -w -e class com.authorityclosers.salesxray.DeviceCaptureTest#aCallWasRecordedByTheRecorder \
  -e expectCall phone "$P.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.txt
adb logcat -d -s SXTEST:I | tail -20
grep -q "^OK (1 test)" instrument.txt
