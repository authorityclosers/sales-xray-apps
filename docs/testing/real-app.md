# Test the installed staging app

The Capacitor Android build is the reference for phone bugs. Localhost browser checks do not cover the bundled web copy, Android WebView, native bridge, permissions or system bars.

Staging installs beside Play as `com.authorityclosers.salesxray.staging`, labelled **Sales X-Ray Staging**, with a purple and gold icon. It uses `https://salesxray-staging.authorityclosers.com`. Its native sources (including Play Java) are shared, and its manifest is the **same Play overlay**, including auto-capture changes from PR #10; it does not gain the direct build's restricted recorder services. Its data and sign-in are separate from Play. Staging's source-set Capacitor config overrides the main asset after `cap sync`; the S9 manifest must name staging too.

## Build an exact staging revision

After merge, use the deployed **web** commit from `sudo /usr/local/sbin/ac-release --json status` → `environments.staging.web`. Core and web can differ: do not use core, latest main, or a moving branch name.

```bash
gh workflow run staging-android.yml -R authorityclosers/sales-xray-apps --ref main -f web_ref=FULL_40_CHARACTER_STAGING_WEB_SHA
```

The workflow bundles S9 from that exact platform checkout, builds `assembleStagingRelease` with the existing `android-release` environment and `SX_ANDROID_SIGNING_STORE_BASE64` / `SX_ANDROID_SIGNING_PASSWORD`, and keeps `sales-xray-staging-N` for 14 days. It does not publish a release or upload to Play. The existing environment approval is retained; no new signing secret is needed. PR builds run unit tests, compare staging and Play merged permissions/services, and compile a debug APK. Debug PR APKs have throwaway keys and are not the durable QA upgrade path. Missing signing secrets fail the signed build.

The artifact contains `sales-xray-staging.apk`, its checksum, `platform-sha.txt` and `apps-sha.txt`. Review the source pins when filing a bug. The staging flavour uses `sx_store=play` to retain native Play behaviour. Shared Play update/review entry points require the production package, so staging never launches a Play flow. A sideloaded staging package cannot receive Play distribution updates or submit a store review.

## One command on the owner's laptop

Install Python 3, Android platform-tools (`adb`), SDK build-tools (`aapt`), and GitHub CLI (`gh auth login`). Bash is available on macOS/Linux and Git Bash/WSL on Windows; use the same adb connection visible to your terminal. Enable USB debugging, unlock the phone, and accept its USB prompt. Use an existing **staging** lead and completed report (the report ID is the call ID in `/analysis/calls/ID`). Sign in to staging once with the email code; the script keeps that session and does not create accounts, place calls, upload audio, analyse calls or alter workspace records.

```bash
scripts/real-app-check.sh USB_SERIAL --lead STAGING_LEAD_ID --report STAGING_CALL_ID
```

This downloads the latest successful signed staging workflow artifact on **main**, verifies its package ID with `aapt`, checks both staging hosts and S9's platform SHA, installs/upgrades with `adb -s SERIAL install -r`, then opens Home, Leads, the lead, Calls, the report, Chats, Organisation and Settings. It never uninstalls, clears data, downgrades, drives the Play package or opens the store. Signature/version conflicts stop the run; use a newer durable staging APK, never remove the Play app.

For a specific build or offline use:

```bash
scripts/real-app-check.sh USB_SERIAL --apk /path/sales-xray-staging.apk --lead STAGING_LEAD_ID --report STAGING_CALL_ID --out /private/path/phone-check
scripts/real-app-check.sh USB_SERIAL --lead test-lead --report test-call --dry-run
```

`--aapt /path/to/aapt` overrides automatic SDK discovery. `--timeout 30` controls the FCP wait and `--settle 3` the additional screenshot delay. Dry run prints the intended eight launches and install without invoking adb/gh, creating locks, downloading files or writing evidence.

### One driver per phone

The canonical lock is `$HOME/.cache/sales-xray/phone-locks/SERIAL.lock` (override directory with `SX_PHONE_LOCK_DIR` or `--lock-dir`). Every phone-driving tool on the laptop must use that same lock. Acquisition uses an atomic exclusive file create. If the file already exists, this command refuses before downloads, install or adb. It never steals a lock. Normal exit, failures, Ctrl-C and SIGTERM release its own lock; after a killed process or power loss, inspect the lock's PID, stop all phone drivers, then manually remove the stale file. Do not run phone-journeys, Appium or manual automation concurrently.

### Evidence and limits

The private output directory contains `REPORT.md`, `report.json`, screenshots, per-screen `am start -W` output, staging-UID-only `logcat.txt` and extracted `errors.txt`. Review WebView console, Capacitor/bridge exceptions and AndroidRuntime crashes. Error extraction is a triage aid; inspect the complete log and screenshots. Errors or paint timeout/redirect return nonzero. A partial screen report is retained on launch/capture failures after logcat starts. No global logcat buffer is cleared.

Each screen starts a fresh process/document while retaining sign-in. The staging-only custom deep link is `com.authorityclosers.salesxray.staging:/dashboard/` (and the other allowlisted paths). The app logs Chromium's buffered **first-contentful-paint**, converted to milliseconds from Android process start; it is not `am start`'s activity time. The launch command's wall time includes adb overhead and is kept separately. FCP says pixels appeared, not that authenticated data finished loading. Missing signals are `unavailable`, never invented. Initial sign-in redirects are failures with the final URL recorded. Lead query selection and content, loading errors, empty screens and layout still require screenshot review. These checks do not prove call recording/upload/background capture: use PR #10's consent/retry/Undo proof separately.

Screenshots and logs may contain staging account data; keep them local/private, redact before sharing, and do not upload this report as a public CI artifact.

## Public staging reachability

On 29 Oct 2026, unauthenticated HTTPS GET `/dashboard` returned HTTP 200 and app HTML with `x-authority-closers-route: sales-xray-staging`, without an Access redirect or Access cookie. The ac check carries no browser session or CF Access headers. This is public reachability evidence from ac, not a physical phone/mobile-network test. Open staging over mobile data on the laptop-connected phone before the first signed-in check. If it redirects to Cloudflare Access, the operator must remove Access enforcement for the staging hostname (or add an explicit public bypass covering HTML, `/_next/*`, `/v1/*`, auth callbacks and media); a service token embedded in an APK is not a solution. Application sign-in still applies.

## Deploy hook on ac

Platform workbench supplies `infra/application/scripts/ac-staging-app-after-release` and its systemd service/timer. The root operator installs these **after this apps workflow is merged to main**:

```bash
sudo install -m 755 infra/application/scripts/ac-staging-app-after-release /usr/local/sbin/ac-staging-app-after-release
sudo install -m 644 infra/application/systemd/ac-staging-app-after-release.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ac-staging-app-after-release.timer
sudo systemctl start ac-staging-app-after-release.service
sudo journalctl -u ac-staging-app-after-release.service -n 30
```

The timer follows `ac-play-after-release`'s dispatch pattern, but reads deployed staging web state directly. It serializes ticks, dispatches the exact SHA to `staging-android.yml` on main, records successful dispatches, retries API failures and observes rollbacks. A minute poll targets the latest observed deployment, so very short-lived intermediate deploys between ticks are not built. GitHub's existing `android-release` approval still applies; it does not auto-approve. A failed build can be rerun in GitHub; delete `/var/lib/ac-staging-app-after-release/last` only when intentionally redispatching the current deployed revision. This session cannot install root services or read root-only release status; installation and activation are an operator handoff, not claimed as done.

## Firebase Test Lab readiness (disabled)

`tests/real-app/robo.yml` and `firebase-robo` in `staging-android.yml` are ready for a **signed-out**, one-physical-device Robo smoke check of this staging APK. The job is disabled unless `SX_STAGING_TESTLAB_ENABLED=true` **and** `SX_TESTLAB_PROJECT` is set. Do not enable before the owner supplies the project. Use a Spark project with no billing account so Google's shared five-physical-runs/day free cap cannot turn into spend. Existing Test Lab workflows share that quota; this job does not reserve five additional runs. Automated requests beyond the quota will be refused by Test Lab.

The owner/operator supplies `SX_TESTLAB_PROJECT`, chooses `SX_STAGING_TESTLAB_DEVICE` from `gcloud firebase test android models list` (e.g. `model=...,version=...,locale=en,orientation=portrait`), enables the Test Lab/Tool Results APIs, and grants the existing keyless workflow identity Test Lab Admin and Firebase Analytics Viewer on that project. See `tests/testlab/README.md` for the existing WIF identity. No service-account key or new secret is introduced. Only then enable the staging flag. Robo is signed out until a separately reviewed staging-only login fixture exists; it does not cover the eight authenticated screens. Results live in the owner's Firebase project.

### Provider sign-in / Connect dependency

Email-code sign-in is the supported way to run this QA script initially. Browser Google/Facebook/Apple return and provider Connect are **not yet proven for the staging package**: platform `http/auth.py` and `lead_desk/connectors/app_return.py` currently construct the production package's custom scheme. They must select the staging scheme from trusted staging environment/origin configuration, preserving the existing verifier/challenge rules, and the native return checks must recognize their own package. Do not register the production scheme in the staging manifest: that would send returns to a chooser or the Play app. The sign-in file owner has a concrete integration request in `/home/acdev/strikes/sign-in/INBOX.md`. Provider consoles must also register the staging package and durable QA certificate fingerprint for native SDK sign-in. These are feature-parity follow-ups, not reasons to test against production accounts or embed credentials.
