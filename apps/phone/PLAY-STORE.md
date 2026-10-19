# Sales Xray on Google Play — checklist

Owner, 19 Oct: the Google Play developer account is being created today. This lists what the app side has ready, what is needed from the owner's accounts, and the Play Console forms.

## What is ready in this repo

- **Play build** (`-PsxPlay`). It has no accessibility call recorder (Play doesn't allow accessibility for call recording), no all-files access, and doesn't ask to skip battery optimisation. Dialer recordings are read as audio files instead, and setup opens Android's battery list. `READ_CALL_LOG` stays, and needs the Call log declaration below. CI checks the merged manifest and that the bundle builds on every push (`android.yml`).
- **`play.yml`** (Actions → Google Play → Run workflow, on main). It builds a signed App Bundle with version code 100000+run (above every sideload build) and version name 1.0.run, and puts it on the chosen track. Internal testing goes out to testers at once; other tracks stay drafts for the owner to roll out. It runs in the protected `play-release` environment, which the owner approves.
- **`scripts/play-upload.py`** uses the Play Developer API (edits → bundle upload → track → commit). It reads the service account from the environment and never prints it.

## What is needed from the owner's accounts

All secrets go into the GitHub environment `play-release` from the owner's laptop, with `gh secret set NAME --env play-release --repo authorityclosers/sales-xray-apps < file`. They are never pasted into chat.

1. **Play Console account** as an organisation (Authority Closers), with identity verification finished. Organisation accounts need a D-U-N-S number.
2. **Create the app** in Play Console: name "Sales Xray", default language English (India), App, Free. The package name `com.authorityclosers.salesxray` is set by the first upload.
3. **App signing.** We recommend letting Play use the existing durable key, the one that signs the published sideload APK, as the app signing key. Play Console → App signing → "Use a key from Java keystore" (PEPK export on the owner's laptop). Phones that installed the APK then update from Play without uninstalling.
4. **Upload key.** On the laptop:
   `keytool -genkeypair -v -keystore sales-xray-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`
   Register its certificate in Play Console. Then set the secrets `SX_PLAY_UPLOAD_STORE_BASE64` (`base64 -w0 sales-xray-upload.jks`), `SX_PLAY_UPLOAD_PASSWORD` and `SX_PLAY_UPLOAD_ALIAS` (`upload`).
5. **Service account for uploads.** In Google Cloud, create a project for the Play Console API, enable "Google Play Android Developer API", create a service account and download a JSON key. In Play Console → Users and permissions, invite the service account's email for this app with "Release to testing tracks" (and "Release to production" later). Set `SX_PLAY_SERVICE_ACCOUNT_JSON` from the file, then delete the file.
6. **First release by hand.** Google only accepts API uploads after the app has one bundle. Run `play.yml` once; it keeps the signed `.aab` as an artifact. Upload that file in Play Console → Testing → Internal testing → Create release. From then on, `play.yml` uploads by itself.
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
| **Foreground service (microphone)** | The in-app recorder records a meeting or an in-person sales talk that the seller starts. Notification while recording; stops when they stop. |
| Phone (`CALL_PHONE`) | No declaration needed |

If Google refuses the Call log declaration, the Play build drops `READ_CALL_LOG` and logs only calls placed from the app (start/end from the app's own call tracking). The sideload build keeps full logging.

### Data safety (draft)

- **Collected:** name and email (account); phone numbers of leads the seller works with, and their call times and lengths (app functionality); audio recordings the seller chooses to analyse (app functionality). Confirm whether any crash or analytics data is collected before submitting.
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
