# Call capture setup: real-phone proof (30 Oct 2026)

Platform: call capture setup (workbench f7fce521d, release v1.19.0, staging only until this proof passes).
Phone: Redmi 13 (USB 7505cec, HyperOS 3), one driver, lock `~/.cache/sales-xray/phone-locks/7505cec.lock`.
App: this pull request's staging build (debug-signed, staging flavour, web bundled from platform `main` after
v1.19.0 merged). Fresh install: uninstall the staging app first, so the install id is new.

Fictional staging data only: a staging QA seller in an organisation workspace with minutes, and a fictional lead
whose number the call goes to. No customer names or numbers in screenshots kept here.

## Steps and evidence

| # | Step | Pass when | Evidence |
|---|---|---|---|
| 1 | Fresh install, sign in, open Home | The setup opens over Home; Settings stays reachable | screenshot |
| 2 | Your name and phone (if the profile is incomplete) | Saved; next step opens | screenshot |
| 3 | What Sales X-Ray captures | The four lines and the approved words show; nothing ticked; I agree records the consent | screenshot, GET /auto-analysis `on` |
| 4 | Pick your call recordings folder | Android's picker opens at the recorder folder; Use this folder, Allow | screenshot of each screen |
| 5 | Let Sales X-Ray run in the background | Android's battery question, then Autostart (Xiaomi) | screenshots; `dumpsys deviceidle whitelist` |
| 6 | Turn uploads on | Notices allowed; the app's own Turn on dialog | screenshot |
| 7 | You're set | Shows the last recording found; Team lists the seller as Set up with the phone's model | screenshots |
| 8 | Close the app (swipe away), make an answered call to the lead, hang up | The recording uploads after its Undo time without opening the app | notice screenshots, server state |
| 9 | Report | The call reaches report ready on staging | report screenshot |

## HyperOS battery saver

Check after step 5 whether Android's battery question also set Settings, Apps, Sales X-Ray, Battery saver to
"No restrictions". If it didn't and step 8 waits until the app is opened, the app needs its own step for that
screen (`setupOpen("batterySaver")`): tell the orchestrator before merging anything.
