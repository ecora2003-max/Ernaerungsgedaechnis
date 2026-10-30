# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Einstieg nach einer Unterbrechung: zuerst dieses Dokument und `AGENTS.md` lesen.

## Auftrag und Grenzen

Die vorhandene Android-App schrittweise nach `product/Gesamtplanung-2026-10-30.txt` (PDF daneben) weiterentwickeln. Fachregeln sind verbindlich; technische Umsetzung bleibt flexibel. In sinnvollen, geprüften Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort Code und ehrliche Übergabe sichern, keinen großen neuen Build beginnen. Prüfen statt nur Testdateien anlegen; Aufwand auf fachlich riskante Regeln konzentrieren.

Demo-/Testdaten bleiben isoliert. Sheets ist bis zu einer gesondert freigegebenen Migration produktiv verbindlich. Keine neue Sheets-Datenbank, kein eigener Server, keine verpflichtende kostenpflichtige KI-API. GitHub speichert Code und Dokumentation, niemals persönliche App-Daten, Datenbankdateien oder Geheimnisse. Cloud-Umgebung nicht eigenständig veröffentlichen.

## Aktueller Meilenstein: Buchungen und Meal Prep

**Neuer Meilenstein in Arbeit: Ernährung/Nährwerte (0.4).** Der Rechenkern und seine ersten 13 gezielten Tests wurden erfolgreich ausgeführt (`NutritionMathTest` 4 + `NutritionDatabaseTest` 9). Berechnung liest innerhalb einer Room-Transaktion unveränderliche Artikelversionen, tatsächliche Produktionsbewegungen und tatsächliche Ausbeute; keine Schemaänderung und kein zweiter gespeicherter Summenstand. Unbekannt/0/Teilsumme bleiben unterscheidbar pro Nährstoff. Verschachtelte/umgelagerte Fertigportionen, Stornos, Datenlücken und sichere Tagesbestätigungen mit Snapshotprüfung sind im Service implementiert. Oberflächenintegration und Gesamtprüfung stehen noch aus. Zusätzlich ergänzt, noch im nächsten Lauf zu prüfen: alter Produkt-Fingerprint und Auswertung migrierter v1-Nährwerte. Neues Produkt-API unterstützt zusätzliche Nährstoffe, behält alte Produkt-Retry-Hashes ohne diese Zusatzfelder bei. Dies ist noch keine Freigabe von 0.4; die unten dokumentierte Version 0.3 bleibt letzter vollständig geprüfter Hauptstand.

Version **0.3.0-transactions**, versionCode 3, Datenbankschema **2** (25 Tabellen).

- Umlagerung mit neuer Zielcharge, Quellbezug, erhaltenen Datums-/Versionsangaben und zwei atomaren Bewegungen. Bestehende Freigabe am Ziel nur nach ausdrücklicher Prüfung beibehalten; gesperrte/ungeklärte Ware wird nicht freigegeben.
- Entsorgung ohne Ernährungseintrag; begründete Istzählung als Differenzbewegung, auch bis Restbestand 0. Schutz gegen inzwischen veränderten Ausgangsbestand und gegen Wiederbelebung stornierter Chargen.
- Vollständiges, einmaliges Storno mit verknüpften Gegenbewegungen. Originale bleiben erhalten. Abhängige Buchungen zuerst stornieren. Stornierter Verzehr zählt nicht zur aktiven Ernährung; Tagesstatus wird TEILWEISE.
- Rezeptidentitäten/-versionen und tatsächliche Herstellung: alle Zutatenabgänge und der Fertigportionenzugang gemeinsam oder gar nicht. Kein Verzehr durch Kochen, kein erneuter Rohwarenabgang beim späteren Essen einer Portion. Andere Artikelversionen benötigen eine neue Rezeptversion; abweichende/ungeklärte Mengen oder Ausbeuten eine Begründung.
- Manuell bestätigte Lagerbedingungen/Behälter/Platz samt Notiz sind in dieser ersten Herstellungsoberfläche erforderlich. Das ersetzt keine automatische Kühlketten-/Kapazitätsprüfung. Fertigportionen-Nährwerte bleiben bis zur korrekten Berechnung unbekannt.
- Bedienung im vorhandenen M03-Testbereich: Katalog, Lager, Küche und Chronik. Bereichsauswahl umbrechend auf kleinen Displays; Stornovorschau enthält alle Gegenbewegungen des gewählten Vorgangs. Bisherige 33 Fachansichten bleiben unveränderliche Beispiele.

Bedienung und Architektur: `LOCAL_DATABASE.md`. Produktplan bleibt unverändert im Repository.

## Prüfergebnisse und Freigabestatus

Der vorherige Kontingent-Zwischenstand lag auf `development/stock-events`, Commit `6740d96` (53 Tests, ein offener UI-Test). Dieser Fehler ist behoben: horizontales Scrollen brachte den Chronik-Chip nicht in den vertikalen Ausschnitt. Eine umbrechende lokale Bereichsauswahl und eine zusätzliche Auswahl-Assertion lösen das Problem, ohne Bestands-/Stornoassertionen zu entfernen.

Eine weitere Regression wurde vor der Korrektur nachgewiesen: die Rückdatierungssperre ignorierte bekannte Schema-1-Verzehrdaten. Nun berücksichtigt die Leseabfrage vorhandene Verzehrdaten per COALESCE; alte Bewegungsfelder bleiben NULL. Der Migrationstest prüft die gesperrte Rückdatierung ohne Teilbuchung.

Vollständiger lokaler Lauf: **54 Tests bestanden, 0 Fehlschläge, 0 Fehler, 0 Skips**, APK-Build und Lint erfolgreich. Die aktuellen neun XML-Dateien wurden ausgewertet. Die vier Formularzustände in `KitchenEditor` sind als schreibgeschützte Listen typisiert (weiterhin nur Kopien ersetzen); der abschließende vollständige Lauf dieses Stands war nach 50 Sekunden erfolgreich. Lint meldet nur zehn Bibliotheks-Updatehinweise, keine Collection-State-Warnungen. Kein Smartphone-/Emulatortest. Detaillierter Prüfbericht: `TEST_REPORT_TRANSACTIONS.md`.

**Meilenstein abgeschlossen und auf main gesichert.** Geprüfter Code-Commit: `68c80ba911517b0a90a974af2834f198d13d47c7` (auch auf `development/stock-events`). Frischer [GitHub-Actions-Lauf 41039703367](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41039703367) auf JDK 17 erfolgreich nach 9 min 8 s: APK, Schema-Abgleich, Lint und **54 Tests, 0 Fehlschläge, 0 Fehler, 0 Skips**. Die neun heruntergeladenen aktuellen XML-Dateien wurden mit Code-SHA und Laufzeitstempeln abgeglichen. CI-Lint: 11 nicht fatale Hinweise, keine Fehler.

[APK 0.3.0 herunterladen](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41039703367/artifacts/12970112194) (ein Tag verfügbar). ZIP entpacken, `app-debug.apk` verwenden. APK-Metadaten mit aapt geprüft: versionCode 3, minSdk 26, targetSdk 35. `apksigner verify` erfolgreich. Debug-/Testversion, noch kein Produktivbetrieb und kein Gerätetest. Bei abgelaufenem Artefakt unter Actions → Android demo APK → Run workflow neu bauen.

Der frühere Kontingent-Zwischenstand und der offene UI-Test sind damit erledigt. Nicht erneut implementieren oder alte fehlgeschlagene Läufe als aktuellen Status behandeln. Nächster sinnvoller Entwicklungsschritt ist die unten beschriebene Nährwertrechnung/Tagesbilanz.

## Migration und Wiederaufnahme

Schema 1 unverändert erhalten. Schema 2 ergänzt `batches.parentBatchId`, `stock_movements.occurredOn`, `operation_reversals`, `recipe_output_products` und `productions`. `FoodMigrations.MIGRATION_1_2` ist im App-Builder registriert; keine destruktive Fallback-Migration. Der Dateitest befüllt alle 22 alten Tabellen, vergleicht alle bisherigen Spalten/Zeilen, prüft Fremdschlüssel, NULL gegenüber 0, alte Retry-Schlüssel, neue Vorgänge und Wiederöffnung. Ab Freigabe von Schema 2 sind Strukturänderungen Schema 3+ mit eigener Erhaltungsmigration.

Lokale Werkzeuge (außerhalb des Repositories): SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper:

```sh
python /workspace/tooling/run-gradle.py :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Der Helper verwendet den geerbten Proxy nur für Build-/Testprozesse und `-PtestHome=/workspace/tooling/test-home`. TLS-/Checksum-Prüfungen bleiben aktiv. Falls diese Werkzeuge nach Wiederaufnahme fehlen, normale Android-Studio-/Gradle-Einrichtung oder vorhandenen Actions-Workflow verwenden. JDK 17+, SDK 35, Build Tools 34.0.0, Gradle 8.9/AGP 8.7.3/Kotlin 2.0.21/Room 2.7.2. Keine Zugangsdaten in Skripte übernehmen.

## Nächster Meilenstein: Ernährung und berechnete Nährwerte

1. Nährwertrechnung pro tatsächlicher Menge und konkreter Artikelversion einführen; Masse/Volumen/Portion nicht vermischen. Unbekannte Werte nicht als 0 summieren, Teilabdeckung pro Nährstoff kenntlich machen.
2. Produktionsportionen aus tatsächlich entnommenen Versionen/Mengen und tatsächlicher Ausbeute berechnen. Historische Ansätze nach späteren Produkt-/Rezeptänderungen nicht umdeuten. Umgelagerte Portionen behalten Herkunft; Rohwaren nicht doppelt zählen. Gegebenenfalls Schema 3 mit geprüftem Upgrade erforderlich.
3. Aktive Verzehrhistorie und Tagesbilanz mit echten lokalen Daten anzeigen, Stornos herausrechnen und Vollständigkeit ausdrücklich bestätigen lassen. Restaurant-/unbekannte Mengen ohne fiktive Lagerentnahme unterstützen.
4. Fachtests mit konkreten erwarteten Nährstoffmengen, Datenlücken, Stornos und Produktionsketten ausführen; erst dann die passenden bisherigen Demoansichten schrittweise anschließen.

Weitere Phasen: Planung/Bedarf/Einkauf/Budget; Barcode/OCR/Produktrecherche; validierter JSON-Austausch; Profil-/Zielhistorie und Körper/Training; verschlüsselte Backups, stabile Release-Signierung und ausdrücklich bestätigte Migration realer Daten. Vorbereitete Tabellen sind keine fertig implementierten Module.

## Historie

Erstes Datenfundament 0.2.0: `TEST_REPORT_FOUNDATION.md`, Code `39ea5ad`, erfolgreicher CI-Lauf 41032992779 mit 36 Tests. Die damaligen APK-Artefakte sind zeitlich begrenzt; der Workflow kann erneut ausgeführt werden. Für neue Versionen ausschließlich neue Prüfberichte als Freigabenachweis verwenden.
