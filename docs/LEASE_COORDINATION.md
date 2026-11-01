# Integration lease reliability

Four hourly integration automations share one exclusive lock in the final
YAML block of "Prüfe mich ob du dran bist und was du machen sollst und darfst.md"
on the GitHub main branch.

## Acquire FREE (own execution)

- Read current main HEAD, the complete lock file and its GitHub blob SHA.
- A running ChatGPT automation may **not** be able to retrieve its own run ID.
  Never require ownerRunId as a prerequisite for a new FREE acquisition.
- Use the known automation ID and a **fresh, cryptographically random**
  per-execution nonce to construct an unguessable, non-reused ownerRunToken.
  Record ownerRunId only when the actual current run ID is independently
  available; otherwise set it to null. Do not use the latest *previous* run ID.
- Write ACTIVE with a 15-minute lease, ownerAutomationId, ownerChatId, the
  optional ownerRunId, ownerRunToken, timestamps, task and workingBranch
  through the current file SHA (compare-and-swap). Use the pure
  `tools/acquire_integration_lease.py` helper to construct this change.
- Re-read main and confirm the exact token, ACTIVE and an unexpired lease
  **before** any other writes. On a conflict, stop instead of overwriting.
- While actively working, renew your **own** valid lease before expiration,
  with fresh SHA-CAS at least every 10 minutes; after work release immediately
  and verify FREE on main.

## Recovery of a foreign expired lease

- Expiration alone is NOT proof that the owner has finished.
- Use a fresh, trusted Automations.peek result, not a text inference from the
  lock or a GitHub CI run.
- `tools/recover_integration_lease.py` can propose release only when a
  stored ownerRunId exactly matches the completed/failed automation run and
  its automation ID, token and timeline also validate.
- If the prior owner's run ID was unavailable and recorded as null, there is
  **no automatic recovery**. Report a lock-blocking exception for manual,
  separately verified release. Never infer the run ID from a later run.
- Commit FREE only using the live file blob SHA; verify FREE afterward,
  then acquire the next separate owner token. Never rewrite another ACTIVE
  owner.

## CI trade-off

GitHub Actions cannot independently verify the lifetime of a ChatGPT
automation run. CI-triggered lease renewal has therefore been disabled.
The lock remains advisory rather than a server-enforced GitHub write policy.

Fail-closed recovery ensures safety but does not guarantee progress after a
crash. Reliability in actual scheduled automation runs still needs testing.
