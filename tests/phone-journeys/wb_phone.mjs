// The seller journeys in the Android app's own WebView, on a real phone over adb or on an emulator (orchestrator for
// the owner, 28 Oct 2026: the free levers, "you can use both Redmi all time, it's for testing heavy"). The journeys are
// the bug radar's (the platform's scripts/ci/wb_journeys_lib.mjs): each step is timed; a step that can't be done is a
// journey-broken finding with a screenshot of the phone and what was on screen; errors after a step are tap-errors.
// The result is a radar part (part-0.json) that the platform's `wb_radar.mjs merge` turns into radar.json, which
// `ac-bug-radar ingest` keeps as radar:<group> cards. Pages are keyed per phone (phone:<model>:<journey>), so a phone's
// findings never close or reopen the GitHub radar's.
//
//   node wb_phone.mjs <out-dir> --lib <dir with wb_journeys_lib.mjs> [--serial S] [--pkg P] [--origin URL]
//                     [--fixtures URL] [--journeys a,b] [--sha SHA] [--require-device] [--dry-run]
//
// --origin: where the WebView opens the fixtures (the Dev app's own site by default). --fixtures: a fixture server on
// this machine; every request to --origin is answered from it (the WebView never leaves the phone, so the app's
// https-only setting holds and dev's sign-in never comes up), which is how the emulator lane runs. --dry-run names the
// device, the app and the journeys, and touches nothing. Without a device it exits 0 (the laptop's nightly skips
// quietly), unless --require-device. It starts only --pkg: never the Play Store or any other app.
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const args = process.argv.slice(2);
const option = (name, fallback = "") => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 ? args[at + 1] : fallback;
};
const out = resolve(args[0] && !args[0].startsWith("--") ? args[0] : "phone-run");
const LIB = resolve(option("lib", "."));
const SERIAL = option("serial", process.env.SX_PHONE_SERIAL || "");
const PKG = option("pkg", "com.authorityclosers.salesxray.dev");
const ORIGIN = option("origin", "https://salesxray-dev.authorityclosers.com").replace(/\/$/, "");
const FIXTURES = option("fixtures").replace(/\/$/, "");
const ASKED = option("journeys");
const SHA = option("sha", null);
const DRY = args.includes("--dry-run");

const lib = await import(pathToFileURL(resolve(LIB, "wb_journeys_lib.mjs")).href);
const plans = ASKED
  ? lib.pickJourneys(ASKED.split(",").map((key) => `journey:${key}`))
  : lib.pickJourneys([]); // the finished ones, as the nightly radar walks them

const { _android } = await import("playwright");
const devices = await _android.devices();
const device = SERIAL ? devices.find((d) => d.serial() === SERIAL) : devices[0];
if (!device) {
  console.log(`wb_phone: no device${SERIAL ? ` ${SERIAL}` : ""} on adb; nothing ran`);
  process.exit(args.includes("--require-device") ? 1 : 0);
}
const text = async (command) => String(await device.shell(command)).trim();
const model = device.model();
const android = await text("getprop ro.build.version.release");
const installed = (await text(`pm list packages ${PKG}`)).split(/\s+/).includes(`package:${PKG}`);
const slug = model.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
console.log(`wb_phone: ${model} (Android ${android}, ${device.serial()}); ${PKG} ${installed ? "installed" : "NOT installed"}`);
console.log(`wb_phone: journeys ${plans.map(([key]) => key).join(", ")} at ${FIXTURES ? `${ORIGIN} (answered from ${FIXTURES})` : ORIGIN}`);
if (DRY || !installed) {
  await device.close();
  process.exit(DRY ? 0 : 1);
}

// The phone's own budget: a real phone has no CPU throttle to scale by, so it gets the Android profile's limits.
const PROFILE = { cpu: 4 };
const timeout = lib.journeyTimeout(PROFILE);
const budget = lib.journeyBudget(PROFILE);
const firstLine = (error) => String(error?.message ?? error).split("\n")[0].slice(0, 200);

/** A step's action, then what must show: the radar's act(), on the first match a person can see. */
async function act(page, item) {
  const seen = (selector) => page.locator(selector).filter({ visible: true }).first();
  if (item.goto) await page.goto(ORIGIN + item.goto, { waitUntil: "load", timeout: 90000 });
  if (item.click) {
    const target = seen(item.click);
    if (item.optional && !(await target.count())) return;
    await target.click({ timeout });
  }
  if (item.fill) await seen(item.fill[0]).fill(item.fill[1], { timeout });
  if (item.check) {
    const box = page.locator(item.check).first();
    const label = box.locator("xpath=ancestor::label[1]");
    if (await label.count()) await label.click({ position: { x: 12, y: 12 }, timeout });
    else await box.check({ timeout });
    if (!(await box.isChecked())) throw new Error("the box did not tick");
  }
  if (item.file) await page.locator(item.file).first().setInputFiles(lib.fictionalRecording(), { timeout });
  if (item.wait) await seen(item.wait).waitFor({ state: "visible", timeout });
  if (item.has) await page.locator(item.has).first().waitFor({ state: "attached", timeout });
  if (item.gone) {
    const until = Date.now() + timeout;
    while (await page.locator(item.gone).filter({ visible: true }).count()) {
      if (Date.now() > until) throw new Error(`still on screen after ${timeout} ms: ${item.gone}`);
      await page.waitForTimeout(200);
    }
  }
}

/** In the page: headings, status lines and controls a person can see (an open sheet's only, when one is). */
function onScreen() {
  const shown = (el) => {
    const box = el.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && getComputedStyle(el).visibility !== "hidden";
  };
  const sheet = [...document.querySelectorAll('dialog[open], [role="dialog"]')].filter(shown).pop();
  const root = sheet ?? document;
  const words = (el) =>
    (el.getAttribute("aria-label") || el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 40) +
    (el.disabled ? " (off)" : "");
  const list = (selector, most) =>
    [...new Set([...root.querySelectorAll(selector)].filter(shown).map(words).filter(Boolean))].slice(0, most);
  const says = list('[role="status"], [role="alert"]', 4);
  return (
    `${sheet ? "in a sheet, " : ""}headings "${list("h1, h2, h3, [role=heading]", 6).join('" / "')}"` +
    `${says.length ? `; says "${says.join('" / "')}"` : ""}; controls "${list("button, a[href], [role=button], [role=tab], input, textarea, select", 30).join('", "')}"`
  );
}

await mkdir(resolve(out, "crops"), { recursive: true });
const started = Date.now();
await device.shell(`am force-stop ${PKG}`);
await device.shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`);
const webview = await device.webView({ pkg: PKG }, { timeout: 60000 });
const page = await webview.page();
if (FIXTURES)
  await page.route(`${ORIGIN}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    // Fixtures have no API: a read the page doesn't answer itself gets its 404 at once, as on the radar.
    if (url.pathname.startsWith("/v1/"))
      return route.fulfill({ status: 404, contentType: "application/json", body: '{"detail":"Not Found"}' });
    try {
      const answer = await fetch(FIXTURES + url.pathname + url.search, {
        method: request.method(),
        headers: { accept: request.headers().accept ?? "*/*" },
        body: ["GET", "HEAD"].includes(request.method()) ? undefined : request.postDataBuffer(),
      });
      const headers = {};
      answer.headers.forEach((value, name) => {
        if (!["content-encoding", "content-length", "transfer-encoding", "connection"].includes(name)) headers[name] = value;
      });
      return route.fulfill({ status: answer.status, headers, body: Buffer.from(await answer.arrayBuffer()) });
    } catch {
      return route.abort();
    }
  });
const events = [];
page.on("pageerror", (error) => events.push({ severity: "high", message: firstLine(error) }));
page.on("console", (message) => {
  if (message.type() === "error" && !/Failed to load resource|webpack-hmr|\[HMR\]|DevTools/.test(message.text()))
    events.push({ severity: /hydrat/i.test(message.text()) ? "high" : "medium", message: message.text().slice(0, 200) });
});

const where = `${model} Android ${android} (the app)`;
const pages = [];
const journeys = [];
const findings = [];
for (const [key, plan] of plans) {
  const name = `phone:${slug}:${key}`;
  const path = plan.steps[0].goto;
  const info = { key: name, path, ms: 0, loads: 0, failed: [], crawled: false, taps: 0, skipped: {} };
  const record = { key, title: `${plan.title} (${model})`, profile: `phone:${slug}`, total: plan.steps.length, steps: [], stopped: null };
  pages.push(info);
  journeys.push(record);
  const t = Date.now();
  for (const [n, item] of plan.steps.entries()) {
    const step = `${n + 1}. ${item.name}`;
    const base = { page: name, path, state: where, label: item.name, context: `${plan.title}: step ${step}` };
    events.length = 0;
    const t0 = Date.now();
    try {
      await act(page, item);
      if (n === 0) info.loads++;
    } catch (error) {
      record.stopped = step;
      const seen = await page.evaluate(onScreen).catch(() => "");
      const crop = `crops/${slug}-${key}-${n + 1}.png`;
      const shot = await device.screenshot({ path: resolve(out, crop) }).then(() => crop, () => undefined);
      findings.push({
        ...base,
        check: "journey-broken",
        severity: "high",
        selector: step,
        detail: `stops at step ${step}: ${firstLine(error)}${seen ? ` On screen: ${seen}` : ""}`,
        ...(shot ? { crop: shot } : {}),
      });
      break;
    }
    const ms = Date.now() - t0;
    record.steps.push({ name: step, ms });
    if (ms > budget)
      findings.push({ ...base, check: "journey-slow", severity: "low", selector: step, detail: `step ${step} took ${(ms / 1000).toFixed(1)} s (over ${budget / 1000} s)` });
    for (const event of events)
      findings.push({ ...base, check: "tap-error", severity: event.severity, selector: `${step} ⇒ ${event.message}`, message: event.message, detail: event.message });
  }
  info.ms = Date.now() - t;
}
await device.shell(`am force-stop ${PKG}`);
await device.close();

const part = {
  schema: "wb-radar-part/1",
  shard: `phone:${slug}`,
  source_sha: SHA,
  elapsed_seconds: Math.round((Date.now() - started) / 1000),
  pages,
  journeys,
  findings,
};
await writeFile(resolve(out, "part-0.json"), JSON.stringify(part));
console.log(
  `wb_phone: ${journeys.length} journeys on ${model}: ` +
    journeys.map((j) => `${j.key} ${j.steps.length}/${j.total}${j.stopped ? ` (stops at ${j.stopped})` : ""}`).join("; "),
);
