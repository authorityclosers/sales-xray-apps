# Call log declaration — Play Console answers, ready to paste

Where: Play Console → Sales X-Ray → **App content** → **Permissions declaration form** (Call log). Play shows it
(and an App content alert) once a release contains the bundle with READ_CALL_LOG. Until it is filled, Play publishes
no change at all, store listing included. Steps follow Google's form (Play Console Help 9214102, checked 2 Nov 2026).

## Step 1 — Requested permissions
READ_CALL_LOG only. Declare it. If any other SMS or call-log permission is listed, stop: the bundle is wrong.

## Step 2 — Core functionality
Tick only: **Enterprise archive, business & enterprise customer relationship management (CRM), and/or enterprise
device management.** Sales X-Ray is not a default phone, SMS or assistant handler.

## Step 3 — Instructions for review (paste)
> Sales X-Ray is a sales CRM for companies. Call-log access is offered only inside a company's organisation
> workspace, after a prominent disclosure. Sign in with the account given below; it opens the organisation
> workspace "Example Sales". Open Leads > Now > "Log work calls by themselves" > Turn on. Read the disclosure
> "Log your company's lead calls", tap Enable call logging, then Allow. Call a lead's number from the phone's own
> dialer (or add a lead with a number you can call), then reopen Sales X-Ray: the call appears on that lead with
> its time, duration and direction. Calls with numbers that are not the workspace's leads are not uploaded.
> The app checks the organisation login before every sync; in a Personal workspace the feature is not offered and
> Android never asks. Logging can be turned off at any time (Leads or Settings > Selling).

## Step 4 — Video link (paste)
https://drive.google.com/file/d/1RZT2OClpx3frYw1nCHUApKGwz7cQw7sp/view
(42.2 s, 1920×1080, captions and English voice-over; shared "Anyone with the link"; local copy
`C:\Users\Suyash\Downloads\sales-xray-call-log-declaration.mp4`.)

## Step 5 — Restricted content access
Choose **All or some functionality is restricted**.
- Username and password: the reviewer account from App content → App access. The owner types them from the
  password manager; they never go in chat or in this file.
- Other instructions (paste):
  > Sign in with email and password. The account opens the organisation workspace "Example Sales", which has the
  > fictional lead "Alex Example" (+1 202 555 0123). Work-call logging is under Leads > Now.

The account's default workspace must be an organisation with at least one lead. Check this in production before
you submit.

## Step 6 — APK exceptions
Not shown: one bundle, no multi-APK.

## Step 7 — Confirmation
Tick both boxes (the declaration is accurate; the permissions are used only as declared). Save.

## Data safety (same submission)
App content → Data safety → Edit:
- **Data types:** add **Contacts → Contacts**. Keep every existing type.
- **For Contacts:**
  - Collected: Yes. Shared: No.
  - Processed ephemerally: No.
  - Collection is optional ("Users can choose").
  - Purpose: App functionality.
- Keep "encrypted in transit: Yes" and "users can request deletion: Yes".

Then Next → Save, and check that Publishing overview lists it.
