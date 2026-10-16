// Fails when this public repo holds a key, keystore, certificate, .env file or
// a secret-looking string. Signing keys live in GitHub secrets (set from the
// owner's laptop) and Infisical, never here. Usage: node scripts/check-no-secrets.mjs
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const forbiddenNames = [
  /\.(keystore|jks|p12|pfx|pem|key|p8|cer|crt|der|mobileprovision|provisionprofile)$/i,
  /(^|\/)\.env(\..*)?$/i,
  /(^|\/)(google-services\.json|GoogleService-Info\.plist|local\.properties|keystore\.properties)$/i,
];

// Pattern sources are split so this file doesn't match itself.
const secretPatterns = [
  ["private key", new RegExp(String.raw`-----BEGIN [A-Z ]*PRIVATE ` + "KEY-----")],
  ["AWS key", new RegExp("AKIA" + String.raw`[0-9A-Z]{16}`)],
  ["GitHub token", new RegExp(String.raw`\b(gh[pousr]_[A-Za-z0-9]{36,}|github_` + String.raw`pat_[A-Za-z0-9_]{40,})`)],
  ["Slack token", new RegExp("xox" + String.raw`[baprs]-[A-Za-z0-9-]{10,}`)],
  ["Google API key", new RegExp("AI" + String.raw`za[0-9A-Za-z_-]{35}`)],
  ["Stripe or Razorpay key", new RegExp(String.raw`\b(sk_live_|rk_live_|rzp_live_|rzp_test_)` + String.raw`[A-Za-z0-9]{8,}`)],
  ["Anthropic or OpenAI key", new RegExp(String.raw`\bsk-(ant-)?[A-Za-z0-9_-]{` + "32,}")],
  ["hard-coded signing password", new RegExp(String.raw`(store|key)Password\s*[=:]?\s*["'][^"'$]`)],
];

export function findProblems(files) {
  const problems = [];
  for (const { path, content } of files) {
    if (forbiddenNames.some((pattern) => pattern.test(path))) {
      problems.push(`${path}: file type that may hold a key or secret`);
      continue;
    }
    if (content.subarray(0, 8000).includes(0)) continue; // binary (icons, the Gradle wrapper)
    const text = content.toString("utf8");
    for (const [label, pattern] of secretPatterns) {
      if (pattern.test(text)) problems.push(`${path}: looks like a ${label}`);
    }
  }
  return problems;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const paths = execFileSync("git", ["ls-files", "-z"], { encoding: "utf8" }).split("\0").filter(Boolean);
  const problems = findProblems(paths.map((path) => ({ path, content: readFileSync(path) })));
  if (problems.length) {
    console.error(`This repo is public. Remove these before pushing:\n${problems.map((p) => `- ${p}`).join("\n")}`);
    process.exit(1);
  }
  console.log(`No keys or secrets in ${paths.length} tracked files.`);
}
