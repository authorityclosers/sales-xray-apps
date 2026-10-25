#!/usr/bin/env python3
"""Upload a signed Android App Bundle to a Google Play track (play.yml).

    play-upload.py <bundle.aab> <track: internal|alpha|beta|production> [release notes]

Signs in keyless: play.yml's google-github-actions/auth step trades the run's GitHub OIDC token
(Workload Identity Federation) for play-publisher@ac-sales-xray-ads.iam.gserviceaccount.com and leaves
its credentials in GOOGLE_APPLICATION_CREDENTIALS, which Application Default Credentials read here. No
key exists anywhere (owner, 25 Oct 2026). The account may release to testing tracks only.

Reads the release's in-app update priority from PLAY_UPDATE_PRIORITY (0-5; 4-5 makes the app force
the update, StoreUpdateRules); only the API can set one, Play Console can't. Release notes are in
PLAY_NOTES_LANGUAGE (en-IN, the listing's language). Uses the Play Developer API's edits: insert,
upload the bundle, put it on the track, commit. Internal testing releases go out "completed" (testers
get it at once); every other track is left as a draft for the owner to roll out in Play Console.
"""

import os
import sys

import google.auth
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

PACKAGE = "com.authorityclosers.salesxray"
TRACKS = {"internal", "alpha", "beta", "production"}
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def update_priority(raw: str) -> int:
    """Play's in-app update priority, 0 to 5; anything else is 0."""
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return 0
    return value if 0 <= value <= 5 else 0


def commit(edits, edit: str) -> None:
    """Commits the edit. When other changes in Play Console wait to be sent for review, Play refuses an
    automatic send; the release then goes out without sending those (they stay for the owner)."""
    try:
        edits.commit(packageName=PACKAGE, editId=edit).execute()
    except HttpError as error:
        if "changesNotSentForReview" not in str(error):
            raise
        edits.commit(packageName=PACKAGE, editId=edit, changesNotSentForReview=True).execute()
        print("committed without sending Play Console's other pending changes for review")


def main() -> int:
    if len(sys.argv) < 3 or sys.argv[2] not in TRACKS:
        print(__doc__)
        return 2
    bundle, track = sys.argv[1], sys.argv[2]
    notes = sys.argv[3] if len(sys.argv) > 3 else ""
    if not os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"):
        print("No Google credentials: play.yml's google-github-actions/auth step signs in first")
        return 2
    credentials, _ = google.auth.default(scopes=[SCOPE])
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
    priority = update_priority(os.environ.get("PLAY_UPDATE_PRIORITY", "0"))
    if priority:
        release["inAppUpdatePriority"] = priority
    if notes:
        release["releaseNotes"] = [{"language": os.environ.get("PLAY_NOTES_LANGUAGE", "en-IN"), "text": notes[:500]}]
    edits.tracks().update(
        packageName=PACKAGE, editId=edit, track=track, body={"track": track, "releases": [release]}
    ).execute()
    commit(edits, edit)
    print(f"uploaded version code {code} to the {track} track ({release['status']}, update priority {priority})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
