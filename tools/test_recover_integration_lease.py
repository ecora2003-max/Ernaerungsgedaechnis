import unittest

class RecoveryTests(unittest.TestCase):
    def test_import(self):
        import recover_integration_lease
        self.assertIsNotNone(recover_integration_lease)

import datetime as dt
from dataclasses import replace
from recover_integration_lease import RunEvidence, propose_release

NOW = dt.datetime(2026, 11, 1, 20, 1, tzinfo=dt.timezone.utc)
FENCE = chr(96) * 3
DOC = ("Instructions\n\n" + FENCE + """yaml
state: "ACTIVE"
ownerAutomationId: "a"
ownerChatId: "c"
ownerRunId: "r"
ownerRunToken: "a:r:unique"
acquiredAtUtc: "2026-11-01T19:19:00Z"
lastHeartbeatUtc: "2026-11-01T19:47:00Z"
leaseExpiresAtUtc: "2026-11-01T19:57:00Z"
task: "work"
workingBranch: "branch"
lastReleasedAtUtc: null
lastReleasedCommit: null
nextStep: "continue"
""" + FENCE + "\n\n**Hier arbeitet jetzt ein Chat!**\n")
PROOF = RunEvidence("a", "r", "completed", "2026-11-01T19:18:00Z",
                    "2026-11-01T19:48:00Z")


class RecoverySafetyTests(unittest.TestCase):
    def test_exact_completed_run(self):
        doc, status = propose_release(DOC, PROOF, NOW, "a" * 40)
        self.assertEqual(status, "RECOVERABLE")
        self.assertIn('state: "FREE"', doc)
        self.assertIn('ownerRunId: null', doc)

    def test_in_progress_rejected(self):
        proof = replace(PROOF, status="in_progress", completed_at=None)
        self.assertEqual(propose_release(DOC, proof, NOW, "a" * 40)[1],
                         "OWNER_NOT_FINISHED")

    def test_other_run_rejected(self):
        proof = replace(PROOF, run_id="another")
        self.assertEqual(propose_release(DOC, proof, NOW, "a" * 40)[1],
                         "RUN_ID_UNVERIFIED")
