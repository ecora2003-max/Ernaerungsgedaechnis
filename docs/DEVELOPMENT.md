# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Einstieg nach einer Unterbrechung: zuerst dieses Dokument und `AGENTS.md` lesen.

## Auftrag und Grenzen

Die vorhandene Android-App schrittweise nach `product/Gesamtplanung-2026-10-30.txt` (PDF daneben) weiterentwickeln. Fachregeln sind verbindlich; technische Umsetzung bleibt flexibel. In sinnvollen, geprüften Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort Code und ehrliche Übergabe sichern, keinen großen neuen Build beginnen. Prüfen statt nur Testdateien anlegen; Aufwand auf fachlich riskante Regeln konzentrieren.

Demo-/Testdaten bleiben isoliert. Sheets ist bis zu einer gesondert freigegebenen Migration produktiv verbindlich. Keine neue Sheets-Datenbank, kein eigener Server, keine verpflichtende kostenpflichtige KI-API. GitHub speichert Code und Dokumentation, niemals persönliche App-Daten, Datenbankdateien oder Geheimnisse. Cloud-Umgebung nicht eigenständig veröffentlichen.

**Neue dauerhafte Nutzerpräferenz:** APKs nur für bewusst ausgewählte Meilensteine, nicht nach jedem „weiter“. Automatische Push-/PR-Läufe prüfen weiterhin alle Tests, Lint und versioniertes Schema, ohne APK-Verpackung/Upload. Manuell unter **Android checks and optional APK → Run workflow → `build_apk=true`** eine APK anfordern; Standard ist false. Lokale Routineprüfungen ohne `assembleDebug`. Tests tatsächlich ausführen und aktuelle XML-Ergebnisse prüfen; nie für APK-Ersparnis abschwächen. App-Code/Ressourcen werden für Tests weiterhin kompiliert. Die lokale APK 0.7 wurde bereits vor dieser Präferenz gebaut/geprüft; keine zusätzliche 0.7-Download-APK erzeugen.

## Aktueller Meilenstein: Einkaufsvorschläge und Budgetrahmen (0.7)

Gesicherter Stand auf `development/shopping-proposals` und `main`, Version **0.7.0-shopping**, versionCode 7, Schema **5** mit 29 Tabellen. Lokal und im frischen GitHub-Prüflauf vollständig geprüft. Isolierter Testbetrieb, keine persönlichen Daten oder produktiven Sheets-Buchungen.

- `ShoppingReader` leitet Angebote/Kaufmengen aus offenem `PlanningReader`-Restbedarf in derselben Transaktion ab, einschließlich tatsächlichem Bestand, Ist-Erfüllung und konkurrierenden Plänen. Keine automatische Auswahl eines größeren/nominal billigeren Angebots.
- Bestätigte nutzbare Menge je ganzer Kaufpackung, konkrete Artikelversion, Mengenbasisbeschreibung, EUR-Preis, Qualität und Gültigkeit zum Einkaufstag. Alte Mengen/Katalog-Packungsgrößen beweisen keine Kaufpackung. Fehlende Mengen/Preise/Währung/Gültigkeit bleiben ungeklärt. Bekannte Nullpreise bleiben 0. Ganze Packungen aufrunden, Überschuss zeigen, Mengen/Produkte mit BigInteger und Geld als ganze Cents.
- Preis- und Budgetfassungen append-only mit eindeutiger Vorgängerkette. Alte Bearbeitung/Retry/andere Artikelversionen bzw. Budgetzeiträume werden geprüft. Eine ersetzte ausgewählte Preisfassung wird nicht automatisch durch die neue übernommen.
- `shopping_marks` hält nur manuelle Auswahl/Abhakstatus pro Artikelversion, Bedarfsausschnitt und Einkaufstag. Atomar/idempotent, Revisionskette und aktueller Snapshot. Datenänderungen und gleiche-Uhrzeit-ABA veralten alte Häkchen; alte Retries reaktivieren nichts. Andere reine Listenmarkierungen veralten bestehende Häkchen nicht. Eine Markierung erzeugt keinerlei Kauf, Beleg, Ausgabe, Zugang oder Verzehr; abgehakte Positionen bleiben im Bedarf und Kostentotal.
- Budgetvergleich verwendet genau eine ausgewählte aktuelle bestätigte EUR-Obergrenze am Einkaufstag; überlappende Budgets nicht addieren. Bekannte Kosten können deren Überschreitung belegen. Tatsächliche bezahlte Ausgaben/Vollständigkeit fehlen noch: **verbleibendes Budget immer ungeklärt**, kein erfundener Null-Ausgabenstand.
- Neuer lokaler Bereich **Einkauf** mit 1-/7-/56-Tage-Filtern, Einkaufstag, Preis-/Packungs-/Budgetformularen, Vorschau und gespeicherter Listenentscheidung. Bestehende 33 Haupt-Fachansichten bleiben Demoansichten. Noch keine vollständige Phase 4/Produktionsreife.

Lokaler Abschluss: **122 Tests bestanden, 0 Fehlschläge/Fehler/Skips**, alle 15 aktuellen XML-Dateien ausgewertet (13:25:39–13:25:58 UTC). APK-Build/Lint erfolgreich nach 1 Minute 20 Sekunden mit vorbereiteten Abhängigkeiten, zehn Bibliotheks-Updatehinweise/keine Lint-Fehler. Schemaexporte 1/2/3/4 unverändert. APK 0.7.0-shopping/code 7, minSdk 26/targetSdk 35, v2-Signatur gültig. Keine Assertion entfernt/deaktiviert.

12 neue Datenbank-/Integrationsfälle, vier Geld-/Rundungsfälle, vier Dateimigrationen und zehn Bedienprüfungen tatsächlich ausgeführt. Großer-Schrift-/Kleindisplay-Test: 350 g Fehlmenge → 2×200 g, 50 g Überschuss, 3,98 €; 3-€-Budget um 0,98 € überschritten; Abhaken ohne Kaufwirkung. Preisänderung erhält Historie und verlangt neue Auswahl. Reaktiver Ist-/Planabgleich und Dateiwiederöffnung geprüft. Die Migrationstests fanden einen falschen Selbstbezug beim Preistabellenumbau, korrigiert und alle vier Wege erhaltend bestanden. Frischen Listenausschnitt visuell geprüft. Kein Geräte-/Emulatortest. Details: `TEST_REPORT_SHOPPING.md`, Regeln/Bedienung: `SHOPPING.md`.

App-Code gesichert: `89d658c2835c82d77b88687ef2b9abc3f578cc91`. Workflow-/Regeländerung ohne App-/Schema-/Teständerung: `73f98c69d1726a26a6fd74b1c1e300d6fde58733`. Der frühere [APK-Lauf 41063947663](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41063947663) wurde auf Nutzerpräferenz bewusst abgebrochen, kein Prüfnachweis.

Frischer [automatischer Prüflauf 41064824153](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41064824153) auf genau der Workflow-SHA erfolgreich, JDK 17, Job 7 Minuten 37 Sekunden. Alle 15 heruntergeladenen aktuellen XML-Dateien (13:44:17–13:45:09 UTC) ausgewertet: **122 Tests, 0 Fehlschläge/Fehler/Skips**. Lint elf Bibliotheks-/`OldTargetApi`-Warnungen, keine Fehler; Schema-Abgleich erfolgreich. Beide APK-Schritte durch API als **skipped** bestätigt; genau ein Testbericht-Artefakt und kein APK-Artefakt. Gradle-Routine-Aufgabenplan ohne APK-Tasks und Workflow-Schaltbedingungen geprüft. Optionalen manuellen APK-Pfad nicht nochmals ausgeführt. Abschlusscommit ergänzt nur Dokumentation; für 0.7 keinen weiteren APK-Lauf auslösen.

## Bisherige Ernährung und Buchungen

Der abgeschlossene Ernährungsmeilenstein 0.4 bleibt erhalten: konkrete Artikelversionen, tatsächliche Zutaten/Ausbeute, getrennte Nährstofflücken, Tagesbestätigung mit Snapshot und Statushistorie, alte Produkt-Retry-Fingerprints. Bedienung/Fachregeln in `NUTRITION.md`, Architektur in `LOCAL_DATABASE.md`. Historische Prüfberichte unten; kein alter Lauf ist ein Nachweis für 0.7.

## Migration und Wiederaufnahme

Veröffentlichte Schemaexporte 1/2/3/4 unverändert; App registriert Migrationen 1→2→3→4→5 ohne destruktiven Fallback. Schema 5 macht Preismenge/-betrag nullable und ergänzt Packungs-/Gültigkeits-/Währungs-/Fassungsmetadaten, Budgetfassungen sowie `shopping_marks` (29 Tabellen). `MIGRATION_4_5` baut die Preistabelle erhaltend um: Selbst-FK auf den endgültigen Namen `price_observations` richten, nicht auf die temporäre Tabelle. Keine alten Mengen/Preise, IDs, Qualität oder Zuordnungen umdeuten; neue unbekannte Metadaten bleiben NULL.

Vier Dateitests aus den veröffentlichten Schemaexporten, alle 22/25/26/28 alten Tabellen befüllt und jede alte Spalte/Zeile verglichen, Fremdschlüssel und Wiederöffnung geprüft. Schema-4-Test erhält dauerhafte Position und tatsächliche Zuordnung über alte Planfassungen hinweg; alte Retry-Schlüssel unverändert. Nach Veröffentlichung von Schema 5 neue Strukturänderungen nur als Schema 6+ mit Erhaltungsmigration.

Lokale Werkzeuge (außerhalb des Repositories): SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper:

```sh
python /workspace/tooling/run-gradle.py --continue :app:lintDebug :app:testDebugUnitTest
```

Der Helper verwendet den geerbten Proxy nur für Build-/Testprozesse und `-PtestHome=/workspace/tooling/test-home`. TLS-/Checksum-Prüfungen bleiben aktiv. Falls diese Werkzeuge nach Wiederaufnahme fehlen, normale Android-Studio-/Gradle-Einrichtung oder vorhandenen Actions-Workflow verwenden. JDK 17+, SDK 35, Build Tools 34.0.0, Gradle 8.9/AGP 8.7.3/Kotlin 2.0.21/Room 2.7.2. Keine Zugangsdaten in Skripte übernehmen.

## Vor Produktivbetrieb zu schließen

Die bestehende konservative Bestandsrückdatierungssperre kann bestandsgebundene Vergangenheitskorrekturen nach einem heutigen Storno verhindern. Der neue direkte Storno-/Neuerfassungsweg ist für solche mehrtägigen Ersatzbuchungen nicht vollständig. Kein falsches tatsächliches Datum empfehlen. Vor Produktivbetrieb eine atomare Korrektur mit erhaltener Tageszuordnung und nachvollziehbarer Bestandswirkung entwerfen/testen; vorhandene historische Referenzen nicht überschreiben. Ohne Lagerbezug bleiben vergangene Verzehre normal erfassbar. Erläuterung in `NUTRITION.md`.

## Nächste Entwicklungsschritte

Tatsächliche Einkaufs-/Ausgabenereignisse entwickeln: bezahlten Betrag, Datum und gegebenenfalls Beleg als bestätigtes Ereignis speichern. Genau eine Ausgabenwahrheit; weder Preisbeobachtung noch Einkaufslistenmarkierung noch zusätzliche Bestandsbewegung als weitere Ausgabe zählen. Ein Beleg darf nicht über Bonposition und Lagerzugang doppelt bezahlt erscheinen. Idempotenz, Teilbestätigung, stornierbare Historie und Quelle erhalten.

Bekannte Kauf-/Händler-/Preisangaben dürfen trotz unklarer Produktversion/Lagermenge gespeichert werden. Nur ausreichend geklärte ausdrücklich bestätigte Belegpositionen erzeugen Zugänge, höchstens einmal und atomar mit ihrem bestätigten Vorgang. Packungsbasis/Abtropf-/Stückgewicht weiterhin trennen. Budgetrest erst bei geeigneten bestätigten Ausgaben und nachvollziehbarer Erfassungsvollständigkeit ausweisen; keine automatische Null für fehlende Ausgaben. Überschätzte/unklare Posten sichtbar lassen.

Dafür vorhandene `receipts`/`receipt_lines` fachlich weiterentwickeln, nicht als bereits fertigen Zahlungsledger behandeln. Bestehende Restbedarfe/Markierungen müssen nach echten Einkäufen neu geprüft werden. Kein Barcode/OCR oder produktiver Import nötig, solange sichere manuelle Erfassung noch fehlt.

Später Haupt-Demoansichten schrittweise anschließen, Belegerkennung/Aliase, validierten JSON-Austausch, Körper/Training, Backups, stabile Release-Signierung und produktive Migration. Bestehende Grenze vergangener bestandsgebundener Ersatzkorrekturen vor Produktivbetrieb lösen. Persönliche Ziele, Datenimporte und reale Sheets-Daten weiterhin gesondert freigeben. Quelle/IDs/Historie bewahren, keine fiktiven Tatsachen oder stillen Sicherheitsfreigaben.

## Historie

Ist-Zuordnung 0.6.0: `TEST_REPORT_EXECUTION.md`, geprüfter App-Code `a66a4ea`, erfolgreicher CI-Lauf 41057076313 mit 103 Tests. Node-24-Actions und Gradle-Cache dort erfolgreich eingerichtet.

Planung 0.5.0: `TEST_REPORT_PLANNING.md`, geprüfter App-Code `62370b1`, erfolgreicher CI-Lauf 41051110227 mit 84 Tests.

Ernährung 0.4.0: `TEST_REPORT_NUTRITION.md`, geprüfter App-Code `4a79515`, erfolgreicher CI-Lauf 41045875849 mit 69 Tests.

Buchungen/Meal Prep 0.3.0: `TEST_REPORT_TRANSACTIONS.md`, Code `68c80ba`, erfolgreicher CI-Lauf 41039703367 mit 54 Tests.

Erstes Datenfundament 0.2.0: `TEST_REPORT_FOUNDATION.md`, Code `39ea5ad`, erfolgreicher CI-Lauf 41032992779 mit 36 Tests. Die damaligen APK-Artefakte sind zeitlich begrenzt; der Workflow kann erneut ausgeführt werden. Für neue Versionen ausschließlich neue Prüfberichte als Freigabenachweis verwenden.
