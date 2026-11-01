#!/usr/bin/env python3
"""Pure CAS-ready FREE->ACTIVE proposal; live GitHub update is caller's duty.

The ChatGPT Automations API does not guarantee that an executing worker can
read its own run ID. A unique per-execution token is sufficient to acquire
FREE; an ownerRunId is OPTIONAL and must never be guessed.
"""
import datetime as dt
import json
import re

from renew_integration_lease import parse_lock, utc_text

UTC = dt.timezone.utc
LEASE = dt.timedelta(minutes=15)


def propose_acquire(document: str, automation_id: str, chat_id: str,
                    nonce: str, now: dt.datetime, task: str,
                    run_id: str | None = None) -> tuple[str | None, str, str | None]:
    """Return (candidate, reason, unique token), with no network/write effects.

    Callers must use a fresh nonce for EVERY attempt (for example, uuid4().hex)
    and CAS-update main using the freshly read blob SHA. After CAS, verify the
    exact recorded owner token and an unexpired ACTIVE lease on live main.
    """
    start, end, block, fields = parse_lock(document)
    if (fields["state"] != "FREE" or
            not document.endswith("**Hier arbeitet kein Chat mehr.**\n")):
        return None, "NOT_FREE", None
    for key in ("ownerAutomationId", "ownerChatId", "ownerRunToken",
                "ownerRunId", "leaseExpiresAtUtc", "workingBranch"):
        if key not in fields or fields[key] is not None:
            return None, "INCONSISTENT_FREE", None
    if not re.fullmatch(r"[A-Za-z0-9_-]{6,128}", automation_id):
        return None, "INVALID_AUTOMATION", None
    if not chat_id or not task or len(chat_id) > 300 or len(task) > 300:
        return None, "INVALID_CONTEXT", None
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", nonce):
        return None, "INVALID_NONCE", None
    if run_id is not None and not re.fullmatch(r"[A-Za-z0-9_-]{6,128}", run_id):
        return None, "INVALID_RUN_ID", None
    if now.tzinfo is None:
        return None, "NAIVE_TIMESTAMP", None
    now = now.astimezone(UTC)
    token = f"{automation_id}:{run_id if run_id is not None else 'self'}:{nonce}"
    changes = {
        "state": "ACTIVE",
        "ownerAutomationId": automation_id,
        "ownerChatId": chat_id,
        "ownerRunId": run_id,
        "ownerRunToken": token,
        "acquiredAtUtc": utc_text(now),
        "lastHeartbeatUtc": utc_text(now),
        "leaseExpiresAtUtc": utc_text(now + LEASE),
        "task": task,
        "workingBranch": "pending",
    }
    updated = block
    for key, value in changes.items():
        if key not in fields:
            return None, "MISSING_LOCK_FIELD", None
        encoded = "null" if value is None else json.dumps(value)
        updated, count = re.subn(r"(?m)^" + re.escape(key) + r": .*?$",
                                 lambda _: key + ": " + encoded, updated)
        if count != 1:
            raise ValueError("ambiguous lock field: " + key)
    candidate = document[:start] + updated + document[end:]
    terminal = "**Hier arbeitet kein Chat mehr.**\n"
    candidate = candidate[:-len(terminal)] + "**Hier arbeitet jetzt ein Chat!**\n"
    return candidate, "ACQUIRE_CANDIDATE", token
