# Automatic lead call upload — Play submission companion

29 October 2026. Ship with `call-log-declaration.md`; Console submission belongs to the orchestrator.

## Disclosure and controls
Settings → Privacy → Calls. Standing consent must first be on. Choose recordings folder opens
Android's SAF tree picker (the seller grants it). Turn uploads on shows the exact current server
standing-consent `notice.text` and upload-policy `privacy_details`, followed by:

> Sales X-Ray checks the recordings folder you choose, including in the background. Only files
> with a lead's number and a matching time are uploaded to your workspace for analysis. You have
> 60 seconds to Undo before upload. Audio is encrypted in transit and can be deleted from Calls.
> Turn this off in Settings at any time.

Nothing is prechecked. Not now and Back leave uploads off. The first turn-on starts from now; no historical
backfill. Turning uploads on again for the same seller and workspace asks, with nothing chosen, whether to also
upload the lead call recordings saved while uploads were off ("Upload them" / "Skip them", at most 3 days back,
each with its own 60-second Undo); Skip starts from now. Calls already waiting or held when uploads went off are
kept and go once uploads are on again. Turn uploads on first checks the account's write eligibility (the same
check the edge makes before audio) and says "complete your profile" instead of turning on.
Wi-Fi only is the default (Android unmetered constraint plus an active Wi-Fi transport check); Any network is explicit.
Maximum file size: 100,000,000 bytes. WorkManager may delay execution, especially on HyperOS.

## Held calls and notices (Redmi real-call test, 30 Oct 2026)
A call the server or phone can't take yet waits on the phone ("held") with its reason, and the seller is told by a
notification on the "Call uploads that need you" channel (at most once per reason every 6 hours, or when more calls
wait) and on the capture-health card in Settings → Privacy → Calls:
- contact profile incomplete (the edge's write-eligibility 403): opens Settings → Profile; uploads stay on and the
  call goes by itself once eligibility answers 204;
- call minutes used up (409 at upload): uploads stay on; the watch reads the workspace allowance and sends the call
  once minutes exist;
- signed out, another workspace, folder grant gone, background network blocked: uploads stay on and resume by
  themselves; nothing goes to another workspace or seller;
- only standing-consent loss (off, or its terms changed), blocked upload notices (no Undo) or a sign-out on this
  phone turn uploads off, and each of these also notifies.

## Background network (Android 15+ / HyperOS)
Android 15+ cuts a cached app off the network (netpolicy `blocked=APP_BACKGROUND`). A capture worker that finds its
network blocked asks for a JobScheduler job with a network constraint (expedited on Android 12+, falling back to a
regular job when out of quota), which lifts the block while it runs, and waits up to 90 seconds instead of failing.
No foreground service, foreground-service type or permission is added: the Play bundle has none (play.yml refuses
them without a declaration). If the phone still blocks uploads for 15 minutes, the seller is told to set battery use
to No restrictions.

The Play app does not create call recordings: the phone dialler does. A readable persisted folder
grant is required. No MANAGE_EXTERNAL_STORAGE, CALL_PHONE, microphone/recording foreground service,
or accessibility call recorder is added by this change. READ_CALL_LOG is a separate Enterprise CRM
exception; it is never needed to scan granted folders.

## Data safety — exact entries to add/update
These are additions to the existing complete app declaration, not replacement answers for unrelated
analytics, authentication or crash-reporting SDKs.

| Console question | Answer for this feature |
| --- | --- |
| Does the app collect or share required user data types? | Yes |
| Audio files → Voice or sound recordings | Collected: Yes; processed ephemerally: No; optional: Yes; purpose: App functionality |
| Contacts → Contacts | Collected: Yes, workspace lead contact numbers and call history (time, duration, direction when available); ephemeral: No; optional: Yes; purpose: App functionality |
| Personal info → Phone number | Keep the existing account-phone answer; this feature adds lead contact numbers under Contacts, not the seller’s own number |
| Is all user data encrypted in transit? | Yes, HTTPS to the app's own origin |
| Can users request deletion? | Yes, existing Calls deletion and account deletion paths |
| Data used for advertising or sold? | No for this feature |
| Shared with third parties? | Existing consent-listed analysis service providers process audio only through the existing consent-gated plan. Apply Google's service-provider exception only where contractual conditions hold; the owner must reconcile the existing provider inventory. No new provider is introduced. |

Recordings follow the current server upload policy displayed before consent (standard policy up to
7 days, unless an explicitly approved training policy applies). Reports and CRM history follow
the existing workspace policy. Do not promise a new retention period. Pending native job metadata
(including why a call is held and the server's refusal reason) is local, contains no audio copy or credential, and
is excluded from cloud backup by the existing manifest. SAF revocation blocks audio reads. Withdrawal blocks new automatic upload and plan starts;
server upload completion checks cover withdrawal during transfer.

## OEM evidence and limits
The parser supports numeric identity plus compact `yyMMddHHmmss`, Samsung `yyMMdd_HHmmss`,
`yyyyMMddHHmmss`, and separated `yyyy-MM-dd_HH-mm-ss` clocks. OEM labels are fixture variants,
not certification for every dialler version. Name-only recordings remain manual. OnePlus/Google
Dialer may keep recordings in app-private storage; SAF cannot bypass Android restrictions.

Official storage references: https://community.oneplus.com/thread/824415 and
https://static.realme.net/common/pdf/realmeUI-1.0-UserGuide-V1.0.pdf . Neither establishes a universal
filename contract. Redmi/HyperOS confirmation remains a real-phone release gate.

The upload API has atomic PUT, not byte-range resumption. WorkManager resumes the workflow by
reconciling the same submission UUID before sending; an interrupted unaccepted transfer restarts
its bytes. Never claim byte-range resume. Stable UUIDs and attachment/plan keys prevent duplicate
server objects on retries. No new long-lived phone secret is used.

Google's Data safety taxonomy puts contact recency, interaction duration and call history under
**Contacts**, even though this feature never requests READ_CONTACTS. Source:
https://support.google.com/googleplay/android-developer/answer/10787469?hl=en .
