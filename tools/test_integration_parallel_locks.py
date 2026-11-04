"""Regression suite for independent role locks and serialized main merge lock."""
from datetime import datetime, timedelta, timezone
import json
import unittest

import integration_idle_recovery as legacy
from integration_parallel_lock_recovery import (
    A_ID, B_ID, LOCK_SCOPES, recover_all, scoped_lock,
)

NOW = datetime(2026, 11, 4, 18, 0, tzinfo=timezone.utc)


def sample(name, *, age=40, state="ACTIVE", owner=None, branch="integration-demo"):
    if owner is None:
        owner = A_ID if name in ("A", "MERGE") else B_ID
    fields = {
        "schemaVersion": 1, "state": state,
        "ownerAutomationId": owner if state == "ACTIVE" else None,
        "ownerChatId": name + " run" if state == "ACTIVE" else None,
        "ownerRunId": None,
        "ownerRunToken": owner + "-test-" + name if state == "ACTIVE" else None,
        "acquiredAtUtc": (NOW - timedelta(minutes=age)).isoformat().replace("+00:00", "Z") if state == "ACTIVE" else None,
        "lastHeartbeatUtc": None, "leaseExpiresAtUtc": None,
        "task": name + " user feature",
        "workingBranch": branch if state == "ACTIVE" else None,
        "lastReleasedAtUtc": None, "lastReleasedCommit": None,
        "nextStep": name + " next step",
    }
    return json.dumps({k: fields[k] for k in legacy.KEY_ORDER}, indent=2) + "\n"


class MemoryGithub:
    def __init__(self, repo, token, *, store=None, branch_age=40, run_items=None):
        self.store = store if store is not None else {}
        self.branch_age = branch_age
        self.run_items = [] if run_items is None else run_items
        self.writes = []

    def lock(self):
        path = legacy.LOCK_PATH
        item = self.store[path]
        return item["sha"], item["text"]

    def branch_head(self, name):
        return "branch-head", NOW - timedelta(minutes=self.branch_age)

    def pull_updates(self, name):
        return []

    def runs(self, name):
        return self.run_items

    def release(self, sha, text):
        path = legacy.LOCK_PATH
        assert sha == self.store[path]["sha"]
        self.writes.append(path)
        self.store[path] = {"sha": self.store[path]["sha"] + "-new", "text": text}


def fake_factory(store, *, branch_age=40, runs=None):
    def make(repo, token):
        return MemoryGithub(repo, token, store=store, branch_age=branch_age, run_items=runs)
    return make


def prepared_store():
    entries = {path: {"sha": name + "-old-sha", "text": sample(name, state="FREE")}
               for name, (path, _) in LOCK_SCOPES.items()}
    entries["integration-lock.json"] = {"sha": "legacy", "text": sample("B", owner=B_ID)}
    return entries


class ParallelLockRecoveryTests(unittest.TestCase):
    def test_disjoint_owner_scope_and_explicit_merge_scope(self):
        self.assertEqual(LOCK_SCOPES["A"][1], frozenset((A_ID,)))
        self.assertEqual(LOCK_SCOPES["B"][1], frozenset((B_ID,)))
        self.assertEqual(LOCK_SCOPES["MERGE"][1], frozenset((A_ID, B_ID)))
        self.assertEqual(len({p for p, _ in LOCK_SCOPES.values()}), 3)

    def test_scope_does_not_change_default_legacy_state(self):
        before = legacy.LOCK_PATH, legacy.ALLOWED_AUTOMATIONS
        with scoped_lock("A"):
            self.assertEqual(legacy.LOCK_PATH, "integration-lock-A.json")
            self.assertEqual(legacy.ALLOWED_AUTOMATIONS, frozenset((A_ID,)))
        self.assertEqual((legacy.LOCK_PATH, legacy.ALLOWED_AUTOMATIONS), before)

    def test_unknown_scope_cannot_write(self):
        with self.assertRaises(legacy.RecoveryBlocked):
            with scoped_lock("bogus"):
                pass

    def test_only_stale_b_is_released_while_a_remains_active(self):
        store = prepared_store()
        a_path = LOCK_SCOPES["A"][0]
        b_path = LOCK_SCOPES["B"][0]
        store[a_path]["text"] = sample("A", age=10)
        store[b_path]["text"] = sample("B", age=40)
        messages, blocked = recover_all(legacy.REPOSITORY, "test", now=NOW, github_factory=fake_factory(store))
        self.assertFalse(blocked, messages)
        self.assertEqual(legacy.parse_lock(store[a_path]["text"]).values["state"], "ACTIVE")
        self.assertEqual(legacy.parse_lock(store[b_path]["text"]).values["state"], "FREE")
        self.assertEqual(store["integration-lock.json"]["sha"], "legacy")

    def test_a_does_not_recover_b_owned_a_lock(self):
        store = prepared_store()
        a_path = LOCK_SCOPES["A"][0]
        store[a_path]["text"] = sample("A", owner=B_ID)
        messages, blocked = recover_all(legacy.REPOSITORY, "test", now=NOW, github_factory=fake_factory(store))
        self.assertFalse(blocked, messages)
        self.assertTrue(any("A: SKIP: owner" in m for m in messages), messages)
        self.assertEqual(legacy.parse_lock(store[a_path]["text"]).values["state"], "ACTIVE")

    def test_merge_lock_can_recover_either_registered_owner(self):
        for owner in (A_ID, B_ID):
            with self.subTest(owner=owner):
                store = prepared_store()
                merge_path = LOCK_SCOPES["MERGE"][0]
                store[merge_path]["text"] = sample("MERGE", owner=owner)
                messages, blocked = recover_all(legacy.REPOSITORY, "test", now=NOW, github_factory=fake_factory(store))
                self.assertFalse(blocked, messages)
                self.assertEqual(legacy.parse_lock(store[merge_path]["text"]).values["state"], "FREE")
                self.assertTrue(any(m.startswith("MERGE: RELEASED") for m in messages))

    def test_broken_a_does_not_prevent_b_recovery(self):
        store = prepared_store()
        a_path = LOCK_SCOPES["A"][0]
        b_path = LOCK_SCOPES["B"][0]
        store[a_path]["text"] = "corrupt and unidentifiable"
        store[b_path]["text"] = sample("B", age=40)
        messages, blocked = recover_all(legacy.REPOSITORY, "test", now=NOW, github_factory=fake_factory(store))
        self.assertTrue(blocked)
        self.assertTrue(any("A: BLOCKED" in m for m in messages))
        self.assertEqual(store[a_path]["text"], "corrupt and unidentifiable")
        self.assertEqual(legacy.parse_lock(store[b_path]["text"]).values["state"], "FREE")

    def test_ongoing_b_ci_prevents_only_b_recovery(self):
        store = prepared_store()
        store[LOCK_SCOPES["A"][0]]["text"] = sample("A")
        store[LOCK_SCOPES["B"][0]]["text"] = sample("B")
        running = [{"status": "in_progress", "created_at": NOW.isoformat()}]
        messages, blocked = recover_all(legacy.REPOSITORY, "test", now=NOW,
                                        github_factory=fake_factory(store, runs=running))
        self.assertFalse(blocked)
        self.assertEqual(legacy.parse_lock(store[LOCK_SCOPES["B"][0]]["text"]).values["state"], "ACTIVE")
        self.assertEqual(legacy.parse_lock(store[LOCK_SCOPES["A"][0]]["text"]).values["state"], "ACTIVE")

    def test_lock_templates_have_identical_strict_schema(self):
        for name in LOCK_SCOPES:
            with self.subTest(name=name):
                self.assertEqual(tuple(json.loads(sample(name, state="FREE"))), legacy.KEY_ORDER)
                self.assertTrue(all(json.loads(sample(name, state="FREE"))[field] is None for field in legacy.RELEASE_FIELDS))

    def test_github_cas_rechecks_each_independent_path(self):
        store = prepared_store()
        for name in LOCK_SCOPES:
            path = LOCK_SCOPES[name][0]
            store[path]["text"] = sample(name)
        messages, blocked = recover_all(legacy.REPOSITORY, "test", now=NOW, github_factory=fake_factory(store))
        self.assertFalse(blocked, messages)
        self.assertEqual({legacy.parse_lock(store[LOCK_SCOPES[n][0]]["text"]).values["state"] for n in LOCK_SCOPES}, {"FREE"})
        self.assertEqual(store["integration-lock.json"]["sha"], "legacy")


if __name__ == "__main__":
    unittest.main()
