import assert from "node:assert/strict";
import test from "node:test";
import { findProblems } from "./check-no-secrets.mjs";

const file = (path, text) => ({ path, content: Buffer.from(text) });

test("refuses keystores, certificates and .env files by name", () => {
  const problems = findProblems([
    file("apps/phone/android/app/sales-xray-test.keystore", "x"),
    file("apps/desktop/signing.p12", "x"),
    file("apps/phone/.env.production", "x"),
    file("apps/phone/ios/App/App/GoogleService-Info.plist", "x"),
  ]);
  assert.equal(problems.length, 4);
});

test("refuses secret-looking strings in text files", () => {
  const header = ["-----BEGIN RSA PRIVATE", "KEY-----"].join(" ");
  const problems = findProblems([
    file("a.txt", `${header}\nabc`),
    file("b.gradle", "store" + 'Password "android"'),
    file("c.js", `const t = "${"gh" + "p_"}${"a".repeat(36)}";`),
  ]);
  assert.deepEqual(
    problems.map((p) => p.split(":")[0]),
    ["a.txt", "b.gradle", "c.js"],
  );
});

test("passes the durable-key wiring, which reads secrets from the environment", () => {
  const gradle = 'storePassword System.getenv("SX_SIGNING_PASSWORD")';
  const workflow = "STORE_BASE64: ${{ secrets.SX_ANDROID_SIGNING_STORE_BASE64 }}";
  assert.deepEqual(findProblems([file("build.gradle", gradle), file("android.yml", workflow)]), []);
});

test("skips binary files", () => {
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0]), Buffer.from("AKIA" + "ABCDEFGHIJKLMNOP")]);
  assert.deepEqual(findProblems([{ path: "icon.png", content: png }]), []);
});
