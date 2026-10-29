# Call-to-report pipeline simulator

Owner order, 30 Oct 2026: "simulate, emulate the whole call to analysis pipeline … I need each single call getting
tracked". `scripts/pipeline-sim.sh` drives the real path on an Android 15 emulator against staging and times every
stage. The first stage that doesn't happen fails the run, and the error names it: `PIPELINE STUCK at <stage>: <why>`.

| Stage | What happens | Typical stuck reason |
|---|---|---|
| `sign_in` | The QA seller signs in (password) and chooses the organisation workspace (`SX_SIM_WORKSPACE`, default Authority Closers). The fictional lead +1 202 555 0142 is added once (the add is idempotent). | No staging login in `staging-sim`; the seller isn't in the workspace |
| `profile` | write-eligibility answers 204 | The QA seller's contact profile is incomplete |
| `consent` | Standing consent (automatic analysis) is on | Consent is off, or its terms changed |
| `allowance` | The workspace has minutes for the clip | Minutes are used up |
| `app_ready` | The staging app is installed. `PipelineSimTest` puts the session in its WebView, picks `Recordings/Call` through the app's own folder picker, and turns uploads on. The app is opened once (as a seller does), then sent to the background (`SX_SIM_APP=killed` kills it). | Emulator boot, install, or the picker |
| `dialled` | The phone dials the lead, the modem answers, they "talk" for the clip's length, and the phone hangs up | — |
| `recorded` | The fictional canary call (re-encoded mp3, new bytes each night) lands as `Pipeline Sim(0012025550142)_yyyyMMddHHmmss.mp3`, saved at hang-up | — |
| `matched` | The app's watcher finds it with the app in the background (Android's own schedule, up to 25 min; `nudge` runs the jobs at once) | Background work never ran (see `jobscheduler.txt`, `netpolicy.txt`) |
| `waiting` | The 60-second Undo window, then the upload starts | The phone holds the call and the run names the hold (`profile`, `allowance`, `network` …) |
| `uploaded` | The capture guard shows the call's recording on the server | The upload never arrived |
| `analysing` | The staging analysis run starts | The plan didn't start, or the run failed |
| `report_ready` | The report exists | Analysis failed or timed out (20 min) |

## Run it
- By hand: Actions → Pipeline simulator (staging) → Run workflow, or `gh workflow run pipeline-sim.yml
  -R authorityclosers/sales-xray-apps [-f nudge=true]`.
- Nightly at 03:00 IST once the environment `staging-sim` has `SX_SIM_EMAIL` and `SX_SIM_PASSWORD` and the
  repository variable `SX_SIM_ENABLED` is `true`.
- One analysis a night on staging: about 44 s of fictional audio from the staging workspace's minutes.

## What a run keeps (artifact `pipeline-sim-<n>`, 14 days)
- `summary.md`: the stage table, also in the run summary.
- `stages.jsonl`: one line per stage with seconds since the start.
- On a red run:
  - `device-jobs.json`: the phone's queue, with each job's state and hold reason;
  - `jobscheduler.txt`, `netpolicy.txt`, `process.txt`: whether Android ran the app's background work, and whether
    its network was blocked;
  - `logcat.txt` and `screen.png`.

No real customer audio or numbers are used. Sessions and the password never reach the logs: the cookie is masked
and the helper prints only the server's `detail` words.
