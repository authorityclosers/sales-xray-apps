import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

// A cold start does only what the first screen needs (owner bug #67, 30 Oct
// 2026: Leads waited ~4.6 s after a cold start; 0.28 s of it was WorkManager and
// the Facebook SDK starting on the main thread, and ~0.1 s WebView's engine).
const main = new URL("../apps/phone/android/app/src/main/", import.meta.url);
const manifest = readFileSync(new URL("AndroidManifest.xml", main), "utf8");
const java = (name) =>
  readFileSync(
    new URL(`java/com/authorityclosers/salesxray/${name}.java`, main),
    "utf8",
  );

test("WorkManager's start-up provider is off and the app configures it on first use", () => {
  assert.match(
    manifest,
    /<meta-data\s+android:name="androidx\.work\.WorkManagerInitializer"\s+android:value="androidx\.startup"\s+tools:node="remove"\s*\/>/,
  );
  const app = java("SalesXrayApp");
  assert.match(app, /class SalesXrayApp extends Application implements Configuration\.Provider/);
  assert.match(app, /public Configuration getWorkManagerConfiguration\(\)/);
});

test("the Facebook SDK starts with sign-in, not with the app", () => {
  assert.match(
    manifest,
    /<provider\s+android:name="com\.facebook\.internal\.FacebookInitProvider"[^>]*tools:node="remove"\s*\/>/,
  );
  assert.match(java("NativeSignIn"), /FacebookSdk\.sdkInitialize\(/);
});

test("the screen reads its web copy on another thread while Capacitor creates the WebView", () => {
  const activity = java("MainActivity");
  const onCreate = activity.slice(activity.indexOf("public void onCreate(Bundle"));
  const read = onCreate.indexOf('new Thread(web, "sx-web-copy").start()');
  const capacitor = onCreate.indexOf("super.onCreate(savedInstanceState)");
  const awaited = onCreate.indexOf("bundled = await(web)");
  assert.ok(read >= 0 && read < capacitor && capacitor < awaited, "read, then Capacitor, then wait");
  assert.doesNotMatch(onCreate.slice(0, onCreate.indexOf("\n    }\n")), /BundledWeb\.load\(|live\.choose\(\)/);
});

test("WebView's engine starts first, and only for a screen", () => {
  const app = java("SalesXrayApp");
  const onCreate = app.slice(app.indexOf("public void onCreate()"));
  const calls = [...onCreate.matchAll(/^\s+(\w+(?:\.\w+)*)\(/gm)].map((m) => m[1]);
  assert.deepEqual(calls.slice(0, 2), ["super.onCreate", "WebViewStart.ifOpening"]);
  const start = java("WebViewStart");
  assert.match(start, /importance <= ActivityManager\.RunningAppProcessInfo\.IMPORTANCE_FOREGROUND/);
  assert.match(start, /WebViewCompat\.startUpWebView\(/);
});
