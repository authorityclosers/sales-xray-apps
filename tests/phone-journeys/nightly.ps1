# The seller journeys on a testing Redmi, nightly from the owner's laptop (orchestrator for the owner, 28 Oct 2026: "you
# can use both Redmi all time, it's for testing heavy"). Windows Task Scheduler starts it at night when the laptop is
# idle (README.md). It skips quietly when the phone, ac or the network can't be reached: nothing depends on it, and the
# bug radar's own nightly runs on GitHub.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File nightly.ps1 -Serial 192.168.1.20:5555 [-DryRun]
#
# Each night it:
# 1. takes the journeys and the report's code from the workbench head on ac, the same code dev serves;
# 2. tunnels the phone's requests through ssh to dev-live's review fixtures, so the Dev app opens fictional screens
#    with no account and dev's sign-in never comes up;
# 3. walks the journeys with wb_phone.mjs;
# 4. ingests the result with `ac-bug-radar ingest`, so what it finds becomes radar:<group> cards.
# It starts only the Dev app, never the Play Store.
param(
  [string]$Serial = $env:SX_PHONE_SERIAL,
  [string]$Pkg = "com.authorityclosers.salesxray.dev",
  [switch]$DryRun
)
$ErrorActionPreference = "Stop"
$root = Join-Path $env:LOCALAPPDATA "sx-phone-journeys"
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmZ")
$out = Join-Path $root "runs\$stamp"
New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null
$log = Join-Path $root "logs\$stamp.log"
function Say($text) { "$((Get-Date).ToUniversalTime().ToString('s'))Z $text" | Tee-Object -FilePath $log -Append }

if (-not $Serial) { Say "no phone serial (set SX_PHONE_SERIAL); nothing ran"; exit 0 }
# A phone on Wireless debugging: connect, then it must answer as a device.
if ($Serial -match ":") { & adb connect $Serial 2>&1 | Out-Null }
$state = (& adb -s $Serial get-state 2>$null) -join ""
if ($state -ne "device") { Say "phone $Serial not on adb ($state); nothing ran"; exit 0 }
& ssh -o ConnectTimeout=20 -o BatchMode=yes ac true 2>$null
if ($LASTEXITCODE -ne 0) { Say "ac not reachable; nothing ran"; exit 0 }

# The code: the journeys and the report's from the workbench head, the runner from this repository's main.
$wb = "/home/acdev/src/dev-live/authority-closers-platform"
$sha = (& ssh ac "sudo -u acdev git -C $wb rev-parse HEAD").Trim()
# Bytes, not text: through a file, so PowerShell never re-encodes the scripts.
$files = "wb_journeys_lib.mjs", "wb_radar.mjs", "wb_radar_lib.mjs", "wb_matrix_lib.mjs" | ForEach-Object { "scripts/ci/$_" }
$libTar = Join-Path $root "lib.tar"
Start-Process ssh -NoNewWindow -Wait -RedirectStandardOutput $libTar `
  -ArgumentList "ac", "sudo -u acdev git -C $wb archive --format=tar HEAD $($files -join ' ')"
& tar -xf $libTar -C $root
$lib = Join-Path $root "scripts\ci"
Invoke-WebRequest -UseBasicParsing -OutFile (Join-Path $root "wb_phone.mjs") `
  "https://raw.githubusercontent.com/authorityclosers/sales-xray-apps/main/tests/phone-journeys/wb_phone.mjs"
if (-not (Test-Path (Join-Path $root "node_modules\playwright"))) {
  $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1"
  & npm install --no-save --no-audit --no-fund --prefix $root playwright@1.58.2 | Out-Null
}
Say "workbench $($sha.Substring(0, 9)); phone $Serial"

if ($DryRun) {
  & node (Join-Path $root "wb_phone.mjs") $out --lib $lib --serial $Serial --pkg $Pkg --dry-run 2>&1 | Tee-Object -FilePath $log -Append
  exit 0
}

# dev-live's review fixtures (port 3026 on ac) on this laptop's 18216, for this run only.
$tunnel = Start-Process -PassThru -WindowStyle Hidden ssh -ArgumentList "-N", "-o", "ExitOnForwardFailure=yes", "-L", "18216:127.0.0.1:3026", "ac"
try {
  Start-Sleep -Seconds 5
  & node (Join-Path $root "wb_phone.mjs") $out --lib $lib --serial $Serial --pkg $Pkg `
    --fixtures "http://127.0.0.1:18216" --sha $sha 2>&1 | Tee-Object -FilePath $log -Append
  if (-not (Test-Path (Join-Path $out "part-0.json"))) { Say "no result; nothing ingested"; exit 0 }
  & node (Join-Path $lib "wb_radar.mjs") merge $out 2>&1 | Out-Null
  # The run on ac, beside the radar's own, and into the radar's cards.
  $dir = "/var/lib/ac-bug-radar/runs/phone-$stamp"
  $runTar = Join-Path $root "run.tar"
  & tar -C $out -cf $runTar .
  Start-Process ssh -NoNewWindow -Wait -RedirectStandardInput $runTar -RedirectStandardOutput (Join-Path $root "ingest.log") `
    -ArgumentList "ac", "sudo mkdir -p $dir && sudo tar -xf - -C $dir && sudo /usr/local/sbin/ac-bug-radar ingest $dir"
  Get-Content (Join-Path $root "ingest.log") | Tee-Object -FilePath $log -Append
  Say "ingested as $dir"
} finally {
  if (-not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force }
}
# Keep a week of runs and logs here.
Get-ChildItem (Join-Path $root "runs"), (Join-Path $root "logs") | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-7) } |
  Remove-Item -Recurse -Force
