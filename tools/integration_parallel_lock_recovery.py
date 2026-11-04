#!/usr/bin/env python3
"""Conservative, sequential recovery for independent A/B and shared merge JSON locks.

Reuses the independently tested legacy JSON parser, activity proof and GitHub CAS.
The legacy integration-lock.json is retired and is never touched by this runner.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import os
import sys
from typing import Callable, Iterator

import integration_idle_recovery as recovery
from integration_codex_lock import CODEX_ID

A_ID = "6ae64ea269448191ac8f0b8a5fd206cb"
B_ID = "6ae659b2cf2081918f3414dce24a090e"

# Codex acquisition requires all three locks. Legacy A/B owners remain eligible
# for conservative recovery only; their automations must remain paused.
LOCK_SCOPES = {
    "A": ("integration-lock-A.json", frozenset((A_ID, CODEX_ID))),
    "B": ("integration-lock-B.json", frozenset((B_ID, CODEX_ID))),
    "MERGE": ("integration-merge-lock.json", frozenset((A_ID, B_ID, CODEX_ID))),
}


@contextmanager
def scoped_lock(name: str) -> Iterator[None]:
    """Scope the existing synchronous recovery functions to exactly one file.

    Never use concurrently or in threads. The runner processes files in order
    and restores globals even if one GitHub call fails.
    """
    if name not in LOCK_SCOPES:
        raise recovery.RecoveryBlocked("unknown lock scope")
    previous_path = recovery.LOCK_PATH
    previous_owners = recovery.ALLOWED_AUTOMATIONS
    path, owners = LOCK_SCOPES[name]
    recovery.LOCK_PATH = path
    recovery.ALLOWED_AUTOMATIONS = owners
    try:
        yield
    finally:
        recovery.LOCK_PATH = previous_path
        recovery.ALLOWED_AUTOMATIONS = previous_owners


def recover_all(
    repo: str,
    token: str,
    *,
    now: datetime,
    github_factory: Callable[[str, str], object] = recovery.Github,
) -> tuple[list[str], bool]:
    """A broken/unverifiable lock must not cause changes to that same file.

    Continue checking the other independent locks; report a failing exit code
    if any scope is blocked, rather than disguising a partial recovery.
    """
    messages = []
    blocked = False
    for name in LOCK_SCOPES:
        try:
            with scoped_lock(name):
                result = recovery.execute(github_factory(repo, token), now=now)
            messages.append(f"{name}: {result}")
        except recovery.RecoveryBlocked as exc:
            messages.append(f"{name}: BLOCKED: {exc}")
            blocked = True
    return messages, blocked


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args()
    if (
        args.repo != recovery.REPOSITORY
        or os.environ.get("GITHUB_REF", "refs/heads/main") != "refs/heads/main"
    ):
        print("BLOCKED: wrong repo or ref")
        return 1
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        print("BLOCKED: GH_TOKEN missing")
        return 1
    messages, blocked = recover_all(
        args.repo, token, now=datetime.now(timezone.utc)
    )
    for message in messages:
        print(message)
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main())
