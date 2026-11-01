# GitHub-Wächter: 35 Minuten Inaktivität

**Status:** neue, gesonderte Sicherheitsfunktion; kein Lease-System und keine automatische Sperrverlängerung. Die manuellen `FREE → ACTIVE → FREE`-Schritte der vier Integrationsautomatisierungen bleiben unverändert.

## Ausführung

- Workflow: `.github/workflows/integration-idle-recovery.yml` mit `schedule` alle fünf Minuten (best-effort GitHub Actions, **keine minutengenaue Garantie**) und optional `workflow_dispatch`.
- Ausschließlich der bereits installierte, vertrauenswürdige Python-Code auf `main` wird ausgeführt: `tools/integration_idle_recovery.py`. Das GitHub-Action-Token erhält `contents: write`, `actions: read` und `pull-requests: read`. Keine zusätzlichen API-Schlüssel.
- Die Sperre liegt weiter im **letzten YAML-Block** der Datei `Prüfe mich ob du dran bist und was du machen sollst und darfst.md` auf `main`.
- Ein normaler Entwicklungs-Chat nimmt die Sperre **manuell** mit eindeutigem `ownerRunToken` und frischem GitHub-Datei-SHA, bestätigt `ACTIVE` und gibt sie selbst wieder frei. Der Wächter greift nur bei Störungen nach mindestens 35 Minuten ohne verifizierte GitHub-Aktivität ein. Er darf die Sperre niemals übernehmen oder erneuern.

## Genaue Freigabebedingungen

**Alle** folgenden Prüfungen sind erforderlich:

1. Letzter YAML-Block und Schlussmarker sind konsistent `ACTIVE`; Besitzer-Automatisierungs-ID ist eine der vier bekannten Integrationen; `ownerRunToken` und `acquiredAtUtc` sind vorhanden. Andere Besitzer, einschließlich manueller Wartung, werden **niemals** automatisch freigegeben.
2. Seit der Übernahme sind mindestens **35 Minuten** vergangen.
3. Seit der jüngsten bekannten GitHub-Aktivität sind mindestens **35 Minuten** vergangen: Sperrübernahme/ggf. Heartbeat, letzter Commit des registrierten Arbeitsbranches, Update eines offenen PR zu diesem Branch sowie GitHub Actions-Läufe desselben Branches. Ein laufender oder wartender CI-Lauf verhindert die Freigabe.
4. Fehlender/gelöschter Arbeitsbranch, unerreichbare API, zu viele nicht vollständig erfasste CI/PR-Ergebnisse, mehrdeutige YAML-Daten oder widersprüchlicher `ACTIVE`-/Markerstatus führen zu **keiner** automatischen Freigabe.
5. Unmittelbar vor der Freigabe werden der aktuelle YAML-/Marker-/Besitzerzustand, der exakte Datei-Blob-SHA, Arbeitsbranch, PR und CI **erneut** kontrolliert. Bei irgendeiner Änderung: Abbruch.
6. Erst dann wird die Datei auf `main` **atomar per GitHub-Contents-SHA-CAS** umgestellt: `state: "FREE"`, Schlussmarker `Hier arbeitet kein Chat mehr.`, alle Besitzer-, Run-, Token-, Branch-, Heartbeat- und Leasefelder `null`, Freigabezeit + Folgeaufgabe dokumentiert. Anschließend frische Lesekontrolle auf `main`.

**Grenze:** GitHub sieht nicht, ob ein Chat gerade nur nachdenkt oder lokal Dateien vorbereitet. Eine 35-minütige Abwesenheit sichtbarer GitHub-Aktivität beweist kein Ende des Chats. Dieses Risiko wurde vom Auftraggeber für auf weniger als 30 Minuten ausgelegte Integrationsläufe ausdrücklich akzeptiert. Die Wiederprüfung verringert, beseitigt aber nicht vollständig ein Rennen mit einem gleichzeitig gestarteten Branch-Push; es gibt keine repo-übergreifende atomare Transaktion.

## Prüfung und Betrieb

- Offline-Regressionssuite: `python3 -m unittest discover -s tools -p 'test_integration_idle_recovery.py' -v`.
- Der GitHub-Workflow führt diese Tests **vor jeder Prüfung** aus; fehlgeschlagene Tests stoppen den Wächter.
- Testfälle: 34/35/36-Minuten-Grenze, frische Commits/PR-Updates, laufende oder frisch beendete CI, falsche/beschädigte Besitzer-/YAML-Information, konkurrierendes GitHub-SHA-Update, nicht erreichbarer Branch und korrekt verifizierte Freigabe.
- Bei GitHub-Action-`GITHUB_TOKEN` ohne Schreibberechtigung, verzögertem/übersprungenem Zeitplan oder GitHub-API-Ausfall erfolgt **keine** automatische Freigabe. Workflow-Protokoll prüfen; keine Erfolgsbehauptung ohne beobachteten Workflow-/Commitnachweis.
- Frühere automatische Lease-/Renewal-Workflows bleiben **deaktiviert beziehungsweise entfernt**. Keine zusätzliche CI-bedingte Verlängerung, kein selbsttätiges Wiedersetzen von `ACTIVE`, keine neue Sperrdatei.
