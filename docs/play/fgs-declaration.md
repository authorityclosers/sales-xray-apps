# Foreground service (dataSync): Play Console declaration package

Owner order, 3 Nov 2026 (via the orchestrator): request the next permission on its own: a foreground service that
makes call recording uploads reliable. Play Console submission and **Send** belong to the owner. Nothing here has
been sent to Google.

## What the app does
- **Before:** call recordings upload only when the seller opens the app. When the app is cached, HyperOS and
  Android 15 cut its network (netpolicy `blocked=APP_BACKGROUND`, capture gap 3, Redmi 13, 30 Oct 2026). The
  WorkManager worker's first request then fails, and only a system job (CaptureNet) sometimes lets it through.
- **Now:** while a recording's bytes are being uploaded, `CaptureUploadWorker` runs as WorkManager's foreground
  service with type `dataSync` (`CaptureForeground`).
  - The notification is quiet: the "Automatic call uploads" channel, low importance, no sound. It reads
    **"Uploading 1 call recording"** and disappears when the server has the recording.
  - The 60-second Undo wait, the folder scan every 15 minutes and the analysis polls never run in the foreground.
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

## Order
1. Orchestrator: merge the PR when CI is green.
2. Orchestrator: Actions → Google Play → Run workflow: track **alpha**, **draft on**, `web_ref` = production web.
3. Orchestrator (browser): Play Console → App content → **Foreground service permissions** → fill in the answers
   below with the video link → Save.
4. **Owner:** Publishing overview → **Send changes for review**.
5. Orchestrator: `gh variable set PLAY_FGS_DECLARATION --body submitted --repo authorityclosers/sales-xray-apps`.
   After approval: `--body approved`.
6. If refused: revert the PR, delete the variable, send the next bundle without the service. Uploads fall back to
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
> The seller's call does not reach the lead or produce its report until they next open the app. On Android 15 and
> HyperOS a cached app has no network, so a deferred upload does not run by itself; managers see the seller's calls
> as missing, and the seller cannot review the call before the next one. Phone diallers may also remove older
> recordings, losing the call.

**User impact if the task is interrupted:**
> The server accepts a recording in a single request, so an interrupted upload starts again from the beginning and
> uses the seller's mobile data a second time. If the recording is removed before the next try, the call is lost.

**Video:** link to the 30–45 s video below (Drive, "Anyone with the link").

## Video script (30–45 s, captions and voice-over as in the call-log video)
| Time | Screen | Caption |
| --- | --- | --- |
| 0–6 s | Sales X-Ray › Settings › Privacy › Calls: automatic uploads On | "The seller turned on automatic call uploads" |
| 6–12 s | Leads: Alex Example; the phone dialer's call with the lead ends | "A call with a lead ends in the phone's dialer" |
| 12–18 s | Home button; Sales X-Ray is not on screen | "Sales X-Ray is closed" |
| 18–24 s | Notification shade: "Call with Alex Example waiting for upload · Undo" | "60 seconds to Undo" |
| 24–34 s | Notification: **"Uploading 1 call recording"** (ongoing, quiet), then "uploaded for analysis" | "Uploading in the foreground, then gone" |
| 34–42 s | Open Sales X-Ray › Alex Example: the call shows its recording and analysis | "The call is on the lead" |

Voice-over (about 90 words):
> "Sales X-Ray is a sales CRM. This seller turned on automatic call uploads. A call with a lead has just ended in the
> phone's own dialer, and Sales X-Ray is closed. After a 60-second chance to undo, the app uploads the recording as a
> foreground service. The notification shows exactly that: Uploading 1 call recording. When the upload finishes, the
> service stops and the notification goes. The call is now on the lead, ready for analysis."

## Capture steps (Redmi 13, staging app from this PR, fictional data)
1. Install the Staging Android QA artifact of this PR's head. Sign in as `admin+playdecl@` → Example Sales, lead
   Alex Example (+1 202 555 0123) or another fictional lead.
2. On the phone:
   - Sales X-Ray Staging › Battery saver › **No restrictions**;
   - Autostart on;
   - in the app: uploads on, Any network, recordings folder `MIUI/sound_recorder/call_rec`.
3. Lock portrait (`cmd window user-rotation lock 0`). Start `screenrecord --size 720x1640`.
4. Simulate the dialler's recording (no real call; recipe in memory reference-dialler-capture-sim):
   - push a short clip to `/sdcard/MIUI/sound_recorder/call_rec/` named `<00+cc+number>(<same>)_yyyyMMddHHmmss.mp3`,
     with a stamp inside work hours (Mon–Sat 10:30–19:00 IST) and after the lead was added;
   - `touch` the file so the saved time is after the stamp;
   - seed the matching call-log row (`content insert`, outgoing, 40 s).
5. Press Home. Wait until `dumpsys netpolicy` shows the UID `blocked=APP_BACKGROUND`, which proves the app is cached.
6. Wait for the 15-minute watch, or run WorkManager's job with `cmd jobscheduler run -f`. Pull the shade down for
   "waiting for upload", then **"Uploading 1 call recording"**.
7. Proof (keep it with the video): `dumpsys activity services com.authorityclosers.salesxray.staging` shows
   `SystemForegroundService` with `isForeground=true` and `foregroundServiceType=0x00000001` (dataSync) during the
   upload; the call shows on the lead.
8. Edit like the call-log video (`build_hq.py`: crop the system bars, captions, ElevenLabs voice). Keep the
   "Staging" label. Upload to Drive "Sales X-Ray Play listing" and share as "Anyone with the link".
