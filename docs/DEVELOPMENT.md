# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Einstieg nach einer Unterbrechung: zuerst dieses Dokument und `AGENTS.md` lesen.

## Auftrag und Grenzen

Die vorhandene Android-App schrittweise nach `product/Gesamtplanung-2026-10-30.txt` (PDF daneben) weiterentwickeln. Fachregeln sind verbindlich; technische Umsetzung bleibt flexibel. In sinnvollen, geprüften Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort Code und ehrliche Übergabe sichern, keinen großen neuen Build beginnen. Prüfen statt nur Testdateien anlegen; Aufwand auf fachlich riskante Regeln konzentrieren.

Demo-/Testdaten bleiben isoliert. Sheets ist bis zu einer gesondert freigegebenen Migration produktiv verbindlich. Keine neue Sheets-Datenbank, kein eigener Server, keine verpflichtende kostenpflichtige KI-API. GitHub speichert Code und Dokumentation, niemals persönliche App-Daten, Datenbankdateien oder Geheimnisse. Cloud-Umgebung nicht eigenständig veröffentlichen.

## Aktueller Meilenstein: Ernährung und berechnete Nährwerte

Version **0.4.0-nutrition**, versionCode 4, weiterhin Datenbankschema **2** (25 Tabellen). Aktiver Entwicklungsbranch: `development/nutrition-diary`.

- Rechnung pro konkreter Artikelversion und tatsächlicher Menge, getrennt nach g/ml/Portion. Alle bereits modellierten Makro-/Mikronährstoffe sind auswertbar und im Produktformular erfassbar. Alte Produkt-Retry-Fingerprints ohne Zusatznährstoffe bleiben kompatibel.
- NULL ist unbekannt, eingegebene 0 bleibt bekannt. Bekannte Teilsummen und Lücken werden pro Nährstoff getrennt ausgewiesen; keine falschen Prozentabdeckungen. BigDecimal/DECIMAL128, Rundung nur für Anzeige.
- Fertigportionen basieren auf tatsächlichen Zutatenbewegungen und tatsächlicher Ausbeute, auch nach Weiterverarbeitung/Umlagerung. Neue Produkt-/Rezeptversionen ändern historische Ansätze nicht. Rezeptvorschauen verwenden weiterhin eigene Soll-Mengen.
- Reaktive lokale Tagesbilanz nur aus aktiven tatsächlichen Verzehren; Pläne, Zugänge, Entsorgung und bloßes Kochen zählen nicht. Verzehr ohne Lagerbezug und mit unbekannter Menge ist auch über die Oberfläche möglich.
- Tageserfassung ausdrücklich bestätigen oder wieder öffnen; Lücken bleiben sichtbar. Snapshotprüfung schützt gegen veraltete Bestätigungen, Statushistorie gegen gleiche Uhrzeit/erneute Zustandsfolge. Neue Einträge/Stornos setzen TEILWEISE; alter Retry setzt einen geänderten Tag nicht erneut vollständig.
- Bedienung im vorhandenen M03-Testbereich jetzt mit Ernährung, direktem Verzehrstorno und scrollbaren Bestätigungsdialogen. Die bisherigen 33 Fachansichten bleiben getrennte fiktive Beispiele. Keine persönlichen Zielwerte aus der Demo übernehmen.

Bedienung/Fachregeln: `NUTRITION.md`; allgemeine Architektur: `LOCAL_DATABASE.md`. Produktplan unverändert im Repository.

## Prüfergebnisse und Freigabestatus

Abschließender vollständiger lokaler Lauf: **69 Tests bestanden, 0 Fehlschläge, 0 Fehler, 0 Skips**, APK-Build und Lint erfolgreich nach 32 Sekunden bei vorbereiteten Abhängigkeiten. Die elf aktuellen JUnit-XML-Dateien wurden ausgewertet. Lint: zehn Bibliotheks-Updatehinweise, keine Fehler. Keine Assertion wurde deaktiviert. Datenbankschema 1 und 2 unverändert.

UI-Prüfungen: zusätzliche Nährstoffe über das Produktformular; Rezeptvorschau; tatsächliches Kochen ohne Ernährung und späterer Portionsverzehr mit 170 kcal/12 g Protein bei unveränderten Rohwaren; Teilsummen/unklare Mengen/Tagesbestätigung/Storno auf 320×640 dp mit Schriftfaktor 1,3. Die zwei zugehörigen Ernährungsansicht-Ausschnitte wurden visuell nachgeprüft. Kein Smartphone-/Emulatortest. Details: `TEST_REPORT_NUTRITION.md`.

**Noch vor Abschluss:** lokal geprüften Stand auf Entwicklungsbranch und main sichern, frischen GitHub-Actions-Lauf prüfen und dessen Code-SHA, XML-Zahlen und APK-Link hier ergänzen. Bis dahin bleibt der frische CI-Nachweis offen. Nicht frühere Prüfberichte als neuen Nachweis ausgeben.

## Migration und Wiederaufnahme

Schema 1 unverändert erhalten. Schema 2 ergänzt `batches.parentBatchId`, `stock_movements.occurredOn`, `operation_reversals`, `recipe_output_products` und `productions`. `FoodMigrations.MIGRATION_1_2` ist im App-Builder registriert; keine destruktive Fallback-Migration. Der Dateitest befüllt alle 22 alten Tabellen, vergleicht alle bisherigen Spalten/Zeilen, prüft Fremdschlüssel, NULL gegenüber 0, alte Retry-Schlüssel, neue Vorgänge und Wiederöffnung. Ab Freigabe von Schema 2 sind Strukturänderungen Schema 3+ mit eigener Erhaltungsmigration.

Lokale Werkzeuge (außerhalb des Repositories): SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper:

```sh
python /workspace/tooling/run-gradle.py :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Der Helper verwendet den geerbten Proxy nur für Build-/Testprozesse und `-PtestHome=/workspace/tooling/test-home`. TLS-/Checksum-Prüfungen bleiben aktiv. Falls diese Werkzeuge nach Wiederaufnahme fehlen, normale Android-Studio-/Gradle-Einrichtung oder vorhandenen Actions-Workflow verwenden. JDK 17+, SDK 35, Build Tools 34.0.0, Gradle 8.9/AGP 8.7.3/Kotlin 2.0.21/Room 2.7.2. Keine Zugangsdaten in Skripte übernehmen.

## Nächster Meilenstein: verknüpfte Planung und Bedarf

Auf Basis der bestehenden bestätigten Ereignisse zunächst die zeitbezogene Bestands-/Bedarfsrechnung entwickeln und mit konkreten Beispielen testen. Nutzbare Chargen von physischem Bestand trennen: Freigabe, Verbrauchsdatum, Zieltermin und konkurrierende Verwendung berücksichtigen. Keine automatische Sicherheitsfreigabe bei unbekannten Bedingungen.

Planarten unterscheiden: direkter Produktverzehr, Zubereitung und späterer Verzehr fertiger Portionen. Zutaten nicht bei jedem Portionsverzehr erneut als Bedarf zählen; Tages-/Wochen-/Langfristziele nicht additiv doppelt zählen. Pläne bleiben vollständig ohne Ist-Buchungen. Unbekannte Mengen, Packungsbasen, Preise/Budgets sichtbar lassen; Einkaufsrundung nur bei bestätigten Größen. Vor Tabellenänderungen auf Schema 3 mit Migration und Erhaltungstests gehen; Schema 2 nicht überschreiben.

Schrittweise die passenden bisherigen Fachansichten anschließen, ohne fiktive und tatsächliche Daten zu vermischen. Später: Beleg-/Ausgabenlogik und Erkennung, validierter JSON-Austausch, persönliche Zielhistorie/Körper/Training, sichere Backups/stabile Release-Signierung und ausdrücklich bestätigte Migration realer Daten. Vorbereitete Tabellen sind keine fertigen Module.

## Historie

Buchungen/Meal Prep 0.3.0: `TEST_REPORT_TRANSACTIONS.md`, Code `68c80ba`, erfolgreicher CI-Lauf 41039703367 mit 54 Tests.

Erstes Datenfundament 0.2.0: `TEST_REPORT_FOUNDATION.md`, Code `39ea5ad`, erfolgreicher CI-Lauf 41032992779 mit 36 Tests. Die damaligen APK-Artefakte sind zeitlich begrenzt; der Workflow kann erneut ausgeführt werden. Für neue Versionen ausschließlich neue Prüfberichte als Freigabenachweis verwenden.
