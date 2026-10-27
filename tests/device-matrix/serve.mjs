// The release's public pages for the browser jobs: the web app's static export (device-matrix.yml's
// web-export job) served on 127.0.0.1, the same way on Linux, Windows and macOS. Production sits
// behind Cloudflare's bot check, which holds GitHub's runners back (README.md), so the browsers open
// this build of the same code instead (orchestrator, 27 Oct 2026).
//
//   node tests/device-matrix/serve.mjs <export dir> [port, default 8080]
//
// A path is its file, or its folder's index.html (the export's trailing-slash pages); anything else
// is the export's 404.html with status 404. Nothing outside the export is ever served.
//
// There is no API here, so /v1 answers as it does a signed-out visitor: 401 for everything, as a
// signed-out session read gets (so /dashboard shows sign-in, not "Can't reach Sales X-Ray"), except the
// public sign-in settings, which offer an email code and no Google, Apple, Facebook or Pulse (nothing
// here could open them). Nothing is ever sent anywhere.
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize, resolve, sep } from "node:path";

const root = resolve(process.argv[2] || "site");
const port = Number(process.argv[3] || 8080);
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".webmanifest": "application/manifest+json",
};

/** The file a request path names inside the export, or null. */
export async function fileFor(path) {
  let clean;
  try {
    clean = normalize(decodeURIComponent(path.split("?")[0]));
  } catch {
    return null;
  }
  const full = join(root, clean);
  if (full !== root && !full.startsWith(root + sep)) return null;
  for (const candidate of [full, join(full, "index.html"), `${full}.html`]) {
    try {
      if ((await stat(candidate)).isFile()) return candidate;
    } catch {
      // Not this one.
    }
  }
  return null;
}

const SIGN_IN_SETTINGS = {
  enabled: true,
  consent_version: "device-matrix",
  google_enabled: false,
  apple_enabled: false,
  facebook_enabled: false,
  pulse_enabled: false,
  expires_in_seconds: 600,
  resend_after_seconds: 30,
};

/** What the API would tell a signed-out visitor: [status, body]. */
export function apiAnswer(method, path) {
  if (method === "GET" && path.split("?")[0] === "/v1/auth/email-code/config") return [200, SIGN_IN_SETTINGS];
  return [401, { detail: "Not signed in." }];
}

const server = createServer(async (request, response) => {
  if ((request.url || "").startsWith("/v1/")) {
    const [status, body] = apiAnswer(request.method, request.url);
    response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
    response.end(JSON.stringify(body));
    return;
  }
  const file = await fileFor(request.url || "/");
  const body = await readFile(file || join(root, "404.html")).catch(() => Buffer.from("Not found"));
  response.writeHead(file ? 200 : 404, {
    "content-type": file ? TYPES[extname(file)] || "application/octet-stream" : TYPES[".html"],
  });
  response.end(body);
});
server.listen(port, "127.0.0.1", () => console.log(`serving ${root} on http://127.0.0.1:${port}`));
