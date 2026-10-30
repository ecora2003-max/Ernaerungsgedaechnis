# Entwicklungsstand / Übergabe

Stand: 30.10.2026. Einstieg nach einer Unterbrechung: zuerst dieses Dokument und `AGENTS.md` lesen.

## Auftrag und Grenzen

Die vorhandene Android-App schrittweise nach `product/Gesamtplanung-2026-10-30.txt` (PDF daneben) weiterentwickeln. Fachregeln sind verbindlich; technische Umsetzung bleibt flexibel. In sinnvollen, geprüften Meilensteinen auf GitHub sichern. Bei Kontingentwarnung sofort Code und ehrliche Übergabe sichern, keinen großen neuen Build beginnen. Prüfen statt nur Testdateien anlegen; Aufwand auf fachlich riskante Regeln konzentrieren.

Demo-/Testdaten bleiben isoliert. Sheets ist bis zu einer gesondert freigegebenen Migration produktiv verbindlich. Keine neue Sheets-Datenbank, kein eigener Server, keine verpflichtende kostenpflichtige KI-API. GitHub speichert Code und Dokumentation, niemals persönliche App-Daten, Datenbankdateien oder Geheimnisse. Cloud-Umgebung nicht eigenständig veröffentlichen.

## Aktueller Meilenstein: bestätigte Ist-Zuordnung und Restbedarf (0.6)

Gesicherter Stand auf `development/plan-execution` und `main`, Version **0.6.0-execution**, versionCode 6, Schema **4** mit 28 Tabellen. Vollständig lokal und im frischen GitHub-Actions-Lauf geprüft; weiterhin isolierter Testbetrieb.

- `plan_positions` gibt Planabsichten eine dauerhafte Identität. Neue Planfassungen behalten diese für unverändert fortgeführte Positionen; aktuelle Zeilen-IDs und Koch-/Portionsbezüge werden weiterhin neu angelegt. Artikel/Rezept/Planart/Einheit/Kochbezug nicht unter derselben Absicht austauschen. Termin-/Mengenänderung bei aktiven Ist-Zuordnungen braucht ausdrückliche Übernahme.
- `plan_executions` ist eine unveränderliche Zuordnungs-/Rücknahmekette je tatsächlichem Vorgang mit Vorgangs-ID, Revision und Vorgänger. Ein echtes Ereignis höchstens einer Position, mehrere echte Teilereignisse je Position. Alte Retry-Schlüssel dürfen keine zurückgenommene Zuordnung reaktivieren; globale Snapshots mit Historie/Stornos erkennen gleiche-Uhrzeit-Zustandsfolgen und veraltete Bestätigungen.
- `ExecutionLedger` leitet bekannte ausgeführte Mengen, unbekannte Beiträge und Rest aus den tatsächlichen Originalereignissen ab. Kein zweites Mengenregister. Stornos zählen nicht mehr als Ausführung. Zurücknehmen einer Zuordnung storniert keine echte Buchung. Abhängige Portionszuordnungen vor der Herstellungszuordnung zurücknehmen.
- `PlanningReader` rechnet nur noch offene Soll-Mengen. Wirklich vorhandene Portionsbestände zugeordneter Herstellungsansätze teilen denselben Bestandsledger wie direkte Produktpläne; erst danach bedingte geplante Restportionen. Bereits gekochte Zutaten und bereits gegessene Portionen werden nicht nochmals verlangt. Tatsächlicher Verzehr bleibt bei später geändertem Herstellungsbezug ein Verzehr; Herkunftskonflikt sichtbar halten.
- Lokale Planungsoberfläche: bestehende Ist-Buchungen bewusst zuordnen, abweichenden Termin/Teil-/Mehr-/unbekannte Menge ausdrücklich annehmen, Zuordnung mit Grund zurücknehmen. Auch belegte entfernte/frühere Positionen bleiben zur Rücknahme erreichbar. Formularmengen bleiben bis Speichern Text, damit Dezimal-/leere Eingaben möglich bleiben.

Abschließender vollständiger lokaler Lauf: **103 Tests bestanden, 0 Fehlschläge/Fehler/Skips**, alle 13 aktuellen XML-Dateien ausgewertet (12:34:26–12:34:43 UTC). APK-Build/Lint erfolgreich nach 56 Sekunden bei vorbereiteten Abhängigkeiten, zehn Bibliotheks-Updatehinweise/keine Lint-Fehler. Schemaexporte 1/2/3 unverändert; neue Schema-4-Datei geprüft. APK-Metadaten 0.6.0-execution/code 6, minSdk 26/targetSdk 35, v2-Signatur gültig. Keine Assertion deaktiviert.

16 Ausführungs-Architekturtests, drei Dateimigrationen und acht Bedienprüfungen tatsächlich ausgeführt. Auf 320×640 dp/Schriftfaktor 1,3 Teilverzehr, bestätigte Mengenänderung über neue Planfassung und reine Zuordnungsrücknahme geprüft; Ist-Bestand und Verzehr bleiben erhalten. Reale Teilherstellung und gegessene Portionen über getrennte UI-Zuordnung abgeglichen. Den frischen Ausschnitt mit 50 g Ist / 300 g neuem Plan / 250 g Rest visuell geprüft. Kein Geräte-/Emulatortest. Details: `TEST_REPORT_EXECUTION.md`, Bedienung/Rechenregeln: `PLAN_EXECUTION.md`.

Geprüfter App-Code auf `development/plan-execution` und `main`: `a66a4ead514d82d64d38fd3aa63e628535e56838`. Frischer [Actions-Lauf 41057076313](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41057076313) auf genau dieser SHA erfolgreich, JDK 17, Build-Job 9 Minuten 37 Sekunden. APK, Schema-Abgleich, Lint, aktualisierte Actions und alle **103 Tests** bestanden. Alle 13 heruntergeladenen aktuellen XML-Dateien ausgewertet (12:48:45–12:49:34 UTC): 0 Fehlschläge/Fehler/Skips. Lint elf Warnungen zu Bibliotheksversionen/`OldTargetApi`, keine Fehler. Der folgende Abschlusscommit ergänzt nur Dokumentation.

[APK-Artefakt 0.6](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41057076313/artifacts/12977696884), Aufbewahrung ein Tag. Heruntergeladen und Version 0.6.0-execution/code 6, minSdk 26/targetSdk 35, v2-Signatur mit einem Signierer geprüft. Debug-Signierung, kein Geräte-/Emulatortest. Nach Ablauf vorhandenen Workflow manuell neu starten.

GitHub-Workflow für denselben ohnehin nötigen Build aktualisiert: verifizierte Commit-Pins für checkout v7.0.1, setup-java v6.1.0, setup-android v4.0.4, upload-artifact v7.0.2, jeweils Node 24. YAML und tatsächliche Action-Inputs vorab geprüft, frischer kompletter Lauf und beide Artefakt-Downloads erfolgreich. CI-Log bestätigt erstmalige Speicherung von Gradle-Wrapper und Abhängigkeitscache; Geschwindigkeit künftiger Cache-Treffer noch nicht gemessen. Kein zusätzlicher Cache-Messlauf. Standard-Linux-Runner, 20 Minuten und Artefaktaufbewahrung ein Tag bleiben bestehen.

Weiterhin isolierter Testbetrieb. Kein Geräte-/Emulatortest. Eine Ist-Buchung wird noch nicht auf mehrere Planpositionen aufgeteilt. Keine Preis-/Packungs-/Budgetlogik oder automatische Ausführung. Überfällige offene Termine bewusst neu planen, nicht mit heutigem Bestand rückrechnen. Geplante Restportionen sind bedingt und ohne Sicherheitsfreigabe. Sehr große Historien nicht lastgetestet; globale Snapshotänderungen können konservativ auch unbeteiligte Bestätigungen veralten lassen.

## Bisherige Ernährung und Buchungen

Der abgeschlossene Ernährungsmeilenstein 0.4 bleibt erhalten: konkrete Artikelversionen, tatsächliche Zutaten/Ausbeute, getrennte Nährstofflücken, Tagesbestätigung mit Snapshot und Statushistorie, alte Produkt-Retry-Fingerprints. Bedienung/Fachregeln in `NUTRITION.md`, Architektur in `LOCAL_DATABASE.md`. Historische Prüfberichte unten; kein alter Lauf ist ein Nachweis für 0.6.

## Migration und Wiederaufnahme

Schemaexporte 1/2/3 unverändert. App registriert Migrationen 1→2→3→4 ohne destruktiven Fallback. Schema 4 ergänzt `plan_positions`, `planned_entries.positionId` und `plan_executions`. Jede alte Eintragszeile erhält eine eigene Identität; Zusammenhänge zwischen früher kopierten Planversionen und Ausführung nicht erraten. Alte Plan-/Produkt-/Buchungs-Retry-Schlüssel bleiben kompatibel.

Drei echte Dateitests aus veröffentlichtem Schema 1, 2 und 3, jeweils alle 22/25/26 alten Tabellen mit Daten und Vergleich sämtlicher alter Spalten/Zeilen, Fremdschlüssel, alte Retry-Schlüssel, neue Vorgänge und Wiederöffnung. Schema-3-Test enthält zwei ähnliche historische Planfassungen: getrennte Positionen, keinerlei automatische Ist-Zuordnung, alter Plan-Retry unverändert. Nach Veröffentlichung von Schema 4 neue Strukturänderungen nur als Schema 5+ mit Erhaltungsmigration.

Lokale Werkzeuge (außerhalb des Repositories): SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper:

```sh
python /workspace/tooling/run-gradle.py :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Der Helper verwendet den geerbten Proxy nur für Build-/Testprozesse und `-PtestHome=/workspace/tooling/test-home`. TLS-/Checksum-Prüfungen bleiben aktiv. Falls diese Werkzeuge nach Wiederaufnahme fehlen, normale Android-Studio-/Gradle-Einrichtung oder vorhandenen Actions-Workflow verwenden. JDK 17+, SDK 35, Build Tools 34.0.0, Gradle 8.9/AGP 8.7.3/Kotlin 2.0.21/Room 2.7.2. Keine Zugangsdaten in Skripte übernehmen.

## Vor Produktivbetrieb zu schließen

Die bestehende konservative Bestandsrückdatierungssperre kann bestandsgebundene Vergangenheitskorrekturen nach einem heutigen Storno verhindern. Der neue direkte Storno-/Neuerfassungsweg ist für solche mehrtägigen Ersatzbuchungen nicht vollständig. Kein falsches tatsächliches Datum empfehlen. Vor Produktivbetrieb eine atomare Korrektur mit erhaltener Tageszuordnung und nachvollziehbarer Bestandswirkung entwerfen/testen; vorhandene historische Referenzen nicht überschreiben. Ohne Lagerbezug bleiben vergangene Verzehre normal erfassbar. Erläuterung in `NUTRITION.md`.

## Nächste Entwicklungsschritte

Offene Restbedarfe in eine überprüfbare Einkaufsliste überführen: bestätigte nutzbare Packungsbasis (Abtropf-/Stück-/Packungsinhalt nicht verwechseln), versionsbezogene Preisbeobachtungen mit Quelle/Gültigkeit und Budgetunsicherheit. Einkaufspackungen nur bei bekannten Größen runden. Abhaken ist kein Einkauf/Zugang. Echte Einkäufe, Kosten und Zugänge müssen als eigene bestätigte Ereignisse zusammenpassen, keine zweite schreibbare Datenwahrheit.

Dafür die vorhandenen Preis-/Bon-/Budgetmodelle fachlich prüfen; vorbereitete Tabellen sind keine fertigen Module. Bestehende 1-/7-/56-Tage-Planansichten, Restbedarfe, unbekannte Mengen, Zutaten/Portionen und konkurrierende Verwendung weiter berücksichtigen. Übernahme persönlicher Zielwerte, große Datenimporte oder reale Sheets-Daten weiterhin gesondert freigeben.

Später passende Haupt-Demoansichten schrittweise anschließen, Belegerkennung/Aliase, validierten JSON-Austausch, Körper/Training, Backups, stabile Release-Signierung und produktive Migration. Bestehende Grenze vergangener bestandsgebundener Ersatzkorrekturen vor Produktivbetrieb lösen. Quelle/IDs/Historie bewahren, keine fiktiven Tatsachen oder stillen Sicherheitsfreigaben.

## Historie

Planung 0.5.0: `TEST_REPORT_PLANNING.md`, geprüfter App-Code `62370b1`, erfolgreicher CI-Lauf 41051110227 mit 84 Tests.

Ernährung 0.4.0: `TEST_REPORT_NUTRITION.md`, geprüfter App-Code `4a79515`, erfolgreicher CI-Lauf 41045875849 mit 69 Tests.

Buchungen/Meal Prep 0.3.0: `TEST_REPORT_TRANSACTIONS.md`, Code `68c80ba`, erfolgreicher CI-Lauf 41039703367 mit 54 Tests.

Erstes Datenfundament 0.2.0: `TEST_REPORT_FOUNDATION.md`, Code `39ea5ad`, erfolgreicher CI-Lauf 41032992779 mit 36 Tests. Die damaligen APK-Artefakte sind zeitlich begrenzt; der Workflow kann erneut ausgeführt werden. Für neue Versionen ausschließlich neue Prüfberichte als Freigabenachweis verwenden.
