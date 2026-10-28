#!/usr/bin/env bash
# The web app inside the app (S9, owner 23 Oct 2026: "a full app now, not like a website loading thing").
# Builds Sales X-Ray's static export from the platform repo and puts it in the Android app's assets, with
# web/sx-web.json listing every file. BundledWeb.java serves it at Sales X-Ray's own address; the API and
# anything it doesn't have still come from the server.
#
#   build-web-shell.sh <platform checkout> <assets/web dir> <host>
#
# Runs on GitHub's runners (android.yml, play.yml), never on a laptop or ac.
set -euo pipefail

platform=$1
out=$2
host=$3

cd "$platform"
sha=$(git rev-parse HEAD)
ref=$(git describe --always --tags 2>/dev/null || echo "$sha")
# The commit's own time: live web updates (LiveWeb) compare releases by it, so a rebuild of an old commit stays old.
commit_ms=$(( $(git log -1 --format=%ct) * 1000 ))
corepack enable
corepack prepare pnpm@11.19.0 --activate
pnpm install --frozen-lockfile --filter "@ac/sales-xray-web..."
AC_SALES_XRAY_STATIC_PREVIEW=1 NEXT_TELEMETRY_DISABLED=1 pnpm --filter @ac/sales-xray-web build
cd - >/dev/null

rm -rf "$out"
mkdir -p "$out"
cp -R "$platform/apps/sales-xray-web/out/." "$out/"
# Review pages are for screenshots only; pages that need the server to read their address are never
# served from the app (BundledWebRules.NETWORK_ONLY), so they aren't carried either.
rm -rf "$out/review-fixture" "$out/auth" "$out/organisation/invites" "$out/provider-review"
find "$out" -name '*.map' -delete
# The brand icon packs (public/brands/<letter>.json, about 1.7 MB) load on demand from the server, so the Play
# bundle stays under the 10 MB the upload takes (orchestrator, 23 Oct 2026). Their index and colour logos stay in.
find "$out/brands" -maxdepth 1 -name '?.json' -delete 2>/dev/null || true

SX_OUT="$out" SX_SHA="$sha" SX_REF="$ref" SX_HOST="$host" SX_COMMIT_MS="$commit_ms" node -e '
const fs = require("fs");
const path = require("path");
const root = process.env.SX_OUT;
const files = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else files.push(path.relative(root, full).split(path.sep).join("/"));
  }
})(root);
files.sort();
const manifest = {
  host: process.env.SX_HOST,
  sha: process.env.SX_SHA,
  ref: process.env.SX_REF,
  builtAtMs: Date.now(),
  commitAtMs: Number(process.env.SX_COMMIT_MS),
  files,
};
fs.writeFileSync(path.join(root, "sx-web.json"), JSON.stringify(manifest));
const bytes = files.reduce((sum, file) => sum + fs.statSync(path.join(root, file)).size, 0);
console.log(`web shell: ${files.length} files, ${(bytes / 1048576).toFixed(1)} MB, platform ${manifest.sha.slice(0, 8)} (${manifest.ref})`);
for (const page of ["index.html", "dashboard/index.html", "leads/index.html", "chats/index.html"]) {
  if (!files.includes(page)) { console.error(`web shell: missing ${page}`); process.exit(1); }
}
'
