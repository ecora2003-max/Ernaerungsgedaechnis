#!/usr/bin/env python3
"""GitHub-only 30-minute inactivity recovery. Fail closed on uncertainty."""
from __future__ import annotations
import argparse
import base64
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import os
import re
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

REPOSITORY = "ecora2003-max/Ernaerungsgedaechnis"
LOCK_PATH = "integration-lock.json"
MIN_IDLE = timedelta(minutes=30)
ALLOWED_AUTOMATIONS = frozenset({
    "6ae659b2cf2081918f3414dce24a090e",
    "6ae65fdd040081919b0a5fb1c8962104",
    "6ae66058b40c8191b8efb843b437b0ca",
    "6ae64ea269448191ac8f0b8a5fd206cb",
})
# This file is the ONLY operational lock. Never parse lock data from Markdown.
RELEASE_FIELDS = ("ownerAutomationId", "ownerChatId", "ownerRunId", "ownerRunToken",
                  "acquiredAtUtc", "lastHeartbeatUtc", "leaseExpiresAtUtc", "workingBranch")
KEY_ORDER = ("schemaVersion", "state", "ownerAutomationId", "ownerChatId", "ownerRunId",
             "ownerRunToken", "acquiredAtUtc", "lastHeartbeatUtc", "leaseExpiresAtUtc",
             "task", "workingBranch", "lastReleasedAtUtc", "lastReleasedCommit", "nextStep")
REQUIRED_FIELDS = frozenset(KEY_ORDER)
REPAIR_REQUIRED = frozenset(RELEASE_FIELDS) | {"schemaVersion", "state"}

class RecoveryBlocked(Exception):
    """Malformed evidence, unavailable API or uncertain owner: do not unlock."""

def utc(iso: str) -> datetime:
    if not isinstance(iso, str) or not iso:
        raise RecoveryBlocked("timestamp missing")
    try:
        value = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError as e:
        raise RecoveryBlocked("timestamp invalid") from e
    if value.tzinfo is None:
        raise RecoveryBlocked("timezone missing")
    return value.astimezone(timezone.utc)

def format_utc(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")

@dataclass(frozen=True)
class Lock:
    values: dict[str, Any]
    recovered: bool = False

def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    obj: dict[str, Any] = {}
    for key, value in pairs:
        if key in obj:
            raise RecoveryBlocked("duplicate JSON lock key")
        obj[key] = value
    return obj

def _validate_lock(values: dict[str, Any]) -> None:
    if not isinstance(values, dict) or set(values) != REQUIRED_FIELDS:
        raise RecoveryBlocked("missing or extra lock fields")
    if type(values["schemaVersion"]) is not int or values["schemaVersion"] != 1:
        raise RecoveryBlocked("unsupported lock schema")
    if values["state"] not in ("ACTIVE", "FREE"):
        raise RecoveryBlocked("invalid lock state")
    if values["state"] == "FREE" and any(values[k] is not None for k in RELEASE_FIELDS):
        raise RecoveryBlocked("FREE lock has owner metadata")
    for name in ("ownerAutomationId", "ownerChatId", "ownerRunToken", "task",
                 "workingBranch", "lastReleasedCommit", "nextStep"):
        if values[name] is not None and not isinstance(values[name], str):
            raise RecoveryBlocked("invalid field type: " + name)
    for name in ("acquiredAtUtc", "lastHeartbeatUtc", "leaseExpiresAtUtc", "lastReleasedAtUtc"):
        if values[name] is not None:
            utc(values[name])
    if values["ownerRunId"] is not None:
        raise RecoveryBlocked("ownerRunId must be null")

def _repairable_active_values(content: str) -> dict[str, Any]:
    """Conservatively extract an identifiable ACTIVE owner from damaged pretty JSON.

    This is ONLY used by the watchdog, never by an acquiring integrator. A missing,
    conflicting or duplicated identity, timestamp or branch is not recoverable.
    """
    if len(content) > 8192:
        raise RecoveryBlocked("damaged lock too large")
    fields: dict[str, Any] = {}
    pattern = re.compile(r'\s*"([A-Za-z][A-Za-z0-9]*)"\s*:\s*("(?:\\.|[^"\\])*"|null|true|false|-?[0-9]+)\s*,?\s*')
    for line in content.splitlines():
        matched = pattern.fullmatch(line)
        if matched:
            key, literal = matched.groups()
            if key in fields:
                raise RecoveryBlocked("duplicate damaged lock field")
            try:
                fields[key] = json.loads(literal)
            except json.JSONDecodeError as e:
                raise RecoveryBlocked("invalid damaged scalar") from e
    if not REPAIR_REQUIRED.issubset(fields):
        raise RecoveryBlocked("cannot recover all owner and activity evidence")
    if fields["state"] != "ACTIVE" or fields["schemaVersion"] != 1:
        raise RecoveryBlocked("cannot recover non-ACTIVE or unknown format")
    # Even if only punctuation was damaged, never infer or overwrite owner data.
    values = {k: fields.get(k) for k in KEY_ORDER}
    for key in ("task", "lastReleasedCommit", "nextStep", "lastReleasedAtUtc"):
        values.setdefault(key, None)
    _validate_lock(values)
    if not is_eligible_owner(Lock(values)):
        raise RecoveryBlocked("damaged owner identity is not verifiable")
    if not values["ownerRunToken"].startswith(values["ownerAutomationId"] + "-"):
        raise RecoveryBlocked("damaged token does not match owner")
    return values

def parse_lock(content: str, *, allow_repair: bool = False) -> Lock:
    try:
        values = json.loads(content, object_pairs_hook=_unique_pairs)
    except json.JSONDecodeError as e:
        if not allow_repair:
            raise RecoveryBlocked("standalone lock JSON malformed") from e
        return Lock(_repairable_active_values(content), recovered=True)
    _validate_lock(values)
    return Lock(values)

def is_eligible_owner(lock: Lock) -> bool:
    v = lock.values
    return (v["state"] == "ACTIVE"
            and v["ownerAutomationId"] in ALLOWED_AUTOMATIONS
            and isinstance(v["ownerRunToken"], str) and bool(v["ownerRunToken"].strip())
            and isinstance(v["ownerChatId"], str) and bool(v["ownerChatId"].strip())
            and v["ownerRunId"] is None and v["leaseExpiresAtUtc"] is None
            and isinstance(v["acquiredAtUtc"], str)
            and (v["workingBranch"] is None or isinstance(v["workingBranch"], str)))

def latest_activity(lock: Lock, *, now: datetime, branch_commit: datetime | None,
                    pr_updates: list[datetime],
                    runs: list[dict[str, Any]]) -> tuple[datetime, bool]:
    timestamps = [utc(lock.values["acquiredAtUtc"])]
    if lock.values["lastHeartbeatUtc"] is not None:
        timestamps.append(utc(lock.values["lastHeartbeatUtc"]))
    if branch_commit is not None:
        timestamps.append(branch_commit)
    timestamps.extend(pr_updates)
    for run in runs:
        if run.get("status") != "completed":
            return max(timestamps), True
        for key in ("created_at", "updated_at", "run_started_at"):
            if run.get(key):
                timestamps.append(utc(run[key]))
    return max(timestamps), False

def render_free_lock(lock: Lock, *, now: datetime, last_commit: str | None) -> str:
    """Replace the COMPLETE standalone lock with a known-good FREE JSON template."""
    values = dict(lock.values)
    values["schemaVersion"] = 1
    values["state"] = "FREE"
    for field in RELEASE_FIELDS:
        values[field] = None
    values["task"] = "Automatisch wiederhergestellt nach mindestens 30 Minuten Inaktivitaet"
    values["lastReleasedAtUtc"] = format_utc(now)
    values["lastReleasedCommit"] = last_commit
    values["nextStep"] = "Aus docs/STATUS.md fortsetzen; JSON-Sperre neu uebernehmen"
    return json.dumps({key: values[key] for key in KEY_ORDER},
                      ensure_ascii=False, indent=2) + "\n"

class Github:
    def __init__(self, repo: str, token: str):
        self.repo, self.token = repo, token
        self.prefix = f"https://api.github.com/repos/{repo}"

    def api(self, method: str, path: str, *, data: dict[str, Any] | None = None) -> Any:
        req = Request(self.prefix + path, method=method,
                      data=(json.dumps(data).encode("utf-8") if data is not None else None),
                      headers={"Authorization": f"Bearer {self.token}",
                               "Accept": "application/vnd.github+json",
                               "X-GitHub-Api-Version": "2022-11-28",
                               "User-Agent": "eg-integration-idle-recovery",
                               "Content-Type": "application/json"})
        try:
            with urlopen(req, timeout=20) as resp:
                return json.load(resp)
        except (HTTPError, URLError, OSError, json.JSONDecodeError) as e:
            raise RecoveryBlocked(f"GitHub API failed {method} {path.split('?')[0]}") from e

    def lock(self) -> tuple[str, str]:
        r = self.api("GET", f"/contents/{quote(LOCK_PATH, safe='/')}?ref=main")
        if not isinstance(r.get("sha"), str) or r.get("encoding") != "base64":
            raise RecoveryBlocked("incomplete contents response")
        try:
            content = base64.b64decode(r["content"]).decode("utf-8")
        except (ValueError, UnicodeError, KeyError) as e:
            raise RecoveryBlocked("cannot decode lock") from e
        return r["sha"], content

    def branch_head(self, name: str) -> tuple[str, datetime]:
        r = self.api("GET", f"/branches/{quote(name, safe='')}")
        sha = r["commit"]["sha"]
        commit = self.api("GET", f"/commits/{quote(sha, safe='')}")
        return sha, utc(commit["commit"]["committer"]["date"])

    def runs(self, branch: str) -> list[dict[str, Any]]:
        q = urlencode({"branch": branch, "per_page": "100"})
        d = self.api("GET", f"/actions/runs?{q}")
        runs = d["workflow_runs"]
        if not isinstance(runs, list) or not isinstance(d["total_count"], int):
            raise RecoveryBlocked("invalid Actions response")
        if d["total_count"] > len(runs):
            raise RecoveryBlocked("truncated Actions history; cannot prove inactivity")
        return runs

    def pull_updates(self, branch: str) -> list[datetime]:
        owner = self.repo.split("/", 1)[0]
        q = urlencode({"state": "open", "head": f"{owner}:{branch}", "per_page": "100"})
        d = self.api("GET", f"/pulls?{q}")
        if not isinstance(d, list) or len(d) == 100:
            raise RecoveryBlocked("incomplete PR response")
        return [utc(pr["updated_at"]) for pr in d]

    def release(self, sha: str, content: str) -> Any:
        return self.api("PUT", f"/contents/{quote(LOCK_PATH, safe='/')}", data={
            "message": "chore(lock): restore standalone JSON lock after verified inactivity",
            "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
            "sha": sha, "branch": "main"})

def check_idle(gh: Github, lock: Lock, *, now: datetime) -> tuple[bool, str | None]:
    if not is_eligible_owner(lock):
        return False, None
    if now - utc(lock.values["acquiredAtUtc"]) < MIN_IDLE:
        return False, None
    branch = lock.values["workingBranch"]
    branch_sha, branch_updated, prs, runs = None, None, [], []
    if branch:
        branch_sha, branch_updated = gh.branch_head(branch)
        prs, runs = gh.pull_updates(branch), gh.runs(branch)
    last, unfinished = latest_activity(lock, now=now, branch_commit=branch_updated,
                                        pr_updates=prs, runs=runs)
    if unfinished or now - last < MIN_IDLE:
        return False, branch_sha
    return True, branch_sha

def execute(gh: Github, *, now: datetime) -> str:
    old_sha, old_text = gh.lock()
    lock = parse_lock(old_text, allow_repair=True)
    if lock.values["state"] == "FREE":
        return "SKIP: FREE"
    if not is_eligible_owner(lock):
        return "SKIP: owner unknown or not registered"
    eligible, branch_sha = check_idle(gh, lock, now=now)
    if not eligible:
        return "SKIP: insufficient inactivity or ongoing CI"
    # Recheck lock SHA, exact owner and GitHub activity before each release.
    new_sha, new_text = gh.lock()
    if (old_sha, old_text) != (new_sha, new_text):
        return "SKIP: lock changed"
    eligible_again, branch_sha_again = check_idle(gh, parse_lock(new_text, allow_repair=True), now=now)
    if not eligible_again or branch_sha_again != branch_sha:
        return "SKIP: branch or activity changed"
    gh.release(new_sha, render_free_lock(lock, now=now, last_commit=branch_sha))
    verified_sha, verified_text = gh.lock()
    verified = parse_lock(verified_text)
    if (verified_sha == new_sha or verified.values["state"] != "FREE"
        or any(verified.values[k] is not None for k in RELEASE_FIELDS)):
        raise RecoveryBlocked("release not independently verified")
    return ("RESTORED: malformed lock replaced after >=30m verified inactivity" if lock.recovered
            else "RELEASED: >=30m inactivity; owner/token/sha verified")

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    o = p.parse_args()
    if o.repo != REPOSITORY or os.environ.get("GITHUB_REF", "refs/heads/main") != "refs/heads/main":
        print("BLOCKED: wrong repo or ref")
        return 1
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        print("BLOCKED: GH_TOKEN missing")
        return 1
    try:
        print(execute(Github(o.repo, token), now=datetime.now(timezone.utc)))
    except RecoveryBlocked as e:
        print(f"BLOCKED: {e}")
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
