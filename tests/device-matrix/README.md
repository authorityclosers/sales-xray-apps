# Device matrix

`.github/workflows/device-matrix.yml` checks Sales Xray on every kind of device. It runs nightly at 02:00 IST, on every Android release, and by hand (Actions → Device matrix → Run workflow). Screenshots are kept for 14 days as artifacts named `shots-…`.

| Job | Where | What |
| --- | --- | --- |
| web-export | Ubuntu | The release's public pages: the platform repository at `web_ref` (`main` by default, what production runs) built as the web app's static export, for the browser jobs to serve on the runner (`serve.mjs`) |
| browsers-linux | Ubuntu | Chromium, Firefox and WebKit (Playwright) on five screens: 320, 390, 673 (foldable), 820 (tablet) and 1440 px |
| browsers-windows | Windows | Chrome and Edge (the runner's own), same five screens |
| safari-macos | macOS | Real Safari through safaridriver |
| ios-simulator | macOS | Safari on iPhone SE (3rd gen), 13 mini, 15 and 16 Pro Max, whichever the runner's Xcode has |
| android-emulators | Ubuntu | The published Android app on 12 emulators: Android 10 to 15 on small and medium phones, Pixels and a tablet. Five of them have budget memory (2-3 GB, like a Redmi A, Samsung A0x or Realme C). Between them they cover punch-holes, notches, big fonts, dark mode and both navigation styles. Each variant gets a screenshot and a check that the page starts below the status bar and cut-out |
| call-recorder | Ubuntu | Starts `android.yml` with `emulator=true`: an emulated SIM call and an internet call, each must make one tagged recording |

Today it covers the release's public, signed-out pages:

- the sign-in screen
- `/apps`
- privacy, pricing and terms

On each page it checks that the key words render and that nothing is wider than the screen.

## Cloudflare's bot check (needs the owner)

Production sits behind Cloudflare's bot check. The first run (19 Oct, run 39206406136) showed it to GitHub's Linux and Windows runners and to the Android emulators instead of Sales Xray; only the macOS runners got through. Tests never try to solve the check.

Since 27 Oct (the matrix was red every night until then), the browsers don't open production:
- the `web-export` job builds the same code that production runs;
- each browser job serves it on its own runner and opens that, so the check never comes up;
- there is no API there, so `serve.mjs` answers it as it would a signed-out visitor: every `/v1` read is 401, so `/dashboard` shows its sign-in, and the sign-in settings offer an email code only. Nothing is sent anywhere.

The emulators still open the real app. When the check holds it back, the job prints `CHALLENGED` and adds a line to the run's summary, but it doesn't fail; the layout checks still run.

Letting the runners through production is a Cloudflare setting for the owner. For example, a WAF skip rule for a header carrying a secret token, which the workflow sends from a GitHub secret.

## Not yet (needs the owner)

The signed-in flows need a test account the owner approves:

- sign-in
- Calls
- New analysis upload
- Report, Prospect and Coaching
- Leads
- lead → call → recording → analysis → confirm → follow-up

They also need its credentials stored as a GitHub secret. The dev site sits behind Cloudflare Access, which GitHub's runners can't pass, so these flows would run on production. Each nightly run would then spend one analysis.

## iOS app

There is no native iOS app yet; the iOS Simulator job tests the web app in Safari. A native app would need:

- an Apple Developer account;
- an iOS target in the Capacitor project (`npx cap add ios`);
- signing certificates and profiles as secrets;
- macOS build runners.

iOS never lets an app record phone calls; only in-app conversation recording would be possible.
