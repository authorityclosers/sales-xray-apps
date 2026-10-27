# Sales Xray apps

The Sales Xray apps for phones, computers and Chrome, by Authority Closers. Each app opens the Sales Xray web app (https://salesxray.authorityclosers.com), so every web release reaches all of them at once. Each adds only what a browser can't do on that device.

This repo holds app code and build workflows only. The web app, the server and everything private live elsewhere. There are no keys, keystores, certificates, passwords or `.env` files here, and the [No secrets](.github/workflows/no-secrets.yml) check fails any push that adds one.

## The apps

| Folder | App | Built with | Builds |
| --- | --- | --- | --- |
| [`apps/phone`](apps/phone) | Android and iPhone | Capacitor 8 | Android on every push to `main` that touches it; iPhone by hand |
| [`apps/chrome`](apps/chrome) | Chrome extension | Manifest V3 | Every push to `main` that touches it |
| [`apps/desktop`](apps/desktop) | Mac and Windows | Tauri 2 | By hand |

- **Android** places a lead's call from the app, fills in "How did it go?" from the phone's call history, and offers the phone's own recording for analysis. See [apps/phone/README.md](apps/phone/README.md).
- **iPhone** is the same shell. It builds for the Simulator and as an App Store archive; TestFlight and the App Store need the Apple Developer Program. See [apps/phone/APP-STORE.md](apps/phone/APP-STORE.md).
- **Chrome** adds leads to Sales Xray from any page with a right-click. It makes no network requests and keeps no sign-in. See [apps/chrome/README.md](apps/chrome/README.md).
- **Mac and Windows** open Sales Xray in its own window. Tray, alerts and call recording come later. The Microsoft Store package and listing are in [apps/desktop/MICROSOFT-STORE.md](apps/desktop/MICROSOFT-STORE.md).

## Builds

All builds run on GitHub's standard hosted runners. They are free while this repo is public.

| Workflow | Runs | Keeps | Publishes |
| --- | --- | --- | --- |
| [Android](.github/workflows/android.yml) | Push to `main` (`apps/phone`, not `ios/`), or by hand | Test APKs: Sales Xray (live) and a Dev test build (CI only) | Only by hand with "publish" on `main`: the live app alone, signed with the durable key. "signed_only" signs the same way for QA and keeps the APK as the run's artifact; the published release stays as it is |
| [Chrome extension](.github/workflows/chrome.yml) | Push to `main` (`apps/chrome`), or by hand | The extension zip | Only by hand with "publish" on `main` |
| [Mac and Windows](.github/workflows/desktop.yml) | By hand only | Windows `.exe` and `.msi`, Microsoft Store `.msix` (Store upload copy, and a test copy installed and opened on the runner), Mac universal `.dmg`, live and dev, plus screenshots | Only by hand with "publish" on `main`, and only when Windows is code-signed and the Mac app notarised: the `sales-xray-desktop-latest` release |
| [iPhone](.github/workflows/iphone.yml) | By hand only | Simulator app and screenshots; an App Store archive (unsigned until the Apple account) | Only by hand with "testflight" on `main`: uploads to App Store Connect for TestFlight |
| [No secrets](.github/workflows/no-secrets.yml) | Every push and pull request | Nothing | Nothing |

- Artifacts are kept for 14 days. Published files live on the `sales-xray-android-latest` and `sales-xray-chrome-latest` releases.
- Action versions are pinned to commits.

## Signing

- **Android:** builds use the durable Authority Closers key when the `SX_ANDROID_SIGNING_STORE_BASE64` and `SX_ANDROID_SIGNING_PASSWORD` secrets are set in this repo. Only the owner's laptop sets them, and the backup is in Infisical. Without them a build is signed with the runner's throwaway key. Such a build can be installed for a test, won't install over another build, and is never published.
- **Windows:** the Microsoft Store signs the Store MSIX itself, so the Store route needs no certificate. The `.exe` and `.msi` for direct download are code-signed with SSL.com eSigner on publish runs once `SX_WINDOWS_SIGN_USERNAME`, `SX_WINDOWS_SIGN_PASSWORD` and `SX_WINDOWS_SIGN_TOTP_SECRET` are set ([sign-windows.ps1](apps/desktop/sign-windows.ps1)). Until then they are unsigned: click More info, then Run anyway.
- **Mac:** Developer ID signed and notarised by Apple once `SX_APPLE_DEVELOPER_ID_P12_BASE64`, `SX_APPLE_DEVELOPER_ID_P12_PASSWORD`, the App Store Connect key (`SX_ASC_KEY_ID`, `SX_ASC_ISSUER_ID`, `SX_ASC_KEY_P8_BASE64`) and the variable `SX_APPLE_DEVELOPER_ID_IDENTITY` are set. Until then ad-hoc signed: on macOS 15 and later, open it once, then System Settings → Privacy & Security → Open Anyway.
- **iPhone:** Xcode signs with the team's cloud-managed certificates through the same App Store Connect key and the variable `SX_APPLE_TEAM_ID`. Without them the archive is unsigned and installs nowhere.
- All of these are set by the owner from the laptop (`gh secret set … < file`), backed up in Infisical, and never pasted into chat. Without them every workflow skips signing and says so.

## Where things live

- This repo is the only home of the app code. It moved here from `authorityclosers/authority-closers-platform` on 17 Oct 2026, from the dev workbench at `66f1daea`.
- The web side the apps talk to stays in that repo: `apps/sales-xray-web/app/leads/native-phone.ts` (the `SXPhone` channel) and `apps/sales-xray-web/app/leads/phone-app.tsx` (the Phone app dialog and its download link).

## Licence

Copyright Authority Closers. All rights reserved. The code is public so GitHub can build it. No licence is granted; see [LICENSE](LICENSE).
