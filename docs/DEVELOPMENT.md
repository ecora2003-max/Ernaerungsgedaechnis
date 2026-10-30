# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Dieses Dokument ist der Einstieg nach einer Unterbrechung.

## Auftrag und verbindliche Grundlage

Die vorhandene Android-Demo schrittweise nach `product/Gesamtplanung-2026-10-30.txt` weiterentwickeln. Die Spezifikation ist vollständig im Repository gespeichert. Kapitel 30 beschreibt die Reihenfolge; zunächst das lokale Room-/SQLite-Fundament, keine vollständige Umsetzung aller Module gleichzeitig. Quellcode, Tests und Übergabe in relevanten Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort einen ehrlichen Zwischenstand sichern.

## Bereits verifiziert vor diesem Meilenstein

- Kotlin 2.0.21, Compose, AGP 8.7.3, Gradle 8.9; 33 Demoansichten + Mehr.
- GitHub Actions funktioniert auch privat; Build, Lint und bisherige Tests bei `f2a7639` erfolgreich.
- APK wird vor Lint/Tests hochgeladen; Artefakte einen Tag verfügbar.
- Repository wurde in `ecora2003-max/Ernaerungsgedaechnis` umbenannt; alter Remote leitet weiter.

## Aktueller Meilenstein: erstes lokales Datenfundament

**Fortsetzung in Arbeit: Buchungen und Meal Prep (0.3.0, Schema 2).** Implementiert: Umlagerung mit Herkunftsbezug, Entsorgung, begründete Istzählung mit Schutz gegen veraltete Formulare, verknüpfte vollständige Stornos, Rezeptversionen und atomare tatsächliche Herstellung. Erste 12 Fachtests und die separate Migration 1→2 erfolgreich ausgeführt. Der Migrationstest vergleicht alle bisherigen Daten aus 22 befüllten Tabellen und prüft alte Retry-Schlüssel sowie neue Buchungen nach Upgrade. Ergänzte Parallelitäts-/Chroniktests laufen; Oberflächenintegration und abschließender Build/Lint/Gesamttest/CI stehen noch aus. Dieser Absatz ist ein Zwischenstand, keine Fertigmeldung für 0.3.0.

Schema 1 unverändert lassen. Schema 2 ergänzt `batches.parentBatchId`, `stock_movements.occurredOn` sowie `operation_reversals`, `recipe_output_products`, `productions`. `FoodMigrations.MIGRATION_1_2` ist in der App registriert. Unbekannte alte Angaben bleiben NULL, keine destruktive Migration. Fachentscheidungen: Stornos entfernen keine Historie; aktive Verzehre schließen stornierte Vorgänge aus. Erst abhängige Ereignisse stornieren. Umlagerung übernimmt keine neue Freigabe automatisch; bei geänderten Bedingungen bleibt sie ungeklärt. Produktion benötigt ausdrücklich geprüfte Lagerbedingungen/Behälter/Platz plus Notiz; Abweichungen von Rezeptmengen/Ausbeute benötigen eine Begründung. Nährwerte fertiger Portionen bleiben bis zur fachlichen Berechnung unbekannt.

1. Room-Schema für Produktidentitäten/-versionen, Aliase, Lagerorte/Chargen/Bewegungen, Vorgänge, Rezepte, Verzehr, Tagesstatus, Planung, Belege/Preise, Körper/Training.
2. Transaktionsschicht mit wiederholbaren Vorgangs-IDs, Konfliktprüfung und Schutz vor negativen Beständen.
3. Persistenten, deutlich isolierten lokalen Testbereich in der bestehenden Navigation anbieten; keine stillschweigende Übernahme der bisherigen Fixtures.
4. Beziehungen, Rollback, Idempotenz, Dateipersistenz und unveränderte Historie automatisiert testen; Schema exportieren, APK/Lint/Tests ausführen.

Implementiert sind das Schema mit 22 Tabellen, die Transaktionsschicht für Produktversion/Lagerort/Zugang/Verzehr, der isolierte Testbereich auf M03 und fachliche Datenbank-/Mengen-/UI-Tests. Details und Bedienung: `LOCAL_DATABASE.md`.

Lokal verifiziert: APK-Build, Lint ohne Fehler und **35 ausgeführte Tests, alle erfolgreich, keine übersprungen**. Davon 16 Datenbank-, 2 Mengen-, 1 neuer UI-Test sowie 16 bestehende Demo-/Navigations-/Renderprüfungen. Danach wurde ein zusätzlicher Uhren-/Retry-Regressionsfall ergänzt: alle **17 Datenbanktests** im gezielten Nachlauf erfolgreich. Der abschließende vollständige CI-Lauf hat anschließend alle **36 Tests** ausgeführt und bestanden. Ein UI-Test wurde nach Diagnose der Test-Looper-Synchronisation korrigiert und anschließend im vollständigen Lauf erneut ausgeführt. Schema 1 ist exportiert und versioniert; keine destruktive Fallback-Migration. Detaillierte Ergebnisse: `TEST_REPORT_FOUNDATION.md`.

GitHub-Absicherung: Meilenstein auf `development/room-foundation` und `main` gesichert. Der erste CI-Lauf (`41032615862`) wurde für die ergänzte Retry-Korrektur bewusst abgebrochen; das ist kein erfolgreicher Testlauf.

**Abschließender frischer CI-Lauf erfolgreich:** [41032992779](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41032992779) für Code-Commit `39ea5ada7da979e00595a22dc21f9cc54a202436`. APK, exakter Schema-Abgleich, Lint und **36 Tests (0 Fehlschläge, 0 Fehler, 0 Skips)** bestanden. Die sechs aktuellen JUnit-XML-Dateien wurden heruntergeladen und nach Testzahlen und Zeitstempeln geprüft. Laufzeit 8 min 5 s auf frischem JDK-17-Runner; kein Geräte-/Emulatortest und keine produktive Migration.

[APK-Artefakt](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41032992779/artifacts/12966832971) (1 Tag verfügbar): Version 0.2.0-foundation, versionCode 2, minSdk 26, targetSdk 35; heruntergeladene APK mit aapt und apksigner erfolgreich überprüft. Nach Ablauf über Actions → Android demo APK → Run workflow neu bauen. Öffnen in der App: Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen.

Der Meilenstein ist damit abgeschlossen. Noch keine vollständige Produkt-App: die Folgemodule unten sind ausdrücklich offen. Ohne Backups und kontrollierte Migration keine persönlichen Daten übernehmen.

Lokale Cloud-Werkzeuge (nicht Teil des App-Repositories): SDK in `/workspace/tooling/android`, Gradle-Cache in `/workspace/tooling/gradle`, Helper `python /workspace/tooling/run-gradle.py :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`. Der Helper übergibt den geerbten Netzwerkproxy nur für den Buildprozess. SDK-Lizenz-/TLS-/Archivprüfung wurde nicht deaktiviert. Falls diese Dateien nach Wiederaufnahme fehlen, Android Studio oder den vorhandenen Actions-Workflow verwenden; keine Laufzeit-Zugangsdaten in Quellcode schreiben.

## Folgende Phasen

- Tatsächliche Ereignisse und vollständige Buchungsoberflächen, Korrektur/Gegenbuchung, Umlagerung und Meal Prep.
- Ernährung/Rezepte und lückenbewusste Tagesbilanz.
- Verknüpfte Planung, Bedarf, Einkauf, Preise/Budget.
- Barcode/OCR/Produktabfrage; versionierter JSON-Austausch ohne Buchungsberechtigung.
- Körper/Training, belastbare Auswertungen; verschlüsselte Backups und kontrollierte Migration.

## Konkreter nächster Entwicklungsschritt

Auf dieser Basis zuerst die Buchungsregeln für Umlagerung, Entsorgung und Gegenbuchung/Korrektur ergänzen: mehrteilige Bewegungen atomar, ursprüngliche Ereignisse unverändert, wiederholte Korrektur-IDs harmlos, keine negativen Quellbestände. Danach Rezeptversionen und tatsächliche Herstellung mit Rohwarenabgang/Fertigportionszugang und eigener Produktion-ID verbinden. Dafür sind erst Fachtests mit konkreten Soll-/Ist-Mengen zu schreiben und auszuführen; erst dann die bestehenden Bestands-/Rezeptansichten schrittweise auf lokale Daten umstellen. Herkunft, Mengenbasis (netto/abgetropft/roh/gegart) und Sicherheitsmetadaten weiter ausbauen. Keine bloße Umschaltung aller Demoansichten auf unfertige Datenlogik.

Die zusätzlichen Tabellen für Planung, Preise, Körper und Training sind ein Schemafundament, keine fertigen Module. Ihre jeweiligen Schreib-/Validierungsservices und Oberflächen sind noch ausstehend. Profil-/Zielhistorie folgt ebenfalls. Bei Strukturänderungen ab jetzt Schema 2 mit erhaltender Migration und Upgrade-Test verwenden, statt die veröffentlichte Version 1 umzuschreiben.

## Grenzen

Die aktuelle Demo ist kein produktiver Ersatz der Sheets-Datei. Keine echten Daten übernehmen. Keine verpflichtende KI-API, kein eigener Server. Ein APK-Build ersetzt keinen fachlichen Test und keinen Test auf einem echten Android-Gerät.
