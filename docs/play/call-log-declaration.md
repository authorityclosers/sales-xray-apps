# READ_CALL_LOG — Permissions Declaration Form

Submit with the first Play bundle that retains READ_CALL_LOG. Do not upload that bundle without
this declaration. Play Console submission and approval belong to the orchestrator. Approval has
not been obtained by this implementation.

Policy: https://support.google.com/googleplay/android-developer/answer/10208820
Selected exception: **Enterprise archive, business & enterprise customer relationship management
(CRM), and/or enterprise device management**. Corporate login required for access.

## Form text
Core functionality:
> Enterprise CRM: logs sales calls with a company's leads so the company's sellers and managers
> see call history.

Permission: READ_CALL_LOG. Do not request SMS, WRITE_CALL_LOG, PROCESS_OUTGOING_CALLS, or
MANAGE_EXTERNAL_STORAGE for this feature. The app is not a default phone handler.

Why necessary:
> Sales X-Ray is a sales CRM. Signed-in company sellers may enable work-call logging after a
> prominent disclosure. The app matches call history numbers against leads visible in the current
> organisation workspace and sends only matching lead calls, with number, time, duration and
> direction. This captures business calls made outside the app. Personal-workspace users cannot
> enable this permission flow. Call history is not used to record audio, advertise or profile users.

Corporate login gating:
> Before asking Android for call-history permission, the app authenticates through its existing
> signed-in WebView session and requires a current organisation membership from its own API.
> The phone-book response identifies both organisation workspace and seller. Each background
> sync obtains a fresh phone book before querying history. A changed workspace/person or a
> failed membership check stops logging. Android permission alone never authorizes a sync.

Disclosure, shown before the runtime prompt:
> Sales X-Ray reads call history only while you are signed in to an organisation workspace, to
> log the number, time, duration and direction of calls with that workspace's leads. Other calls
> are not uploaded. You can turn work-call logging off at any time.

Retention/deletion:
> Uploaded call metadata follows the existing organisation CRM retention policy. Sellers can mark
> eligible calls private using the existing call control; deletion follows the existing call/account
> deletion process. The app's local pending recording jobs expire after three days. Local unknown
> call offers are a pre-existing direct-build feature and must not be enabled in the Play build.
> Audio retention is defined by the current upload terms, independently of call-log metadata.

## 45-second declaration video
0–8 s: launch and sign in to the fictional Example Sales organisation. Show organisation selection.
8–15 s: Leads → Enable work-call logging. Pause on the full disclosure and seller's explicit action.
15–22 s: show Android's READ_CALL_LOG permission prompt; the tester grants it personally.
22–32 s: show a seeded call with fictional lead +1 202 555 0123 appearing on that lead, with time,
duration and direction. Show a seeded non-lead call staying absent from CRM history.
32–39 s: switch to Personal. Show that enablement is rejected and no permission prompt appears.
39–45 s: return to the organisation; show Turn off and the existing call privacy/deletion controls.
Do not describe this permission as a call recorder. Folder upload is a separate demonstration.

## Data safety changes
Declare **Contacts → Contacts** (lead contact numbers and call history) collected for App functionality, optional,
non-ephemeral, encrypted in transit, user-deletable through existing paths. Add audio entries only
with the separate folder-upload feature (`auto-capture-compliance.md`). Reconcile the full app's
existing provider/SDK declaration; do not mark the whole app as collecting only these fields.
