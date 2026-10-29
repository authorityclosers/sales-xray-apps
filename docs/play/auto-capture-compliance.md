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

Nothing is prechecked. Not now and Back leave uploads off. Enabling starts from now; no historical
backfill. Wi-Fi only is the default (Android unmetered network constraint); Any network is explicit.
Maximum file size: 100,000,000 bytes. WorkManager may delay execution, especially on HyperOS.

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
| Personal info → Phone number | Collected: Yes, only workspace lead call numbers; ephemeral: No; optional: Yes; purpose: App functionality |
| App activity → Other actions | Collected: Yes, lead call time, duration and direction when available; ephemeral: No; optional: Yes; purpose: App functionality |
| Is all user data encrypted in transit? | Yes, HTTPS to the app's own origin |
| Can users request deletion? | Yes, existing Calls deletion and account deletion paths |
| Data used for advertising or sold? | No for this feature |
| Shared with third parties? | Existing consent-listed analysis service providers process audio only through the existing consent-gated plan. Apply Google's service-provider exception only where contractual conditions hold; the owner must reconcile the existing provider inventory. No new provider is introduced. |

Recordings follow the current server upload policy displayed before consent (standard policy up to
7 days, unless an explicitly approved training policy applies). Reports and CRM history follow
the existing workspace policy. Do not promise a new retention period. Pending native job metadata
is local, contains no audio copy or credential, and is excluded from cloud backup by the existing
manifest. SAF revocation blocks audio reads. Withdrawal blocks new automatic upload and plan starts;
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
