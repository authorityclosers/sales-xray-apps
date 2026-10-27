# Sales X-Ray on the iPhone: TestFlight and App Store checklist and listing pack

Owner order, 28 Oct 2026: an Apple-ready iPhone app. This lists what this repo has ready, what the owner's Apple
account must give it, the App Review risks to clear first, and the listing text to paste. Facts about fees,
enrolment and review times are from Apple's own pages (checked 28 Oct 2026); links are at the end.

## What is ready in this repo

- **`iphone.yml`** (Actions → iPhone → Run workflow), on GitHub's Macs with Xcode 26, which Apple requires for
  uploads since 28 Apr 2026:
  - **simulator:** a Simulator build, opened in a simulator, with screenshots.
  - **device:** a Release archive for real iPhones. Without the Apple account it is unsigned. It proves the App
    Store build compiles (arm64, iOS 15 and later, iPhone only, version 1.0.<run>, build <run>) and carries its
    privacy manifest, but installs nowhere.
  - **With the account's App Store Connect key** (below), Xcode signs it with the team's cloud-managed certificates
    and exports an `.ipa`. Tick **testflight** on `main` to upload it to App Store Connect.
- **`ios/App/App/PrivacyInfo.xcprivacy`:** no tracking and no tracking domains. Collected data, all linked to the
  seller's account and none used for tracking: name, email, audio (recordings the seller uploads), other user
  content (leads, notes), customer support, product interaction, performance data. The app itself reads only its
  own UserDefaults (reason CA92.1). Capacitor's framework carries its own manifest.
- **`Info.plist`:** `ITSAppUsesNonExemptEncryption = false` (HTTPS only, so no export-compliance questions on each
  upload); `arm64`; iPhone only (no iPad screenshots needed; iPads run it in iPhone mode).

## What the Apple account must give this repo (owner)

1. **Apple Developer Program** membership (99 USD a year). From India, enrolment goes through the **Apple Developer
   app** on an iPhone, iPad or Mac, not the website. Organisation (Authority Closers) or Individual: see the
   owner-steps note.
2. **App Store Connect → Apps → + New App:** iOS, name "Sales X-Ray: AI Sales Coach", primary language English
   (India), bundle ID `com.authorityclosers.salesxray` (register it under Certificates, IDs & Profiles →
   Identifiers first if the list doesn't offer it), SKU `sales-xray-ios`, Full Access.
3. **App Store Connect API key** (Users and Access → Integrations → App Store Connect API → Team Keys → +), access
   **Admin**: cloud-managed signing needs Admin. Download the `.p8` once. From the owner's laptop, never in chat:
   - `gh secret set SX_ASC_KEY_ID --repo authorityclosers/sales-xray-apps` (the Key ID)
   - `gh secret set SX_ASC_ISSUER_ID --repo authorityclosers/sales-xray-apps` (the Issuer ID)
   - `base64 -w0 AuthKey_XXXX.p8 | gh secret set SX_ASC_KEY_P8_BASE64 --repo authorityclosers/sales-xray-apps`
   - `gh variable set SX_APPLE_TEAM_ID --repo authorityclosers/sales-xray-apps --body <Team ID>` (Membership page;
     not a secret)
   - Back the `.p8` up in Infisical (prod `/application`, `AC_APPLE_ASC_KEY_P8`).
   The same key notarises the Mac app (`desktop.yml`).
4. Run `iphone.yml` with **testflight** ticked on `main` (the orchestrator approves). The build shows in TestFlight
   once Apple has processed it.
5. **TestFlight → Internal Testing:** add the team (up to 100 App Store Connect users). Internal builds need no
   review. External testers (up to 10,000, by public link) need Beta App Review for the first build.

## App Review risks: clear these before submitting to the App Store

TestFlight internal testing can start without these. App Store review will check them.

1. **Google sign-in fails inside the app.** Google refuses sign-in from embedded web views (a WKWebView), so
   "Continue with Google" breaks on the iPhone. Android solved this with native sign-in; the iPhone app has none yet.
2. **4.8 Login Services.** If Google or Facebook sign-in sets up the account, the app must also offer an
   equivalent private login (Sign in with Apple meets it). **Quickest fix for both 1 and 2:** the web app hides
   the Google and Facebook buttons inside the iPhone app (user agent has `SalesXrayApp/` and `iPhone`), leaving
   email-code sign-in. A web-app task, proposed to the orchestrator.
3. **3.1.1 / 3.1.3 Payments.** Outside the US storefront, the app may not sell plans with anything but Apple's
   in-app purchase, nor point users to buy elsewhere. The iPhone app must show no plan, upgrade, checkout or
   "buy on our website" path. It shows no prices already (house rule); the buy paths must also be hidden for the
   iPhone app user agent. A web-app task.
4. **4.2 Minimum Functionality.** Apple rejects apps that are a repackaged website. The iPhone app today is the
   web app in a shell. Before App Store submission, add native value, for example (proposed task, this repo):
   push alerts for new leads and nudges (APNs through the Firebase project Android already uses), Home Screen
   quick actions (Add lead, Calls, Chats, as on Android), haptics through the same `SXHaptics` channel, the share
   sheet, and an after-call "How did it go?" when a call placed from the app ends (CallKit's call observer). Explain them in
   the review notes.
5. **5.1.1(v) Account deletion.** Settings → Delete account exists in the web app (23 Oct 2026). Check that it is
   reachable inside the iPhone app before submitting.

## Listing (App Store Connect → App Information and the 1.0 version page)

| Field | Text |
| --- | --- |
| Name (30) | Sales X-Ray: AI Sales Coach |
| Subtitle (30) | Calls, leads and AI coaching |
| Primary category | Business |
| Secondary category | Productivity |
| Privacy Policy URL | https://salesxray.authorityclosers.com/privacy |
| Support URL | https://salesxray.authorityclosers.com/contact |
| Marketing URL | https://authorityclosers.com |
| Copyright | 2026 Authority Closers |
| Price | Free. No in-app purchases. |
| Keywords (100) | `sales crm,call coaching,lead management,sales coach,call analysis,pipeline,telecalling,closer` |

**Promotional text (170)**

```
Every analysed sales call becomes a clear Report: what happened, what the buyer needs and the one thing to fix on your next call.
```

**Description**

```
Sales X-Ray is the sales CRM that reads your calls like a top sales coach and tells you what to do next. Built for sales teams and closers who sell on the phone.

AI CALL REPORTS
• Every analysed call becomes a clear Report: what happened, what the buyer needs and what to do next.
• A coaching score rates 18 sales skills across how you talk, how well you understood the buyer and how you moved the sale forward.
• See your biggest strength and the one thing to fix, each linked to the exact moment in the call.
• Upload a recording you already have and get a Report.

LEADS AND PIPELINES
• All your leads on one page, with stage tabs and a To call list that puts late follow-ups first.
• Start calling takes you through your list one lead at a time, with your opener ready.
• Call, WhatsApp or text a lead in one tap, then log how it went and book the next call.
• Add leads in seconds: quick add, or paste rows from Google Sheets, Excel, a CSV or ChatGPT.

CHATS
• WhatsApp, Messenger and Instagram messages from your leads in one inbox, saved on each lead's page.

BUILT FOR TEAMS
• Invite your team by email, set roles, and let colleagues with your company email join.
• Owners and managers see the team at a glance: who's calling, who's running late and how leads move through the stages.

PRIVATE BY DESIGN
• Mark any call private to keep it out of the CRM and analysis.
• Your organisation's data stays yours. Delete your account any time from Settings.

Reports are in English, with an optional second language. A Sales X-Ray account is needed; sign up free in the app.
```

Unlike the Play listing, this leaves out automatic call logging and dialler recordings: iOS gives apps neither.

**What's New (1.0):** `The first Sales X-Ray for iPhone.`

**Screenshots:** the side-notes folder `store-listings-20261028/app-store/` holds the iPhone sets from the real app
on the dev review fixtures (made-up company and people, no prices, no customer names):
`iphone-6.9/` (1320 x 2868) and `iphone-6.5/` (1284 x 2778). Apple needs one of the two; upload 6.9 if both fit.

**App icon:** the 1024 x 1024 icon is in the app (`Assets.xcassets/AppIcon.appiconset`); App Store Connect takes it
from the build.

## App Privacy (App Store Connect → App Privacy): draft

Tracking: **No.** Data linked to the user, for App Functionality unless noted:

| Apple category | Data type | Notes |
| --- | --- | --- |
| Contact Info | Name, Email Address | The seller's account |
| User Content | Audio Data | Recordings the seller uploads for analysis |
| User Content | Customer Support | Bug reports and support messages |
| User Content | Other User Content | Leads, notes, call outcomes the seller enters |
| Usage Data | Product Interaction | App Functionality and Analytics, under the seller's consent |
| Diagnostics | Performance Data | Analytics (page speed) |

Keep this, `PrivacyInfo.xcprivacy` and the privacy policy in step. The CTO confirms the list against what the web
app collects today before the first submission; add anything new (for example phone numbers, if the seller's own
number is collected).

## Age rating (App Store Connect questionnaire): draft

No violence, sexual content, profanity, drugs, gambling, horror or mature themes; no unrestricted web access (the
app shows only Sales X-Ray). Messaging: sellers message their own business leads (WhatsApp, Messenger, Instagram
inbox); no chat with strangers, no public profiles. No user-generated content shown to the public. Apple computes
the rating from the answers; the lowest band is expected. Answer the live questionnaire as it is worded.

## App Review Information

- Sign-in required: yes. The owner types the QA account's email and password into App Store Connect (never in
  chat or git). Email-code sign-in needs a code: give Apple a password account, or note how the reviewer gets in.
- Notes: "Sales X-Ray is a sales CRM for businesses. After sign-in: Home, Leads, Calls and Chats. Calls placed
  from a lead open the iPhone's Phone app."
- Contact: the owner's name, phone and email (owner fills in).

## Sources (Apple, checked 28 Oct 2026)

- Enrolment (India via the Apple Developer app), fee, organisation requirements: https://developer.apple.com/programs/enroll/ and https://developer.apple.com/support/enrollment/
- D-U-N-S (free; up to 5 business days, then up to 2 for Apple): https://developer.apple.com/help/account/membership/D-U-N-S/
- Review times (50% within 24 hours, 90% within 48): https://developer.apple.com/distribute/app-review/
- TestFlight (100 internal, 10,000 external, 90-day builds): https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview
- Review Guidelines 3.1, 4.2, 4.8, 5.1.1(v): https://developer.apple.com/app-store/review/guidelines/
- Xcode 26 / iOS 26 SDK for uploads: https://developer.apple.com/news/upcoming-requirements/
