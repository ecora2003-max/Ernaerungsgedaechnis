# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Dieses Dokument ist der Einstieg nach einer Unterbrechung.

## Auftrag und verbindliche Grundlage

Die vorhandene Android-Demo schrittweise nach `product/Gesamtplanung-2026-10-30.txt` weiterentwickeln. Die Spezifikation ist vollständig im Repository gespeichert. Kapitel 30 beschreibt die Reihenfolge; zunächst das lokale Room-/SQLite-Fundament, keine vollständige Umsetzung aller Module gleichzeitig. Quellcode, Tests und Übergabe in relevanten Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort einen ehrlichen Zwischenstand sichern.

## Bereits verifiziert vor diesem Meilenstein

- Kotlin 2.0.21, Compose, AGP 8.7.3, Gradle 8.9; 33 Demoansichten + Mehr.
- GitHub Actions funktioniert auch privat; Build, Lint und bisherige Tests bei `f2a7639` erfolgreich.
- APK wird vor Lint/Tests hochgeladen; Artefakte einen Tag verfügbar.
- Repository wurde in `ecora2003-max/Ernaerungsgedaechnis` umbenannt; alter Remote leitet weiter.

## Aktueller Meilenstein: lokales Datenfundament (in Arbeit)

1. Room-Schema für Produktidentitäten/-versionen, Aliase, Lagerorte/Chargen/Bewegungen, Vorgänge, Rezepte, Verzehr, Tagesstatus, Planung, Belege/Preise, Körper/Training.
2. Transaktionsschicht mit wiederholbaren Vorgangs-IDs, Konfliktprüfung und Schutz vor negativen Beständen.
3. Persistenten, deutlich isolierten lokalen Testbereich in der bestehenden Navigation anbieten; keine stillschweigende Übernahme der bisherigen Fixtures.
4. Beziehungen, Rollback, Idempotenz, Dateipersistenz und unveränderte Historie automatisiert testen; Schema exportieren, APK/Lint/Tests ausführen.

Implementierungs- und Prüfergebnisse werden vor dem nächsten Meilenstein hier eingetragen. Die Punkte oben sind noch keine Fertigmeldung.

## Folgende Phasen

- Tatsächliche Ereignisse und vollständige Buchungsoberflächen, Korrektur/Gegenbuchung, Umlagerung und Meal Prep.
- Ernährung/Rezepte und lückenbewusste Tagesbilanz.
- Verknüpfte Planung, Bedarf, Einkauf, Preise/Budget.
- Barcode/OCR/Produktabfrage; versionierter JSON-Austausch ohne Buchungsberechtigung.
- Körper/Training, belastbare Auswertungen; verschlüsselte Backups und kontrollierte Migration.

## Grenzen

Die aktuelle Demo ist kein produktiver Ersatz der Sheets-Datei. Keine echten Daten übernehmen. Keine verpflichtende KI-API, kein eigener Server. Ein APK-Build ersetzt keinen fachlichen Test und keinen Test auf einem echten Android-Gerät.
