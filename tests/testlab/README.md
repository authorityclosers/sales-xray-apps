# Firebase Test Lab, free (Spark)

`.github/workflows/testlab.yml` runs Robo on Google's own phones after each green Android build on main. It uses one
physical budget phone and two virtual phones (the oldest and the newest Android in range), at most 3 runs a day. Spark
allows 10 virtual and 5 physical test runs a day.

## Where and who

- **Project:** `sales-xray-alerts`, a Firebase project on Spark with no billing account, so it can't be charged.
  - Its Cloud Testing API and Cloud Tool Results API are on (orchestrator, 27 Oct 2026).
  - Never upgrade it to Blaze, and never add Firebase to a Cloud project that has billing: that puts it on Blaze.
- **Identity:** `play.yml`'s keyless one. The run's GitHub OIDC token goes through Workload Identity Federation (pool
  `github`, provider `sales-xray-apps` in project `501399142248`, trusting only this repository) and acts as
  `play-publisher@ac-sales-xray-ads.iam.gserviceaccount.com`.
  - `scripts/testlab.py credentials` writes the credential file for gcloud. It is 0600 in the runner's temp folder,
    never printed, and deleted when the job ends.
  - No key exists anywhere.

## What is left to switch it on

1. **The owner's grant** (Google Cloud console → `sales-xray-alerts` → IAM → **Grant access**). Principal:
   `play-publisher@ac-sales-xray-ads.iam.gserviceaccount.com`. Roles:
   - **Firebase Test Lab Admin** (`roles/cloudtestservice.testAdmin`)
   - **Firebase Analytics Viewer** (`roles/firebase.analyticsViewer`)
2. **Where results go.**
   - Test Lab's default results bucket can only be created with Editor, and nobody asks for that. Give it a bucket that
     already exists instead: set the repository variable `SX_TESTLAB_RESULTS_BUCKET`, e.g. `gs://<bucket>`.
   - If there is none, one first run started by the owner from the Firebase console (Test Lab → **Run a test** → Robo,
     any APK) creates the default bucket, and later runs use it.
3. **The switch:** the repository variable `SX_TESTLAB_PROJECT` = `sales-xray-alerts`
   (https://github.com/authorityclosers/sales-xray-apps/settings/variables/actions). It is not a secret.

Until step 3, each run says "not set up yet" and passes. Between step 3 and the owner's grant, each run says "no Test
Lab access yet" and passes. Neither uses any of the quota. To stop it, delete `SX_TESTLAB_PROJECT`.

**Try it:** https://github.com/authorityclosers/sales-xray-apps/actions/workflows/testlab.yml → **Run workflow**. The
summary lists the three devices and a link to the results. Firebase console → Test Lab shows videos and screenshots.

## What it can reach today

The Play flavour opens the live site signed out. Without a sign-in account for robots (an owner decision, pending),
Robo reaches only what a signed-out person sees:
- the sign-in screen;
- its links;
- "Analyse a call without an account".

It can't open Leads, Calls, the Report or settings. When the robot account exists, its sign-in goes in a Robo script
(the password as a GitHub secret, never in the repository), and the seller journeys follow it.
