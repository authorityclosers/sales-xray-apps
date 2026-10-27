# Firebase Test Lab, free (Spark)

`.github/workflows/testlab.yml` runs Robo on Google's own phones after each green Android build on main. It uses one
physical budget phone and two virtual phones (the oldest and the newest Android in range), at most 3 runs a day. Spark
allows 10 virtual and 5 physical test runs a day and has no billing, so it can't cost anything. Until the steps below
are done, each run says "not set up yet" and passes.

## What it can reach today

The Play flavour opens the live site signed out. Without a sign-in account for robots (an owner decision, pending),
Robo reaches only what a signed-out person sees:
- the sign-in screen;
- the links on it;
- "Analyse a call without an account".

It can't open Leads, Calls, the Report or settings. When the robot account exists, its sign-in goes in a Robo script
(the password as a GitHub secret, never in the repository), and the seller journeys follow it.

## Owner steps (about 15 minutes, all clicks; no keys are created)

Do these in a **new** Firebase project. Don't add Firebase to `ac-sales-xray-ads`: if that Cloud project has a billing
account, Firebase puts it on the Blaze plan, and Test Lab could then charge. A new project with no billing account stays
on Spark.

1. **Create the project.**
   1. Open https://console.firebase.google.com and click **Create a project** (or **Add project**).
   2. Name: `AC Sales X-Ray Test Lab`. Note the **project ID** it shows under the name, e.g. `ac-sales-xray-test-lab`.
   3. **Google Analytics:** turn it off. Click **Create project**.
   4. Don't click **Upgrade** anywhere. The plan shown bottom left must stay **Spark**.
2. **Turn on three APIs.** Open https://console.cloud.google.com/apis/library and pick the new project in the project
   menu at the top. For each, search, open it and click **Enable**:
   - **Cloud Testing API**
   - **Cloud Tool Results API**
   - **IAM Service Account Credentials API**
3. **Create the robot's service account.** In https://console.cloud.google.com/iam-admin/serviceaccounts (new project
   selected):
   1. Click **Create service account**. Name: `test-lab-runner`. Click **Create and continue**.
   2. **Role:** Basic → **Editor**. Test Lab's free results bucket needs Editor, and this project holds nothing else.
      Click **Done**.
   3. **Don't** create a key for it.
4. **Let this repository act as it, keyless.**
   1. Open `test-lab-runner` → **Permissions** tab → **Grant access**.
   2. **New principals:** paste the same principal that `play-publisher@ac-sales-xray-ads.iam.gserviceaccount.com` has on
      its own Permissions tab, i.e. the one from the GitHub pool `github` / provider `sales-xray-apps` in project
      `501399142248`.
      - For this repository it is normally `principalSet://iam.googleapis.com/projects/501399142248/locations/global/workloadIdentityPools/github/attribute.repository/authorityclosers/sales-xray-apps`.
   3. **Role:** **Workload Identity User**. Click **Save**.
5. **Tell GitHub where to run.** None of these values is a secret.
   1. Open https://github.com/authorityclosers/sales-xray-apps/settings/variables/actions and click
      **New repository variable**.
   2. Add `SX_TESTLAB_PROJECT` = the project ID from step 1.
   3. Add `SX_TESTLAB_SERVICE_ACCOUNT` = `test-lab-runner@<project ID>.iam.gserviceaccount.com`.
6. **Try it.**
   1. Open https://github.com/authorityclosers/sales-xray-apps/actions/workflows/testlab.yml and click **Run workflow**.
   2. The summary lists the three devices and a link to the results. Firebase console → **Test Lab** shows the same
      run with videos and screenshots.

To stop it, delete either repository variable.
