#!/usr/bin/env bash
# Call-to-report pipeline simulator (owner order, 30 Oct 2026: "simulate, emulate the whole call to analysis
# pipeline ... I need each single call getting tracked").
#
# The real path on an Android 15 emulator against staging: the staging app signed in as the QA seller in the
# organisation workspace (minutes on), a dialler call to a fictional lead, a MIUI-style recording of the
# fictional canary call dropped in the picked folder, the app in the background, then the watcher, the
# 60-second Undo window, the upload, staging analysis and the report. Every stage is timed; the first one that
# doesn't happen fails the run, by name, with its diagnostics (pipeline-sim/).
#
#   bash scripts/pipeline-sim.sh STAGING_DEBUG_APK STAGING_DEVICE_TESTS_APK CANARY_WAV
#
# The QA seller's login: in GitHub Actions from Infisical with the run's OIDC token (SX_SIM_INFISICAL_IDENTITY_ID);
# for a run by hand, SX_SIM_EMAIL and SX_SIM_PASSWORD typed into its environment. Optional: SX_SIM_BASE,
# SX_SIM_WORKSPACE (default "Authority Closers"), SX_SIM_NUDGE=1 (run the app's jobs at once instead of
# waiting for Android's schedule), SX_SIM_APP=background|killed. No real customer audio: the clip is the
# platform's fictional canary call (Piper TTS, PROVENANCE.md), re-encoded so each night is a new recording.
set -euo pipefail
apk=$1 tests_apk=$2 wav=$3
here=$(cd "$(dirname "$0")" && pwd)
base=${SX_SIM_BASE:-https://salesxray-staging.authorityclosers.com}
package=com.authorityclosers.salesxray.staging
lead=+12025550142   # fictional (US 555-01xx is reserved for fiction)
lead_dial=12025550142
nudge=${SX_SIM_NUDGE:-0}
app_state=${SX_SIM_APP:-background}
out=${SX_SIM_OUT:-pipeline-sim}
work=$(mktemp -d)
mkdir -p "$out"
: > "$out/stages.jsonl"
sim=(python3 "$here/pipeline_sim.py")
started=$(date +%s)
summary="$out/summary.md"
printf '| Stage | Result | Seconds | Detail |\n|---|---|---|---|\n' > "$summary"

stage() { # name result detail
  local seconds=$(( $(date +%s) - started ))
  printf '{"stage":"%s","result":"%s","seconds":%s,"detail":%s}\n' "$1" "$2" "$seconds" "$(printf '%s' "$3" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" >> "$out/stages.jsonl"
  printf '| %s | %s | %s | %s |\n' "$1" "$2" "$seconds" "$3" >> "$summary"
  echo "[$seconds s] $1: $2 $3"
}
diagnose() {
  set +e
  adb shell run-as "$package" cat shared_prefs/sx_auto_capture.xml > "$work/prefs.xml" 2>/dev/null
  "${sim[@]}" device "$work/prefs.xml" > "$out/device-jobs.json" 2>/dev/null
  adb shell dumpsys jobscheduler "$package" > "$out/jobscheduler.txt" 2>&1
  uid=$(adb shell cmd package list packages -U "$package" | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | tr -d '\r')
  adb shell dumpsys netpolicy | grep -A2 "uid=$uid" > "$out/netpolicy.txt" 2>&1
  adb shell dumpsys activity processes "$package" | grep -iE "procstate|curProcState|setProcState" > "$out/process.txt" 2>&1
  adb logcat -d -v time | grep -iE "WM-|salesxray|CaptureNet|WorkManager|AndroidRuntime" | tail -300 > "$out/logcat.txt"
  adb exec-out screencap -p > "$out/screen.png" 2>/dev/null
  set -e
}
stuck() { # stage detail
  stage "$1" STUCK "$2"
  diagnose
  {
    echo
    echo "**PIPELINE STUCK at $1:** $2"
    echo
    echo "Phone queue: \`$(cat "$out/device-jobs.json" 2>/dev/null)\`"
  } >> "$summary"
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$summary" >> "$GITHUB_STEP_SUMMARY"
  echo "::error::PIPELINE STUCK at $1: $2"
  exit 3
}
jobs_now() { # the phone's queue as JSON
  adb shell run-as "$package" cat shared_prefs/sx_auto_capture.xml > "$work/prefs.xml" 2>/dev/null || : > "$work/prefs.xml"
  "${sim[@]}" device "$work/prefs.xml"
}
run_jobs() { # what Android would run later, now (SX_SIM_NUDGE=1)
  for id in $(adb shell dumpsys jobscheduler "$package" | sed -n 's/.*JOB #u0a[0-9]*\/\([0-9]*\).*/\1/p' | sort -u); do
    adb shell cmd jobscheduler run -f "$package" "$id" >/dev/null 2>&1 || true
  done
}

# 1. The QA seller on staging: workspace, profile, consent, minutes, the fictional lead.
clip_seconds=$(python3 -c 'import sys,wave; w=wave.open(sys.argv[1]); print(int(w.getnframes()/w.getframerate())+1)' "$wav")
if ! "${sim[@]}" prepare --base "$base" --lead-number "$lead" --clip-seconds "$clip_seconds" \
  --jar "$work/jar.json" --cookies "$work/cookies.b64" --out "$work/state.json" > "$work/prepare.json"; then
  stuck "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("stuck","sign_in"))' "$work/prepare.json")" \
    "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("detail",""))' "$work/prepare.json")"
fi
cookies=$(cat "$work/cookies.b64")
[ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$cookies"
stage sign_in ok "$(cat "$work/prepare.json")"

# 2. A new recording of the fictional call every night (its own bytes, so never a repeat upload).
ffmpeg -loglevel error -y -i "$wav" -metadata comment="pipeline-sim ${GITHUB_RUN_ID:-local} $(date -u +%FT%TZ)" \
  -codec:a libmp3lame -b:a 64k "$work/clip.mp3"

# 3. The emulator: Android 15 (its background network rule is the one HyperOS hit).
sdk=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
image='system-images;android-35;google_apis;x86_64'
set +o pipefail
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --install emulator platform-tools "$image" >/dev/null
set -o pipefail
export ANDROID_AVD_HOME="$HOME/.android/avd" PATH="$sdk/platform-tools:$PATH"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n sim -k "$image" -d pixel_6 --force -p "$ANDROID_AVD_HOME/sim.avd" >/dev/null
"$sdk/emulator/emulator" -avd sim -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -camera-back none -camera-front none >"$out/emulator.txt" 2>&1 &
booted=0
for _ in $(seq 1 120); do
  if [ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)" = 1 ]; then booted=1; break; fi
  sleep 5
done
[ "$booted" = 1 ] || stuck app_ready "the emulator did not boot"
adb install -r "$apk" >/dev/null || stuck app_ready "the staging app did not install"
adb install -r "$tests_apk" >/dev/null || stuck app_ready "the device tests did not install"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb shell mkdir -p /sdcard/Recordings/Call

# 4. Set up like a seller: session, the app's own folder picker, uploads on.
adb shell am instrument -w -e sim 1 -e cookies "$cookies" -e class com.authorityclosers.salesxray.PipelineSimTest \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$work/instrument.txt" 2>&1 || true
grep -q '^OK (1 test)' "$work/instrument.txt" || stuck app_ready "set-up failed: $(grep -m1 -E 'Error|Exception|assert' "$work/instrument.txt" | head -c 300)"
# Instrumentation ends with a force stop, which drops the app's jobs; opening the app reschedules them,
# as when a seller opens it. Then the seller leaves it.
adb shell am start -W -n "$package/com.authorityclosers.salesxray.MainActivity" >/dev/null
sleep 20
adb shell input keyevent KEYCODE_HOME
sleep 5
[ "$app_state" = killed ] && adb shell am kill "$package"
stage app_ready ok "signed in, folder granted, uploads on, app $app_state"

# 5. The call: dialled from the phone, answered, talked for the clip's length, hung up.
t0=$(adb shell date +%s | tr -d '\r')
name_stamp=$(adb shell date +%Y%m%d%H%M%S | tr -d '\r')
adb shell am start -a android.intent.action.CALL -d "tel:$lead" >/dev/null
answered=0
for _ in $(seq 1 15); do
  # The modem lists the number as the dialler sent it (with or without +).
  listed=$(adb emu gsm list 2>/dev/null | grep -o "[+0-9]*$lead_dial" | head -1 || true)
  if [ -n "$listed" ]; then adb emu gsm accept "$listed" >/dev/null 2>&1 && answered=1; break; fi
  sleep 1
done
sleep "$clip_seconds"
adb emu gsm cancel "${listed:-$lead_dial}" >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_HOME
t1=$(adb shell date +%s | tr -d '\r')
logged=$(adb shell content query --uri content://call_log/calls --projection number:duration --sort "date DESC" 2>/dev/null | head -1 | tr -d '\r')
stage dialled ok "answered=$answered, $((t1 - t0)) s, phone log: ${logged:-none}"

# 6. The dialler's recording: MIUI's name(number)_yyyyMMddHHmmss, saved when the call ended.
file="/sdcard/Recordings/Call/Pipeline Sim(00${lead_dial})_${name_stamp}.mp3"
adb push "$work/clip.mp3" "$file" >/dev/null
adb shell touch -m -d "@$t1" "'$file'" 2>/dev/null || adb shell touch -m -t "$(date -u -d "@$t1" +%Y%m%d%H%M.%S)" "'$file'"
stage recorded ok "$(basename "$file")"

# 7. The watcher finds and matches it (after the file is a minute old), with the app in the background.
deadline=$(( $(date +%s) + ${SX_SIM_MATCH_SECONDS:-1500} ))
job=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  job=$(jobs_now | python3 -c 'import json,sys; jobs=[j for j in json.load(sys.stdin) if "Pipeline Sim" in (j.get("name") or "")]; print(json.dumps(jobs[0]) if jobs else "")')
  [ -n "$job" ] && break
  [ "$nudge" = 1 ] && [ $(( $(date +%s) - t1 )) -gt 65 ] && run_jobs
  sleep 10
done
[ -n "$job" ] || stuck matched "the phone did not pick the recording up within ${SX_SIM_MATCH_SECONDS:-1500} s"
stage matched ok "$job"

# 8. Waiting (the Undo window), then the upload; a held call names its reason.
deadline=$(( $(date +%s) + 900 ))
seen=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  job=$(jobs_now | python3 -c 'import json,sys; jobs=[j for j in json.load(sys.stdin) if "Pipeline Sim" in (j.get("name") or "")]; print(json.dumps(jobs[0]) if jobs else "{}")')
  state=$(printf '%s' "$job" | python3 -c 'import json,sys; j=json.load(sys.stdin); print(j.get("state",""), j.get("hold") or "")')
  [ "$state" != "$seen" ] && echo "phone: $state" && seen=$state
  case "$state" in
    held*) stuck waiting "the phone holds the call: ${state#held }" ;;
    cancelled*) stuck waiting "the upload was cancelled (Undo)" ;;
    analysing*|report_ready*) break ;;
  esac
  [ "$nudge" = 1 ] && run_jobs
  sleep 10
done
stage waiting ok "Undo window passed"
"${sim[@]}" poll --state "$work/state.json" --jar "$work/jar.json" --since "$t0" --until uploaded --stage uploaded --timeout 600 \
  > "$work/uploaded.jsonl" || stuck uploaded "$(tail -1 "$work/uploaded.jsonl")"
stage uploaded ok "$(tail -1 "$work/uploaded.jsonl")"

# 9. Staging analysis, then the report.
"${sim[@]}" poll --state "$work/state.json" --jar "$work/jar.json" --since "$t0" --until analysing --stage analysing --timeout 600 \
  > "$work/analysing.jsonl" || true
grep -q '"report_ready"' "$work/analysing.jsonl" || grep -q '"analysing"' "$work/analysing.jsonl" || stuck analysing "$(tail -1 "$work/analysing.jsonl")"
stage analysing ok "$(tail -1 "$work/analysing.jsonl")"
"${sim[@]}" poll --state "$work/state.json" --jar "$work/jar.json" --since "$t0" --until report_ready --stage report_ready --timeout 1200 \
  > "$work/report.jsonl" || stuck report_ready "$(tail -1 "$work/report.jsonl")"
stage report_ready ok "$(tail -1 "$work/report.jsonl")"

diagnose
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$summary" >> "$GITHUB_STEP_SUMMARY"
echo "PIPELINE OK: dial to report in $(( $(date +%s) - t0 )) s"
