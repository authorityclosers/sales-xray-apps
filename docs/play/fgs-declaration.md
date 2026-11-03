# Foreground service (dataSync): Play Console declaration package

Owner order, 3 Nov 2026 (via the orchestrator): request the next permission on its own: a foreground service that
makes call recording uploads reliable. Play Console submission and **Send** belong to the owner. Nothing here has
been sent to Google.

## What the app does
- **Before:** call recordings often upload only when the seller opens the app. A cached app is frozen or stopped by
  HyperOS, and Android 15 cuts its network (netpolicy `blocked=APP_BACKGROUND`, capture gap 3, Redmi 13,
  30 Oct 2026). The WorkManager worker's first request then fails, and only a system job (CaptureNet) sometimes lets
  it through.
- **Now:** while a recording's bytes are being uploaded, `CaptureUploadWorker` runs as WorkManager's foreground
  service with type `dataSync` (`CaptureForeground`).
  - The notification is quiet: the "Automatic call uploads" channel, low importance, no sound. It reads
    **"Uploading 1 call recording"** and disappears when the server has the recording.
  - The 60-second Undo wait, the folder scan every 15 minutes and the analysis polls never run in the foreground.
- **Proven on the Redmi 13 (3 Nov 2026, staging build of this PR, app cached, no screen):**
  - A real 42 s call went from the phone's dialler to Jio's toll-free IVR (the fictional lead "Test Jio care IVR").
  - "Waiting for upload" appeared at 17:43:50 IST.
  - For 45 s after that (17:44:49 to 17:45:34), `dumpsys activity services` showed:
    - `SystemForegroundService` `isForeground=true types=0x00000001` (dataSync);
    - notification flags `FOREGROUND_SERVICE|SILENT`;
    - start allowed as `SYSTEM_ALLOW_LISTED` (the battery exemption).
  - Then "uploaded for analysis" appeared, and the upload notification was gone.
  - What the service adds on this phone: the battery exemption already let the cached app use the network
    (netpolicy `effective=NONE`). The foreground service keeps the process at foreground-service priority for the
    whole upload, so HyperOS can't freeze or stop it partway. The Play answers below say exactly this.
- **Limit:** Android 12+ starts a foreground service from the background only when an exemption applies. Here that
  means the seller has taken Sales X-Ray off battery optimisation (Settings > Apps > Sales X-Ray > Battery saver >
  No restrictions), which the app's setup checklist already asks for. Without it the start is refused and the
  upload carries on through CaptureNet, as before. Tests cover both cases.
- **Not added:** microphone, accessibility, all-files access, the battery-exemption request permission, or
  any other foreground service type. CI fails the Play build if one appears.

## Why dataSync, not shortService
| | dataSync (chosen) | shortService |
| --- | --- | --- |
| Fits the task | Play's own example: "data upload or download" | for short critical work only |
| Length | no short cut-off; Android 15 allows 6 h per day, far above a seller's uploads | stopped after about 3 min: a 30-minute call recording over slow mobile data can take longer, and an interrupted upload restarts from zero |
| Play declaration | listed in Play's form; reviewed openly | not listed in Play's form. Only a third-party blog says it needs no declaration, which is too weak to rely on for policy |

Play recommends user-initiated data transfer jobs for transfers the user asks for. Those must be scheduled while the
app is on screen, and an upload begins after a call ends in the phone's dialer, when Sales X-Ray isn't visible.

## Build and gate
- Play manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`; WorkManager's `SystemForegroundService`
  with `foregroundServiceType="dataSync"`.
- `android.yml` requires exactly that type in Play and refuses every other foreground service type.
- `play.yml` refuses any other type. It uploads a bundle with the service only once the repository variable
  `PLAY_FGS_DECLARATION` is `submitted` or `approved`. Before that, a run with track alpha and draft on may put the
  bundle in Play Console as a **draft alpha release, which reaches no tester**, so the declaration form appears.
  Any other run keeps the signed bundle as an artifact and stops.

## Order (orchestrator = O, this declaration session = D, owner = OWNER)
Merging this PR holds every later Play build to a draft until Google approves, as call log did. So sellers get the
pending fixes first (orchestrator, 3 Nov 2026):
1. O: ship the v1.37 Play build (run 41655952688 on 6369bc9, apps #29 and #30).
2. O: after #31 (in-app dial timing, 02f0a9a) passes its Redmi proof, dispatch one more Play build with it.
3. O: only then, merge this PR when CI is green.
4. D: Actions → Google Play → Run workflow on main: track **alpha**, **draft on**, `web_ref` = production web. The
   draft reaches no tester; it makes Play Console show the declaration.
5. D (browser): Play Console → App content → **Foreground service permissions** → Data sync → paste the answers
   below with the video link → Save. Check that Publishing overview lists the change.
6. **OWNER:** Publishing overview → **Send changes for review**.
7. O, right after: `gh variable set PLAY_FGS_DECLARATION --body submitted --repo authorityclosers/sales-xray-apps`.
   After approval: `--body approved`. Avoid new alpha uploads while the review runs.
8. If refused: revert the PR, delete the variable, send the next bundle without the service. Uploads fall back to
   CaptureNet.

## Play Console answers (paste)
**Foreground service type:** Data sync.

**Use case:** pick the preset closest to "Network transfer: upload or download" (wording may differ).

**Description of the functionality:**
> Sales X-Ray is a sales CRM. When a seller turns on automatic call uploads (Settings > Privacy > Calls, after a
> disclosure and the seller's consent), the app uploads the phone dialer's recording of each call with one of the
> company's leads to the company's workspace, where it is analysed for the seller and their manager. The foreground
> service runs only while that recording is being uploaded, shows the notification "Uploading 1 call recording",
> and stops as soon as the server has received it. Calls with numbers that are not the company's leads are never
> uploaded.

**User impact if the task is deferred:**
> The seller's call does not reach the lead or produce its report until they next open the app. Android and phone
> makers' battery managers freeze or stop a background app, so a deferred upload often does not finish by itself;
> managers see the seller's calls as missing, and the seller cannot review the call before the next one. Phone
> diallers may also remove older recordings, losing the call.

**User impact if the task is interrupted:**
> The server accepts a recording in a single request, so an interrupted upload starts again from the beginning and
> uses the seller's mobile data a second time. If the recording is removed before the next try, the call is lost.

**Video:** https://drive.google.com/file/d/1-Nw3jmtCcPWWrjkev7vqlJaDwjliF-9d/view (shared "Anyone with the link").

## The video (recorded 3 Nov 2026)
- Drive "Sales X-Ray Play listing":
  [sales-xray-fgs-declaration.mp4](https://drive.google.com/file/d/1-Nw3jmtCcPWWrjkev7vqlJaDwjliF-9d/view),
  37.9 s, 1920×1080, H.264 + AAC, sha256 `41903092…626dbb`.
- Local copy: `C:/Users/Suyash/Downloads/sales-xray-fgs-declaration.mp4`.
- Redmi 13, staging build of this PR (run 41630391859, apps b507e6e), Example Sales, fictional lead "Test Jio care
  IVR". The "Staging" label is kept.
- Six captioned steps with an ElevenLabs English voice-over (the tutorials narrator):
  1. The seller turns on automatic call uploads (the disclosure, then Turn on).
  2. A real call with the lead ends in the phone's dialer; Sales X-Ray is not on screen.
  3. "Call with Test Jio care IVR waiting for upload" (60 s to Undo).
  4. **"Uploading 1 call recording"**: the foreground service.
  5. The upload is done and the service stops; its notification goes away.
  6. "Call with Test Jio care IVR uploaded for analysis".
- The notification shots show only Sales X-Ray's card, cut from the pulled-down shade. Other apps' notifications are
  never shown.

## Re-recording (if Google asks for another take)
1. Install this PR's Staging Android QA artifact. Sign in as `admin+playdecl@` → Example Sales.
2. Setup: Phone, Notifications and Recordings folder allowed; Battery **No restrictions**; Settings › Privacy ›
   Automatic call uploads → **Turn uploads on** (Any network).
3. Lock portrait (`cmd window user-rotation lock 0`). Record with `screenrecord --size 720x1640`.
4. Call the lead's number from the dialer inside work hours (Mon–Sat 10:30–19:00 IST). Jio's toll-free IVR
   18008899999, dialled without +91, connects with no person on the line. Hang up from the phone after about 45 s.
   Press Home.
5. The folder check runs about every 15 minutes; a forced job run is ignored. When "waiting for upload" appears,
   record the shade (`cmd statusbar expand-notifications`) for about 3 minutes.
6. Keep the proof: a loop on the phone that logs `dumpsys activity services <package>` lines with
   `SystemForegroundService|isForeground=|types=` every 0.5 s.
7. Edit: crop the system bars from phone shots and only Sales X-Ray's card from the shade; captions; voice-over.
