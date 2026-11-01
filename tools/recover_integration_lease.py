#!/usr/bin/env python3
"""Pure fail-closed recovery for an expired integration lock.

The caller MUST obtain RunEvidence from a fresh, trusted Automations.peek
result, not from model output, a GitHub PR, or an unverified string. This
module cannot independently query ChatGPT automation state. The caller must
then perform a GitHub contents-API compare-and-swap using the freshly read
main-branch file blob SHA and verify FREE before reacquiring.
"""
from dataclasses import dataclass
import datetime as dt
import json
import re

from renew_integration_lease import parse_lock, parse_time, utc_text

UTC = dt.timezone.utc


@dataclass(frozen=True)
class RunEvidence:
    automation_id: str
    run_id: str
    status: str
    started_at: str
    completed_at: str | None


def propose_release(document: str, evidence: RunEvidence, now: dt.datetime,
                    main_head_sha: str) -> tuple[str | None, str]:
    """Return an updated FREE document or (None, reason); never acquire ACTIVE.

    Only the latest exactly identified completed/failed owner run qualifies.
    A still-running, unidentifiable or legacy run is never reclaimed here.
    """
    start, end, block, fields = parse_lock(document)
    if fields["state"] != "ACTIVE" or not document.endswith(
            "**Hier arbeitet jetzt ein Chat!**\n"):
        return None, "NOT_ACTIVE"
    if not now.tzinfo:
        raise ValueError("now must have UTC offset")
    now = now.astimezone(UTC)
    expiry = parse_time(fields["leaseExpiresAtUtc"])
    acquired = parse_time(fields["acquiredAtUtc"])
    heartbeat = parse_time(fields["lastHeartbeatUtc"])
    if now <= expiry:
        return None, "LEASE_NOT_EXPIRED"
    if heartbeat > now:
        return None, "FUTURE_HEARTBEAT"
    if not fields["ownerAutomationId"] or not fields["ownerRunToken"]:
        return None, "OWNER_NOT_IDENTIFIABLE"
    if fields["ownerAutomationId"] != evidence.automation_id:
        return None, "OTHER_AUTOMATION"
    if not evidence.run_id or fields.get("ownerRunId") != evidence.run_id:
        return None, "RUN_ID_UNVERIFIED"
    if not fields["ownerRunToken"].startswith(
            evidence.automation_id + ":" + evidence.run_id + ":"):
        return None, "RUN_TOKEN_UNVERIFIED"
    if evidence.status not in ("completed", "failed") or not evidence.completed_at:
        return None, "OWNER_NOT_FINISHED"
    started = parse_time(evidence.started_at)
    completed = parse_time(evidence.completed_at)
    if not (started <= acquired <= completed <= now):
        return None, "RUN_TIMELINE_MISMATCH"
    if not re.fullmatch(r"[0-9a-f]{40}", main_head_sha):
        return None, "INVALID_MAIN_SHA"

    changes = {
        "state": "FREE",
        "ownerAutomationId": None,
        "ownerChatId": None,
        "ownerRunId": None,
        "ownerRunToken": None,
        "acquiredAtUtc": None,
        "lastHeartbeatUtc": None,
        "leaseExpiresAtUtc": None,
        "workingBranch": None,
        "task": "RECOVERED: verified ended automation run " + evidence.run_id,
        "lastReleasedAtUtc": utc_text(now),
        "lastReleasedCommit": main_head_sha,
    }
    updated = block
    for key, value in changes.items():
        if key not in fields:
            if key == "ownerRunId":
                return None, "RUN_ID_UNVERIFIED"
            return None, "MISSING_LOCK_FIELD"
        replacement = "null" if value is None else json.dumps(value)
        updated, count = re.subn(r"(?m)^" + re.escape(key) + r": .*?$",
                                 lambda _: key + ": " + replacement, updated)
        if count != 1:
            raise ValueError("duplicated or missing lock field: " + key)

    new_document = document[:start] + updated + document[end:]
    end_marker = "**Hier arbeitet jetzt ein Chat!**\n"
    if not new_document.endswith(end_marker):
        raise ValueError("unexpected active terminal marker")
    new_document = (new_document[:-len(end_marker)]
                    + "**Hier arbeitet kein Chat mehr.**\n")
    return new_document, "RECOVERABLE"
