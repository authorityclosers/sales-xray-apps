#!/usr/bin/env bash
# Synthetic audio and a mocked own API. No real calls, API traffic or prompt interaction.
set -euo pipefail
run=$1
package=com.authorityclosers.salesxray.dev
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
image='system-images;android-33;google_apis;x86_64'
set +o pipefail
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$image" >/dev/null
set -o pipefail
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n capture -k "$image" -d pixel_6 --force -p "$ANDROID_AVD_HOME/capture.avd" >/dev/null
"$sdk/emulator/emulator" -avd capture -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none >auto-capture-emulator.txt 2>&1 &
export PATH="$sdk/platform-tools:$PATH"
booted=0
for _ in $(seq 1 100); do
  if [ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)" = 1 ]; then booted=1; break; fi
  sleep 5
done
[ "$booted" = 1 ] || { tail -60 auto-capture-emulator.txt; exit 1; }
adb install "dist/sales-xray-dev-android-0.3.${run}-test.apk"
adb install "dist/sales-xray-dev-android-0.3.${run}-device-tests.apk"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -e class com.authorityclosers.salesxray.CaptureUploadTest "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee auto-capture-proof.txt
grep -q '^OK (6 tests)' auto-capture-proof.txt
