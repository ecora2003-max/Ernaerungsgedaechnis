#!/usr/bin/env python3
"""Deprecated pure lease parsing/tests. No unattended CI renewal is authorized."""
import datetime as dt
import json
import re

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


def main():
    """Never write a lease from CI: the owner automation may have completed."""
    print("DISABLED: CI has no trusted access to the automation owner's live run status")
    return "DISABLED"


if __name__ == "__main__":
    main()
