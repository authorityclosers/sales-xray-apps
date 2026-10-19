# Device matrix

`.github/workflows/device-matrix.yml` checks Sales Xray on every kind of device. It runs nightly at 02:00 IST, on every Android release, and by hand (Actions → Device matrix → Run workflow). Screenshots are kept for 14 days as artifacts named `shots-…`.

| Job | Where | What |
| --- | --- | --- |
| browsers-linux | Ubuntu | Chromium, Firefox and WebKit (Playwright) on five screens: 320, 390, 673 (foldable), 820 (tablet) and 1440 px |
| browsers-windows | Windows | Chrome and Edge (the runner's own), same five screens |
| safari-macos | macOS | Real Safari through safaridriver |
| ios-simulator | macOS | Safari on iPhone SE (3rd gen), 13 mini, 15 and 16 Pro Max, whichever the runner's Xcode has |
| android-emulators | Ubuntu | The published Android app on Android 10 (small phone), 13 (Pixel 6) and 15 (Pixel Tablet) |
| call-recorder | Ubuntu | Starts `android.yml` with `emulator=true`: an emulated SIM call and an internet call, each must make one tagged recording |

Today it covers production's public, signed-out pages:

- the sign-in screen
- `/apps`
- privacy, pricing and terms

On each page it checks that the key words render and that nothing is wider than the screen.

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
