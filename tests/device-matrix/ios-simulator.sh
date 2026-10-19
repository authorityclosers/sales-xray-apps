#!/usr/bin/env bash
# Safari on iOS Simulator for old, small and large iPhones (owner order, 19 Oct): open
# each public page in the simulator's Safari and keep a screenshot. A page that fails
# to load still gets its screenshot; the job fails only when no device could boot.
set -uo pipefail
BASE=${SX_BASE_URL:-https://salesxray.authorityclosers.com}
OUT=${OUT:-shots}
mkdir -p "$OUT"
pages="/dashboard /apps /privacy"
# Old/small, small, current, large: whichever of each the runner's Xcode has.
wanted=("iPhone SE (3rd generation)" "iPhone 13 mini" "iPhone 15" "iPhone 16 Pro Max")
xcrun simctl list devices available | grep -E "iPhone" | head -40
booted_any=0
for name in "${wanted[@]}"; do
  udid=$(xcrun simctl list devices available | grep -F "    $name (" | head -1 | grep -oE '[0-9A-F-]{36}')
  if [ -z "$udid" ]; then
    echo "skip: $name is not on this runner"
    continue
  fi
  echo "== $name ($udid)"
  xcrun simctl boot "$udid" || true
  if ! xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1; then
    echo "FAIL: $name did not boot"
    continue
  fi
  booted_any=1
  slug=$(echo "$name" | tr ' ()' '---' | tr -s '-' | sed 's/-$//')
  for page in $pages; do
    xcrun simctl openurl "$udid" "$BASE$page"
    sleep 12
    xcrun simctl io "$udid" screenshot "$OUT/ios-$slug${page//\//-}.png" >/dev/null
    echo "shot ios/$slug$page"
  done
  xcrun simctl shutdown "$udid" || true
done
[ "$booted_any" = 1 ]
