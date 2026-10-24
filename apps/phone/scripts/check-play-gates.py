#!/usr/bin/env python3
"""Is this web_ref fit for a Google Play bundle? (play.yml; owner, 24 Oct 2026: the Android release as a train)

    check-play-gates.py <platform checkout at web_ref> <play-gates.txt>

1. web_ref is on the platform repo's main: a released commit, never an unreleased one.
2. Every commit in play-gates.txt is in it. Releases reach main in three shapes, so a gate counts when
   it is in the history of any of these:
   - web_ref itself (the workbench history was kept);
   - the release pull request's head (a squashed "Release vX" has its exact files);
   - the workbench commit a "Hold for vX: main … + workbench <sha>" head names (holds keep no history).
   As a last resort, a gate counts when at least 90% of the lines it added are in web_ref's files.
Everything is read from GitHub's public API (gh api, GH_TOKEN = the run's own token), plus the files
of the checkout.
"""

import json
import os
import re
import subprocess
import sys

REPO = "authorityclosers/authority-closers-platform"
THRESHOLD = 0.9
HOLD = re.compile(r"\bworkbench ([0-9a-f]{7,40})\b")


def api(path: str):
    done = subprocess.run(["gh", "api", f"repos/{REPO}/{path}"], capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or path)
    return json.loads(done.stdout)


def contains(ref: str, gate: str) -> bool:
    """Is gate in ref's history (GitHub compare: ref is ahead of the gate, or the same commit)?"""
    try:
        return api(f"compare/{gate}...{ref}").get("status") in ("ahead", "identical")
    except RuntimeError:
        return False


def added_lines(gate: str) -> dict:
    """The lines a commit added, per file it kept (blank and very short lines don't count)."""
    files = {}
    for changed in api(f"commits/{gate}").get("files", []):
        if changed.get("status") not in ("added", "modified") or "patch" not in changed:
            continue
        lines = [line[1:].strip() for line in changed["patch"].splitlines() if line.startswith("+") and not line.startswith("+++")]
        lines = [line for line in lines if len(line) >= 8]
        if lines:
            files[changed["filename"]] = lines
    return files


def by_content(gate: str, web: str) -> tuple:
    found = total = 0
    for path, lines in added_lines(gate).items():
        shown = subprocess.run(["git", "show", f"{web}:{path}"], capture_output=True, text=True)
        have = {line.strip() for line in shown.stdout.splitlines()} if shown.returncode == 0 else set()
        total += len(lines)
        found += sum(1 for line in lines if line in have)
    return found, total


def main() -> int:
    platform, gates_file = sys.argv[1], sys.argv[2]
    with open(gates_file, encoding="utf-8") as handle:
        gates = [line.split()[0] for line in (raw.strip() for raw in handle) if line and not line.startswith("#")]
    os.chdir(platform)
    web = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()

    if not contains("main", web):
        print(f"::error::web_ref {web} is not on the platform repo's main: build Play bundles from a production release only.")
        return 1

    # Where else the release's history lives: its pull request's head, and the workbench a hold names.
    places = [("web_ref", web)]
    try:
        pulls = api(f"commits/{web}/pulls")
    except RuntimeError:
        pulls = []
    if pulls:
        head = pulls[0]["head"]["sha"]
        places.append((f"release PR #{pulls[0]['number']} head", head))
        message = api(f"commits/{head}")["commit"]["message"]
        named = HOLD.search(message.split("\n", 1)[0])
        if message.startswith("Hold for") and named:
            places.append((f"workbench {named.group(1)} (the hold's)", named.group(1)))

    missing = []
    for gate in gates:
        where = next((label for label, ref in places if contains(ref, gate)), None)
        if where:
            print(f"{gate}: in the history of {where}")
            continue
        try:
            found, total = by_content(gate, web)
        except RuntimeError as error:
            print(f"{gate}: not found ({error})")
            missing.append(gate)
            continue
        if total and found / total >= THRESHOLD:
            print(f"{gate}: in web_ref by content ({found} of {total} added lines)")
        else:
            print(f"{gate}: NOT in web_ref ({found} of {total} added lines)")
            missing.append(gate)
    if missing:
        print(f"::error::web_ref {web} lacks Play gates {' '.join(missing)}.")
        return 1
    print(f"Play gates: all in {web}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
