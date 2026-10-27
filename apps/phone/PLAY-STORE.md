# Sales Xray on Google Play — checklist

Owner, 19 Oct: the Google Play developer account is being created today. This lists what the app side has ready, what is needed from the owner's accounts, and the Play Console forms.

## What is ready in this repo

- **Play build** (the `play` flavour; the download-link app is `direct`; owner, 23 Oct 2026). It has no call history (`READ_CALL_LOG`, Play's SMS and Call Log policy), no direct calling (`CALL_PHONE`: the app opens the dialer), no accessibility call recorder (Play doesn't allow accessibility for call recording), no all-files access, and doesn't ask to skip battery optimisation. Without call history it doesn't log work calls from the phone's dialer, and "How did it go?" is answered by the seller. Dialer recordings are read as audio files, and setup opens Android's battery list. The app checks what its build declares and doesn't offer what's missing. CI checks both merged manifests and that the bundle builds on every push (`android.yml`). The Call log declaration below applies only if the Play build ever asks for call history again.
- **Play build extras (23 Oct 2026):** on Xiaomi and HyperOS, whose call recordings Android's media index never sees, the seller picks the recordings folder once ("Choose your recordings folder", Android's folder picker opened at `MIUI/sound_recorder/call_rec`), and the app reads that folder only (S8). Google Play in-app updates offer a new version, download it while the seller works, and restart into it with one tap ("No thanks" holds three days). Backup is off (`allowBackup=false`), so the sign-in and recorded conversations are never copied to Google Drive.
- **`play.yml`** (Actions → Google Play → Run workflow, on main). It builds a signed App Bundle with version code 100000+run (above every sideload build) and version name 1.0.run, and puts it on the chosen track. Internal testing goes out to testers at once; other tracks stay drafts for the owner to roll out. It runs in the protected `android-release` environment, which the owner approves.
- **`scripts/play-upload.py`** uses the Play Developer API (edits → bundle upload → track → commit). It reads the service account from the environment and never prints it.

## What is needed from the owner's accounts

All secrets go into the GitHub environment `play-release` from the owner's laptop, with `gh secret set NAME --env play-release --repo authorityclosers/sales-xray-apps < file`. They are never pasted into chat.

1. **Play Console account** as an organisation (Authority Closers), with identity verification finished. Organisation accounts need a D-U-N-S number.
2. **Create the app** in Play Console: name "Sales Xray", default language English (India), App, Free. The package name `com.authorityclosers.salesxray` is set by the first upload.
3. **App signing.** We recommend letting Play use the existing durable key, the one that signs the published sideload APK, as the app signing key. Play Console → App signing → "Use a key from Java keystore" (PEPK export on the owner's laptop). Phones that installed the APK then update from Play without uninstalling.
4. **Upload key.** On the laptop:
   `keytool -genkeypair -v -keystore sales-xray-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`
   Register its certificate in Play Console. Then set the secrets `SX_PLAY_UPLOAD_STORE_BASE64` (`base64 -w0 sales-xray-upload.jks`), `SX_PLAY_UPLOAD_PASSWORD` and `SX_PLAY_UPLOAD_ALIAS` (`upload`).
5. **Uploads, keyless (done 25 Oct 2026).** `play.yml` signs in with the run's GitHub OIDC token through Workload Identity Federation: pool `github`, provider `sales-xray-apps` in project 501399142248 (ac-sales-xray-ads), with the condition `repository == 'authorityclosers/sales-xray-apps'`. That impersonates `play-publisher@ac-sales-xray-ads.iam.gserviceaccount.com`, which holds `roles/iam.workloadIdentityUser` for this repository's principal set. androidpublisher, iamcredentials and sts are enabled. In Play Console the account may view app info and quality and release to testing tracks, never production. No JSON key exists anywhere; don't create one.
6. **First release by hand.** Google only accepts API uploads after the app has one bundle. Run `play.yml` once; it keeps the signed `.aab` as an artifact. Upload that file in Play Console → Testing → Internal testing → Create release. From then on, `play.yml` uploads by itself (internal testing goes out at once; alpha and beta stay drafts).
7. **Internal testers:** a list of the testers' Google account emails, for Internal testing → Testers. The opt-in link then goes to them.
8. **Reviewer sign-in** (App content → App access). Google's reviewers need a working account. Use the production QA account. The owner types its email and password into Play Console himself; they never go in chat.

## Play Console forms (App content)

| Form | Answer |
| --- | --- |
| Privacy policy | https://salesxray.authorityclosers.com/privacy |
| Ads | No ads |
| App access | Sign-in required; reviewer account (item 8) |
| Content rating | Business / productivity questionnaire: no violence, no user-to-user public content |
| Target audience | 18 and over (business users) |
| News app / Government / Financial features / Health | No |
| Data safety | See below |
| **Call log permission declaration** | Core purpose: business and enterprise CRM. A seller calls leads from the app; the app reads the call log only to log calls with those leads (when, how long, connected). Other numbers never leave the phone. Covered by the "Enterprise archive, business and enterprise CRM" exception; Google decides on review. |
| **Foreground service (microphone)** | Needed from the second Play version, which brings the in-app recorder back (owner, 27 Oct 2026; the first version left it out). Filed before the bundle goes to any track: see "Microphone declaration" below. |
| Phone (`CALL_PHONE`) | No declaration needed |

If Google refuses the Call log declaration, the Play build drops `READ_CALL_LOG` and logs only calls placed from the app (start/end from the app's own call tracking). The sideload build keeps full logging.

### Microphone declaration (second Play version)

**Order:** the owner records the video and uploads it to YouTube as Unlisted; then the declaration is filed with the link; only then does `play.yml` put a bundle with the recorder on a track. Play rejects a bundle whose foreground-service type has no declaration.

Play Console → App content → Foreground service permissions → **Microphone**:

| Field | Text |
| --- | --- |
| Use case | User-initiated audio recording (if Play only offers presets: "Background audio access", then describe the recording below) |
| Description | Sales X-Ray lets a seller record an in-person sales conversation (a meeting or a walk-in) so it can be analysed later. The seller taps "Record a conversation", reads what is recorded and agrees, then Android asks for the microphone. Recording runs as a foreground service with an ongoing notification and a Stop button, so it continues while the seller switches apps or the screen turns off. It never starts by itself or from the background, never runs during a phone call, and the recording stays on the phone until the seller chooses to analyse it. |
| Impact if deferred or interrupted | The conversation would be lost or cut off part-way: a live meeting can't be repeated, so recording must continue until the seller taps Stop. |
| Video link | The unlisted YouTube link |

**Video** (about 30 s, screen recording on a test phone signed in as the reviewer account):
1. 0–5 s: open Sales X-Ray → the You menu (your initial, at the top) → **Calls on this phone**.
2. 5–12 s: tap **Record a conversation → Record**. Sales X-Ray's explanation appears; tap OK. Android's microphone prompt appears; tap **While using the app**.
3. 12–18 s: recording starts and the timer runs. Pull down the notification shade: the ongoing **Recording** notification with Stop.
4. 18–24 s: press Home (the notification stays), then come back to the app.
5. 24–30 s: tap **Stop**. The recording is at the top of the list, with **Analyse**.

**Prominent disclosure:** the explanation in step 2 (`MIC_DISCLOSURE` in `sx-phone.js`) shows before Android's prompt until the microphone is allowed, and recording goes on only on the seller's OK.

**Reviewer note (App access):** add "To record a conversation: You menu → Calls on this phone → Record a conversation → Record."

**Data safety:** already declared (Audio → Voice or sound recordings: collected, optional, app functionality). Check it in the Console when filing; nothing new is collected.

### Data safety (draft)

- **Collected:** name and email (account); phone numbers of leads the seller works with, and their call times and lengths (app functionality); audio recordings the seller chooses to analyse (app functionality).
- **Crash logs and diagnostics (S6, Firebase Crashlytics):** collected, not shared, required (no switch). Data types: *Crash logs*, *Diagnostics*, and *Device or other IDs* (a Firebase installation ID, not linked to the seller). Purpose: *App functionality* and *Analytics* (finding and fixing crashes). Google Firebase processes them for us. A report carries the stack trace, device model, Android version, app version, store and web-copy version; never the seller's name, email, leads, numbers or recordings. Reports start once the phone has Sales X-Ray's alert settings (the same Firebase project as lead alerts). Name Firebase Crashlytics in the privacy policy before submitting.
- **Facebook Login (Facebook SDK 18.3, from 23 Oct 2026):** used only when the seller taps "Continue with Facebook". It has no app events and no advertising ID (`AutoLogAppEventsEnabled` and `AdvertiserIDCollectionEnabled` are off, and `AD_ID` is removed). Facebook gets what its sign-in needs, as an action the seller starts; Sales X-Ray gets the name and email (Account info, above) and keeps no Facebook token on the phone. Google sign-in uses Android's Credential Manager. Name Meta and Google as sign-in providers in the privacy policy.
- **Shared with third parties:** none for advertising. Recordings are processed by our AI providers acting for us (processing, not sharing).
- **Encrypted in transit:** yes. **Deletion:** to confirm before submitting — Google requires an in-app and a web way to ask for account deletion; check what Sales Xray offers today.
- Recordings stay on the phone until the seller picks one to analyse.

## Store listing (draft)

- **App name:** Sales Xray
- **Short description (80):** Call leads, log every call and get an AI Report and coaching for each sales call.
- **Full description:** Sales Xray is the calling desk for sales teams. Leads arrive from your forms, Meta and Google Ads; call them in one tap; say how each call went; and Sales Xray logs the call on the lead. Choose a call's recording and get its Report: what the prospect needs, how the call went, and coaching on what to say next time. Managers see the team's calls and follow-ups in one place.
- **Category:** Business. **Contact:** support email and website https://salesxray.authorityclosers.com
- **Graphics:** icon 512×512 (from the app icon), feature graphic 1024×500, and 4–8 phone screenshots (we capture them from the emulator matrix once the onboarding build is live).

## Installs outside Play: Android developer verification

The "Play Protect" warning appears because Google doesn't know the sideload APK's developer.

- Google's timeline: registration has been required since 30 Sep 2026 in Brazil, Indonesia, Singapore and Thailand, and goes global in 2027. India isn't named yet. Registering now removes the risk.
- With a verified Play Console account, Google registers Play apps automatically. Register the sideload package (same package name, durable key) from Play Console's developer verification page. Without Play, use the Android Developer Console.
- Needed from the owner: the organisation's verified identity (done once for Play), and proof of the signing key. Google gives a snippet to sign with the durable key; we sign it on the owner's laptop, where the key lives.
- "Restricted setting" when turning on the call recorder: Android 13+ blocks Accessibility for apps installed from a file. The person opens App info → ⋮ → Allow restricted settings, then Accessibility. The first-run screen says so. Play installs aren't restricted, but the Play build has no recorder.

Sources: [Play: Call log & SMS permissions](https://support.google.com/googleplay/android-developer/answer/10208820), [Play policy announcement, July 2026](https://support.google.com/googleplay/android-developer/answer/17134731), [Android developer verification rollout](https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console), [Android Developers Blog, June 2026](https://android-developers.googleblog.com/2026/06/android-developer-verification.html).
