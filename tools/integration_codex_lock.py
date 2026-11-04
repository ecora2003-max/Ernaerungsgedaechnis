#!/usr/bin/env python3
"""Exclusive Codex ownership of all three operational locks; Contents SHA CAS only.

Run tokens identify a session, not GitHub credentials. Never adopt an existing
session from a lock file. A resumed process must have its original session file
or wait for regular release/recovery and acquire a new session.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import uuid

import integration_idle_recovery as recovery

CODEX_ID = "codex-exclusive-ernaehrung-20261104"
# Acquire the merge gate first; release it first, as required by the handoff.
LOCK_PATHS = ("integration-merge-lock.json", "integration-lock-A.json", "integration-lock-B.json")


class GhContents:
    """Use the environment's configured gh authentication, never read credentials."""

    def api(self, method, path, data=None):
        args = ["gh", "api", "--method", method,
                f"repos/{recovery.REPOSITORY}/{path}"]
        if data is not None:
            args += ["--input", "-"]
        result = subprocess.run(args, input=json.dumps(data) if data else None,
                                text=True, capture_output=True, check=False)
        if result.returncode:
            raise recovery.RecoveryBlocked(f"GitHub {method} failed; no retry without a fresh read")
        return json.loads(result.stdout)

    def read(self, path):
        value = self.api("GET", f"contents/{path}?ref=main")
        if value.get("encoding") != "base64" or not value.get("sha"):
            raise recovery.RecoveryBlocked("incomplete contents response")
        return value["sha"], base64.b64decode(value["content"]).decode("utf-8")

    def write(self, path, sha, text):
        self.api("PUT", f"contents/{path}", {
            "branch": "main", "sha": sha,
            "message": "chore(lock): update exclusive Codex ownership",
            "content": base64.b64encode(text.encode()).decode(),
        })


def new_session():
    return {"session": "codex-" + uuid.uuid4().hex,
            "tokens": {path: CODEX_ID + "-" + uuid.uuid4().hex for path in LOCK_PATHS}}


def validate_session(session):
    if (set(session) != {"session", "tokens"}
            or not isinstance(session["session"], str) or not session["session"].strip()
            or set(session["tokens"]) != set(LOCK_PATHS)
            or len(set(session["tokens"].values())) != len(LOCK_PATHS)
            or any(not isinstance(t, str) or not t.startswith(CODEX_ID + "-")
                   or len(t) <= len(CODEX_ID) + 1 for t in session["tokens"].values())):
        raise recovery.RecoveryBlocked("invalid exclusive session")


def owned(values, session, path):
    return (values["state"] == "ACTIVE" and values["ownerAutomationId"] == CODEX_ID
            and values["ownerChatId"] == session["session"]
            and values["ownerRunToken"] == session["tokens"][path]
            and values["ownerRunId"] is None and values["leaseExpiresAtUtc"] is None
            and values["acquiredAtUtc"] is not None)


def snapshot(store, session, *, require_all=False):
    validate_session(session)
    result = {}
    for path in LOCK_PATHS:
        sha, text = store.read(path)
        values = recovery.parse_lock(text).values  # never repair while acquiring
        if not owned(values, session, path) and (require_all or values["state"] != "FREE"):
            raise recovery.RecoveryBlocked("foreign, lost or incomplete ownership: " + path)
        result[path] = sha, values
    return result


def replace(store, path, sha, values):
    text = json.dumps({k: values[k] for k in recovery.KEY_ORDER}, ensure_ascii=False, indent=2) + "\n"
    recovery.parse_lock(text)
    store.write(path, sha, text)
    new_sha, readback = store.read(path)
    if new_sha == sha or recovery.parse_lock(readback).values != values:
        raise recovery.RecoveryBlocked("independent CAS readback failed: " + path)


def acquire(store, session, *, branch, task, now):
    initial = snapshot(store, session)
    if any(v["state"] != "FREE" for _, v in initial.values()):
        raise recovery.RecoveryBlocked("acquisition requires three FREE locks")
    if not branch or branch == "main" or not task.strip():
        raise recovery.RecoveryBlocked("an existing work branch and task are required")
    # A racing A/B acquisition can only produce a partial acquisition. No code
    # writes are authorized until all three independent readbacks succeed.
    for path in LOCK_PATHS:
        sha, values = snapshot(store, session)[path]
        if values["state"] != "FREE":
            raise recovery.RecoveryBlocked("lock no longer FREE")
        values.update(state="ACTIVE", ownerAutomationId=CODEX_ID,
                      ownerChatId=session["session"], ownerRunToken=session["tokens"][path],
                      acquiredAtUtc=recovery.format_utc(now), lastHeartbeatUtc=None,
                      task=task, workingBranch=branch)
        replace(store, path, sha, values)
    snapshot(store, session, require_all=True)


def refresh(store, session, *, now, branch=None):
    snapshot(store, session, require_all=True)
    for path in LOCK_PATHS:
        sha, values = snapshot(store, session, require_all=True)[path]
        values["lastHeartbeatUtc"] = recovery.format_utc(now)
        if branch is not None:
            if not branch or branch == "main":
                raise recovery.RecoveryBlocked("work branch required")
            values["workingBranch"] = branch
        replace(store, path, sha, values)


def release(store, session, *, now, commit, next_step):
    # Release ONLY matching tokens, even after failed/partial acquisition. A
    # foreign ACTIVE on another path never authorizes modifying that path.
    validate_session(session)
    for path in LOCK_PATHS:
        # Inspect all three, including foreign locks, before each release write.
        locks = {p: store.read(p) for p in LOCK_PATHS}
        parsed = {p: recovery.parse_lock(t) for p, (_, t) in locks.items()}
        sha, _ = locks[path]
        lock = parsed[path]
        if not owned(lock.values, session, path):
            continue
        values = json.loads(recovery.render_free_lock(lock, now=now, last_commit=commit))
        values.update(task="Codex exclusive session released", nextStep=next_step)
        replace(store, path, sha, values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("acquire", "check", "refresh", "release"))
    parser.add_argument("--session-file", type=Path, required=True)
    parser.add_argument("--branch")
    parser.add_argument("--task", default="Complete the next functional Android milestone")
    parser.add_argument("--commit")
    parser.add_argument("--next-step", default="Continue the existing functional branch and exact-head tests")
    args = parser.parse_args()
    try:
        if args.action == "acquire":
            session = new_session()
            # Exclusive creation: never replace another process's session file.
            with args.session_file.open("x") as target:
                json.dump(session, target)
            args.session_file.chmod(0o600)
        else:
            session = json.loads(args.session_file.read_text())
        store, now = GhContents(), datetime.now(timezone.utc)
        if args.action == "acquire":
            acquire(store, session, branch=args.branch, task=args.task, now=now)
        elif args.action == "check":
            snapshot(store, session, require_all=True)
        elif args.action == "refresh":
            refresh(store, session, now=now, branch=args.branch)
        else:
            release(store, session, now=now, commit=args.commit, next_step=args.next_step)
        print("OK: " + args.action)
    except (recovery.RecoveryBlocked, OSError, ValueError, TypeError, KeyError) as exc:
        print("BLOCKED: " + str(exc))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
