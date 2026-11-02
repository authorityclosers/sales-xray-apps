#!/usr/bin/env bash
# The seller journeys in the Dev app's WebView on an emulator (phone-journeys.yml): boot Android <api> as a budget
# phone, install the Dev test APK, and walk the journeys with wb_phone.mjs, every request to the Dev site answered from
# the fixture server the workflow started on this runner (127.0.0.1:18216). The emulator boot is the device matrix's
# (tests/device-matrix/android-emulator.sh).
#
#   bash tests/phone-journeys/emulator.sh <api> <dev apk> <platform checkout> [out dir]
set -euo pipefail
api=$1
apk=$2
platform=$3
out=${4:-phone-run}
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
image="system-images;android-${api};google_apis;x86_64"
set +o pipefail
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$image" >/dev/null
set -o pipefail
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n p -k "$image" -d medium_phone --force -p "$ANDROID_AVD_HOME/p.avd" >/dev/null
# A budget phone's memory.
config="$ANDROID_AVD_HOME/p.avd/config.ini"
grep -v '^hw.ramSize=' "$config" > "$config.new" || true
echo "hw.ramSize=3072" >> "$config.new"
mv "$config.new" "$config"
"$sdk/emulator/emulator" -avd p -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio >emulator.log 2>&1 &
export PATH="$sdk/platform-tools:$PATH"
for _ in $(seq 1 120); do
  state=$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
  [ "$state" = "1" ] && break
  sleep 5
done
[ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ] || { tail -40 emulator.log; exit 1; }
adb install -r "$apk"
node tests/phone-journeys/wb_phone.mjs "$out" --lib "$platform/scripts/ci" --fixtures http://127.0.0.1:18216 \
  --require-device --sha "$(git -C "$platform" rev-parse HEAD)"
