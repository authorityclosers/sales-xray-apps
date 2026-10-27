#!/usr/bin/env python3
"""Upload a signed Android App Bundle to a Google Play track (play.yml).

    play-upload.py <bundle.aab> <track: internal|alpha|beta|production> [release notes]

Signs in keyless (owner, 25 Oct 2026): the run's GitHub OIDC token (play.yml has id-token: write) goes
through Workload Identity Federation (PLAY_WIF_PROVIDER) to impersonate PLAY_SERVICE_ACCOUNT, all inside
google-auth's identity-pool credentials, the same exchange google-github-actions/auth makes. No key and
no credentials file exist anywhere. The account may release to testing tracks only.

Reads the release's in-app update priority from PLAY_UPDATE_PRIORITY (0-5; 4-5 makes the app force
the update, StoreUpdateRules); only the API can set one, Play Console can't. Release notes are in
PLAY_NOTES_LANGUAGE (en-IN, the listing's language). Uses the Play Developer API's edits: insert,
upload the bundle, put it on the track, commit.

Internal testing and closed testing (alpha) releases go out "completed": testers get them at once, and
every alpha upload is a fresh closed-track release, so Play makes its pre-launch report for it and,
with managed publishing off, sends it to review without Console clicks (orchestrator, 27 Oct 2026:
no report had ever run while alpha uploads were drafts). PLAY_DRAFT=true leaves an alpha release as a
draft instead; open testing (beta) and production are always drafts for the owner to roll out.
Google's libraries load inside the functions that use them, so the release rules run in tests alone.
"""

import os
import sys
from urllib.parse import quote

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


def release_status(track: str, draft: bool = False) -> str:
    """"completed" for internal testing, and for closed testing (alpha) unless a draft is asked for;
    "draft" for open testing and production."""
    if track == "internal" or (track == "alpha" and not draft):
        return "completed"
    return "draft"


def draft_requested(raw: str | None) -> bool:
    """play.yml's draft input as it reaches the script ("true" or "false")."""
    return str(raw or "").strip().lower() in {"true", "1", "yes"}


def credentials():
    """Short-lived Google credentials from the run's GitHub OIDC token (Workload Identity Federation)."""
    from google.auth import identity_pool

    provider = os.environ.get("PLAY_WIF_PROVIDER", "")
    account = os.environ.get("PLAY_SERVICE_ACCOUNT", "")
    request_url = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL", "")
    request_token = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "")
    if not (provider and account and request_url and request_token):
        raise SystemExit("Keyless sign-in needs play.yml's id-token: write, PLAY_WIF_PROVIDER and PLAY_SERVICE_ACCOUNT")
    audience = "https://iam.googleapis.com/" + provider
    return identity_pool.Credentials.from_info(
        {
            "type": "external_account",
            "audience": "//iam.googleapis.com/" + provider,
            "subject_token_type": "urn:ietf:params:oauth:token-type:jwt",
            "token_url": "https://sts.googleapis.com/v1/token",
            "service_account_impersonation_url": (
                "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/" + account + ":generateAccessToken"
            ),
            "credential_source": {
                "url": request_url + "&audience=" + quote(audience, safe=""),
                "headers": {"Authorization": "Bearer " + request_token},
                "format": {"type": "json", "subject_token_field_name": "value"},
            },
        },
        scopes=[SCOPE],
    )


def commit(edits, edit: str) -> bool:
    """Commits the edit; False when it went in without being sent for review. When other changes in
    Play Console wait to be sent for review, Play refuses an automatic send; the release then goes in
    without sending those (they stay for the owner)."""
    from googleapiclient.errors import HttpError

    try:
        edits.commit(packageName=PACKAGE, editId=edit).execute()
        return True
    except HttpError as error:
        if "changesNotSentForReview" not in str(error):
            raise
        edits.commit(packageName=PACKAGE, editId=edit, changesNotSentForReview=True).execute()
        print("committed without sending Play Console's other pending changes for review")
        return False


def main() -> int:
    if len(sys.argv) < 3 or sys.argv[2] not in TRACKS:
        print(__doc__)
        return 2
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload

    bundle, track = sys.argv[1], sys.argv[2]
    notes = sys.argv[3] if len(sys.argv) > 3 else ""
    api = build("androidpublisher", "v3", credentials=credentials(), cache_discovery=False)
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
        "status": release_status(track, draft_requested(os.environ.get("PLAY_DRAFT"))),
    }
    priority = update_priority(os.environ.get("PLAY_UPDATE_PRIORITY", "0"))
    if priority:
        release["inAppUpdatePriority"] = priority
    if notes:
        release["releaseNotes"] = [{"language": os.environ.get("PLAY_NOTES_LANGUAGE", "en-IN"), "text": notes[:500]}]
    edits.tracks().update(
        packageName=PACKAGE, editId=edit, track=track, body={"track": track, "releases": [release]}
    ).execute()
    sent = commit(edits, edit)
    print(f"uploaded version code {code} to the {track} track ({release['status']}, update priority {priority})")
    if release["status"] == "completed" and track != "internal" and not sent:
        print("it waits in Play Console: send the pending changes for review there to release it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
