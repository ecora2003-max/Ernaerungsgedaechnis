# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Einstieg nach einer Unterbrechung: zuerst dieses Dokument und `AGENTS.md` lesen.

## Auftrag und Grenzen

Die vorhandene Android-App schrittweise nach `product/Gesamtplanung-2026-10-30.txt` (PDF daneben) weiterentwickeln. Fachregeln sind verbindlich; technische Umsetzung bleibt flexibel. In sinnvollen, geprüften Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort Code und ehrliche Übergabe sichern, keinen großen neuen Build beginnen. Prüfen statt nur Testdateien anlegen; Aufwand auf fachlich riskante Regeln konzentrieren.

Demo-/Testdaten bleiben isoliert. Sheets ist bis zu einer gesondert freigegebenen Migration produktiv verbindlich. Keine neue Sheets-Datenbank, kein eigener Server, keine verpflichtende kostenpflichtige KI-API. GitHub speichert Code und Dokumentation, niemals persönliche App-Daten, Datenbankdateien oder Geheimnisse. Cloud-Umgebung nicht eigenständig veröffentlichen.

**Neue dauerhafte Nutzerpräferenz:** APKs nur für bewusst ausgewählte Meilensteine, nicht nach jedem „weiter“. Automatische Push-/PR-Läufe prüfen weiterhin alle Tests, Lint und versioniertes Schema, ohne APK-Verpackung/Upload. Manuell unter **Android checks and optional APK → Run workflow → `build_apk=true`** eine APK anfordern; Standard ist false. Lokale Routineprüfungen ohne `assembleDebug`. Tests tatsächlich ausführen und aktuelle XML-Ergebnisse prüfen; nie für APK-Ersparnis abschwächen. App-Code/Ressourcen werden für Tests weiterhin kompiliert. Die lokale APK 0.7 wurde bereits vor dieser Präferenz gebaut/geprüft; keine zusätzliche 0.7-Download-APK erzeugen.

## Aktueller Meilenstein: tatsächliche Käufe und Ausgaben (0.8)

Version **0.8.0-purchases**, versionCode 8, Schema **6**, 32 Tabellen. Entwicklung auf `development/purchases-expenses`, geprüft lokal; Sicherung und frischer automatischer GitHub-Prüflauf folgen. Isolierter Testbetrieb, keine persönlichen Daten/Sheets, keine APK für diesen Meilenstein.

- `FoodStore` → `PurchaseOperations`: ausdrücklich bestätigten bezahlten Einkauf mit Händler, optional bekanntem Datum/EUR-Betrag und mehreren Original-/interpretierten Bonpositionen speichern. Fehlende Produktversion/Menge/Positionspreise blockieren bekannte Zahlungen nicht. Die einzige gezählte Zahlung ist `receipts.totalCents`, wirksam durch genau ein `purchase_events`-Ereignis; keine Summe aus Positionen, Lagerbewegungen, Preisen oder Häkchen. Leeres Datum bleibt unbekannt, bekannte Null bleibt 0. Originaltext wird nicht aus Interpretation erfunden.
- Position separat mit konkreter Artikelversion, nutzbarer Menge/Basisbestätigung, bekanntem Kaufdatum und bestätigtem Ort ins Lager buchen. Eigener atomarer Zugang mit `receipt_stock_bookings`, je Originalposition höchstens einmal einschließlich Stornohistorie. Keine automatische Lebensmittelfreigabe. Andere ungeklärte Positionen bleiben erhalten. Freigegebener Zugang reduziert offenen Restbedarf, kein fiktiver Planabschluss/Verzehr.
- `ExpenseReader`: konsistente tatsächliche Ausgaben, Teilsumme und Lücken, explizite Vollständigkeit für exakten Zeitraum bis heute (1–366 Tage). Undatierte Ereignisse bleiben überall prüfpflichtig. Alte Belege ohne Zahlungsereignis gelten nicht als bezahlt und verhindern falsche Vollständigkeit; tatsächliche Zahlung kann ausdrücklich bestätigt werden. Keine automatisch vollständige Nullsumme.
- `expense_status_events`: append-only Bestätigung/Öffnung mit Revision/Vorgänger/Snapshot. Neue Käufe/Stornos und gleiche-Uhrzeit-ABA veralten Bestätigungen; alte Retries reaktivieren nichts. Lagerbuchung allein verändert keine Zahlung und veraltet deren Vollständigkeitsnachweis nicht. Parallele Bestätigung versus Zahlung getestet.
- `ShoppingReader` verwendet denselben Ausgabenabgleich im Budgetzeitraum von Beginn bis min(heute, Ende). Erst bei expliziter Vollständigkeit verfügbarer Rest, gegebenenfalls negativ, sowie nach vollständig geschätztem offenem Einkauf verbleibender Spielraum. Bekannte Kosten/Ausgaben können auch vorher eine Überschreitung belegen. Keine Addition überlappender Budgets.
- Fehlerhafte Lagerzugänge erst mit ihren Abhängigkeiten stornieren, danach gegebenenfalls Kaufbuchung. Historie bleibt erhalten; keine echte Erstattung/Retourenbuchung. Noch keine ergänzenden Belegkorrekturfassungen, unbekannte Daten derzeit erforderlichenfalls durch Storno/korrekte Neuerfassung klären. Kein falsches Kaufdatum empfehlen.
- Neuer lokaler Bereich **Käufe**, bestehender **Einkauf** um tatsächlichen Budgetabgleich erweitert. Die 33 Haupt-Demoansichten bleiben getrennt. Regeln/Bedienung: `PURCHASES.md`, Prüfbericht: `TEST_REPORT_PURCHASES.md`.

Lokaler vollständiger Abschluss: **140 Tests, 0 Fehlschläge/Fehler/Skips**, alle 16 aktuellen XML-Berichte **14:02:57–14:03:14 UTC** ausgewertet. Lint/Tests erfolgreich in 1 Minute 8 Sekunden, zehn Bibliotheks-Updatehinweise, keine Lint-Fehler. 15 neue Datenbank-/Integrationsfälle, fünf erhaltende Dateimigrationen, zwölf Bedienprüfungen insgesamt. Echte SQLite-Abort-Trigger, konkurrierende Buchungen/Statusentscheidungen, Dateiwiederöffnung, Budget-/Restbedarfsreaktivität und gespeicherte Mengen/Zahlungen geprüft. 320×640 dp/Schriftfaktor 1,3 visuell geprüft. Erster gezielter Lauf fand eine inkonsistente Storno-Retry-Referenz, Implementation korrigiert und Erst-/Retry-Ergebnis ausdrücklich geprüft. Kein Test abgeschwächt, kein Geräte-/Emulatortest, keine APK gebaut.

## Bisherige Ernährung und Buchungen

Der abgeschlossene Ernährungsmeilenstein 0.4 bleibt erhalten: konkrete Artikelversionen, tatsächliche Zutaten/Ausbeute, getrennte Nährstofflücken, Tagesbestätigung mit Snapshot und Statushistorie, alte Produkt-Retry-Fingerprints. Bedienung/Fachregeln in `NUTRITION.md`, Architektur in `LOCAL_DATABASE.md`. Historische Prüfberichte unten; kein alter Lauf ist ein Nachweis für 0.8.

## Migration und Wiederaufnahme

Veröffentlichte Schemaexporte 1 bis 5 unverändert; Migrationen 1→2→3→4→5→6 registriert, kein destruktiver Fallback. Schema 6 ergänzt `purchase_events`, `receipt_stock_bookings`, `expense_status_events`; sämtliche alten Daten bleiben unverändert. Kein alter Beleg wird stillschweigend zur Zahlung. Fünf Dateitests aus veröffentlichten Exporten, alle 22/25/26/28/29 alten Tabellen befüllt und jede alte Spalte/Zeile verglichen, Fremdschlüssel/neue Buchungen/Wiederöffnung/alte Retry-Schlüssel geprüft. Schema-5-Packungs-/Budget-/Listenmetadaten einschließlich bekannter Null erhalten. Nach Veröffentlichung von Schema 6 Strukturänderungen nur mit Schema 7+ und Erhaltungsmigration.

Historische Migrationsfalle 4→5: Beim erhaltenden Preistabellenumbau Selbst-FK auf den endgültigen Namen `price_observations` richten, nicht auf die temporäre Tabelle. Keine alten Werte oder Zuordnungen umdeuten.

Lokale Werkzeuge (außerhalb des Repositories): SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper:

```sh
python /workspace/tooling/run-gradle.py --continue :app:lintDebug :app:testDebugUnitTest
```

Der Helper verwendet den geerbten Proxy nur für Build-/Testprozesse und `-PtestHome=/workspace/tooling/test-home`. TLS-/Checksum-Prüfungen bleiben aktiv. Falls diese Werkzeuge nach Wiederaufnahme fehlen, normale Android-Studio-/Gradle-Einrichtung oder vorhandenen Actions-Workflow verwenden. JDK 17+, SDK 35, Build Tools 34.0.0, Gradle 8.9/AGP 8.7.3/Kotlin 2.0.21/Room 2.7.2. Keine Zugangsdaten in Skripte übernehmen.

## Vor Produktivbetrieb zu schließen

Die bestehende konservative Bestandsrückdatierungssperre kann bestandsgebundene Vergangenheitskorrekturen nach einem heutigen Storno verhindern. Der neue direkte Storno-/Neuerfassungsweg ist für solche mehrtägigen Ersatzbuchungen nicht vollständig. Kein falsches tatsächliches Datum empfehlen. Vor Produktivbetrieb eine atomare Korrektur mit erhaltener Tageszuordnung und nachvollziehbarer Bestandswirkung entwerfen/testen; vorhandene historische Referenzen nicht überschreiben. Ohne Lagerbezug bleiben vergangene Verzehre normal erfassbar. Erläuterung in `NUTRITION.md`.

## Nächste Entwicklungsschritte

Belegkorrekturen und Ergänzungen fachlich entwerfen: bekannte Kaufangaben nachtragen/berichtigen, ohne ursprünglichen Beleg, Zahlung oder gebuchte Position zu überschreiben und ohne eine zweite Ausgabenwahrheit. Bisherige Grenze: unbekanntes Datum/Betrag lässt sich noch nicht bequem nachtragen, tatsächliche Erstattungen und unbezahlte Altbelege verwerfen fehlen. Keine falsche Zahlungsbestätigung/Datierung als Umgehung empfehlen. Versionierte Korrekturen, doppelte Positionszugänge, Idempotenz/Parallelität, Stornoabhängigkeiten, Budget-Snapshots und Migration gezielt prüfen.

Die konservative Sperre bestandsgebundener Vergangenheitskorrekturen vor Produktivbetrieb lösen. Dann Haupt-Demoansichten schrittweise anschließen, validierten JSON-Austausch/Sicherungen, stabile Release-Signierung, Geräteprüfung und produktive Migration vorbereiten. OCR/Barcode/Aliase, Körper/Training und Profil-/Zielhistorie folgen nach fachlichem Nutzen. Keine verpflichtende kostenpflichtige KI-API, kein eigener Server. Persönliche Ziele/Importe/reale Sheets-Daten weiterhin gesondert freigeben. Nutzerpräferenz bleibt: Tests/Lint/Schema automatisch, APKs nur bewusst ausgewählte Meilensteine.

## Historie

Einkaufsvorschläge 0.7: App-Code `89d658c`, Workflow-/Regeländerung `73f98c6`, Dokumentationsabschluss `cbe9b39`. Frischer automatischer Prüflauf [41064824153](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41064824153) erfolgreich: 122 Tests, beide APK-Schritte skipped, nur Testbericht-Artefakt. Details `TEST_REPORT_SHOPPING.md`. Frühere APK-Ausführung 41063947663 auf Nutzerpräferenz abgebrochen, kein Prüfnachweis. Für 0.7 keinen weiteren APK-Lauf auslösen.

Ist-Zuordnung 0.6.0: `TEST_REPORT_EXECUTION.md`, geprüfter App-Code `a66a4ea`, erfolgreicher CI-Lauf 41057076313 mit 103 Tests. Node-24-Actions und Gradle-Cache dort erfolgreich eingerichtet.

Planung 0.5.0: `TEST_REPORT_PLANNING.md`, geprüfter App-Code `62370b1`, erfolgreicher CI-Lauf 41051110227 mit 84 Tests.

Ernährung 0.4.0: `TEST_REPORT_NUTRITION.md`, geprüfter App-Code `4a79515`, erfolgreicher CI-Lauf 41045875849 mit 69 Tests.

Buchungen/Meal Prep 0.3.0: `TEST_REPORT_TRANSACTIONS.md`, Code `68c80ba`, erfolgreicher CI-Lauf 41039703367 mit 54 Tests.

Erstes Datenfundament 0.2.0: `TEST_REPORT_FOUNDATION.md`, Code `39ea5ad`, erfolgreicher CI-Lauf 41032992779 mit 36 Tests. Die damaligen APK-Artefakte sind zeitlich begrenzt; der Workflow kann erneut ausgeführt werden. Für neue Versionen ausschließlich neue Prüfberichte als Freigabenachweis verwenden.
