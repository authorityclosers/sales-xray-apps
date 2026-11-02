import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// Attempts with no outcome are filled in from what the phone kept (owner via the
// orchestrator, 2 Nov 2026). A recording's number, start and length only: its
// audio is uploaded and analysed only through call uploads, which the seller chooses.
const main = new URL("../apps/phone/android/app/src/main/", import.meta.url);
const java = (name) =>
  readFileSync(
    new URL(`java/com/authorityclosers/salesxray/${name}.java`, main),
    "utf8",
  );

test("filling in outcomes sends a recording's facts, never its audio", () => {
  const backfill = java("OutcomeBackfill");
  const posts = [...backfill.matchAll(/"(\/v1\/[^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(posts, ["/v1/lead-desk/call-outcomes"]);
  for (const upload of [
    "CaptureUploadWorker",
    "acquisition",
    "openInputStream",
    "AutoCapture.enqueue",
    "X-Upload-Consent",
  ]) {
    assert.ok(!backfill.includes(upload), `OutcomeBackfill must not use ${upload}`);
  }
  assert.match(backfill, /\.put\("key", [^)]+\)\)\.put\("number", number\)/);
  assert.match(backfill, /\.put\("started_at", [^)]+\)\)\.put\("duration_seconds", seconds\)\);/);
});

test("outcomes are read on opening, after the first screen, and when uploads turn on", () => {
  assert.match(java("MainActivity"), /OutcomeBackfill\.soon\(app\);/);
  assert.match(java("OutcomeBackfill"), /IO\.schedule\([\s\S]*AFTER_OPEN_SECONDS, TimeUnit\.SECONDS\)/);
  assert.match(java("AutoCapture"), /now\(c\);\s+OutcomeBackfill\.now\(c\);/);
});
