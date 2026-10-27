# Seller journeys on phones

The bug radar's seller journeys (the platform's `scripts/ci/wb_journeys_lib.mjs`), walked in the Android app's own
WebView. On GitHub's runners the radar walks them in Chromium as a phone and as the app; here they run in the real app,
on a real phone or an emulator.

- **`wb_phone.mjs`:** the runner, over adb, with Playwright's Android support.
  - It starts the Dev app and attaches to its WebView.
  - Each step is timed. A step that can't be done becomes a `journey-broken` finding, with a screenshot of the phone and
    the headings and controls then on screen. Errors after a step are tap-errors.
  - It writes a radar part. The platform's `wb_radar.mjs merge` turns that into the radar's report, and
    `ac-bug-radar ingest` turns it into `radar:<group>` cards.
  - Pages are keyed per phone (`phone:<model>:<journey>`), so a phone's findings never close the GitHub radar's cards,
    or the other way round.
- **`--fixtures`:** every request to the Dev site is answered from a review-fixture server instead.
  - The WebView never leaves the phone, so the app's https-only rule holds, nothing reaches dev or production, and no
    account is used.
  - Reads the fixtures don't answer get a 404 at once, as on the radar.
- **`.github/workflows/phone-journeys.yml`:** an emulator lane (Android 34, a budget phone's 3 GB). It runs the
  platform's fixtures on the runner; dispatch only. This was the dry run before any real phone.
- **`nightly.ps1`:** the laptop's nightly on a testing Redmi.
  - It takes the journeys and the report's code from the workbench head on ac.
  - It tunnels the fixtures from dev-live (port 3026 on ac) for the run.
  - It ingests the result on ac.
  - It skips quietly when the phone, ac or the network is off. Nothing depends on it.

## Phones

- **Redmi 13:** for the nightly once the owner turns on Wireless debugging. Its address goes in `SX_PHONE_SERIAL`, e.g.
  `192.168.1.20:5555`.
- **Redmi Note 8 Pro** (`192.168.1.7:40059`): with the on-device AI benchmarks until the orchestrator hands it over.

One driver per phone at a time. The runner starts only the Dev app, never the Play Store.

## The laptop's schedule (once a phone is ours)

A Task Scheduler task that runs at 02:30 IST only when the laptop is idle, on AC power and online, for at most 1 hour:

```powershell
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$env:LOCALAPPDATA\sx-phone-journeys\nightly.ps1`" -Serial 192.168.1.20:5555"
$trigger = New-ScheduledTaskTrigger -Daily -At 02:30
$settings = New-ScheduledTaskSettingsSet -RunOnlyIfIdle -IdleDuration 00:10:00 -IdleWaitTimeout 02:00:00 -RunOnlyIfNetworkAvailable -ExecutionTimeLimit 01:00:00 -StartWhenAvailable:$false
Register-ScheduledTask -TaskName "Sales X-Ray phone journeys" -Action $action -Trigger $trigger -Settings $settings
```

Before the first night, run `nightly.ps1 -DryRun`. It names the phone, the app and the journeys, and touches nothing.
