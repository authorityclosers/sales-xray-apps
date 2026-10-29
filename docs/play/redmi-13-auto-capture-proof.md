# Redmi 13 / HyperOS — orchestrator test script

No real-phone proof has been performed by the builder. Use only the owner's phone after the
owner enables wireless debugging and pairs it. Never open Play Store over adb. Never press a
permission prompt for the owner, including SAF folder access, call log or notifications.

## Setup
1. Orchestrator obtains the PR's CI APK (same reviewed SHA), records APK SHA-256 and version,
   and checks the companion web/API commit is deployed to the test origin. No production deploy
   or Play upload is part of this script.
2. Owner pairs wireless debugging. Orchestrator records `adb devices -l`, then chooses the
   Redmi serial explicitly: `export SX_TEST_SERIAL='<paired Redmi endpoint>'`.
   Read-only device facts:
   `adb -s "$SX_TEST_SERIAL" shell getprop ro.product.model`
   `adb -s "$SX_TEST_SERIAL" shell getprop ro.build.version.release`
   `adb -s "$SX_TEST_SERIAL" shell getprop ro.build.version.incremental`
3. Owner signs into a sandbox organisation with a seller account. Create a fictional lead
   "Alex Example" owned by that seller, using an owner-controlled full telephone number
   provided for this test. No customer number is used. The SIM is the owner's designated
   8999438781 phone. Keep that identifying number out of screenshots and CI fixtures.
4. Owner enables the phone dialler's own recording option and reads any dialler disclosure.
   In Settings → Privacy → Calls, owner enables standing analysis consent after reading the
   current words and upload terms. Owner grants notifications and uses Choose recordings
   folder to select the actual HyperOS dialler folder (usually MIUI/sound_recorder/call_rec).
5. Owner selects Wi-Fi only, taps Turn uploads on, reads the prominent disclosure and taps
   Turn on. Start from this moment; existing recordings must stay untouched.

## Main proof — owner's taps only
6. Owner taps Call on Alex Example and completes a 20–30 second test conversation with the
   consenting owner-controlled recipient. The owner hangs up. No adb call action is sent.
7. Put the app in the background. Allow the dialler to finish saving. Wait for the next
   WorkManager watch (nominal 15 minutes; HyperOS may defer it). Record the actual filename,
   timestamps, elapsed time and grant visibility locally, redacting the telephone number.
8. Confirm the quiet waiting notification identifies Alex Example and offers Undo. Tap Undo
   on this first test. After at least 90 seconds and a watch cycle, confirm zero new audio
   submissions and zero plan starts for that recording through our own app/API.
9. Owner makes a second test call, same lead and recipient. Do not tap Analyse or Upload.
   Observe waiting → uploading → analysing → report ready on the lead's call row. Confirm
   one submission, attached to that call_event_id, one accepted standing plan, and the report.
   Check the upload/plan consent audit events through authorised read-only application tools.
10. Force-stop and reopen only after the test upload completes:
    `adb -s "$SX_TEST_SERIAL" shell am force-stop com.authorityclosers.salesxray`
    Owner reopens the app. Confirm no second submission, attachment or analysis is created.

## Negative and recovery checks
11. Turn standing consent off, make another controlled lead call, wait a watch: zero uploads
    or plan starts. Turn it on again explicitly; held jobs require review and do not silently
    send. Also check AI Off and changed upload terms (sandbox configuration only).
12. Owner makes a controlled call to a consenting test number absent from this workspace's
    leads. Confirm no CRM event, notification, audio upload or plan starts for it.
13. Switch to Personal, try work-call logging: no Android READ_CALL_LOG prompt. Switch to
    another organisation or sign out while waiting: the old recording must not upload there.
14. Owner enables work-call logging in the organisation: prominent CRM disclosure, then owner
    personally grants READ_CALL_LOG. Make one dialler call to the test lead: correct number,
    time, duration and direction, one call row. A non-lead is absent. Disable it afterwards.
15. Wi-Fi only: disconnect Wi-Fi and keep cellular on before a test call. Confirm the upload
    waits. Reconnect and confirm one upload. Any network must be an explicit setting change.
16. Interrupt a sandbox upload by disabling connectivity; reconnect/reopen and confirm the
    same submission id is reconciled and no duplicate is created. Atomic PUT restarts bytes
    if incomplete; workflow stages resume. Do not claim byte-range resume.
17. Owner revokes the folder grant / notifications. Confirm no fresh audio leaves the phone.
    Restore only by the owner's taps. Confirm name-only and wrong-time files remain manual.

## Jio IVR alternative
The existing CRM validates full telephone numbers; 198 is deliberately not accepted as a normal
lead number by this implementation. Do not manufacture +91…198 or rename real audio to bypass
matching. Use the owner-tapped controlled full-number call above. A 198-only sandbox harness would
need a separately scoped short-code contract and is not claimed as proof here.

## Evidence
Save timestamps, app/API SHA, build SHA-256, screenshots (no customer names/numbers), observed
filename pattern, granted tree path (no audio), submission/call event selectors, standing consent
receipt references, duplicate counts and observed delays in the task scratch report. Upload real
recordings only to our own API under standing consent. No third-party test service receives them.
Delete test data only through the existing authorised deletion flow; no adb file deletion.
