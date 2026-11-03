"""Offline regression tests for independent JSON integration lock recovery."""
from datetime import datetime, timedelta, timezone
import json
import unittest
from unittest.mock import Mock

from integration_idle_recovery import (
    ALLOWED_AUTOMATIONS, RecoveryBlocked, parse_lock, latest_activity,
    check_idle, execute, render_free_lock, KEY_ORDER, RELEASE_FIELDS,
)

NOW = datetime(2026, 11, 3, 22, 30, tzinfo=timezone.utc)
ID = sorted(ALLOWED_AUTOMATIONS)[0]


def sample(age=36, state="ACTIVE", branch="integration-ii-test"):
    ts = (NOW - timedelta(minutes=age)).isoformat().replace("+00:00", "Z")
    fields = {
        "schemaVersion": 1, "state": state,
        "ownerAutomationId": ID if state == "ACTIVE" else None,
        "ownerChatId": "Integration II" if state == "ACTIVE" else None,
        "ownerRunId": None,
        "ownerRunToken": ID + "-test" if state == "ACTIVE" else None,
        "acquiredAtUtc": ts if state == "ACTIVE" else None,
        "lastHeartbeatUtc": None,
        "leaseExpiresAtUtc": None,
        "task": "Integrate code",
        "workingBranch": branch if state == "ACTIVE" else None,
        "lastReleasedAtUtc": None, "lastReleasedCommit": None,
        "nextStep": "Implement code",
    }
    return json.dumps({key: fields[key] for key in KEY_ORDER}, indent=2) + "\n"


class FakeGithub:
    def __init__(self, text=None, branch_age=36, runs=None, prs=None):
        self.text = sample() if text is None else text
        self.sha = "old-sha"
        self.branch_age = branch_age
        self.run_items = [] if runs is None else runs
        self.pr_items = [] if prs is None else prs
        self.writes = []

    def lock(self):
        return self.sha, self.text

    def branch_head(self, name):
        return "head-sha", NOW - timedelta(minutes=self.branch_age)

    def pull_updates(self, name):
        return self.pr_items

    def runs(self, name):
        return self.run_items

    def release(self, sha, text):
        assert sha == self.sha
        self.writes.append(text)
        self.text, self.sha = text, "new-sha"


class RecoveryTests(unittest.TestCase):
    def test_canonical_free_template_round_trip(self):
        result = render_free_lock(parse_lock(sample()), now=NOW, last_commit="head-sha")
        values = parse_lock(result).values
        self.assertEqual(values["schemaVersion"], 1)
        self.assertEqual(values["state"], "FREE")
        self.assertEqual(tuple(values), KEY_ORDER)
        self.assertTrue(all(values[k] is None for k in RELEASE_FIELDS))
        self.assertEqual(values["lastReleasedCommit"], "head-sha")
        self.assertTrue(result.endswith("}\n"))
        self.assertNotIn(chr(96) * 3, result)

    def test_free_state_without_owner_is_valid(self):
        gh = FakeGithub(sample(state="FREE"))
        self.assertEqual(execute(gh, now=NOW), "SKIP: FREE")
        self.assertFalse(gh.writes)

    def test_exact_30_minute_boundary(self):
        for age, eligible in [(29, False), (30, True), (31, True)]:
            gh = FakeGithub(text=sample(age), branch_age=age)
            self.assertEqual(check_idle(gh, parse_lock(gh.text), now=NOW)[0], eligible)

    def test_recent_branch_commit_blocks(self):
        gh = FakeGithub(branch_age=2)
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_recent_pr_update_blocks(self):
        gh = FakeGithub(prs=[NOW - timedelta(minutes=1)])
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_ongoing_ci_blocks(self):
        gh = FakeGithub(runs=[{"status": "queued", "created_at": NOW.isoformat()}])
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_recent_completed_ci_blocks(self):
        gh = FakeGithub(runs=[{"status": "completed", "updated_at": NOW.isoformat()}])
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_idle_owner_releases_and_verifies(self):
        gh = FakeGithub()
        self.assertIn("RELEASED:", execute(gh, now=NOW))
        self.assertEqual(len(gh.writes), 1)
        self.assertEqual(parse_lock(gh.text).values["state"], "FREE")

    def test_damaged_nonidentity_punctuation_is_rebuilt(self):
        damaged = sample().replace('"task": "Integrate code",', '"task": "Integrate code"')
        with self.assertRaises(RecoveryBlocked):
            parse_lock(damaged)
        recovered = parse_lock(damaged, allow_repair=True)
        self.assertTrue(recovered.recovered)
        gh = FakeGithub(text=damaged)
        self.assertIn("RESTORED:", execute(gh, now=NOW))
        self.assertEqual(parse_lock(gh.text).values["state"], "FREE")

    def test_damaged_recent_owner_is_not_rebuilt(self):
        damaged = sample().replace('"task": "Integrate code",', '"task": "Integrate code"')
        gh = FakeGithub(text=damaged, branch_age=2)
        self.assertIn("SKIP: insufficient inactivity", execute(gh, now=NOW))
        self.assertFalse(gh.writes)

    def test_damaged_owner_without_token_blocks(self):
        damaged = sample().replace(
            '"ownerRunToken": "' + ID + '-test",', '"ownerRunToken":,'
        )
        gh = FakeGithub(text=damaged)
        with self.assertRaises(RecoveryBlocked):
            execute(gh, now=NOW)
        self.assertFalse(gh.writes)

    def test_damaged_duplicate_identity_blocks(self):
        damaged = sample().replace(
            '"task": "Integrate code",',
            '"ownerRunToken": "another-token",\n  "task": "Integrate code",'
        )
        gh = FakeGithub(text=damaged)
        with self.assertRaises(RecoveryBlocked):
            execute(gh, now=NOW)
        self.assertFalse(gh.writes)

    def test_damaged_free_state_does_not_acquire_or_reset(self):
        damaged = sample(state="FREE").replace('"task": "Integrate code",',
                                               '"task": "Integrate code"')
        gh = FakeGithub(text=damaged)
        with self.assertRaises(RecoveryBlocked):
            execute(gh, now=NOW)
        self.assertFalse(gh.writes)

    def test_json_duplicate_keys_block(self):
        duplicated = sample().replace('"task": "Integrate code",',
                                      '"task": "Integrate code",\n  "task": "Overwritten",')
        with self.assertRaises(RecoveryBlocked):
            parse_lock(duplicated)

    def test_free_with_owner_is_invalid(self):
        contaminated = json.loads(sample())
        contaminated["state"] = "FREE"
        with self.assertRaises(RecoveryBlocked):
            parse_lock(json.dumps(contaminated))

    def test_invalid_null_owner_token_blocks(self):
        obj = json.loads(sample())
        obj["ownerRunToken"] = None
        gh = FakeGithub(json.dumps(obj))
        self.assertIn("SKIP: owner", execute(gh, now=NOW))
        self.assertFalse(gh.writes)

    def test_unregistered_owner_blocks(self):
        obj = json.loads(sample())
        obj["ownerAutomationId"] = "different-agent"
        gh = FakeGithub(json.dumps(obj))
        self.assertIn("SKIP: owner", execute(gh, now=NOW))
        self.assertFalse(gh.writes)

    def test_owner_switch_between_reads_blocks(self):
        gh = FakeGithub()
        original = gh.lock
        calls = [0]
        def changed():
            calls[0] += 1
            if calls[0] == 2:
                gh.sha = "replaced-by-another-owner"
            return original()
        gh.lock = changed
        self.assertIn("SKIP: lock changed", execute(gh, now=NOW))
        self.assertFalse(gh.writes)

    def test_failed_branch_lookup_blocks(self):
        gh = FakeGithub()
        gh.branch_head = Mock(side_effect=RecoveryBlocked("cannot verify branch"))
        with self.assertRaises(RecoveryBlocked):
            execute(gh, now=NOW)
        self.assertFalse(gh.writes)

    def test_empty_or_unknown_lock_blocks(self):
        for invalid in ("", "[]", '{"state": "ACTIVE"}', "unrecognizable"):
            gh = FakeGithub(text=invalid)
            with self.assertRaises(RecoveryBlocked):
                execute(gh, now=NOW)
            self.assertFalse(gh.writes)


if __name__ == "__main__":
    unittest.main()
