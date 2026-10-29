#!/usr/bin/env python3
"""Staging side of the call-to-report pipeline simulator (scripts/pipeline-sim.sh).

prepare  signs the QA seller in, selects the organisation workspace and checks everything a call needs
         before it can be analysed: profile eligibility, standing consent, call minutes, the fictional lead.
poll     follows the call on the server (capture guard) to a report and names the stage it stopped at.
device   reads the phone's upload queue (the app's own preferences, over adb run-as) as JSON.

Stdlib only. Cookies and the password are never printed; the cookie jar is written 0600.
"""
from __future__ import annotations

import argparse
import base64
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone

SIM_NAMESPACE = uuid.UUID("6f2f8a52-5e4b-4b1d-9a43-1f3c0f0e7c11")
# The QA seller's staging login lives only in Infisical (AGENTS.md); the run reads it with its own GitHub
# OIDC token, keyless like play.yml, and keeps it in this process only.
INFISICAL_API = os.environ.get("INFISICAL_API_URL", "https://app.infisical.com/api")
INFISICAL_PROJECT = os.environ.get("INFISICAL_PROJECT_ID", "b421c44e-4599-4394-8df6-758ed8aedfed")
INFISICAL_ENVIRONMENT = "staging"
INFISICAL_PATH = os.environ.get("SX_SIM_INFISICAL_PATH", "/qa/pipeline-sim")
INFISICAL_AUDIENCE = os.environ.get("SX_SIM_INFISICAL_AUDIENCE", "infisical-pipeline-sim")
SERVER_STAGES = ["waiting", "uploaded", "analysing", "report_ready"]


class Stuck(Exception):
    """The pipeline stopped: stage is where, detail is why and what fixes it."""

    def __init__(self, stage: str, detail: str):
        super().__init__(f"{stage}: {detail}")
        self.stage = stage
        self.detail = detail


class Session:
    def __init__(self, base: str, jar: str):
        self.base = base.rstrip("/")
        self.jar = jar
        self.cookies: dict[str, str] = {}
        if os.path.exists(jar):
            with open(jar, encoding="utf-8") as handle:
                self.cookies = json.load(handle)

    def save(self) -> None:
        descriptor = os.open(self.jar, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(self.cookies, handle)

    def request(self, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("Origin", self.base)
        request.add_header("User-Agent", "sales-xray-pipeline-sim")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.cookies:
            request.add_header("Cookie", "; ".join(raw.split(";", 1)[0] for raw in self.cookies.values()))
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, headers, raw = response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            status, headers, raw = error.code, error.headers, error.read()
        for header in headers.get_all("Set-Cookie") or []:
            name = header.split("=", 1)[0].strip()
            if name:
                self.cookies[name] = header
        try:
            value: object = json.loads(raw) if raw else {}
        except ValueError:
            value = {}
        return status, value


def detail(value: object) -> str:
    """The server's {"detail"} words only (a 422 can echo its input, so nothing else)."""
    if isinstance(value, dict) and isinstance(value.get("detail"), str):
        return value["detail"][:200]
    return ""


def digits(number: str) -> str:
    return re.sub(r"[^0-9]", "", number)


def lead_command(tenant: str, number: str) -> str:
    """The same fictional lead every night: the add is idempotent on this command id."""
    return str(uuid.uuid5(SIM_NAMESPACE, f"{tenant}:{digits(number)}"))


def pick_workspace(choices: list[dict], wanted: str) -> dict | None:
    """By tenant id or by name (case-insensitive); never Personal by accident."""
    for choice in choices:
        if wanted and (choice.get("tenant_id") == wanted or str(choice.get("name", "")).lower() == wanted.lower()):
            return choice
    return None


def minutes_left(allowance: object, need_seconds: int) -> bool:
    if not isinstance(allowance, dict):
        return False
    if allowance.get("unlimited"):
        return True
    available = allowance.get("available_seconds")
    return isinstance(available, int) and available >= need_seconds


def _json(request: urllib.request.Request) -> dict:
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def staging_login() -> tuple[str, str]:
    """A login typed in for a run by hand (SX_SIM_EMAIL/SX_SIM_PASSWORD), else Infisical via GitHub OIDC."""
    email, password = os.environ.get("SX_SIM_EMAIL", ""), os.environ.get("SX_SIM_PASSWORD", "")
    if email and password:
        return email, password
    identity = os.environ.get("SX_SIM_INFISICAL_IDENTITY_ID", "")
    oidc_url, oidc_token = os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL", ""), os.environ.get("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "")
    if not identity or not oidc_url or not oidc_token:
        raise Stuck("sign_in", "no staging login: set up the Infisical identity for this workflow (docs/qa/pipeline-sim.md).")
    try:
        github = urllib.request.Request(oidc_url + "&audience=" + urllib.parse.quote(INFISICAL_AUDIENCE))
        github.add_header("Authorization", "Bearer " + oidc_token)
        jwt = _json(github)["value"]
        login = urllib.request.Request(INFISICAL_API + "/v1/auth/oidc-auth/login", method="POST",
                                       data=json.dumps({"identityId": identity, "jwt": jwt}).encode())
        login.add_header("Content-Type", "application/json")
        access = _json(login)["accessToken"]
    except (urllib.error.URLError, KeyError, ValueError) as error:
        raise Stuck("sign_in", f"Infisical refused the run's OIDC login ({type(error).__name__}).") from None
    values = []
    try:
        for key in ("SX_SIM_EMAIL", "SX_SIM_PASSWORD"):
            query = urllib.parse.urlencode({"projectId": INFISICAL_PROJECT, "environment": INFISICAL_ENVIRONMENT,
                                            "secretPath": INFISICAL_PATH})
            read = urllib.request.Request(f"{INFISICAL_API}/v4/secrets/{key}?{query}")
            read.add_header("Authorization", "Bearer " + access)
            values.append(_json(read)["secret"]["secretValue"])
    except (urllib.error.URLError, KeyError, ValueError) as error:
        raise Stuck("sign_in", f"Infisical has no {INFISICAL_PATH} staging login for this identity ({type(error).__name__}).") from None
    finally:
        # The token is not needed again: revoke it at once.
        revoke = urllib.request.Request(INFISICAL_API + "/v1/auth/token/revoke", method="POST",
                                        data=json.dumps({"accessToken": access}).encode())
        revoke.add_header("Content-Type", "application/json")
        try:
            urllib.request.urlopen(revoke, timeout=10).close()
        except (urllib.error.URLError, ValueError):
            pass
    return values[0], values[1]


def prepare(args: argparse.Namespace) -> dict:
    email, password = staging_login()
    session = Session(args.base, args.jar)
    status, value = session.request("POST", "/v1/auth/password/login", {"email": email, "password": password})
    if status >= 400:
        raise Stuck("sign_in", f"password sign-in answered HTTP {status} {detail(value)}".strip())
    status, value = session.request("GET", "/v1/me/workspaces")
    choices = value.get("choices", []) if isinstance(value, dict) else []
    choice = pick_workspace(choices, args.workspace)
    if choice is None:
        names = ", ".join(str(item.get("name")) for item in choices)
        raise Stuck("sign_in", f"the QA seller is not in workspace {args.workspace!r} (has: {names}).")
    tenant = choice["tenant_id"]
    if value.get("selected_tenant_id") != tenant:
        status, value = session.request("POST", "/v1/context", {"tenant_id": tenant})
        if status >= 400:
            raise Stuck("sign_in", f"choosing {args.workspace!r} answered HTTP {status} {detail(value)}".strip())
    status, value = session.request("GET", "/v1/me/sales-xray-profile/write-eligibility")
    if status != 204:
        raise Stuck("profile", f"write-eligibility answered HTTP {status} {detail(value)}: complete the QA seller's profile.".strip())
    status, value = session.request("GET", "/v1/sales-xray/call-ai/auto-analysis")
    if status >= 400 or not isinstance(value, dict) or value.get("state") != "on" or not value.get("can_start"):
        raise Stuck("consent", "standing consent (automatic analysis) is off for the QA seller in this workspace.")
    status, value = session.request("GET", "/v1/conversation/acquisition/session")
    allowance = value.get("allowance") if isinstance(value, dict) else None
    if not minutes_left(allowance, args.clip_seconds):
        raise Stuck("allowance", f"the workspace has no call minutes for a {args.clip_seconds}s clip.")
    status, book = session.request("GET", "/v1/lead-desk/phone-book")
    if status >= 400 or not isinstance(book, dict) or not book.get("organisation_workspace"):
        raise Stuck("sign_in", f"the phone book is not an organisation workspace (HTTP {status}).")
    status, value = session.request("POST", "/v1/lead-desk/leads", {
        "command_id": lead_command(tenant, args.lead_number), "source": "manual",
        "name": args.lead_name, "phone": args.lead_number, "assign": "me",
    })
    lead_id = value.get("id") if isinstance(value, dict) and status < 400 else None
    if lead_id is None:
        status, page = session.request("GET", "/v1/lead-desk/leads?search=" + digits(args.lead_number))
        items = page.get("items", page.get("leads", [])) if isinstance(page, dict) else []
        found = [item for item in items if digits(str(item.get("phone", ""))).endswith(digits(args.lead_number)[-10:])]
        lead_id = found[0].get("id") if found else None
    if lead_id is None:
        raise Stuck("sign_in", f"the fictional lead {args.lead_number} could not be added or found.")
    session.save()
    # The app gets the same session as a sign-in gives it (PipelineSimTest puts these in its WebView).
    with open(args.cookies, "w", encoding="utf-8") as handle:
        handle.write(base64.b64encode("\n".join(session.cookies.values()).encode()).decode())
    os.chmod(args.cookies, 0o600)
    return {"tenant_id": tenant, "workspace": choice.get("name"), "lead_id": lead_id,
            "lead_number": args.lead_number, "base": args.base}


def server_stage(call: dict) -> str:
    return "analysis_failed" if call.get("attention") else str(call.get("state"))


def find_call(calls: list[dict], lead_id: str, since: datetime) -> dict | None:
    """Our call: on the fictional lead, made at or after the simulated dial (newest first)."""
    for call in calls:
        at = datetime.fromisoformat(str(call.get("at")).replace("Z", "+00:00"))
        if call.get("lead_id") == lead_id and at >= since:
            return call
    return None


def poll(args: argparse.Namespace) -> None:
    state = json.load(open(args.state, encoding="utf-8"))
    session = Session(state["base"], args.jar)
    since = datetime.fromtimestamp(args.since, timezone.utc)
    deadline = time.monotonic() + args.timeout
    seen = None
    while time.monotonic() < deadline:
        status, guard = session.request("GET", "/v1/lead-desk/capture-guard?days=1")
        if status == 401:
            raise Stuck("sign_in", "the QA session ended while waiting.")
        call = find_call(guard.get("calls", []) if isinstance(guard, dict) else [], state["lead_id"], since)
        stage = server_stage(call) if call else "not_on_server"
        if stage != seen:
            print(json.dumps({"at": time.time(), "server": stage, "event_id": call and call.get("event_id"),
                              "submission_id": call and call.get("submission_id")}), flush=True)
            seen = stage
        # Polling can step over a stage (uploaded straight to analysing): later counts as reached.
        if stage in SERVER_STAGES and SERVER_STAGES.index(stage) >= SERVER_STAGES.index(args.until):
            return
        if stage == "analysis_failed":
            raise Stuck("analysing", "the analysis run failed or was cancelled on staging.")
        if stage == "missing":
            raise Stuck("recorded", "the server says no recording after 30 minutes.")
        time.sleep(args.every)
    raise Stuck(args.stage or args.until, f"still {seen} after {args.timeout}s.")


JOB = re.compile(r'<string name="(job:[^"]+)">(.*?)</string>', re.S)


def device_jobs(xml: str) -> list[dict]:
    """The phone's upload queue from shared_prefs/sx_auto_capture.xml (state, hold, lead, ids)."""
    jobs = []
    for _key, raw in JOB.findall(xml):
        try:
            job = json.loads(html.unescape(raw))
        except ValueError:
            continue
        jobs.append({key: job.get(key) for key in ("id", "state", "hold", "leadId", "eventId", "stamp", "name")})
    return jobs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    one = sub.add_parser("prepare")
    one.add_argument("--base", required=True)
    one.add_argument("--workspace", default=os.environ.get("SX_SIM_WORKSPACE", "Authority Closers"))
    one.add_argument("--lead-number", required=True)
    one.add_argument("--lead-name", default="Pipeline Sim (fictional)")
    one.add_argument("--clip-seconds", type=int, default=60)
    one.add_argument("--jar", required=True)
    one.add_argument("--cookies", required=True)
    one.add_argument("--out", required=True)
    two = sub.add_parser("poll")
    two.add_argument("--state", required=True)
    two.add_argument("--jar", required=True)
    two.add_argument("--since", type=float, required=True)
    two.add_argument("--until", choices=SERVER_STAGES, required=True)
    two.add_argument("--stage", help="the stage named when this times out")
    two.add_argument("--timeout", type=int, default=900)
    two.add_argument("--every", type=int, default=10)
    three = sub.add_parser("device")
    three.add_argument("xml")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args)
            with open(args.out, "w", encoding="utf-8") as handle:
                json.dump(result, handle)
            print(json.dumps({key: result[key] for key in ("workspace", "lead_id")}))
        elif args.command == "poll":
            poll(args)
        else:
            print(json.dumps(device_jobs(open(args.xml, encoding="utf-8").read())))
    except Stuck as stuck:
        print(json.dumps({"stuck": stuck.stage, "detail": stuck.detail}), flush=True)
        print(f"PIPELINE STUCK at {stuck.stage}: {stuck.detail}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
