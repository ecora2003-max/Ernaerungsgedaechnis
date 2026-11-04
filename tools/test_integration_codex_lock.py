"""Exclusive acquisition, lost ownership, CAS races and manual release safety."""
from copy import deepcopy
from datetime import timedelta
import json
import unittest

import integration_codex_lock as codex
import integration_idle_recovery as recovery
from test_integration_parallel_locks import NOW, sample, prepared_store, fake_factory
from integration_parallel_lock_recovery import LOCK_SCOPES, recover_all


class Store:
    def __init__(self):
        self.entries = prepared_store()
        self.writes = []
        self.race = None
        self.bad_readback = False

    def read(self, path):
        value = self.entries[path]
        return value["sha"], value["text"]

    def write(self, path, sha, text):
        if self.race:
            race, self.race = self.race, None
            race(self, path)
        if self.entries[path]["sha"] != sha:
            raise recovery.RecoveryBlocked("CAS rejected")
        self.writes.append(path)
        self.entries[path] = {"sha": sha + "-next", "text": text if not self.bad_readback else sample("B")}


class CodexLockTests(unittest.TestCase):
    def setUp(self):
        self.store, self.session = Store(), codex.new_session()

    def acquire(self):
        codex.acquire(self.store, self.session, branch="codex-feature", task="A1/B1", now=NOW)

    def test_all_locks_required_and_legacy_file_untouched(self):
        legacy = deepcopy(self.store.entries["integration-lock.json"])
        self.acquire()
        values = codex.snapshot(self.store, self.session, require_all=True)
        self.assertEqual(self.store.writes, list(codex.LOCK_PATHS))
        self.assertEqual({v["ownerAutomationId"] for _, v in values.values()}, {codex.CODEX_ID})
        self.assertEqual(len({v["ownerRunToken"] for _, v in values.values()}), 3)
        self.assertEqual(self.store.entries["integration-lock.json"], legacy)

    def test_any_foreign_active_blocks_all_acquisition_writes(self):
        for name, (path, _) in LOCK_SCOPES.items():
            with self.subTest(path=path):
                self.store = Store()
                self.store.entries[path]["text"] = sample(name)
                with self.assertRaises(recovery.RecoveryBlocked):
                    self.acquire()
                self.assertEqual(self.store.writes, [])

    def test_same_owner_new_session_cannot_adopt_or_release_active(self):
        self.acquire()
        other = codex.new_session()
        before = deepcopy(self.store.entries)
        for action in (lambda: codex.snapshot(self.store, other, require_all=True),
                       lambda: codex.acquire(self.store, other, branch="other", task="other", now=NOW)):
            with self.assertRaises(recovery.RecoveryBlocked):
                action()
        codex.release(self.store, other, now=NOW, commit="abc", next_step="later")
        self.assertEqual(self.store.entries, before)

    def test_lost_role_token_blocks_refresh_and_code_write_check(self):
        self.acquire()
        path = codex.LOCK_PATHS[-1]
        values = json.loads(self.store.entries[path]["text"])
        values["ownerRunToken"] += "-replacement"
        self.store.entries[path]["text"] = json.dumps(values)
        before = len(self.store.writes)
        with self.assertRaises(recovery.RecoveryBlocked):
            codex.refresh(self.store, self.session, now=NOW)
        self.assertEqual(len(self.store.writes), before)

    def test_blob_cas_rejects_competing_acquisition_without_overwrite(self):
        def steal(store, path):
            store.entries[path] = {"sha": "racing-sha", "text": sample("MERGE")}
        self.store.race = steal
        with self.assertRaises(recovery.RecoveryBlocked):
            self.acquire()
        self.assertEqual(self.store.writes, [])
        self.assertEqual(self.store.entries[codex.LOCK_PATHS[0]]["sha"], "racing-sha")

    def test_independent_readback_required(self):
        self.store.bad_readback = True
        with self.assertRaises(recovery.RecoveryBlocked):
            self.acquire()
        self.assertEqual(len(self.store.writes), 1)

    def test_partial_acquisition_release_preserves_foreign_owner(self):
        def take_b(store, path):
            b = LOCK_SCOPES["B"][0]
            store.entries[b] = {"sha": "foreign", "text": sample("B")}
        self.store.race = take_b
        with self.assertRaises(recovery.RecoveryBlocked):
            self.acquire()
        codex.release(self.store, self.session, now=NOW, commit="abc", next_step="retry")
        self.assertEqual(json.loads(self.store.entries[codex.LOCK_PATHS[0]]["text"])["state"], "FREE")
        self.assertEqual(self.store.entries[LOCK_SCOPES["B"][0]]["sha"], "foreign")

    def test_release_order_all_eight_null_fields_and_idempotency(self):
        self.acquire()
        self.store.writes.clear()
        codex.release(self.store, self.session, now=NOW, commit="abc", next_step="A1 next")
        self.assertEqual(self.store.writes, list(codex.LOCK_PATHS))
        for path in codex.LOCK_PATHS:
            values = recovery.parse_lock(self.store.entries[path]["text"]).values
            self.assertEqual(values["state"], "FREE")
            self.assertTrue(all(values[f] is None for f in recovery.RELEASE_FIELDS))
            self.assertEqual((values["lastReleasedCommit"], values["nextStep"]), ("abc", "A1 next"))
        codex.release(self.store, self.session, now=NOW, commit="different", next_step="unchanged")
        self.assertEqual(len(self.store.writes), 3)

    def test_strict_json_no_repair_during_acquisition(self):
        for text in ('{"state":"FREE","state":"ACTIVE"}', '{"schemaVersion":1,}'):
            self.store = Store()
            self.store.entries[codex.LOCK_PATHS[1]]["text"] = text
            with self.assertRaises(recovery.RecoveryBlocked):
                self.acquire()
            self.assertEqual(self.store.writes, [])

    def test_codex_watchdog_requires_inactivity_and_honors_refresh(self):
        self.acquire()
        # An active local session refreshes before 30m; recovery must preserve it.
        codex.refresh(self.store, self.session, now=NOW + timedelta(minutes=25), branch="existing-pr")
        messages, blocked = recover_all(recovery.REPOSITORY, "test", now=NOW + timedelta(minutes=40),
                                       github_factory=fake_factory(self.store.entries))
        self.assertFalse(blocked, messages)
        codex.snapshot(self.store, self.session, require_all=True)
        # Only a genuinely stale session becomes recoverable (independently per path).
        messages, blocked = recover_all(recovery.REPOSITORY, "test", now=NOW + timedelta(minutes=56),
                                       github_factory=fake_factory(self.store.entries))
        self.assertFalse(blocked, messages)
        self.assertTrue(all(json.loads(self.store.entries[p]["text"])["state"] == "FREE" for p in codex.LOCK_PATHS))

    def test_session_tokens_cannot_be_shared_between_locks(self):
        self.session["tokens"] = dict.fromkeys(codex.LOCK_PATHS, codex.CODEX_ID + "-same")
        with self.assertRaises(recovery.RecoveryBlocked):
            self.acquire()
        self.assertEqual(self.store.writes, [])


if __name__ == "__main__":
    unittest.main()
