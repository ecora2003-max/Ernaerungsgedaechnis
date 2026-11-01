# Integration lease reliability

The four hourly integration automations share one lock in the final YAML block of
"Prüfe mich ob du dran bist und was du machen sollst und darfst.md" on main.

## Owner identity

- Before acquiring FREE, obtain the current run's automation ID and exact run ID
  from Automations.peek. Store both as ownerAutomationId and ownerRunId.
- Set ownerRunToken to automation-id:run-id:unique-nonce.
- If a run ID cannot be verified, do not acquire a writing lease.
- A successful compare-and-swap file update is not enough: read main again,
  verify the exact owner token, and only then start writing.
- Renew the lease explicitly at least every 10 minutes while working; use a
  fresh file SHA and do not renew a lease already expired.
- After work, release on main immediately and verify FREE.

## Ended-run recovery

- At ACTIVE + expired lease, fetch the owner automation's latest run from
  Automations.peek. Check both the exact automation ID and the exact run ID
  recorded in the lock. A matching completed or failed run is required.
- Use tools/recover_integration_lease.py propose_release for the pure
  validation of the supplied trusted run evidence. It cannot fetch live runs.
- Commit the proposed FREE document using GitHub file-SHA compare-and-swap;
  on any mismatch stop and re-read the owner. Never delete a fresh lease.
- If the latest run is in_progress, missing, outdated, or inconsistent, stop
  without writing. Do not treat an expired time alone as permission.
- Verify FREE from main. Only then may a new run acquire its own lease.

## CI and safety trade-off

GitHub Actions cannot independently read ChatGPT automation-run state.
Automatic workflow_run-triggered lease writes are disabled; matching CI branch
names do not prove an owner is still alive. Owner-only explicit heartbeats
remain required. A stale lock cannot be automatically cleared if no trusted
run-state evidence is accessible; safety takes priority over liveness.

The recovery Python module proposes a document only. It never writes to
GitHub. Automated worker code must supply verifiable evidence and use the
GitHub contents API with a fresh expected blob SHA.
