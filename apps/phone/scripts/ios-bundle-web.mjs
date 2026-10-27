// Puts Sales X-Ray's web app (the static export build-web-shell.sh makes for Android too) inside the
// iPhone app, after `npx cap sync ios`. Capacitor then serves it at capacitor://localhost instead of
// opening the live site, and SXBridgeViewController sends the page's /v1 calls to the server through
// Capacitor's native HTTP (on here), with cookies in the app's own jar.
//
//   node scripts/ios-bundle-web.mjs <web export dir>        (from apps/phone)
//
// Runs on GitHub's runners (iphone.yml), never on a laptop or ac.
import { cpSync, existsSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

const web = process.argv[2];
if (!web || !existsSync(path.join(web, "index.html"))) {
  console.error("ios-bundle-web: give the web export folder (it has index.html)");
  process.exit(1);
}
const app = path.join("ios", "App", "App");
const pub = path.join(app, "public");
const configFile = path.join(app, "capacitor.config.json");
if (!existsSync(configFile)) {
  console.error("ios-bundle-web: run `npx cap sync ios` first");
  process.exit(1);
}

// Capacitor's own cordova files stay; the shell's offline page and start page give way to the web app.
const keep = new Set(["cordova.js", "cordova_plugins.js"]);
for (const entry of readdirSync(pub)) if (!keep.has(entry)) rmSync(path.join(pub, entry), { recursive: true, force: true });
cpSync(web, pub, { recursive: true });

const config = JSON.parse(readFileSync(configFile, "utf8"));
delete config.server; // no live site: the bundle is the app
config.plugins = {
  ...config.plugins,
  CapacitorHttp: { enabled: true },
  CapacitorCookies: { enabled: true },
};
writeFileSync(configFile, JSON.stringify(config, null, 2));

let files = 0;
let bytes = 0;
(function walk(dir) {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) walk(full);
    else {
      files += 1;
      bytes += statSync(full).size;
    }
  }
})(pub);
for (const page of ["index.html", "dashboard/index.html", "leads/index.html", "chats/index.html"]) {
  if (!existsSync(path.join(pub, page))) {
    console.error(`ios-bundle-web: the bundle has no ${page}`);
    process.exit(1);
  }
}
const manifest = existsSync(path.join(pub, "sx-web.json")) ? JSON.parse(readFileSync(path.join(pub, "sx-web.json"), "utf8")) : {};
console.log(`iPhone bundle: ${files} files, ${(bytes / 1048576).toFixed(1)} MB, web ${String(manifest.sha ?? "?").slice(0, 8)} (${manifest.ref ?? "?"})`);
console.log(`capacitor.config.json: ${JSON.stringify(config)}`);
