// Real Safari on macOS through its own WebDriver (safaridriver): the same public pages
// as smoke.mjs, one screenshot each. No extra packages: plain HTTP to the driver.
import { mkdirSync, writeFileSync } from "node:fs";

const BASE = process.env.SX_BASE_URL || "https://salesxray.authorityclosers.com";
const DRIVER = process.env.SAFARI_DRIVER || "http://127.0.0.1:4444";
const OUT = process.env.OUT || "shots";
const BOT_CHECK = /Performing security verification|Verify you are human/i;
const PAGES = [
  { path: "/dashboard", name: "sign-in", words: /sign in|email|continue/i },
  { path: "/apps", name: "apps", words: /Download for Android/ },
  { path: "/privacy", name: "privacy", words: /privacy/i },
];

async function call(method, path, body) {
  const response = await fetch(DRIVER + path, {
    method,
    headers: { "content-type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await response.json();
  if (!response.ok) throw new Error(`${method} ${path}: ${JSON.stringify(json).slice(0, 300)}`);
  return json.value;
}

mkdirSync(OUT, { recursive: true });
const session = await call("POST", "/session", { capabilities: { alwaysMatch: { browserName: "safari" } } });
const id = session.sessionId;
const failures = [];
try {
  await call("POST", `/session/${id}/window/rect`, { width: 1280, height: 900 });
  for (const target of PAGES) {
    await call("POST", `/session/${id}/url`, { url: BASE + target.path });
    let text = "";
    for (let i = 0; i < 20 && !target.words.test(text) && !BOT_CHECK.test(text); i++) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      text = await call("POST", `/session/${id}/execute/sync`, { script: "return document.body ? document.body.innerText : ''", args: [] });
    }
    const png = await call("GET", `/session/${id}/screenshot`);
    writeFileSync(`${OUT}/safari-macos-${target.name}.png`, Buffer.from(png, "base64"));
    if (BOT_CHECK.test(text)) {
      failures.push(target.name);
      console.log(`FAIL safari-macos/${target.name}: Cloudflare's bot check stopped the page`);
    } else if (target.words.test(text)) console.log(`ok safari-macos/${target.name}`);
    else {
      failures.push(target.name);
      console.log(`FAIL safari-macos/${target.name}`);
    }
  }
} finally {
  await call("DELETE", `/session/${id}`).catch(() => {});
}
if (failures.length) process.exit(1);
console.log("all Safari (macOS) checks passed");
