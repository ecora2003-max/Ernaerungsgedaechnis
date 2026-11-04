# Funktionsorientierter Ausführungsplan für die Gesamt-App

Stand: 04.11.2026. **Exklusiver Codex-Einzelentwickler**, vom Nutzer ausdrücklich autorisiert. Die früheren A/B-Zeitfenster und getrennten Entwicklungszuständigkeiten sind außer Kraft; A/B/III/IV bleiben pausiert. Eigene Besitzerkennung und drei Sperren gemäß [Sperrprotokoll](INTEGRATION_IDLE_RECOVERY.md). Keine alte Automatisierung reaktivieren und keine A/B-Identität verwenden.

## Fortlaufende funktionale Priorität

1. Bestehende funktionale PRs fortsetzen und kontrolliert integrieren, keine doppelten Branches. Zum Cutover ist Rezept-PR #243 bereits als `ce662fc` integriert (CI 41913413053, 604 JVM/7 Room PASS); Einkaufs-PR #244 ist offen auf `838f00ce` (CI 41909547708 PASS), benötigt aber aktuelle main-Basis und erneute erforderliche Prüfung. Aussagen vor tatsächlichem Abgleich nicht als Abschluss behandeln.
2. **A1: bedienbarer Einkaufspfad.** Bon/Kamera/OCR oder brauchbare lokale Eingabe → mehrdeutige Produkt-/Barcode-/Versions-/Händler-/Filialzuordnung korrigieren → ausdrücklicher Kauf → FoodStore → genau einmaliger Lagerzugang und private historische Preise → zeit-/rechtekorrekter Vergleich → sichtbare Bedarf-/FEFO-/Gebinde-/Budgetvorschläge. Rabatte/Pfand, unbekannte Werte, Retry, Storno, Offline, Neustart und Datenschutz tatsächlich prüfen. Vorschläge buchen nichts; fehlende externe Quellen blockieren den lokalen Pfad nicht.
3. **B1: bedienbarer Rezeptpfad.** Offlinegenerator → aktueller Kontext und Allergen-/Ausschluss-/Nährwert-/Mengen-/Einheiten-/RAW-/COOKED-/Versionsprüfung → Vorschau → ausdrückliche authentifizierte Nutzerbestätigung → unabhängig an FoodStore validierter persistenter Rezeptsave → bestätigte Herstellung → separat bestätigter Verzehr. Alle acht Kontextkombinationen einschließlich sicher gesperrter nicht verfügbarer Quellen, Revalidierung nach Änderungen, Abbruch/Retry/Neustart/Offline/Datenschutz. Vorhandene Android-Belege aus #236/#237/#242 übernehmen, fehlende End-to-End-Fälle ergänzen.
4. Nach nachgewiesenem A1/B1 die weiteren offenen funktionalen Architektur-/Produktziele bearbeiten: Händlerfunktionen nur nach unabhängigen Quellen-/APP_DISPLAY-/Artikel-/Filialgates, vollständige v1-Generator-/Revalidator-Parität, Zusammenspiel Ernährung/Vorrat/Einkauf/Rezepte/Planung und weitere dokumentierte Module. Fitness und direkte Samsung-Watch-Anbindung nur anhand vorhandener Anforderungen/Verträge, fehlende Spezifikationen nicht erfinden.

Diese Liste ist funktionsorientiert, keine starre Acht-Phasen-Roadmap. Bei echter Blockade an einer unabhängigen erlaubten Funktion weiterarbeiten. Kein wiederholtes Vollinventar und keine zufälligen Mikroprüfungen ohne Funktionsbezug.

## Architektur und Integration

Einziges Schreibrepository ist die Haupt-App; fünf Referenzrepos ausschließlich lesen. FoodStore bleibt einzige explizit bestätigte, atomare/idempotente Ist-Grenze. null != 0, Integer-Cents, historische Identitäten/Versionen/Filialen/Quellen erhalten. Keine destruktive Room-Migration, kein stiller Wechsel von `1.0.0-draft` / Schema-Blob `bedde4677342817fe6b883962c8dfbbd60100072` zu nicht freigegebenem v1.1, keine unautorisierte externe Datenübertragung oder kostenpflichtige Cloudpflicht.

Vor jedem Schreiben alle drei ursprünglichen Codex-Tokens prüfen. Bestehendes Checkout verwenden, Nutzerarbeit schützen, gemeinsame Dateien und alle Main-Merges exklusiv bearbeiten. Main-/PR-Head und überlappende offene PRs prüfen, Basis ohne Force-Push integrieren, nur unveränderten grünen Head mergen. Bei Abschluss/Fehler eigene Merge-, dann A-/B-Sperre sicher freigeben/rücklesen.

## Abnahme und Arbeitsende

Passende Unit-/Room-/Migrations-/Integrations-/Referenztests, Lint und Schemavergleich auf exaktem Code-/Test-SHA. Reale Android-/Offline-/Neustart-/Privacy-/Kamera-/OCR-Nutzerabläufe getrennt prüfen; Testkompilierung zählt nicht als Gerätetest. [ACCEPTANCE](ACCEPTANCE.md), [Zielmatrix](INTEGRATION_ROADMAP.md) und tatsächliche Nachweise sind maßgeblich. Kein INTEGRATED bei offenen Gates und keine Routine-APK.

Nach jedem Teilziel unmittelbar nächste offene Funktion bearbeiten. Zusammenhängenden Fortschritt in GitHub sichern, Status knapp im zugehörigen Code-PR. Erst bei technischer Sitzungs-/Werkzeug-/Kontingentgrenze Code, Commit, Tests, offene PRs, Blockaden und konkreten nächsten Schritt sichern und eigene Sperren freigeben. Keine künstlichen Fortsetzungspausen.
