# READ_CALL_LOG on Google Play — submission checklist (one page)

The orchestrator submits this to Play Console. Nothing in it has been uploaded yet. Form text, video
script and Data safety: [call-log-declaration.md](call-log-declaration.md) and
[auto-capture-compliance.md](auto-capture-compliance.md).

## The package
| Item | Where |
| --- | --- |
| PR that adds READ_CALL_LOG to Play and flips the CI checks | authorityclosers/sales-xray-apps PR #12 (merge it only at step 3) |
| 45-second declaration video (mp4) | Owner's Drive, Sales X-Ray folder: `sales-xray-call-log-declaration.mp4` |
| Form text | call-log-declaration.md → "Form text" (paste each quoted block as written) |
| Data safety changes | call-log-declaration.md → "Data safety changes"; auto-capture-compliance.md table |
| Video build only (never merge; close after recording) | PR #13: staging app with call history |

## Before you start
1. **Production has platform v1.8.0 or later.** The Play app's call-log flow needs the phone book's
   `organisation_workspace` and `tenant_id` (platform e0340f6a6). Without them every workspace,
   including the reviewer's, gets `organisation_required`, and Google sees a feature that can't be
   turned on.
2. **Production web includes platform workbench a0e073172.** If Turn on is ever reached outside an organisation,
   the card says "Work-call logging is for your company's workspace…". Without it, the card says "Your phone
   didn't answer" instead. The Play bundle's `web_ref` must contain it too.
3. **Reviewer account** (App content → App access): its default workspace is an organisation with one
   fictional lead. Add this line to the instructions (500-character limit for the whole field):
   `Work-call logging: Leads → "Log work calls by themselves" → Turn on → read the disclosure →
   Enable call logging → Allow. Calls with that workspace's leads then appear on the lead.`
4. **Store listing.** Google wants the declared core feature in the full description. Add one
   sentence, for example: "In your company's workspace, Sales X-Ray logs calls with your leads from
   the phone's call history, after you turn it on." Then press Next → Save, and check that
   Publishing overview lists the change. A listing left as a draft is never submitted (24 Oct lesson).
5. **Video link.** The Drive file is shared as "Anyone with the link can view". Reviewers can't
   sign in to our Drive. A YouTube link (unlisted) is Google's preferred alternative.

## In order
1. Check the video once against the script: 0–8 s organisation, 8–15 s disclosure, 15–22 s Allow,
   22–32 s lead call logged and non-lead absent, 32–39 s Personal refused with no prompt, 39–45 s Turn off.
2. Mark PR #12 ready and check that CI is green on its head.
3. Merge PR #12.
4. Actions → Google Play → Run workflow on main: track alpha, `web_ref` = production's web commit.
   The run **stops on purpose** at "…call history only with its declaration" and keeps the
   artifact `sales-xray-play-<run>`. Download the `.aab`.
5. Play Console → Closed testing (Alpha) → Create release → upload that `.aab`. Play then asks for the
   Permissions Declaration.
6. Permissions Declaration (Call log):
   - Core functionality: business and enterprise CRM.
   - Exception: "Enterprise archive, business & enterprise CRM, and/or enterprise device management".
   - Paste the form text. Video URL: the Drive (or YouTube) link.
   - Instructions: as in "Before you start", step 3.
   - Save.
7. Data safety: add **Contacts → Contacts** (collected, optional, app functionality, not ephemeral,
   encrypted in transit, deletable). Keep every existing answer. Save, and check Publishing overview.
8. Publishing overview → Send changes for review (the release, declaration, Data safety and listing together).
9. Run `gh variable set PLAY_CALL_LOG_DECLARATION --body submitted --repo authorityclosers/sales-xray-apps`.
   From now on, play.yml and ac-play-after-release upload normally.
10. Record the submission time, the version code and the reviewer's decision in the auto-capture task.

## After Google decides
- **Approved:** run `gh variable set PLAY_CALL_LOG_DECLARATION --body approved --repo authorityclosers/sales-xray-apps`.
  Then test the work-call logging on a Redmi with the Play build (Leads → Turn on, owner taps Allow).
- **Refused:** revert PR #12, run `gh variable delete PLAY_CALL_LOG_DECLARATION --repo authorityclosers/sales-xray-apps`,
  and send the next play.yml bundle (without READ_CALL_LOG) to the same track, so the refused bundle is replaced.
  Auto-capture keeps working from dialer file names (PLAY-STORE.md, "If Google refuses").

## Don't
- Upload a READ_CALL_LOG bundle without the declaration in the same submission.
- Tap the permission prompt over adb for the video or for a test.
- Open the Play Store app over adb.
- Reload a Play Console tab while it shows a "Leave site?" popup.
- Promote while the target track has a draft.
