import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// Nothing of the Android app goes to a backup or a new phone (owner, 24 Oct
// 2026: "Log work calls" turned itself on): a restored copy turns it back on.
const main = new URL("../apps/phone/android/app/src/main/", import.meta.url);
const manifest = readFileSync(new URL("AndroidManifest.xml", main), "utf8");
const rules = readFileSync(
  new URL("res/xml/data_extraction_rules.xml", main),
  "utf8",
);

test("the app turns Android's backup off, up to Android 11", () => {
  assert.match(manifest, /android:allowBackup="false"/);
  assert.match(manifest, /android:fullBackupContent="false"/);
});

test("Android 12+ copies nothing to a backup or a new phone", () => {
  assert.match(
    manifest,
    /android:dataExtractionRules="@xml\/data_extraction_rules"/,
  );
  for (const place of ["cloud-backup", "device-transfer"]) {
    const block = rules.match(new RegExp(`<${place}>([\\s\\S]*?)</${place}>`));
    assert.ok(block, place);
    for (const domain of ["root", "file", "database", "sharedpref", "external"])
      assert.match(
        block[1],
        new RegExp(`<exclude domain="${domain}" path="\\." />`),
        `${place} ${domain}`,
      );
    assert.doesNotMatch(block[1], /<include /, `${place} includes nothing`);
  }
});
