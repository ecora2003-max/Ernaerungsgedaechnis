# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Einstieg nach einer Unterbrechung: zuerst dieses Dokument und `AGENTS.md` lesen.

## Auftrag und Grenzen

Die vorhandene Android-App schrittweise nach `product/Gesamtplanung-2026-10-30.txt` (PDF daneben) weiterentwickeln. Fachregeln sind verbindlich; technische Umsetzung bleibt flexibel. In sinnvollen, geprüften Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort Code und ehrliche Übergabe sichern, keinen großen neuen Build beginnen. Prüfen statt nur Testdateien anlegen; Aufwand auf fachlich riskante Regeln konzentrieren.

Demo-/Testdaten bleiben isoliert. Sheets ist bis zu einer gesondert freigegebenen Migration produktiv verbindlich. Keine neue Sheets-Datenbank, kein eigener Server, keine verpflichtende kostenpflichtige KI-API. GitHub speichert Code und Dokumentation, niemals persönliche App-Daten, Datenbankdateien oder Geheimnisse. Cloud-Umgebung nicht eigenständig veröffentlichen.

## Aktueller Meilenstein: gemeinsame Planung und Bedarf (0.5)

Arbeitsstand auf `development/planning-demand`, Version **0.5.0-planning**, versionCode 5, Schema **3** mit 26 Tabellen. Vollständig lokal geprüft; der frische CI-Nachweis folgt vor Abschluss.

- `PlanningOperations` speichert ganze unveränderliche Fassungen, stabile `plan_series`-Identität, begründetes Verwerfen, atomare Einträge und remappte Koch-/Portionsverknüpfungen. Retry vor Aktualitätsprüfung, parallele veraltete Änderungen werden abgewiesen.
- `PlanningReader`: eine konsistente Lese-Transaktion, abgeleitete Fehlmengen ohne Buchungen. Alle neuesten bestätigten Planidentitäten teilen sich einmalig passende Chargen nach Termin und näherer Haltbarkeitsgrenze. Auch Tagesausschnitte berücksichtigen vorherige Reservierungsannahmen außerhalb ihres Ausschnitts.
- Direkte Produkte, Zubereitung und vorhandene/geplante Fertigportionen getrennt. Soll-Zutaten nur beim Kochtermin skalieren; spätere verknüpfte Portionen teilen die geplante Ausbeute. Keine physischen Portionen durch Plananlage und keine automatisch bestätigten Lagerbedingungen.
- Freigabe/Verbrauchsdatum/MHD/erfasste Haltbarkeit zum Termin, konkreter Versionsbezug, unbekannte Mengen und Einheitenkonflikte sichtbar. Ungeklärter konkurrierender Bedarf führt zu einer ausdrücklichen Teilergebniswarnung. Bedarfsskalierung mit BigInteger, auf 1/1000 Basiseinheit aufgerundet, keine erfundene Umrechnung.
- Lokaler Bereich **Planung** mit 1-/7-/56-Tage-Ansicht, Planformular, Fehlmengen und Versionshistorie. Bisherige 33 Haupt-Fachansichten bleiben fiktiv.

Abschließender vollständiger lokaler Lauf: **84 Tests bestanden, 0 Fehlschläge/Fehler/Skips**, zwölf aktuelle XML-Dateien ausgewertet (11:49:07–11:49:24 UTC). APK-Build und Lint erfolgreich nach 38 Sekunden bei vorbereiteten Abhängigkeiten, zehn Bibliotheks-Updatehinweise/keine Lint-Fehler. Schemaexporte 1/2 unverändert, Schema 3 exportiert und beide Migrationswege an echten Dateien geprüft. APK-Metadaten und v2-Signatur gültig. Keine Assertion entfernt/deaktiviert.

Sechs Bedienprüfungen enthalten beide neuen Planungsfälle und vorhandene Buchungs-/Ernährungswege. Auf 320×640 dp/Schriftfaktor 1,3 Tages-/Wochen-/56-Tage-Bedarf sowie neue Version und Verwerfen geprüft; ursprünglicher Bestand/Historie bleiben erhalten. Geplante Zubereitung mit verknüpften späteren Portionen/unklarer Menge über die Formulare gespeichert. Ein dabei gefundener veralteter Speicherzustand im später geöffneten Versionsbereich ist korrigiert: aktuelle Zustandsreferenzen für Speichersperre/Vorgangs-ID. Den neuen Planungsansicht-Ausschnitt visuell geprüft, keine Geräte-/Emulatortests. Details: `TEST_REPORT_PLANNING.md`. Bedienung/Rechenregeln: `PLANNING.md`.

**Vor Abschluss noch offen:** lokal geprüften App-Code auf Entwicklungsbranch und `main` sichern, frischen Actions-Lauf verfolgen und dessen Code-SHA, aktuelle XML-Zahlen und APK-Link ergänzen. Keine alten CI-Läufe als Nachweis für 0.5 verwenden.

Wichtige Grenze: noch kein bestätigter Ausführungs-/Erfüllungsabgleich. Offene Planannahmen nach tatsächlicher Durchführung bewusst überarbeiten/verwerfen; Vergangenes wird nicht mit heutigem Bestand rückgerechnet. Geplante Portionen sind bedingt und ohne Sicherheitsfreigabe. Keine Packungs-, Preis-, Budget- oder Kapazitätsoptimierung. Nicht als vollständige Phase 4 oder produktionsreif bezeichnen.

## Bisherige Ernährung und Buchungen

Der abgeschlossene Ernährungsmeilenstein 0.4 bleibt erhalten: konkrete Artikelversionen, tatsächliche Zutaten/Ausbeute, getrennte Nährstofflücken, Tagesbestätigung mit Snapshot und Statushistorie, alte Produkt-Retry-Fingerprints. Bedienung/Fachregeln in `NUTRITION.md`, Architektur in `LOCAL_DATABASE.md`. Historische Prüfberichte unten; kein alter Lauf ist ein Nachweis für 0.5.

## Migration und Wiederaufnahme

Schemaexporte 1 und 2 unverändert erhalten. Schema 2 ergänzt `batches.parentBatchId`, `stock_movements.occurredOn`, `operation_reversals`, `recipe_output_products` und `productions`. Schema 3 ergänzt `plan_series`, nullable historische Metadaten/Planidentitäten und `planned_entries.preparationEntryId`/`label`. Alte Pläne bekommen jeweils ihre eigene Identität; fehlende Titel/Gründe bleiben NULL. App registriert `MIGRATION_1_2` und `MIGRATION_2_3`, keine destruktive Fallback-Migration.

Zwei Dateitests starten aus den veröffentlichten Schemaexporten 1 und 2. Alle 22 bzw. 25 alten Tabellen enthalten Daten; sämtliche bisherigen Spalten/Zeilen werden verglichen, Fremdschlüssel, NULL gegenüber 0, alte Retry-Schlüssel, neue Buchungen/Planversionen und Wiederöffnung geprüft. Nach Veröffentlichung von Schema 3 neue Strukturänderungen nur als Schema 4+ mit Erhaltungsmigration.

Lokale Werkzeuge (außerhalb des Repositories): SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper:

```sh
python /workspace/tooling/run-gradle.py :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Der Helper verwendet den geerbten Proxy nur für Build-/Testprozesse und `-PtestHome=/workspace/tooling/test-home`. TLS-/Checksum-Prüfungen bleiben aktiv. Falls diese Werkzeuge nach Wiederaufnahme fehlen, normale Android-Studio-/Gradle-Einrichtung oder vorhandenen Actions-Workflow verwenden. JDK 17+, SDK 35, Build Tools 34.0.0, Gradle 8.9/AGP 8.7.3/Kotlin 2.0.21/Room 2.7.2. Keine Zugangsdaten in Skripte übernehmen.

## Vor Produktivbetrieb zu schließen

Die bestehende konservative Bestandsrückdatierungssperre kann bestandsgebundene Vergangenheitskorrekturen nach einem heutigen Storno verhindern. Der neue direkte Storno-/Neuerfassungsweg ist für solche mehrtägigen Ersatzbuchungen nicht vollständig. Kein falsches tatsächliches Datum empfehlen. Vor Produktivbetrieb eine atomare Korrektur mit erhaltener Tageszuordnung und nachvollziehbarer Bestandswirkung entwerfen/testen; vorhandene historische Referenzen nicht überschreiben. Ohne Lagerbezug bleiben vergangene Verzehre normal erfassbar. Erläuterung in `NUTRITION.md`.

## Nächste Entwicklungsschritte

Als Erstes den ausdrücklich bestätigten Ausführungs-/Erfüllungsabgleich für Planung entwerfen: tatsächlichen Verzehr/Herstellung einer konkreten Planposition zuordnen, Abweichungen und Storno erhalten, keine neue Ist-Buchung durch bloße Zuordnung. Bereits ausgeführte Planannahmen dürfen nicht zusätzlich vom aktuellen Restbestand abgezogen werden. Keine implizite Zuordnung anhand ähnlicher Bezeichnung/Menge.

Darauf Fehlmengen in eine überprüfbare Einkaufsliste mit bestätigter nutzbarer Packungsbasis, versionsbezogenen Preisbeobachtungen und Budgetunsicherheit überführen. Einkaufsrundung nur bei bekannten Größen; abhaken ist kein Einkauf/Zugang. Konkurrierende Bedarfe, unbekannte Menge, Zutaten/Portionen und zeitliche Haltbarkeit weiterhin getrennt halten. Änderungen nach Veröffentlichung von Schema 3 brauchen Version 4+ und Erhaltungstests.

Schrittweise die passenden bisherigen Fachansichten anschließen, ohne fiktive und tatsächliche Daten zu vermischen. Später: Beleg-/Ausgabenlogik und Erkennung, validierter JSON-Austausch, persönliche Zielhistorie/Körper/Training, sichere Backups/stabile Release-Signierung und ausdrücklich bestätigte Migration realer Daten. Vorbereitete Tabellen sind keine fertigen Module.

## Historie

Ernährung 0.4.0: `TEST_REPORT_NUTRITION.md`, geprüfter App-Code `4a79515`, erfolgreicher CI-Lauf 41045875849 mit 69 Tests.

Buchungen/Meal Prep 0.3.0: `TEST_REPORT_TRANSACTIONS.md`, Code `68c80ba`, erfolgreicher CI-Lauf 41039703367 mit 54 Tests.

Erstes Datenfundament 0.2.0: `TEST_REPORT_FOUNDATION.md`, Code `39ea5ad`, erfolgreicher CI-Lauf 41032992779 mit 36 Tests. Die damaligen APK-Artefakte sind zeitlich begrenzt; der Workflow kann erneut ausgeführt werden. Für neue Versionen ausschließlich neue Prüfberichte als Freigabenachweis verwenden.
