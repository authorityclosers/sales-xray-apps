# Sales X-Ray in the Microsoft Store: checklist and listing pack

Owner order, 28 Oct 2026: a signed Windows app anyone can download, and the Microsoft Store. This lists what this
repo has ready, what the owner's Partner Center account must give it, and the listing text to paste. Facts about
fees, checks and review times are from Microsoft's own pages (checked 28 Oct 2026); links are at the end.

## What is ready in this repo

- **`desktop.yml` builds the Store package.** Actions → Mac and Windows → Run workflow (Windows or both). Tauri
  makes no MSIX, so `apps/desktop/msix/make-msix.ps1` packs the release `.exe` with `msix/AppxManifest.xml`
  (full trust, internet only, Windows 10 1809 and later, x64) using the Windows SDK's MakeAppx.
  - **Store upload copy** `sales-xray-<version>-store-upload.msix`: carries Partner Center's identity, unsigned.
    The Store signs every MSIX with Microsoft's certificate, so this route needs no certificate of ours.
  - **Test copy** `sales-xray-<version>-test.msix` and its `-test-certificate.cer`: signed with a certificate made
    on the runner and thrown away with it. The run installs it, opens Sales X-Ray from it and takes a
    screenshot (`windows-msix-screen.png`). To try it on a PC: import the `.cer` into Local Machine → Trusted People
    (admin), then open the `.msix`.
- **Direct download (outside the Store)** stays the NSIS `.exe` and `.msi`. They are unsigned until the owner
  buys a code-signing certificate (see "Signed .exe outside the Store"). Unsigned, Windows SmartScreen says
  "Windows protected your PC"; More info → Run anyway.
- WebView2 comes with Windows 11 and current Windows 10, so the MSIX doesn't carry it. The `.exe` installer
  fetches it if a PC lacks it.

## What Partner Center must give this repo (owner)

1. **A developer account: Company, not Individual.** Free in the new sign-up flow at
   https://storedeveloper.microsoft.com → Get started. Microsoft's Individual account is for publishing that is
   not part of your business, and an Individual account can't become a Company one later. Company needs a
   D-U-N-S number (checked automatically) or business documents, and a work email on the company's domain
   (admin@authorityclosers.com).
2. **Reserve the name** "Sales X-Ray" (Apps and games → New product → MSIX or PWA app). If it's taken, use
   "Sales X-Ray: AI Sales Coach".
3. **Product identity** (Product management → Product identity) shows three values. They are not secrets. Set
   them as repository *variables* (Settings → Secrets and variables → Actions → Variables):
   - `SX_MSSTORE_IDENTITY_NAME` = Package/Identity/Name
   - `SX_MSSTORE_PUBLISHER` = Package/Identity/Publisher (starts `CN=`)
   - `SX_MSSTORE_PUBLISHER_DISPLAY_NAME` = Package/Properties/PublisherDisplayName
4. Run `desktop.yml` (Windows) on `main` and download `sales-xray-<version>-store-upload.msix` from the run.
5. Start a submission: upload that `.msix` under Packages, paste the listing below, answer the age rating
   questionnaire, and submit. The orchestrator approves before anything is submitted.

Certification takes up to three business days; the listing appears about 15 minutes after it passes.

## Listing (Store listings → English (India))

The package declares `en-IN`, so the listing language is English (India).

| Field | Text |
| --- | --- |
| Product name | Sales X-Ray (reserved name) |
| Short title | Sales X-Ray |
| Short description | Sales CRM with AI coaching on every call. Keep leads, calls and follow-ups in one place, and get a clear Report on what to do next. |
| Category | Business → CRM |
| Privacy policy URL | https://salesxray.authorityclosers.com/privacy |
| Website | https://authorityclosers.com |
| Support contact | https://salesxray.authorityclosers.com/contact |
| Additional license terms | https://salesxray.authorityclosers.com/terms |
| Copyright | © 2026 Authority Closers. All rights reserved. |
| Developed by | Authority Closers |
| Pricing | Free. No in-app purchases. |
| Search terms (7) | sales crm · call coaching · lead management · sales coach · call analysis · sales pipeline · telecalling |

**Description**

```
Sales X-Ray is the sales CRM that reads your calls like a top sales coach and tells you what to do next. Built for sales teams and closers who sell on the phone.

This is Sales X-Ray for your Windows PC: the full app in its own window, signed in once, a click away on the taskbar.

AI CALL REPORTS
• Every analysed call becomes a clear Report: what happened, what the buyer needs and what to do next.
• A coaching score rates 18 sales skills across how you talk, how well you understood the buyer and how you moved the sale forward.
• See your biggest strength and the one thing to fix, each linked to the exact moment in the call.
• Upload a recording you already have and get a Report.

LEADS AND PIPELINES
• All your leads on one page, with stage tabs and a To call list that puts late follow-ups first.
• Start calling takes you through your list one lead at a time, with your opener ready.
• Keep different kinds of selling apart, each with its own stages.
• Add leads in seconds: quick add, or paste rows from Google Sheets, Excel, a CSV or ChatGPT.

CHATS
• WhatsApp, Messenger and Instagram messages from your leads in one inbox, saved on each lead's page.

BUILT FOR TEAMS
• Invite your team by email, set roles, and let colleagues with your company email join.
• Owners and managers see the team at a glance: who's calling, who's running late and how leads move through the stages.

PRIVATE BY DESIGN
• Mark any call private to keep it out of the CRM and analysis.
• Your organisation's data stays yours. You can delete your account from Settings.

Reports are in English, with an optional second language. A Sales X-Ray account is needed; sign up free inside the app.
```

**What's new in this version:** `Sales X-Ray for Windows: the full app in its own window.`

**Product features** (one per line, up to 20)

```
AI Report on every analysed sales call
Coaching score across 18 sales skills
Your biggest strength and the one thing to fix
Leads with stages and a To call list
Start calling: one lead at a time, opener ready
WhatsApp, Messenger and Instagram in one inbox
Team view for owners and managers
Private calls stay out of the CRM
```

**Screenshots** (Desktop, PNG, at least 1366 x 768, up to 10): the side-notes folder
`store-listings-20261028/microsoft-store/` holds 2880 x 1800 shots of the real app on the dev review fixtures
(made-up company and people, no prices, no customer names). Upload them in file-name order.

**Store logos:** the 1:1 app tile icon (300 x 300) is `store-listings-20261028/microsoft-store/logo-300.png`.

## Properties and forms

| Form | Answer |
| --- | --- |
| Does the product access, collect or transmit personal information? | Yes (account, leads, call recordings the seller uploads). Privacy policy URL above. |
| System requirements | Internet connection. Minimum: Windows 10 version 1809. |
| Restricted capability `runFullTrust` | Sales X-Ray is a packaged Win32 desktop app (Tauri with Microsoft Edge WebView2). Full trust is how packaged desktop apps run; it adds no access beyond a normal desktop app. |
| Notes for certification | Sales X-Ray is a business CRM; signing in is required. Test account: [the owner types the QA account's email and password here, never in chat or git]. After sign-in the app opens the seller's home; Leads, Calls and Chats are in the sidebar. |
| Markets | India first; all markets is fine (the app is in English). |
| Visibility | Public, discoverable. For a quiet start: "Public audience, but not discoverable in the Store" (direct link only). |

### Age rating (IARC questionnaire): draft

Category: an app (not a game), business/productivity. Expected answers:

| Question area | Answer |
| --- | --- |
| Violence, fear, sexuality, nudity, language, drugs, alcohol, tobacco, gambling, crude humour | None |
| Users can interact or exchange content | Yes: sellers message their own business leads (WhatsApp, Messenger, Instagram inbox) and teammates see shared leads. No public profiles, no chat with strangers. |
| Shares the user's location with others | No |
| Digital purchases | No |
| Unrestricted internet access (a general web browser) | No: the app shows only Sales X-Ray |

Expected result: the lowest age band in most regions (IARC 3+, PEGI 3, ESRB Everyone), with the "Users Interact"
notice. IARC sets the final rating; answer the live questionnaire as it is worded.

## Signed `.exe` outside the Store

The Store route needs no certificate. A signed `.exe` for direct download does:

- **Azure Artifact Signing** ($9.99 a month) would be cheapest, but it onboards companies only in the US, Canada,
  EU, UK and a few others, and individuals only in the US and Canada. Not India.
- **SSL.com OV code signing with eSigner** (cloud key, works in GitHub Actions): $129 a year for the certificate
  plus eSigner from $20 a month (20 signatures). `sign-windows.ps1` is wired for it. Secrets:
  `SX_WINDOWS_SIGN_USERNAME`, `SX_WINDOWS_SIGN_PASSWORD`, `SX_WINDOWS_SIGN_TOTP_SECRET`; variable
  `SX_WINDOWS_SIGN_CREDENTIAL_ID` if the account holds several certificates. Only `desktop.yml` publish runs sign
  (about 3 signatures each), so the quota lasts.
- A newly signed app still builds SmartScreen reputation over its first downloads. The Store copy avoids that.

## Sources (Microsoft, checked 28 Oct 2026)

- Developer account, fees and checks: https://learn.microsoft.com/en-us/windows/apps/publish/partner-center/open-a-developer-account
- MSIX certification and Store signing: https://learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/msix/app-certification-process
- MSI/EXE submissions need a trusted code-signing certificate: https://learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/msi/app-package-requirements
- Artifact Signing countries and prices: https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart and https://learn.microsoft.com/en-us/azure/artifact-signing/how-to-change-sku
