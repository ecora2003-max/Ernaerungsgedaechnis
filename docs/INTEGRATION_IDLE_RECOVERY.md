# GitHub-Wächter: eigenständige JSON-Sperre mit 30-Minuten-Recovery

## Einzige operative Sperre

Die Datei [integration-lock.json](../integration-lock.json) auf GitHub-main ist die einzige maßgebliche Integrationssperre. Die Datei "Prüfe mich ob du dran bist und was du machen sollst und darfst.md" enthält ausschließlich Arbeitsanweisungen und KEINEN aktiven Sperrstatus mehr. Alter YAML-Block oder alter Textmarker sind nach der Migration niemals als Sperre zu lesen oder zu schreiben.

Die JSON-Datei enthält das feste Schema schemaVersion=1, state=FREE/ACTIVE, ownerAutomationId, ownerChatId, ownerRunId, ownerRunToken, acquiredAtUtc, lastHeartbeatUtc, leaseExpiresAtUtc, task, workingBranch, lastReleasedAtUtc, lastReleasedCommit und nextStep. Der komplette Inhalt wird als korrekt formatiertes JSON serialisiert. Keine manuell zusammengesetzten Markdown-/YAML-Blöcke und keine Dateifragmente.

## Chronologische Schritte für jeden der vier Integratoren

1. Anweisungsdatei und Architekturvorgaben von main lesen; separat den VOLLSTÄNDIGEN Inhalt und den GitHub-Datei-BLOB-SHA von integration-lock.json auf main abrufen.
2. JSON streng parsen und sämtliche Pflichtfelder prüfen. Nur state=FREE mit allen Owner-/Run-/Token-/Zeit-/Branchfeldern null ist übernehmbar. Fremdes ACTIVE, JSON-Fehler, falsche Felder oder unklare Lage: ohne jede Schreibaktion stoppen. Kein Integrator darf eine fremde Sperre reparieren oder übernehmen.
3. Bei FREE frischen Run-Token erzeugen, den gesamten JSON-Inhalt strukturiert mit state=ACTIVE, eigener Automation-ID, ownerChatId, ownerRunToken, acquiredAtUtc, task und workingBranch=null serialisieren; ausschließlich integration-lock.json mit dem frisch gelesenen Datei-BLOB-SHA (GitHub-Contents-CAS) auf main aktualisieren.
4. Main neu lesen und vollständiges JSON samt eigenem Besitzer/Token verifizieren. Erst danach eigenen Arbeitsbranch anlegen, Code ändern, PR und Tests durchführen. Nach Registrierung des Branches workingBranch in derselben JSON-Sperre unter erneutem SHA-/Token-Check setzen. Bei jedem relevanten Schreibvorgang und vor Merge eigene Sperre/aktuellen main-HEAD erneut prüfen.
5. Bei Abschluss erneut den aktuellen Datei-BLOB-SHA, state=ACTIVE sowie eigenen unveränderten Token prüfen. Ausschließlich die eigene Sperre durch komplette, wohlgeformte JSON-Serialisierung auf FREE setzen; alle Owner-, Token-, Run-, Zeit-, Lease-, Heartbeat- und Branchfelder null; lastReleasedAtUtc, lastReleasedCommit und nextStep sachlich setzen. Nach Commit main erneut lesen und FREE strikt verifizieren. Ohne Schreib- und Rücklese-Erfolg keine Freigabe behaupten.
6. Ein normaler Entwicklungs-Chat setzt niemals eine fremde Sperre zurück und pausiert die Automatisierungen nicht. Auf die Recovery-Action darf er sich beim eigenen Abschluss nicht verlassen.

## Wächter und Wiederherstellung

Workflow: .github/workflows/integration-idle-recovery.yml, cron alle fünf Minuten als GitHub-Actions-best-effort; kein minutengenauer Start garantiert. Kein zusätzlicher kostenpflichtiger Dienst. Das Skript tools/integration_idle_recovery.py arbeitet ausschließlich auf dem Hauptrepository und auf main.

- Nur eines der vier bekannten ownerAutomationId-Kennzeichen mit eindeutigem Token und acquiredAtUtc kommt infrage.
- Mindestens 30 Minuten seit Sperrübernahme und seit der letzten bekannten Aktivität. Zu prüfen sind der registrierte Branch-HEAD, offene PR-Updates, Workflow-Runs/CI (laufende oder wartende CI blockiert), gegebenenfalls Heartbeat. Fehlende/unbekannte Branches oder API-Probleme blockieren die Recovery.
- Bei gültigem, inaktivem ACTIVE ersetzt der Wächter den GESAMTEN Inhalt von integration-lock.json durch eine frisch serialisierte, konstante FREE-Struktur. Bei erkennbar beschädigtem JSON ist diese Neuerstellung nur zulässig, wenn der eigenständige Text die identitäts- und aktivitätskritischen Felder noch eindeutig und widerspruchsfrei enthält; andernfalls bleibt die Sperre unverändert und erfordert manuelle Hilfe. Der Wächter interpoliert niemals freie Anweisungen in den Sperrinhalt.
- Unmittelbar vor dem Schreibvorgang muss der Wächter denselben Blob-SHA, Inhalt, Token und dieselben GitHub-Aktivitätsnachweise erneut prüfen. GitHub-Contents-CAS verhindert das Überschreiben zwischenzeitlicher Änderungen. Abschließend prüft er den neuen FREE-JSON-Inhalt samt nullten Besitzerfeldern durch einen unabhängigen erneuten Abruf.
- Keine komplette Wiederherstellung einer älteren Repository- oder Markdown-Dateiversion. Keine Änderung der Entwicklungsanweisungen. Kein Zurücksetzen bei nicht eindeutig feststellbarer Inaktivität.

## Grenzen und Tests

GitHub-Aktivität ist nur ein Proxy für laufende Arbeit; ein Chat kann länger ohne GitHub-Ereignis denken oder lokal entwickeln. Deshalb garantiert selbst der 30-Minuten-Wächter keine perfekte Unterscheidung. Ein fehlender Branch während einer sehr frühen aktiven Phase bleibt ein Rest-Risiko. Die Integratoren sollen den Branch früh registrieren; der Wächter greift konservativ ein.

Die Offline-Suite ist python3 -m unittest discover -s tools -p test_integration_idle_recovery.py -v. Sie prüft normale Freigabe, exakt 30 Minuten, GitHub-Aktivität, CI, konkurrierende Dateiänderung und Neuerstellung nach vorsichtig rekonstruierbarem JSON-Syntaxfehler, sowie Ablehnung bei unbekanntem Besitzer, verlorenem Token oder widersprüchlicher Datei.

Der GitHub-Workflow führt diese Tests bei Änderungen über PR aus, und vor jedem geplanten Recovery-Lauf. Ein echter Restore-Erfolg setzt einen beobachteten Workflow, Commit und korrekt rückgelesenes FREE voraus.
