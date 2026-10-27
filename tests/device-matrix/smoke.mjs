// Sales Xray on every kind of browser and screen (owner order, 19 Oct): the public,
// signed-out pages, with a screenshot of each. SX_BASE_URL is where: the release's own
// build served on the runner (device-matrix.yml, since 27 Oct), else production. Signed-in
// flows need a test account the owner approves (see README.md).
//
//   BROWSER=chromium|firefox|webkit|chrome|msedge node tests/device-matrix/smoke.mjs
//
// Exits non-zero when any page is missing its key words.
import { mkdirSync } from "node:fs";
import { chromium, firefox, webkit } from "playwright";

const BASE = process.env.SX_BASE_URL || "https://salesxray.authorityclosers.com";
// Cloudflare's challenge page, shown instead of the site to some datacenter addresses.
const BOT_CHECK = /Performing security verification|Verify you are human/i;
const BROWSER = process.env.BROWSER || "chromium";
const OUT = process.env.OUT || "shots";

// Key words each page must show once it has rendered.
const PAGES = [
  { path: "/dashboard", name: "sign-in", words: /sign in|email|continue/i },
  { path: "/apps", name: "apps", words: /Download for Android/ },
  { path: "/privacy", name: "privacy", words: /privacy/i },
  { path: "/pricing", name: "pricing", words: /pricing|plan/i },
  { path: "/terms", name: "terms", words: /terms/i },
];

// Small phone, phone, foldable (unfolded), tablet, desktop.
const SCREENS = [
  { name: "small-320", width: 320, height: 568, mobile: true },
  { name: "phone-390", width: 390, height: 844, mobile: true },
  { name: "foldable-673", width: 673, height: 841, mobile: true },
  { name: "tablet-820", width: 820, height: 1180, mobile: true },
  { name: "desktop-1440", width: 1440, height: 900, mobile: false },
];

const engines = { chromium, firefox, webkit, chrome: chromium, msedge: chromium };
const engine = engines[BROWSER];
if (!engine) throw new Error(`unknown BROWSER ${BROWSER}`);
const channel = BROWSER === "chrome" || BROWSER === "msedge" ? BROWSER : undefined;

mkdirSync(OUT, { recursive: true });
const browser = await engine.launch({ channel });
const failures = [];
for (const screen of SCREENS) {
  const context = await browser.newContext({
    viewport: { width: screen.width, height: screen.height },
    bypassCSP: true,
    // Firefox has no mobile emulation; it still gets the narrow viewport.
    ...(screen.mobile && BROWSER !== "firefox" ? { isMobile: true, hasTouch: true } : {}),
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  for (const target of PAGES) {
    const label = `${BROWSER}/${screen.name}/${target.name}`;
    try {
      const response = await page.goto(BASE + target.path, { waitUntil: "domcontentloaded", timeout: 45000 });
      // Read the text through the locator: production's CSP forbids eval, which
      // page.waitForFunction needs in Firefox and WebKit.
      let text = "";
      for (let i = 0; i < 20 && !target.words.test(text); i++) {
        text = await page.locator("body").innerText({ timeout: 5000 }).catch(() => "");
        if (BOT_CHECK.test(text)) break;
        if (!target.words.test(text)) await page.waitForTimeout(1000);
      }
      if (BOT_CHECK.test(text)) throw new Error("Cloudflare's bot check stopped the page (see README.md)");
      if (!target.words.test(text)) throw new Error("the page's key words never appeared");
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > window.innerWidth + 1,
      );
      await page.screenshot({ path: `${OUT}/${BROWSER}-${screen.name}-${target.name}.png`, fullPage: false });
      const status = response ? response.status() : 0;
      console.log(`ok ${label} ${status}${overflow ? " (wider than the screen)" : ""}`);
      if (overflow) failures.push(`${label}: page is wider than the screen`);
    } catch (error) {
      failures.push(`${label}: ${String(error.message || error).split("\n")[0]}`);
      await page.screenshot({ path: `${OUT}/${BROWSER}-${screen.name}-${target.name}-FAILED.png` }).catch(() => {});
      console.log(`FAIL ${label}`);
    }
  }
  if (errors.length) console.log(`page errors on ${screen.name}: ${errors.slice(0, 3).join(" | ")}`);
  await context.close();
}
await browser.close();
if (failures.length) {
  console.log(`\n${failures.length} problem(s):\n- ${failures.join("\n- ")}`);
  process.exit(1);
}
console.log(`\nall ${SCREENS.length * PAGES.length} checks passed on ${BROWSER}`);
