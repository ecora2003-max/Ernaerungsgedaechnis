#!/usr/bin/env python3
"""Only renew the current integration owner's lock after trusted host CI."""
import base64
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

PATH = "Prüfe mich ob du dran bist und was du machen sollst und darfst.md"
UTC = dt.timezone.utc
TEN = dt.timedelta(minutes=10)
FIELDS = ("state", "ownerAutomationId", "ownerRunToken", "acquiredAtUtc", "lastHeartbeatUtc", "leaseExpiresAtUtc", "workingBranch")

def parse_time(value):
    if not isinstance(value, str) or not value:
        raise ValueError("missing timestamp")
    result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("timestamp without offset")
    return result.astimezone(UTC)

def utc_text(value):
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")

def parse_lock(document):
    if not (document.endswith("**Hier arbeitet jetzt ein Chat!**\n")
            or document.endswith("**Hier arbeitet kein Chat mehr.**\n")):
        raise ValueError("missing terminal status")
    begin_marker = chr(96) * 3 + "yaml\n"
    end_marker = "\n" + chr(96) * 3
    beginning = document.rfind(begin_marker)
    if beginning < 0:
        raise ValueError("missing YAML status")
    start = beginning + len(begin_marker)
    end = document.find(end_marker, start)
    if end < 0:
        raise ValueError("unterminated YAML status")
    block = document[start:end]
    fields = {}
    for line in block.splitlines():
        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*): (.*)", line)
        if not match:
            raise ValueError("unexpected status line")
        key, raw = match.groups()
        if key in fields:
            raise ValueError("duplicate status field")
        fields[key] = None if raw == "null" else json.loads(raw) if raw.startswith('"') else raw
    if not all(key in fields for key in FIELDS):
        raise ValueError("missing required status fields")
    return start, end, block, fields

def propose(document, branch, run_created, now):
    """Returns (updated document or None, decision reason); never activates FREE."""
    start, end, block, fields = parse_lock(document)
    if fields["state"] != "ACTIVE" or not document.endswith("**Hier arbeitet jetzt ein Chat!**\n"):
        return None, "FREE_OR_INACTIVE"
    if not fields["ownerAutomationId"] or not fields["ownerRunToken"]:
        return None, "MISSING_OWNER"
    if branch == "main" or not branch or branch == "pending" or fields["workingBranch"] != branch:
        return None, "OTHER_BRANCH"
    acquired = parse_time(fields["acquiredAtUtc"])
    expiry = parse_time(fields["leaseExpiresAtUtc"])
    heartbeat = parse_time(fields["lastHeartbeatUtc"])
    if parse_time(run_created) < acquired:
        return None, "OLD_CI_RUN"
    if now < acquired or now > expiry or heartbeat > now:
        return None, "EXPIRED_OR_INVALID_LEASE"
    # At most 10 minutes after the current CI event, preserving longer valid leases.
    new_expiry = max(expiry, now + TEN)
    updated = block
    for key, value in (("lastHeartbeatUtc", utc_text(now)), ("leaseExpiresAtUtc", utc_text(new_expiry))):
        updated, count = re.subn(r"(?m)^" + key + r": .*?$",
                                 lambda _: key + ": " + json.dumps(value),
                                 updated)
        if count != 1:
            raise ValueError("duplicate or missing lease field")
    return document[:start] + updated + document[end:], "RENEWED"

def request(url, token, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "eg-lease-renewal"}
    if data:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)

def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    if repo != "ecora2003-max/Ernaerungsgedaechnis":
        raise ValueError("wrong repository")
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as stream:
        event = json.load(stream)
    run = event.get("workflow_run") or {}
    if (run.get("name") != "Android checks and optional APK"
        or run.get("event") not in ("push", "pull_request")
        or (run.get("head_repository") or {}).get("full_name") != repo
        or not run.get("head_branch") or run.get("head_branch") == "main"
        or not run.get("created_at")):
        print("SKIP untrusted or irrelevant CI event")
        return
    url = "https://api.github.com/repos/" + repo + "/contents/" + urllib.parse.quote(PATH, safe="/")
    token = os.environ["GITHUB_TOKEN"]
    current = request(url + "?ref=main", token)
    document = base64.b64decode(current["content"]).decode("utf-8")
    updated, reason = propose(document, run["head_branch"], run["created_at"], dt.datetime.now(UTC))
    if updated is None:
        print("SKIP " + reason)
        return
    # The blob SHA is a compare-and-swap condition. A release changes that
    # SHA, so late events cannot resurrect FREE or renew the next owner's lock.
    payload = {"branch": "main", "sha": current["sha"],
               "message": "coordination: renew matching active integration lease [skip ci]",
               "content": base64.b64encode(updated.encode("utf-8")).decode("ascii")}
    try:
        saved = request(url, token, "PUT", payload)
    except urllib.error.HTTPError as exc:
        if exc.code in (409, 422):
            print("SKIP changed concurrently; do not overwrite the lock")
            return
        raise
    print("RENEWED registered branch", run["head_branch"], "at", saved["commit"]["sha"])

if __name__ == "__main__":
    try:
        main()
    except (KeyError, TypeError, ValueError, urllib.error.HTTPError) as error:
        print("LEASE ERROR:", error, file=sys.stderr)
        sys.exit(1)
