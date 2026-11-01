"""Offline regression tests for the 35-minute integration lock recovery."""
from datetime import datetime, timedelta, timezone
import unittest
from unittest.mock import Mock

from integration_idle_recovery import (
    ALLOWED_AUTOMATIONS, RecoveryBlocked, parse_lock, latest_activity,
    check_idle, execute, render_free_lock, FREE_MARKER, ACTIVE_MARKER,
)

NOW = datetime(2026, 11, 1, 22, 30, tzinfo=timezone.utc)
ID = sorted(ALLOWED_AUTOMATIONS)[0]
TEMPLATE = '''# Info: an earlier example state: "ACTIVE" must never be edited
## 5. EINZIGER AKTUELLER SPERRSTATUS – am Ende der Datei belassen

```yaml
state: "ACTIVE"
ownerAutomationId: "%s"
ownerChatId: "Integration II"
ownerRunId: null
ownerRunToken: "%s-test"
acquiredAtUtc: "%s"
lastHeartbeatUtc: null
leaseExpiresAtUtc: null
task: "Integrate code"
workingBranch: "integration-ii-test"
lastReleasedAtUtc: null
lastReleasedCommit: null
nextStep: "Implement code"
```

**Hier arbeitet jetzt ein Chat!**
'''

def sample(age=36):
    ts = (NOW - timedelta(minutes=age)).isoformat().replace("+00:00", "Z")
    return TEMPLATE % (ID, ID, ts)

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
    def test_parse_preserves_prefix_and_round_trip(self):
        locked = parse_lock(sample())
        result = render_free_lock(locked, now=NOW, last_commit="head-sha")
        values = parse_lock(result).values
        self.assertTrue(result.startswith('# Info: an earlier example state: "ACTIVE"'))
        self.assertEqual(values["state"], "FREE")
        self.assertIsNone(values["ownerRunToken"])
        self.assertIsNone(values["workingBranch"])
        self.assertTrue(result.endswith(FREE_MARKER + "\n"))

    def test_exact_35_minute_boundary(self):
        for age, eligible in [(34, False), (35, True), (36, True)]:
            gh = FakeGithub(text=sample(age), branch_age=age)
            self.assertEqual(check_idle(gh, parse_lock(gh.text), now=NOW)[0], eligible)

    def test_recent_branch_commit_blocks(self):
        gh = FakeGithub(branch_age=2)
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_recent_pr_update_blocks(self):
        gh = FakeGithub(prs=[NOW - timedelta(minutes=1)])
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_ongoing_ci_blocks(self):
        gh = FakeGithub(runs=[{
            "status": "in_progress",
            "created_at": (NOW - timedelta(minutes=40)).isoformat()
        }])
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_recent_completed_ci_blocks(self):
        gh = FakeGithub(runs=[{"status": "completed", "updated_at": NOW.isoformat()}])
        self.assertFalse(check_idle(gh, parse_lock(gh.text), now=NOW)[0])

    def test_idle_owner_releases_and_verifies(self):
        gh = FakeGithub()
        self.assertIn("RELEASED:", execute(gh, now=NOW))
        self.assertEqual(len(gh.writes), 1)
        self.assertEqual(parse_lock(gh.text).values["state"], "FREE")

    def test_null_owner_token_blocks(self):
        gh = FakeGithub(text=sample().replace(f'"{ID}-test"', "null"))
        self.assertIn("SKIP: owner", execute(gh, now=NOW))
        self.assertFalse(gh.writes)

    def test_unregistered_owner_blocks(self):
        gh = FakeGithub(text=sample().replace(
            f'ownerAutomationId: "{ID}"', 'ownerAutomationId: "different-agent"'))
        self.assertIn("SKIP: owner", execute(gh, now=NOW))
        self.assertFalse(gh.writes)

    def test_mismatched_marker_blocks(self):
        gh = FakeGithub(text=sample().replace(ACTIVE_MARKER, FREE_MARKER))
        with self.assertRaises(RecoveryBlocked):
            execute(gh, now=NOW)
        self.assertFalse(gh.writes)

    def test_bad_yaml_blocks(self):
        with self.assertRaises(RecoveryBlocked):
            parse_lock(sample().replace('state: "ACTIVE"\nowner',
                                        'state: !!python/name:evil\nowner'))

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

    def test_free_noop(self):
        free = render_free_lock(parse_lock(sample()), now=NOW, last_commit="head")
        gh = FakeGithub(text=free)
        self.assertEqual(execute(gh, now=NOW), "SKIP: FREE")
        self.assertFalse(gh.writes)

if __name__ == "__main__":
    unittest.main()
