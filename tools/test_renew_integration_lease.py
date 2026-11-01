"""Pure regression tests: free and foreign leases never renew."""
import datetime as dt
import importlib.util
from pathlib import Path
import unittest

module_path = Path(__file__).with_name("renew_integration_lease.py")
spec = importlib.util.spec_from_file_location("lease", module_path)
lease = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lease)

MARK = chr(96) * 3
ACTIVE = """# Rules

""" + MARK + """yaml
state: ACTIVE
ownerAutomationId: "automation-a"
ownerChatId: "chat-a"
ownerRunToken: "automation-a:unique-run-1"
acquiredAtUtc: "2026-11-01T07:00:00Z"
lastHeartbeatUtc: "2026-11-01T07:00:00Z"
leaseExpiresAtUtc: "2026-11-01T07:30:00Z"
task: "Work"
workingBranch: "codex/unique-run-1"
lastReleasedAtUtc: null
lastReleasedCommit: null
nextStep: "Next"
""" + MARK + """

**Hier arbeitet jetzt ein Chat!**
"""

class TestLease(unittest.TestCase):
    def check(self, document=ACTIVE, branch="codex/unique-run-1",
              run_created="2026-11-01T07:01:00Z", minute=20):
        now = dt.datetime(2026, 11, 1, 7, minute, tzinfo=dt.timezone.utc)
        return lease.propose(document, branch, run_created, now)

    def test_matching_push_extends_to_now_plus_15(self):
        changed, status = self.check()
        self.assertEqual(status, "RENEWED")
        self.assertIn('leaseExpiresAtUtc: "2026-11-01T07:35:00.000Z"', changed)
        self.assertIn('ownerRunToken: "automation-a:unique-run-1"', changed)

    def test_free_never_reactivated(self):
        free = ACTIVE.replace("state: ACTIVE", "state: FREE").replace(
            "**Hier arbeitet jetzt ein Chat!**", "**Hier arbeitet kein Chat mehr.**")
        self.assertEqual(self.check(free)[1], "FREE_OR_INACTIVE")

    def test_other_branch_rejected(self):
        self.assertEqual(self.check(branch="codex/other-run")[1], "OTHER_BRANCH")

    def test_expired_rejected(self):
        self.assertEqual(self.check(minute=31)[1], "EXPIRED_OR_INVALID_LEASE")

    def test_old_run_started_before_lease_rejected(self):
        self.assertEqual(self.check(run_created="2026-11-01T06:59:00Z")[1], "OLD_CI_RUN")

    def test_existing_longer_expiry_preserved(self):
        changed, status = self.check(minute=5)
        self.assertEqual(status, "RENEWED")
        self.assertIn('leaseExpiresAtUtc: "2026-11-01T07:30:00.000Z"', changed)

    def test_pending_branch_rejected(self):
        status = ACTIVE.replace('workingBranch: "codex/unique-run-1"', 'workingBranch: "pending"')
        self.assertEqual(self.check(status)[1], "OTHER_BRANCH")

    def test_new_owner_and_unique_branch_blocks_old_event(self):
        new = ACTIVE.replace('ownerRunToken: "automation-a:unique-run-1"', 'ownerRunToken: "automation-b:unique-run-2"')
        new = new.replace('workingBranch: "codex/unique-run-1"', 'workingBranch: "codex/unique-run-2"')
        self.assertEqual(self.check(new)[1], "OTHER_BRANCH")

    def test_broken_status_fails_closed(self):
        with self.assertRaises(ValueError):
            self.check(ACTIVE.replace("**Hier arbeitet jetzt ein Chat!**", "**UNKNOWN**"))
