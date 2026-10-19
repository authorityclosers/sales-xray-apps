#!/usr/bin/env python3
"""Upload a signed Android App Bundle to a Google Play track (play.yml).

    play-upload.py <bundle.aab> <track: internal|alpha|beta|production> [release notes]

Reads the Play service account's JSON key from the environment (PLAY_SERVICE_ACCOUNT_JSON),
never from a file in the repo, and never prints it. Uses the Play Developer API's edits:
insert, upload the bundle, put it on the track, commit. Internal testing releases go out
"completed" (testers get it at once); every other track is left as a draft for the owner
to roll out in Play Console.
"""

import json
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "com.authorityclosers.salesxray"
TRACKS = {"internal", "alpha", "beta", "production"}


def main() -> int:
    if len(sys.argv) < 3 or sys.argv[2] not in TRACKS:
        print(__doc__)
        return 2
    bundle, track = sys.argv[1], sys.argv[2]
    notes = sys.argv[3] if len(sys.argv) > 3 else ""
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw:
        print("PLAY_SERVICE_ACCOUNT_JSON is not set")
        return 2
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(raw), scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    api = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)
    edits = api.edits()
    edit = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    uploaded = edits.bundles().upload(
        packageName=PACKAGE,
        editId=edit,
        media_body=MediaFileUpload(bundle, mimetype="application/octet-stream", resumable=True),
    ).execute()
    code = uploaded["versionCode"]
    release = {
        "versionCodes": [str(code)],
        "status": "completed" if track == "internal" else "draft",
    }
    if notes:
        release["releaseNotes"] = [{"language": "en-US", "text": notes[:500]}]
    edits.tracks().update(
        packageName=PACKAGE, editId=edit, track=track, body={"track": track, "releases": [release]}
    ).execute()
    edits.commit(packageName=PACKAGE, editId=edit).execute()
    print(f"uploaded version code {code} to the {track} track ({release['status']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
