# Signed-in nightly on production (runs on ac)

Owner approval (relayed 19 Oct): the existing production QA test account, one analysis a night, its calls kept out of real metrics.

- Runs on the ac server at 02:30 IST (`ac-qa-nightly.timer`, user acdev). It uses headless Chromium against production's edge on loopback (127.0.0.1:8080, public Host header), so Cloudflare's bot check is not in the path. GitHub's runners can't do this, so GitHub keeps only the signed-out device matrix.
- Installed copy: `/home/acdev/qa-nightly/` (`nightly.mjs`, `run.sh`, `node_modules/playwright-core@1.58.2`, `lib/` with Chrome's missing libraries unpacked from Ubuntu packages). Units: `/etc/systemd/system/ac-qa-nightly.{service,timer}`.
- Credentials: read from `/home/acdev/.config/ac-qa/production-account.env` (0600) and never printed. Error bodies are reduced to type/loc/msg, because a 422 echoes the password.
- The one analysis uploads the bundled canary recording `sales_call_v1.wav`. Workbench 9dacd869 makes `recording_is_canary()` match it, which keeps it out of usage, recordings, review, gold and the library. The analysis runs only once `/home/acdev/qa-nightly/ANALYSIS_ENABLED` exists (create it once production runs 9dacd869); until then each run signs in and takes screenshots only.
- Guards: refuses anything but the production QA identity; at most one analysis per IST day (`ledger.jsonl`, claimed before upload); stops without accepting if the quote exceeds ₹450.
- Output: `/home/acdev/qa-nightly/runs/<time>/` holds screenshots and `result.json`, kept for 30 days. `journalctl -u ac-qa-nightly` has the summary.
- A screen counts as failed when the updated-terms box covers it, when Leads says the account has no organisation, or when Cloudflare's check shows.
