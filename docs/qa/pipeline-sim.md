# Call-to-report pipeline simulator

Owner order, 30 Oct 2026: "simulate, emulate the whole call to analysis pipeline … I need each single call getting
tracked". `scripts/pipeline-sim.sh` drives the real path on an Android 15 emulator against staging and times every
stage. The first stage that doesn't happen fails the run, and the error names it: `PIPELINE STUCK at <stage>: <why>`.

| Stage | What happens | Typical stuck reason |
|---|---|---|
| `sign_in` | The QA seller's login comes from Infisical (OIDC), they sign in and choose the organisation workspace (`SX_SIM_WORKSPACE`, default Authority Closers). The fictional lead +1 202 555 0142 is added once (the add is idempotent). | No Infisical identity or login yet; the seller isn't in the workspace |
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
- Nightly at 03:00 IST once the Infisical identity below exists and the repository variable `SX_SIM_ENABLED` is `true`.

## The staging login: Infisical only, read with the run's GitHub OIDC token
AGENTS.md keeps secrets only in Infisical and the team password manager, so nothing is stored in GitHub. The run
asks GitHub for an OIDC token, exchanges it at Infisical for an access token, reads two keys, then revokes the
token. The values stay in `pipeline_sim.py`'s memory; only the session cookie reaches the emulator, masked in the log.

One-time setup (the owner, or the owner's operator on the owner's ask):
1. **Infisical, project `b421c44e-4599-4394-8df6-758ed8aedfed`, environment `staging`, folder `/qa/pipeline-sim`:**
   - add `SX_SIM_EMAIL` and `SX_SIM_PASSWORD`, the staging QA seller's login;
   - it is the existing staging smoke account in ac's `~/.config/ac-qa/staging-account.env`;
   - copy it from stdin (`infisical secrets set`) and never print it.
   The seller must be a member of the staging Authority Closers organisation (verified domain) with a complete
   profile and standing consent on.
2. **Infisical → Access Control → Identities → create `sales-xray-apps pipeline-sim`**, with auth method
   **OIDC Auth**:
   - OIDC discovery URL and Issuer: `https://token.actions.githubusercontent.com`;
   - Subject: `repo:authorityclosers/sales-xray-apps:environment:staging-sim`;
   - Audiences: `infisical-pipeline-sim`;
   - Claims: `repository` = `authorityclosers/sales-xray-apps`, `workflow_ref` starts with
     `authorityclosers/sales-xray-apps/.github/workflows/pipeline-sim.yml@`;
   - access token TTL 600 s, max TTL 600 s, uses 0 (unlimited).
3. **Add the identity to the project** with a custom role that can only read secrets in environment `staging`,
   path `/qa/pipeline-sim`.
4. **GitHub, sales-xray-apps → Settings → Variables (not secrets):**
   - `SX_SIM_INFISICAL_IDENTITY_ID` = the identity's id;
   - `SX_SIM_ENABLED` = `true` for the nightly.
   The environment `staging-sim` exists after the first run; give it no secrets and no reviewers.

For a run by hand on a developer machine, `SX_SIM_EMAIL` and `SX_SIM_PASSWORD` typed into that run's environment
take the place of Infisical.
- One analysis a night on staging: about 44 s of fictional audio from the staging workspace's minutes.

## What a run keeps (artifact `pipeline-sim-<n>`, 14 days)
- `summary.md`: the stage table, also in the run summary.
- `stages.jsonl`: one line per stage with seconds since the start.
- On a red run:
  - `device-jobs.json`: the phone's queue, with each job's state and hold reason;
  - `jobscheduler.txt`, `netpolicy.txt`, `process.txt`: whether Android ran the app's background work, and whether
    its network was blocked;
  - `logcat.txt` and `screen.png`.

No real customer audio or numbers are used. The password never leaves the helper, the session cookie is masked,
and the helper prints only the server's `detail` words.
