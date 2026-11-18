# Betriebsmodus ab 05.11.2026: Codex ohne Lock-Verwaltung

**Verbindliche Nutzerentscheidung vom 05.11.2026:** Es wird nur noch Codex als Entwicklungsintegrator verwendet. Das frühere Drei-Lock-Protokoll, die Legacy-Sperre und die fünfminütige Lock-Recovery samt Sicherheitstest-Workflows sind abgeschafft.

## Wirkung

- `integration-lock.json`, `integration-lock-A.json`, `integration-lock-B.json`, `integration-merge-lock.json` sind keine operative Arbeitssteuerung mehr und wurden im Umstellungscommit gelöscht.
- `tools/integration_codex_lock.py`, `tools/integration_idle_recovery.py`, `tools/integration_parallel_lock_recovery.py` samt ausschließlich zugehörigen Tests sowie `.github/workflows/integration-idle-recovery.yml` und `.github/workflows/integration-idle-recovery-tests.yml` sind entfernt.
- Kein Lock-Check, -Acquire, -Heartbeat, -Release, keine Token-/CAS-Prüfung und keine automatische Recovery als Voraussetzung für Arbeit/Push/Merge. Historische Anweisungen in alten Commits oder Statusberichten sind nicht mehr gültig.
- A/B/III/IV bleiben pausiert; keine neue Parallelentwicklung und keine neue Recovery-Automation. Auch mehrere Codex-Chats sollen nicht gleichzeitig in dieselben Dateien schreiben.
- **Erhalten bleiben**: aktuelle Branch-/PR-/Main-Prüfung, Git-Konfliktvermeidung, PR-CI auf exaktem Head, kein Force-Push, echte Android-Gerätetests und sämtliche FoodStore-/Daten-/Privacy-/Abnahme-Gates.

## Übergang für eine zum Zeitpunkt der Umstellung bereits laufende Codex-Sitzung

Vor Übernahme dieses Commits ihre Arbeit/PRs sichern. Eine laufende Sitzung, die noch die alten Sperren erwartet, sollte den neuen `main`-Stand zuerst synchronisieren und die inzwischen obsoleten Lock-Befehle nicht mehr aufrufen. Ein Lösch-Commit darf **nicht** mitten in einer laufenden fremden Sitzung in main gemergt werden; zuerst einen sicheren Funktions-/CI-/Commit-Haltepunkt erreichen.

Verbindliche Arbeitsanweisungen: [AGENTS.md](../AGENTS.md) und [INTEGRATION_EXECUTION_PLAN.md](INTEGRATION_EXECUTION_PLAN.md).
