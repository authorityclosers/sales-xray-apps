# READ_CALL_LOG on Google Play — submission checklist (one page)

The orchestrator submits this to Play Console. Nothing in it has been uploaded yet. Paste-ready form answers:
[call-log-form-answers.md](call-log-form-answers.md). Background: [call-log-declaration.md](call-log-declaration.md).

## The package
| Item | Where |
| --- | --- |
| PR that adds READ_CALL_LOG to Play and flips the CI checks | authorityclosers/sales-xray-apps PR #12 (merge it at step 2 of "In order") |
| Declaration video (42.2 s, 1080p, captions and voice-over; footage recorded 29 Oct on the Redmi 13 against staging v1.8.0) | Drive "Sales X-Ray Play listing": [sales-xray-call-log-declaration.mp4](https://drive.google.com/file/d/1RZT2OClpx3frYw1nCHUApKGwz7cQw7sp/view) |
| Form answers, field by field | [call-log-form-answers.md](call-log-form-answers.md) |
| Data safety changes | call-log-declaration.md → "Data safety changes"; auto-capture-compliance.md table |
| Video build (closed, never merged) | PR #13: the staging app with call history, run 40877680915 |

## Already true (checked 2 Nov 2026)
- Production runs v1.31.0: the organisation phone book (v1.8.0) and the Personal refusal line (a0e073172) are in.
- The video is shared "Anyone with the link" and opens without signing in. The call-log flow and disclosure are
  unchanged on apps main since it was recorded.
- PR #12 is current with apps main (#19, #20).

## In order (Orchestrator = O, Owner = OWNER)
1. O: check that the reviewer account in App access opens an organisation workspace with a lead in production
   (Step 5 of [call-log-form-answers.md](call-log-form-answers.md)).
2. O: mark PR #12 ready and check that CI is green. Merge it.
3. O: Actions → Google Play → Run workflow on main: track alpha, `web_ref` = production's web commit. The run
   **stops on purpose** at "…call history only with its declaration" and keeps the artifact
   `sales-xray-play-<run>`. Download the `.aab`.
4. O (browser): Play Console → Test and release → Closed testing → Alpha → Create new release → upload the `.aab`,
   then add release notes → Next. Play flags the call-log permission.
5. O (browser): App content → Permissions declaration form. Paste every step from
   [call-log-form-answers.md](call-log-form-answers.md). Leave the Step 5 password empty for the OWNER.
6. O (browser): App content → Data safety: add Contacts → Contacts (as in the answers file). Store listing → full
   description: add "In your company's workspace, Sales X-Ray logs calls with your leads from the phone's call
   history, after you turn it on." Then Next → Save. A listing left as a draft is never submitted.
7. OWNER: type the reviewer password in Step 5 → Save. Publishing overview → check that it lists the release,
   the declaration, Data safety and the listing → **Send changes for review**.
8. O, right after: `gh variable set PLAY_CALL_LOG_DECLARATION --body submitted --repo authorityclosers/sales-xray-apps`.
   From then on, play.yml and ac-play-after-release upload with call history. Avoid new alpha uploads while the
   review runs: each one is a new release for review.

## After Google decides
- **Approved:** run `gh variable set PLAY_CALL_LOG_DECLARATION --body approved --repo authorityclosers/sales-xray-apps`.
  Then test the work-call logging on a Redmi with the Play build (Leads → Turn on, owner taps Allow).
- **Refused:** revert PR #12, run `gh variable delete PLAY_CALL_LOG_DECLARATION --repo authorityclosers/sales-xray-apps`,
  and send the next play.yml bundle (without READ_CALL_LOG) to the same track, so the refused bundle is replaced.
  Auto-capture keeps working from dialer file names (PLAY-STORE.md, "If Google refuses").

## Don't
- Upload a READ_CALL_LOG bundle without the declaration in the same submission.
- Open the Play Store app over adb.
- Reload a Play Console tab while it shows a "Leave site?" popup.
- Promote while the target track has a draft.
