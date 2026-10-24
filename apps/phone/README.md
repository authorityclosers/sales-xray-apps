# Sales Xray for Android and iPhone

The Sales Xray web app (https://salesxray.authorityclosers.com) in a Capacitor 8 shell. On Android it adds calling from the app and **Calls on this phone**, which work only on phones that record calls by themselves (owner order, 5 Oct 2026; AUT-1210). The iPhone app (`ios/`) is the same shell with no phone features yet, built for the iPhone Simulator.

## What it does

- Opens the live Sales Xray site, so every web release reaches the app with no app update.
- **Feels like an app, not a website in a box.** Every request carries the user-agent token `SalesXrayApp/<version>`, the installed build's own version (`1.0.N` from Google Play, `0.3.N` from the download link; `AppUserAgent`, with `SalesXrayApp/0.3` from capacitor.config.json as the fallback); the site checks `/\bSalesXrayApp\/\d/` to hide website links and sign-in chrome. The status and navigation bars take the page's own theme colour. The "can't be reached" screen shows only when the phone has no connection; a server error page shows as it is.
- **Google sign-in.** Google refuses sign-in inside apps, so "Continue with Google" opens in a Chrome Custom Tab with a one-time challenge only this app can answer (`GoogleSignIn.java`). The server hands a sealed 60-second code back to `com.authorityclosers.salesxray:/auth/return`, and the app's web view redeems it once at `/v1/auth/google/app-exchange` for its own session.
- **Connect Facebook & Instagram leads, Google Ads, WhatsApp, and Messenger & Instagram conversations from the app.** Facebook and Google refuse sign-in inside apps, so in the app Connect opens their sign-in in the phone's browser (a Custom Tab, never the Facebook app; `ConnectFlow.java`). The page keeps a one-time verifier and sends only its challenge. Sales Xray's callback there hands a sealed 10-minute result to `com.authorityclosers.salesxray:/connect/return`; the app opens the Connect's page again (`/leads?tab=sources` or `/organisation?tab=integrations`) and the page finishes on its own session with the verifier. The web side is `apps/sales-xray-web/app/leads/app-connect.ts`.
- **Call a lead from the app** (owner order, 16 Oct 2026). On a lead, Call asks once for three permissions: Phone, Call history, and Music and audio. Then:
  - the app places the call itself;
  - when the call ends, the app reads the phone's call history (connected or not, talk time) and the lead screen opens "How did it go?" already filled in;
  - the app looks for the recording the phone's own dialer saved for that call, and the lead screen offers it for analysis.
    Nothing is uploaded until the person ticks the consent box and taps Analyse. The web side is `apps/sales-xray-web/app/leads/native-phone.ts` in `authorityclosers/authority-closers-platform`.
- **Calls on this phone.** Opened from the site's app menu (`window.__sxPhone.openCalls()`, only inside the app), it lists the call recordings the phone saved by itself, from these folders:

  | Phone maker           | Folder                                                           |
  | --------------------- | ---------------------------------------------------------------- |
  | Samsung               | `Recordings/Call`                                                |
  | Xiaomi, Redmi, POCO   | `MIUI/sound_recorder/call_rec`                                   |
  | OnePlus, Realme, Oppo | `Recordings/Call Recordings`, `Music/Recordings/Call Recordings` |
  | Vivo                  | `Record/Call`                                                    |
  | Others                | `PhoneRecord`, `Call Recordings`                                 |

- **Tell me about new recordings.** A 15-minute background check (WorkManager) posts a notification for each new recording.
- **Dialer calls with a lead get their recording** (owner, 22 Oct 2026). Logging work calls (`CallLogSync`) puts calls made from the phone's own dialer, or taken on the phone, on the lead; Sales Xray answers with each call's id. A call that connected waits on the phone (`RecordingQueue`, three days) until the recording its dialer saved is found (`RecordingMatch`): the lead's number and the call's time must fit, a file named after another number never matches, and a recording is used for one call only. The page lists them (`pendingRecordings`) and uploads one only after the seller agrees to the terms; its Report joins that call on the lead. Nothing leaves the phone before that.
- **Silent recordings** (Redmi audit, 22 Oct). `AudioLevels` decodes the first three minutes on the phone and `AudioCheckRules` calls a recording silent when nobody is heard, so a silent one is flagged and not analysed.
- **Xiaomi, Redmi and POCO hide their recordings.** MIUI and HyperOS keep a `.nomedia` file in `MIUI/sound_recorder`, so on Android 11 and newer the app sees the recordings only with All files access. Phone setup says so and opens Android's screen (`needsAllFiles`, `setupOpen("allFiles")`). "Add the recording" opens Android's picker in the phone's recordings folder, which shows them either way.
- **Truecaller's after-call screen.** It covers "How did it go?" after a call placed from the app. If something still covers the app 3 seconds after the call, the app comes forward once more; at 7 seconds a "How did the call with … go?" notification brings it back.

## Limits

- **Google Phone app:** keeps its recordings private, so they can't be read.
- **Play builds and Xiaomi:** a Play build has no All files access, so a Xiaomi phone's hidden recordings are reached only through the picker until a folder grant is built.
- **Call history:** download builds read it to tell connected from not connected. Google Play allows that only for default phone apps, so a Play build would drop it and the person answers by hand.
- **iPhone:** gives apps no access to call audio.
- **Formats:** `.aac` and `.amr` are listed but cannot be analysed until the server accepts them.
- **Google sign-in in the Dev build:** the return link uses the package name, so only the live app (`com.authorityclosers.salesxray`) gets Google sign-in back. Use the email code in Dev. Connect's return works the same way: in Dev, connect from a computer.

## How it is built

- **Page-to-phone channel:** the page talks to the phone through an origin-restricted `WebViewCompat` message channel called `SXPhone`. It answers only Sales Xray's own sites and works under the site's content security policy. Methods: `status`, `requestAccess`, `requestCallAccess`, `call`, `lastCall`, `forgetCall`, `list`, `read`, `setWatch`, `openAllFiles`, `consumeLaunch`, `openConnect`. When a call ends the app also posts `{event: "call", call}` to the page.
- **Where the code lives:**
  - `MainActivity.java`: the shell, the offline rule and the system bar colours.
  - `GoogleSignIn.java`: Google sign-in through a Custom Tab.
  - `ConnectFlow.java` and `ConnectRules.java`: Connect's sign-in in the phone's browser, and the page its return opens.
  - `PhoneBridge.java`: the channel.
  - `CallTracker.java`: placing a lead's call, following it, the call history and matching the recording.
  - `RecordingScanner.java`: finding and reading recordings.
  - `RecordingWatchWorker.java`: the background check.
  - `android/app/src/main/assets/sx-phone.js`: the in-app panel.
- **CI build:** `.github/workflows/android.yml` builds two APKs on GitHub-hosted runners for pushes to `main`: "Sales Xray" (live site) and "Sales Xray Dev" (dev site, package `com.authorityclosers.salesxray.dev`, installs beside it). Download them from the run's artifacts. Build the dev one locally with `./gradlew assembleDebug -PsxDev` after pointing `capacitor.config.json` at dev.
- **Signing:** no key lives here. CI signs with the durable key from this repo's GitHub secrets when the owner has set them; otherwise with the runner's throwaway debug key, and such builds are never published. Versions are 0.3.x with codes from 1001, above the 0.2.x builds the main repo made.
- **iPhone:** `.github/workflows/iphone.yml` builds a simulator app by hand. No Apple account or signing yet.
- **Icons:** `scripts/make-icons.mjs` draws the Android and iPhone icons and splash screens, and the desktop app's source icon.

## Local build

```bash
npm ci --ignore-scripts
npx cap sync android
cd android && ./gradlew assembleDebug
```

iPhone (on a Mac with Xcode): `npx cap sync ios`, then open `ios/App/App.xcodeproj`.
