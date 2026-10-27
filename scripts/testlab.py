#!/usr/bin/env python3
"""Firebase Test Lab on the free Spark quota (.github/workflows/testlab.yml; owner, 28 Oct 2026: "Are we using
Firebase free testing and so on to 100x testing?", free plan only for two weeks).

  testlab.py devices <models.json>       the --device values for one run, one a line, from Test Lab's live catalogue
                                         (gcloud firebase test android models list --format=json): a physical budget
                                         phone, and virtual phones on the oldest and the newest Android in range
  testlab.py credentials <out.json>      a keyless credential file for gcloud: the run's GitHub OIDC token, exchanged
                                         through the same Workload Identity Federation as play.yml (no key anywhere)

The credential file holds the runner's short-lived OIDC request token, so it is written for this user only (0600),
outside the checkout, and the workflow deletes it when the job ends. Nothing here prints it.
"""

from __future__ import annotations

import json
import os
import sys
from urllib.parse import quote

# Android 10 to 16. The app needs Android 7 (minSdk 24), but the phones Indian sellers buy now run 10 and up, and
# Test Lab's older images are being retired.
OLDEST, NEWEST = 29, 36
# Brands of the budget phones the app's sellers use: a physical one of these first.
BUDGET_BRANDS = ("samsung", "xiaomi", "redmi", "poco", "motorola", "oppo", "vivo", "realme", "nokia", "infinix", "tecno")


def usable_versions(model: dict) -> list[int]:
    """The model's Android versions in range that Test Lab hasn't marked deprecated, oldest first."""
    retired = {tag.split("=", 1)[1] for tag in model.get("tags", []) if tag.startswith("deprecated=")}
    return sorted(
        int(v) for v in model.get("supportedVersionIds", []) if v.isdigit() and OLDEST <= int(v) <= NEWEST and v not in retired
    )


def phones(models: list[dict], form: str) -> list[dict]:
    return [m for m in models if m.get("form") == form and m.get("formFactor") == "PHONE" and usable_versions(m)]


def density(model: dict) -> int:
    try:
        return int(model.get("screenDensity") or 9999)
    except (TypeError, ValueError):
        return 9999


def choose(models: list[dict]) -> list[str]:
    """One run's devices: a physical budget phone (one of BUDGET_BRANDS first, then the lowest screen density, on the
    newest Android it has), then a virtual phone on the oldest Android in range and one on the newest. Spark allows 5
    physical and 10 virtual test runs a day, so a run takes 1 and 2."""
    picks: list[tuple[str, int]] = []
    physical = phones(models, "PHYSICAL")
    if physical:
        best = min(
            physical,
            key=lambda m: (m.get("brand", "").lower() not in BUDGET_BRANDS, density(m), -usable_versions(m)[-1], m["id"]),
        )
        picks.append((best["id"], usable_versions(best)[-1]))
    virtual = phones(models, "VIRTUAL")
    if virtual:
        old = min(virtual, key=lambda m: (usable_versions(m)[0], m["id"]))
        new = min(virtual, key=lambda m: (-usable_versions(m)[-1], m["id"]))
        for pick in ((old["id"], usable_versions(old)[0]), (new["id"], usable_versions(new)[-1])):
            if pick not in picks:
                picks.append(pick)
    return [f"model={model},version={version},locale=en,orientation=portrait" for model, version in picks]


def credentials(provider: str, account: str, request_url: str, request_token: str) -> dict:
    """The external-account file gcloud reads (`gcloud auth login --cred-file`), as play-upload.py builds its own."""
    audience = "https://iam.googleapis.com/" + provider
    return {
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
    }


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "devices":
        with open(argv[1], encoding="utf-8") as handle:
            chosen = choose(json.load(handle))
        if not chosen:
            print("testlab: no physical or virtual phone in range in the catalogue", file=sys.stderr)
            return 1
        print("\n".join(chosen))
        return 0
    if len(argv) == 2 and argv[0] == "credentials":
        needed = ("TESTLAB_WIF_PROVIDER", "TESTLAB_SERVICE_ACCOUNT", "ACTIONS_ID_TOKEN_REQUEST_URL", "ACTIONS_ID_TOKEN_REQUEST_TOKEN")
        missing = [name for name in needed if not os.environ.get(name)]
        if missing:
            print(f"testlab: keyless sign-in needs {', '.join(missing)} (id-token: write)", file=sys.stderr)
            return 1
        info = credentials(*(os.environ[name] for name in needed))
        descriptor = os.open(argv[1], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(info, handle)
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
