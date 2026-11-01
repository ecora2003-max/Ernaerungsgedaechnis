"""FREE acquisition works even if running automation ID cannot be read."""
import datetime as dt
import unittest

from acquire_integration_lease import propose_acquire

UTC = dt.timezone.utc
T = dt.datetime(2026, 11, 1, 20, 25, tzinfo=UTC)
MARK = chr(96) * 3
FREE = ("""# Other documentation remains untouched

""" + MARK + """yaml
state: "FREE"
ownerAutomationId: null
ownerChatId: null
ownerRunId: null
ownerRunToken: null
acquiredAtUtc: null
lastHeartbeatUtc: null
leaseExpiresAtUtc: null
task: "previous"
workingBranch: null
lastReleasedAtUtc: "2026-11-01T20:20:00Z"
lastReleasedCommit: "0123456789abcdef0123456789abcdef01234567"
nextStep: "continue"
""" + MARK + """

**Hier arbeitet kein Chat mehr.**
""")
N1 = "0123456789abcdef0123456789abcdef"
N2 = "fedcba9876543210fedcba9876543210"


def acquire(doc=FREE, nonce=N1, run_id=None):
    return propose_acquire(doc, "automation-ii", "integration-ii", nonce, T,
                           "Continue main app integration", run_id=run_id)


class AcquireSafetyTests(unittest.TestCase):
    def test_free_acquisition_without_run_id(self):
        candidate, result, token = acquire()
        self.assertEqual(result, "ACQUIRE_CANDIDATE")
        self.assertEqual(token, "automation-ii:self:" + N1)
        self.assertIn("ownerRunId: null", candidate)
        self.assertIn("state: \"ACTIVE\"", candidate)
        self.assertIn("leaseExpiresAtUtc: \"2026-11-01T20:40:00.000Z\"", candidate)
        self.assertTrue(candidate.endswith("**Hier arbeitet jetzt ein Chat!**\n"))
        self.assertTrue(candidate.startswith("# Other documentation remains untouched"))

    def test_free_acquisition_with_verified_run_id(self):
        candidate, result, token = acquire(run_id="run-12345")
        self.assertEqual(result, "ACQUIRE_CANDIDATE")
        self.assertEqual(token, "automation-ii:run-12345:" + N1)
        self.assertIn('ownerRunId: "run-12345"', candidate)

    def test_distinct_nonce_produces_distinct_token(self):
        self.assertNotEqual(acquire(nonce=N1)[2], acquire(nonce=N2)[2])

    def test_existing_active_owner_rejected(self):
        active = FREE.replace('state: "FREE"', 'state: "ACTIVE"').replace(
            "**Hier arbeitet kein Chat mehr.**",
            "**Hier arbeitet jetzt ein Chat!**")
        self.assertEqual(acquire(active)[1], "NOT_FREE")

    def test_inconsistent_free_owner_rejected(self):
        corrupt = FREE.replace("ownerRunId: null", 'ownerRunId: "old-run"')
        self.assertEqual(acquire(corrupt)[1], "INCONSISTENT_FREE")

    def test_invalid_nonce_rejected(self):
        self.assertEqual(acquire(nonce="same")[1], "INVALID_NONCE")

    def test_invalid_timestamp_rejected(self):
        invalid = propose_acquire(FREE, "automation-ii", "integration-ii", N1,
                                  dt.datetime(2026, 11, 1, 20, 25), "Work")
        self.assertEqual(invalid[1], "NAIVE_TIMESTAMP")

    def test_cannot_acquire_twice(self):
        candidate, _, _ = acquire()
        self.assertEqual(acquire(candidate)[1], "NOT_FREE")
