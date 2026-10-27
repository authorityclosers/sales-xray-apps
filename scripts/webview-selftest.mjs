// Sales X-Ray's web self-test inside the Android app's WebView, over the DevTools protocol (debug builds
// only; emulator-web-selftest.sh forwards the socket). The same checks the iPhone app runs on itself
// (SXSelfTest.swift): where the page came from, a second screen, the API, the phone channel, haptics.
//
//   node scripts/webview-selftest.mjs http://127.0.0.1:9222/json <report.json>
import { writeFileSync } from "node:fs";

const [listUrl, reportFile] = process.argv.slice(2);
const targets = await (await fetch(listUrl)).json();
const page = targets.find((t) => t.type === "page" && t.url.includes("salesxray")) ?? targets.find((t) => t.type === "page");
if (!page) throw new Error(`no page in the WebView: ${JSON.stringify(targets)}`);

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});
let next = 0;
const pending = new Map();
ws.onmessage = (event) => {
  const message = JSON.parse(String(event.data));
  if (message.id && pending.has(message.id)) {
    pending.get(message.id)(message);
    pending.delete(message.id);
  }
};
const send = (method, params) =>
  new Promise((resolve) => {
    const id = ++next;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });

const expression = `(async () => {
  const ask = (method, args = {}) => new Promise((resolve) => {
    const id = "selftest-" + method + "-" + Math.random().toString(36).slice(2);
    SXPhone.addEventListener("message", (event) => {
      try { const reply = JSON.parse(String(event.data)); if (reply.id === id) resolve(reply); } catch (e) {}
    });
    SXPhone.postMessage(JSON.stringify({ id, method, args }));
    setTimeout(() => resolve(null), 5000);
  });
  const out = {};
  out.href = location.href;
  out.title = document.title;
  out.inApp = document.documentElement.hasAttribute("data-sx-app");
  out.hasPhone = typeof SXPhone !== "undefined";
  try {
    const leads = await fetch("/leads/");
    out.leadsScreen = leads.status;
    out.leadsBytes = (await leads.text()).length;
  } catch (e) { out.leadsError = String(e); }
  try {
    const api = await fetch("/v1/auth/email-code/config?surface=sales_xray", { credentials: "same-origin" });
    out.apiStatus = api.status;
    out.apiKeys = Object.keys(await api.json()).sort().slice(0, 8);
  } catch (e) { out.apiError = String(e); }
  if (out.hasPhone) {
    const status = await ask("status");
    out.phoneStatus = status && status.result;
    const shell = await ask("webShell");
    out.webShell = shell && shell.result;
    const haptic = await ask("haptic", { kind: "success" });
    out.haptic = haptic ? haptic.ok === true : null;
  }
  return JSON.stringify(out);
})()`;

const result = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
ws.close();
if (result.result?.exceptionDetails) throw new Error(JSON.stringify(result.result.exceptionDetails));
const report = { page: JSON.parse(result.result.result.value), target: page.url };
writeFileSync(reportFile, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));

const p = report.page;
const checks = [
  ["the page is Sales X-Ray's own address (the bundle answers it)", p.href.startsWith("https://salesxray.authorityclosers.com/")],
  ["the app serves the bundled web app (BundledWeb on)", p.webShell && p.webShell.on === true],
  ["a second screen (/leads/) loads", p.leadsScreen === 200 && p.leadsBytes > 1000],
  ["the API answered", p.apiStatus === 200],
  ["the page sees the phone app (data-sx-app)", p.inApp === true],
  ["SXPhone status says android, with calling", p.phoneStatus && p.phoneStatus.platform === "android" && p.phoneStatus.calling === true],
  ["haptics answered by the native side", p.haptic === true],
];
let failed = 0;
for (const [what, ok] of checks) {
  console.log(`${ok ? "ok  " : "FAIL"} ${what}`);
  if (!ok) failed += 1;
}
process.exit(failed ? 1 : 0);
