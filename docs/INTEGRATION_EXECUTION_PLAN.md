# Funktionsorientierter Ausführungsplan für die Gesamt-App

Stand: 05.11.2026. **Ausschließlich Codex als Einzelentwickler, ohne Sperrdateien oder automatische Lock-Recovery**, vom Nutzer ausdrücklich autorisiert. Die früheren A/B-Zeitfenster und getrennten Zuständigkeiten sind außer Kraft; A/B/III/IV bleiben pausiert. [Umstellung des Betriebsmodus](INTEGRATION_IDLE_RECOVERY.md). Keine Automatisierung reaktivieren und keine A/B-Identität verwenden. Auch ohne Locks keine parallelen schreibenden Codex-Sitzungen.

## Fortlaufende funktionale Priorität

1. Bestehende funktionale PRs fortsetzen und kontrolliert integrieren, keine doppelten Branches. Zum Cutover ist Rezept-PR #243 bereits als `ce662fc` integriert (CI 41913413053, 604 JVM/7 Room PASS); Einkaufs-PR #244 ist offen auf `838f00ce` (CI 41909547708 PASS), benötigt aber aktuelle main-Basis und erneute erforderliche Prüfung. Aussagen vor tatsächlichem Abgleich nicht als Abschluss behandeln.
2. **A1: bedienbarer Einkaufspfad.** Bon/Kamera/OCR oder brauchbare lokale Eingabe → mehrdeutige Produkt-/Barcode-/Versions-/Händler-/Filialzuordnung korrigieren → ausdrücklicher Kauf → FoodStore → genau einmaliger Lagerzugang und private historische Preise → zeit-/rechtekorrekter Vergleich → sichtbare Bedarf-/FEFO-/Gebinde-/Budgetvorschläge. Rabatte/Pfand, unbekannte Werte, Retry, Storno, Offline, Neustart und Datenschutz tatsächlich prüfen. Vorschläge buchen nichts; fehlende externe Quellen blockieren den lokalen Pfad nicht.
3. **B1: bedienbarer Rezeptpfad.** Offlinegenerator → aktueller Kontext und Allergen-/Ausschluss-/Nährwert-/Mengen-/Einheiten-/RAW-/COOKED-/Versionsprüfung → Vorschau → ausdrückliche authentifizierte Nutzerbestätigung → unabhängig an FoodStore validierter persistenter Rezeptsave → bestätigte Herstellung → separat bestätigter Verzehr. Alle acht Kontextkombinationen einschließlich sicher gesperrter nicht verfügbarer Quellen, Revalidierung nach Änderungen, Abbruch/Retry/Neustart/Offline/Datenschutz. Vorhandene Android-Belege aus #236/#237/#242 übernehmen, fehlende End-to-End-Fälle ergänzen.
4. Nach nachgewiesenem A1/B1 die weiteren offenen funktionalen Architektur-/Produktziele bearbeiten: Händlerfunktionen nur nach unabhängigen Quellen-/APP_DISPLAY-/Artikel-/Filialgates, vollständige v1-Generator-/Revalidator-Parität, Zusammenspiel Ernährung/Vorrat/Einkauf/Rezepte/Planung und weitere dokumentierte Module. Für die anschließende fünfte Domäne Fitness gilt der [verbindliche Zielauftrag #253](https://github.com/ecora2003-max/Ernaerungsgedaechnis/issues/253) aus dem Nutzer-PDF: echte automatische Galaxy-Watch-4-Schritte und mindestens eine Trainingseinheit bereits im ersten Fitness-Meilenstein. Keine manuelle Abschrift, Mock oder Connect-Schaltfläche als Abnahme.

Diese Liste ist funktionsorientiert, keine starre Acht-Phasen-Roadmap. Bei echter Blockade an einer unabhängigen erlaubten Funktion weiterarbeiten. Kein wiederholtes Vollinventar und keine zufälligen Mikroprüfungen ohne Funktionsbezug.

## Architektur und Integration

Einziges Schreibrepository ist die Haupt-App; fünf Referenzrepos ausschließlich lesen. FoodStore bleibt einzige explizit bestätigte, atomare/idempotente Ist-Grenze. null != 0, Integer-Cents, historische Identitäten/Versionen/Filialen/Quellen erhalten. Keine destruktive Room-Migration, kein stiller Wechsel von `1.0.0-draft` / Schema-Blob `bedde4677342817fe6b883962c8dfbbd60100072` zu nicht freigegebenem v1.1, keine unautorisierte externe Datenübertragung oder kostenpflichtige Cloudpflicht.

Keine Token-/Sperrprüfung mehr. Bestehendes Checkout verwenden, Nutzerarbeit schützen und keine zweite schreibende Codex-Sitzung parallel betreiben. Main-/PR-Head, offene überlappende PRs, Mergefähigkeit und genaue CI prüfen; Basis ohne Force-Push integrieren und nur unveränderten grünen Head mergen. GitHub-Sperren und Recovery nicht erneut einführen.

## Abnahme und Arbeitsende

Passende Unit-/Room-/Migrations-/Integrations-/Referenztests, Lint und Schemavergleich auf exaktem Code-/Test-SHA. Reale Android-/Offline-/Neustart-/Privacy-/Kamera-/OCR-Nutzerabläufe getrennt prüfen; Testkompilierung zählt nicht als Gerätetest. [ACCEPTANCE](ACCEPTANCE.md), [Zielmatrix](INTEGRATION_ROADMAP.md) und tatsächliche Nachweise sind maßgeblich. Kein INTEGRATED bei offenen Gates und keine Routine-APK.

Nach jedem Teilziel unmittelbar nächste offene Funktion bearbeiten. Zusammenhängenden Fortschritt in GitHub sichern, Status knapp im zugehörigen Code-PR. Erst bei technischer Sitzungs-/Werkzeug-/Kontingentgrenze Code, Commit, Tests, offene PRs, Blockaden und konkreten nächsten Schritt sichern; keine Locks erwerben oder freigeben. Keine künstlichen Fortsetzungspausen.

## Fitness nach A1/B1 – konkreter Anschluss aus Nutzerauftrag #253

Fünfte fachliche Domäne innerhalb der Haupt-App, keine weitere Zulieferautomatisierung. Quelle: Nutzer-PDF „Fitnessmodulplaner“, Version 1.0, 01.11.2026, vollständig als [Issue #253](https://github.com/ecora2003-max/Ernaerungsgedaechnis/issues/253) dauerhaft hinterlegt. [V] sind die vereinbarten Fachregeln, [N] die neue verbindliche Watch-Priorität, [E] technische Empfehlungen und [O] offene Detailentscheidungen.

1. **Watch-Spike [N]:** Galaxy Watch 4 → Samsung Health → Health Connect → App ist der vorrangig zu prüfende Weg [E]. Programmatisch neue echte Schritte und ein Watch-Training lesen; minimale ausdrückliche Leserechte, Geräte-/Appversionen, Herkunft und Verzögerung belegen. SDK-/Mock-/Emulatorgrün ersetzt den physischen Nachweis nicht. Keine Statistik auf nur angenommene Felder aufbauen.
2. **Persistenter Import [V/E]:** getrennte Quellports und FoodStore-Anschluss, erhaltende Room-Migration, Original-ID/Paket/Gerät, UTC und gelieferte Offsets, versionierte Änderungen und begrenzte vollständige Fenster. Retry/Deduplikation/Löschung/Neustart/Offline/Widerruf prüfen. Fehlende kcal bleiben unbekannt; Schritte und Tages-/Workoutenergie nicht doppelt addieren. Historien-/Hintergrundrechte nur bei wirklichem Bedarf und Verfügbarkeit.
3. **K01/K02 [V]:** korrigierbare Messhistorie mit Gerät/Methode/Messbedingungen und Trainingsjournal samt nachvollziehbaren Überschneidungen. Manuelle Ergänzung bleibt möglich, erfüllt für sich allein aber nicht Watch-Gate B.
4. **K03 [V/E]:** Rohwerte, 7/14/28/42/90-Tage-Sichten und reproduzierbare methoden-/quellenbewusste Trends. Exakte EMA-/LOESS-/Ausreißerparameter sind [O], keine erfundene beschlossene Statistik.
5. **K04 [V/E]:** deterministische Energieanalyse aus bestätigter EG-Ernährung, ausreichender Körper-/Aktivitätsabdeckung und belegter Semantik. B + α·A nur als gekennzeichnete Modellfamilie; aktive Energie nicht durch Watch-Gesamtkalorien ersetzen. Methoden-/Unsicherheits-/Freigabeschwellen bleiben [O]. Keine autonome Kalorienzieländerung.

Samsung Health Data SDK ist eine getrennt zu bewertende Option [O], kein vorausgesetzter Pflichtdienst; Wear-OS-Sensorclient ist für den primären Datenweg nicht erforderlich. Keine Änderung an Architektur-/Fachrepo oder bestehendem v1/v1.1-Schema. Kein ungefragter Export echter Gesundheitsdaten. Fitness erst bei tatsächlich erfüllten Gates A–E aus [ACCEPTANCE](ACCEPTANCE.md) und Issue #253 freigeben.
