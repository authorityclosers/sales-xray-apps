#!/usr/bin/env bash
# Live web updates on an emulator (live-web.yml): a web change reaches the installed app with no reinstall, the
# app keeps working offline, a deliberately broken bundle is rolled back, and a bundle that needs a newer app waits.
#
#   emulator-live-web.sh <debug apk> <platform checkout> <export dir> <out dir>
#
# The bundles are the platform's real static export, packed and signed by the platform's own tool
# (infra/application/scripts/sx-web-bundle), with a throwaway key made for this run only ($SX_TEST_KEY: never
# stored, never the release key). Each one stands for a later release: its commit and time are set after the copy
# the app carries, and a stylesheet line shows which bundle is on screen. They are served from the runner at
# http://10.0.2.2:8787/v1/app-web/ (the debug build's live-web.json points there).
set -euo pipefail
apk=$1
platform=$2
export_dir=$3
out=$4
P=com.authorityclosers.salesxray
HOST=salesxray.authorityclosers.com
TOOL="$platform/infra/application/scripts/sx-web-bundle"
SERVE="$out/serve"
mkdir -p "$out/shots" "$SERVE/v1/app-web"
[ -x "$TOOL" ] || [ -f "$TOOL" ] || { echo "The platform ref has no sx-web-bundle (infra/application/scripts): use a ref with live web updates."; exit 1; }
: "${SX_TEST_KEY:?SX_TEST_KEY is the run's throwaway signing key}"
KEY=$(python3 "$TOOL" public-key)
builtin_commit_ms=$(( $(git -C "$platform" log -1 --format=%ct) * 1000 ))

# ---- Bundles: B2 a later release, B3 the same but broken, B4 one that needs a newer app. ----
make_bundle() { # name label day-offset fake-sha min-native [broken]
  local name=$1 label=$2 days=$3 sha=$4 native=$5 broken=${6:-}
  local dir="$out/$name"
  rm -rf "$dir" && mkdir -p "$dir" && cp -R "$export_dir/." "$dir/export/"
  # A visible web change: one line of CSS on every stylesheet.
  while IFS= read -r css; do
    printf '\nbody::after{content:"%s";position:fixed;z-index:2147483647;left:0;right:0;bottom:0;padding:12px;background:#ffd400;color:#111;font:700 16px/1.3 sans-serif;text-align:center}\n' "$label" >> "$css"
  done < <(find "$dir/export" -name '*.css')
  if [ -n "$broken" ]; then
    # Every script throws: the page never starts, so it never says ready.
    find "$dir/export/_next/static/chunks" -name '*.js' -exec sh -c 'printf "throw new Error(\"deliberately broken bundle\");\n" > "$1"' _ {} \;
  fi
  python3 "$TOOL" pack "$dir/export" --out "$dir/bundle" --host "$HOST" --sha "$sha" \
    --commit-at-ms $(( builtin_commit_ms + days * 86400000 )) > "$dir/facts.json"
  python3 "$TOOL" sign --bundle-json "$dir/bundle/bundle.json" --version "$name" \
    --sequence "$(date +%s%3N)" --min-native "$native" --out "$dir/manifest.json" > /dev/null
  python3 "$TOOL" verify "$dir/manifest.json" --key "$KEY" --host "$HOST" --bundle "$dir/bundle/"*.zip > /dev/null
  echo "$name: $(python3 -c 'import json,sys; f=json.load(open(sys.argv[1])); print(f["sha256"][:16], f["bytes"], "bytes", f["files"], "files")' "$dir/facts.json")"
}
publish() { # name
  python3 "$TOOL" publish --manifest "$out/$1/manifest.json" --bundle "$out/$1/bundle/"*.zip --root "$SERVE/v1/app-web" --key "$KEY" > /dev/null
  echo "published $1"
}
make_bundle 1.3.0-b2 "Live web update B2: downloaded and switched, no reinstall" 1 "$(printf 'b%.0s' $(seq 40))" 1
make_bundle 1.3.0-b3 "B3 (deliberately broken): if you see this, rollback failed" 2 "$(printf 'c%.0s' $(seq 40))" 1 broken
make_bundle 1.3.0-b4 "B4 needs a newer app: should never show" 3 "$(printf 'd%.0s' $(seq 40))" 99

python3 -m http.server 8787 --bind 127.0.0.1 --directory "$SERVE" > "$out/http.log" 2>&1 &
http_pid=$!
trap 'kill $http_pid 2>/dev/null || true' EXIT

# ---- The emulator (as emulator-call-recorder.sh: the runner's own SDK, GitHub-owned actions only). ----
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
image="system-images;android-33;google_apis;x86_64"
set +o pipefail
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$image" >/dev/null
set -o pipefail
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n sxweb -k "$image" -d pixel_6 --force -p "$ANDROID_AVD_HOME/sxweb.avd" >/dev/null
"$sdk/emulator/emulator" -avd sxweb -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none -memory 3072 >"$out/emulator.log" 2>&1 &
export PATH="$sdk/platform-tools:$PATH"
booted=0
for _ in $(seq 1 100); do
  [ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)" = 1 ] && { booted=1; break; }
  sleep 5
done
[ "$booted" = 1 ] || { echo "The emulator did not boot in time."; tail -60 "$out/emulator.log"; exit 1; }
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb install -r "$apk" > /dev/null
adb shell pm grant "$P" android.permission.POST_NOTIFICATIONS || true

step=0
log() { adb logcat -d -s SXLiveWeb:I SXStart:I | tr -d '\r'; }
start() { # fresh process, as a person opening the app
  adb shell am force-stop "$P"
  adb logcat -c
  adb shell monkey -p "$P" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
}
wait_for() { # text seconds
  for _ in $(seq 1 "$2"); do
    if log | grep -q -- "$1"; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for: $1"; log | tail -30; return 1
}
shot() { # name
  step=$((step + 1))
  sleep "${2:-3}"
  adb exec-out screencap -p > "$out/shots/$(printf '%02d' "$step")-$1.png"
  log > "$out/shots/$(printf '%02d' "$step")-$1.log"
  echo "screenshot $step: $1"
}
state() { adb shell run-as "$P" cat files/live-web/state.json 2>/dev/null | tr -d '\r'; }

echo "== 1. Installed: the app's own web copy, nothing published yet"
start
wait_for "start: the app's own web copy" 60
wait_for "manifest unreachable" 60
shot built-in 12

echo "== 2. B2 published: downloaded in the background, used from the next start"
publish 1.3.0-b2
start
wait_for "web update 1.3.0-b2 .* downloaded: used from the next start" 180
start
wait_for "start: trying web update 1.3.0-b2" 30
wait_for "web update 1.3.0-b2 .* is up" 40
shot web-update-b2 6

echo "== 3. Offline: the app starts from what it has"
adb shell cmd connectivity airplane-mode enable || true
adb shell svc wifi disable || true
adb shell svc data disable || true
sleep 3
start
wait_for "start: using web update 1.3.0-b2" 30
wait_for "web update 1.3.0-b2 .* is up" 40
wait_for "manifest unreachable" 40
shot offline-b2 4
adb shell cmd connectivity airplane-mode disable || true
adb shell svc wifi enable || true
adb shell svc data enable || true
sleep 8

echo "== 4. B3 (broken) published: tried at the next start, rolled back to B2"
publish 1.3.0-b3
start
wait_for "web update 1.3.0-b3 .* downloaded: used from the next start" 180
start
wait_for "start: trying web update 1.3.0-b3" 30
shot broken-b3-loading 5
wait_for "web update 1.3.0-b3 .* did not start in 15 s: rolled back to web update 1.3.0-b2" 40
wait_for "web update 1.3.0-b2 .* is up" 40
shot rolled-back-to-b2 4

echo "== 5. Next start: B2, and B3 is never fetched again"
start
wait_for "start: using web update 1.3.0-b2" 30
wait_for "web 1.3.0-b3: refused_before" 60
shot after-rollback 4

echo "== 6. B4 needs a newer app: it waits for the store build"
publish 1.3.0-b4
start
wait_for "web 1.3.0-b4: needs_native" 60
wait_for "start: using web update 1.3.0-b2" 5
shot needs-newer-app 4

state > "$out/state.json"
python3 - "$out/state.json" <<'PY'
import json, sys
state = json.load(open(sys.argv[1]))
print("state:", json.dumps({k: state.get(k) for k in ("active", "previous", "pending", "bad", "lastResult")}, indent=1))
assert state["active"]["version"] == "1.3.0-b2", state["active"]
assert len(state["bad"]) == 1, state["bad"]
assert state.get("pending") is None
PY
grep -c "GET /v1/app-web/bundles/" "$out/http.log" | xargs echo "bundle downloads:"
echo "Live web updates proven on the emulator: update without reinstall, offline start, rollback, native gating."
