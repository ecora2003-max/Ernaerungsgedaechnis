# Exklusiver Codex-Einzelentwicklermodus und konservative Recovery

Stand: 04.11.2026. Der Nutzer hat die Übernahme ausdrücklich autorisiert. Alle drei Sperren waren beim Cutover regulär FREE; die eigene Codex-Reservierung erfolgte mit frischen Datei-Blob-SHAs/CAS und unabhängigen Rücklesungen. Keine fremde ACTIVE-Sperre wurde verändert. Die früheren A/B-Zeitpläne sind außer Kraft; A/B/III/IV bleiben pausiert. Keine selbstständige Reaktivierung.

## Besitzer und operative Dateien

Einziger neuer Entwicklungsbesitzer: **`codex-exclusive-ernaehrung-20261104`**. Das historische Feld `ownerAutomationId` trägt diese eigene Codex-Kennung; sie ist ausdrücklich keine A-/B-Automatisierungs-ID. `ownerChatId` identifiziert die konkrete Session, jede Datei besitzt ihren eigenen neuen `ownerRunToken` mit Besitzerpräfix.

- `integration-merge-lock.json`: zuerst erwerben, zuerst freigeben.
- `integration-lock-A.json` und `integration-lock-B.json`: beide zusätzlich erforderlich, unabhängig vom bearbeiteten Fachgebiet.
- `integration-lock.json`: inaktiv, niemals erwerben oder durch den Wächter verändern.

Alle drei behalten schemaVersion 1 mit exakt den bisherigen 14 Feldern. FREE verlangt alle acht Owner-/Run-/Token-/Erwerbs-/Heartbeat-/Lease-/Branchfelder null. ACTIVE verlangt eigene Kennung, ursprüngliche Session, getrennte Tokens und Erwerbszeit; ownerRunId und leaseExpiresAtUtc bleiben null. Heartbeat darf im Einzelentwicklermodus per CAS aktualisiert werden, damit nachweisbar aktive lokale Entwicklung nicht als Inaktivität behandelt wird.

## Erwerb und Schreibkontrolle

`tools/integration_codex_lock.py` verwendet die konfigurierte gh-Authentifizierung, liest keine Geheimnisse und schreibt ausschließlich die drei festen Dateien im Hauptrepository auf main. Sessiondateien bleiben lokal außerhalb des Repositorys.

```sh
python3 tools/integration_codex_lock.py acquire --session-file /tmp/eg-session.json --branch EXISTIERENDER_BRANCH --task FUNKTIONSZIEL
python3 tools/integration_codex_lock.py check --session-file /tmp/eg-session.json
python3 tools/integration_codex_lock.py refresh --session-file /tmp/eg-session.json --branch EXISTIERENDER_BRANCH
python3 tools/integration_codex_lock.py release --session-file /tmp/eg-session.json --commit CODE_SHA --next-step KONKRETE_FORTSETZUNG
```

Vor **jedem Schreibzugriff** alle drei Dateien lesen und eigene ursprüngliche Tokens prüfen. Erwerb setzt drei gültige FREE-Dateien voraus, verwendet frischen Datei-Blob-SHA/CAS je Datei und kontrolliert unmittelbar das vollständige zurückgelesene JSON. Eine konkurrierende Übernahme scheitert spätestens am CAS oder am abschließenden Dreifachcheck. Eine Teilübernahme berechtigt zu keiner Entwicklung; ihre eigenen Tokens mit release freigeben, fremde Tokens unangetastet lassen. Kein JSON-Regex-Schreiben, kein Tokenkopieren, keine Reparatur bei Erwerb.

Auch ein fremder Run mit derselben Codex-Besitzerkennung ist fremd. Neue Sessiondateien werden exklusiv angelegt und überschreiben keine vorhandene Datei. Wiederaufnahme nur mit Originalsessiondatei und drei noch gültigen Tokens; sonst reguläre Freigabe/Recovery abwarten und neu erwerben. Heartbeat vor 30 Minuten ohne sonstige belegte GitHub-Aktivität auffrischen. Geht ein Token verloren, sofort keine weiteren Entwicklungsschreibaktionen.

Vor jedem Main-Merge zusätzlich frische Basis, unveränderten PR-Head, überlappende PRs, tatsächliche Mergefähigkeit und erfolgreiche CI auf exaktem Code-/Test-SHA prüfen. Basis ohne Force-Push integrieren, erforderliche Tests wiederholen. Sperren ersetzen weder Git-Konfliktprüfung noch GitHub-Rechte. Während laufender Entwicklung/CI darf kein zweiter Entwickler übernehmen.

Bei Abschluss/Fehler zuerst eigene Merge-, dann A-/B-Sperre mit frischem SHA und ursprünglichem Token als vollständiges FREE serialisieren/rücklesen; Commit, Freigabezeit und nächsten Schritt erhalten. release darf nach Teilübernahme nur eigene Dateien ändern. Bereits FREE oder fremde Dateien werden nicht überschrieben.

## Wächter

Die bestehende `.github/workflows/integration-idle-recovery.yml` bleibt konservativer Ausfallschutz. Sie wird durch diese Umstellung weder neu aktiviert noch als Entwicklungsautomation benutzt. `integration_parallel_lock_recovery.py` prüft weiterhin ausschließlich die drei festen Pfade separat; die Codex-Kennung ist je Pfad zur Recovery zugelassen. Historische A/B-Besitzer bleiben ausschließlich für sichere Recovery berechtigt, nicht zum neuen Erwerb im Einzelentwicklermodus. A darf weiterhin nicht als Besitzer einer B-Sperre gelten und umgekehrt.

Mindestens 30 Minuten nach Erwerb, Heartbeat und letzter sicher belegter Branch-/PR-/CI-Aktivität; laufende/wartende CI und unvollständige API-Belege blockieren. Doppelte Datei-SHA-/Token-/Aktivitäts-/Branchprüfung, Contents-CAS und unabhängige FREE-Rücklesung bleiben erhalten. Nach einer Recovery besitzt ein noch laufender Chat keine Schreibberechtigung mehr und muss seinen Dreifachcheck scheitern lassen. Fehler an einer Datei erlauben kein Überschreiben derselben Datei; die anderen werden separat geprüft. Legacy-Datei und Automatisierungskonfiguration bleiben unberührt.

## Verifikation

`python3 -m unittest discover -s tools -p 'test_integration*py' -v` führt ursprüngliche Recovery-, Rollen- und neue Codex-Tests aus: fremdes ACTIVE, anderer Codex-Run, Teilübernahme, konkurrierender Blob-CAS, Tokenverlust, fehlerhafte Rücklesung, striktes JSON, drei getrennte Tokens, Freigabereihenfolge/Nullfelder, Heartbeat und konservative Recovery. Beide vorhandenen PR-Sicherheitsworkflows führen alle drei Suites aus, bevor der Wächter schreiben darf.
