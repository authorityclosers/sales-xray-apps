#!/usr/bin/env bash
# Is this web_ref fit for a Google Play bundle? (play.yml; owner, 24 Oct 2026: the Android release as a train)
#
#   check-play-gates.sh <platform checkout at web_ref> <play-gates.txt>
#
# 1. web_ref is on the platform repo's main: a released commit, never an unreleased one.
# 2. Every commit in play-gates.txt is in it. A release that kept the workbench history has them as
#    ancestors. A squashed release ("Release vX") has the exact tree of the workbench commit it shipped,
#    so the release PR's head (GitHub keeps refs/pull/N/head) must have the same tree and carry them.
# Reads only public data from GitHub; GH_TOKEN is the run's own token.
set -euo pipefail

dir=$1
gates=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
repo=authorityclosers/authority-closers-platform
cd "$dir"

web=$(git rev-parse HEAD)
# The checkout is shallow: fetch the history (commits and trees, no file contents).
git fetch --quiet --filter=blob:none --unshallow origin main 2>/dev/null || git fetch --quiet --filter=blob:none origin main

if ! git merge-base --is-ancestor "$web" FETCH_HEAD; then
  echo "::error::web_ref $web is not on the platform repo's main: build Play bundles from a production release only."
  exit 1
fi

missing=()
while read -r gate _; do
  case "$gate" in "" | \#*) continue ;; esac
  git merge-base --is-ancestor "$gate" "$web" 2>/dev/null || missing+=("$gate")
done < "$gates"
if [ ${#missing[@]} -eq 0 ]; then
  echo "Play gates: all in $web"
  exit 0
fi

# A squashed release: find the workbench commit it shipped through its release pull request.
shipped=""
pr=$(gh api "repos/$repo/commits/$web/pulls" --jq '.[0].number // empty' 2>/dev/null || true)
if [ -n "$pr" ] && git fetch --quiet --filter=blob:none origin "refs/pull/$pr/head" 2>/dev/null; then
  shipped=$(git rev-parse FETCH_HEAD)
fi
if [ -z "$shipped" ] || [ "$(git rev-parse "$shipped^{tree}")" != "$(git rev-parse "$web^{tree}")" ]; then
  echo "::error::web_ref $web lacks Play gates ${missing[*]} (no release pull request with the same files to check instead)."
  exit 1
fi
still=()
for gate in "${missing[@]}"; do
  git merge-base --is-ancestor "$gate" "$shipped" 2>/dev/null || still+=("$gate")
done
if [ ${#still[@]} -gt 0 ]; then
  echo "::error::web_ref $web (release PR #$pr, workbench $shipped) lacks Play gates ${still[*]}."
  exit 1
fi
echo "Play gates: all in $web (a squashed release of PR #$pr, workbench $shipped, same files)"
