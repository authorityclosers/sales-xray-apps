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
- **iPhone** is the same shell, built for the iPhone Simulator for now. Real iPhones need the Apple Developer Program.
- **Chrome** adds leads to Sales Xray from any page with a right-click. It makes no network requests and keeps no sign-in. See [apps/chrome/README.md](apps/chrome/README.md).
- **Mac and Windows** open Sales Xray in its own window. Tray, alerts and call recording come later.

## Builds

All builds run on GitHub's standard hosted runners. They are free while this repo is public.

| Workflow | Runs | Keeps | Publishes |
| --- | --- | --- | --- |
| [Android](.github/workflows/android.yml) | Push to `main` (`apps/phone`, not `ios/`), or by hand | Two APKs: Sales Xray (live) and Sales Xray Dev | Only by hand with "publish" on `main`, and only a build signed with the durable key |
| [Chrome extension](.github/workflows/chrome.yml) | Push to `main` (`apps/chrome`), or by hand | The extension zip | Only by hand with "publish" on `main` |
| [Mac and Windows](.github/workflows/desktop.yml) | By hand only | Windows `.exe` and `.msi`, Mac universal `.dmg`, live and dev, plus a screenshot | Never (unsigned) |
| [iPhone](.github/workflows/iphone.yml) | By hand only | Simulator app and a screenshot | Never |
| [No secrets](.github/workflows/no-secrets.yml) | Every push and pull request | Nothing | Nothing |

- Artifacts are kept for 14 days. Published files live on the `sales-xray-android-latest` and `sales-xray-chrome-latest` releases.
- Action versions are pinned to commits.

## Signing

- **Android:** builds use the durable Authority Closers key when the `SX_ANDROID_SIGNING_STORE_BASE64` and `SX_ANDROID_SIGNING_PASSWORD` secrets are set in this repo. Only the owner's laptop sets them, and the backup is in Infisical. Without them a build is signed with the runner's throwaway key. Such a build can be installed for a test, won't install over another build, and is never published.
- **Mac and Windows:** unsigned for now. On a Mac, right-click the app and choose Open. On Windows, click More info, then Run anyway. Signing comes with the store launch.
- **iPhone:** no signing. Simulator builds only, until the Apple Developer Program.

## Where things live

- This repo is the only home of the app code. It moved here from `authorityclosers/authority-closers-platform` on 17 Oct 2026, from the dev workbench at `66f1daea`.
- The web side the apps talk to stays in that repo: `apps/sales-xray-web/app/leads/native-phone.ts` (the `SXPhone` channel) and `apps/sales-xray-web/app/leads/phone-app.tsx` (the Phone app dialog and its download link).

## Licence

Copyright Authority Closers. All rights reserved. The code is public so GitHub can build it. No licence is granted; see [LICENSE](LICENSE).
