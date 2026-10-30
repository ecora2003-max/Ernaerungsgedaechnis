# Prüfnachweis: bestätigte Ist-Zuordnung und Restbedarf

Stand 30.10.2026, Version 0.6.0-execution/code 6, Schema 4. Bedienung und Regeln: `PLAN_EXECUTION.md`. Weiterhin ausschließlich isolierte Testdaten.

## Tatsächlich ausgeführter lokaler Abschlusslauf

`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, Gradle 8.9, JDK 21/Bytecode-Ziel 17, SDK 35/Build Tools 34.0.0. `BUILD SUCCESSFUL` nach **56 Sekunden** mit vorbereiteten Abhängigkeiten. Geerbter Buildproxy und beschreibbares Testverzeichnis, TLS-/Checksum-Prüfung aktiv.

Alle 13 aktuellen JUnit-XML-Dateien ausgewertet, Zeitstempel 30.10.2026 12:34:26–12:34:43 UTC. Keine alten Zwischenlauf-Ergebnisse als Abschlussnachweis verwendet; auch die letzte Entwurfsanzeige-Korrektur ist in diesem Stand kompiliert.

| Suite | Tests | Fehlschläge | Fehler | Skips |
| --- | ---: | ---: | ---: | ---: |
| PlanExecutionTest | 16 | 0 | 0 | 0 |
| PlanningDatabaseTest | 12 | 0 | 0 | 0 |
| DatabaseMigrationTest | 3 | 0 | 0 | 0 |
| LocalDataScreenTest | 8 | 0 | 0 | 0 |
| LocalDatabaseTest | 17 | 0 | 0 | 0 |
| StockOperationsTest | 8 | 0 | 0 | 0 |
| ProductionTest | 6 | 0 | 0 | 0 |
| NutritionDatabaseTest | 10 | 0 | 0 | 0 |
| NutritionMathTest | 4 | 0 | 0 | 0 |
| AmountsTest | 3 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **103** | **0** | **0** | **0** |

Lint erfolgreich, zehn Bibliotheks-Updatehinweise, keine Fehler/Fatal-Befunde. Neue Schema-4-Datei exportiert; veröffentlichte Schemaexporte 1/2/3 unverändert. Keine Assertion entfernt oder deaktiviert.

Lokale APK-Metadaten geprüft: Paket `de.ernaehrung.demo`, Version `0.6.0-execution`, versionCode 6, minSdk 26/targetSdk 35. `apksigner verify --verbose` erfolgreich, v2 mit einem Signierer. Debug-Signierung, keine stabile Release-Signierung.

## Geprüfte fachliche und gespeicherte Ergebnisse

| Fall | Konkreter Nachweis |
| --- | --- |
| Teilverzehr | Plan 200 g, Ist 50 g aus 200 g: Bestand 150 g, nach Zuordnung Planrest 150 g, durch Restbestand gedeckt statt 50 g künstlicher Fehlmenge. Derselbe Verzehr, dieselben Bewegungen und exakt derselbe Ernährungssnapshot wie vor Zuordnung. Zuordnungsvorgang erzeugt keine Bewegung. |
| Einmalige Verwendung / Nebenläufigkeit | Ein Ist-Ereignis kann nicht zwei Planpositionen zugleich erfüllen. Zwei parallele Zuordnungen: genau eine erfolgreich, insgesamt 50 g statt 100 g angerechnet. |
| Rücknahme / Retry / neue Zuordnung | Rücknahme lässt echten Verzehr bestehen. Alter Zuordnungs-Retry reaktiviert nichts. Ausdrückliche Neuzuordnung zu anderer Position funktioniert; ein veralteter Rücknahmeaufruf kann diese nicht wieder löschen. Drei erhaltene Kettenschritte und nur ein echter Verzehr. |
| Ungültige Angaben | Unbestätigt, ohne Grund, nicht ausdrücklich akzeptierte Teilmenge, falscher Artikel, Produktanlage statt Ist-Ereignis, veraltete Planfassung und bereits stornierter Verzehr werden ohne zugehörigen Schreibvorgang abgewiesen. |
| Unbekannte Menge | Zugeordneter tatsächlicher Verzehr mit unbekannter Menge macht den Planrest ungeklärt; die Fehlmengenübersicht zeigt offene Anforderungen. Nach Rücknahme wieder 200 g offener Plan, ursprüngliche Verzehrmenge weiterhin NULL. |
| Dauerhafte Position / Übernahme | Ist 250 g auf Plan 200 g → Rest 0. Neue Fassung mit 300 g an anderem Termin verlangt ausdrückliche Übernahme; danach Rest 50 g, dieselbe Positionsidentität, neue Zeilen-ID. Kein anderer Artikel unter derselben Absicht, kein Stehlen oder Duplizieren von Positionen. Verwerfen/Wiederaktivieren erhält die wirklichen 250 g. |
| Entfernte Position | Entfernen löscht keine Zuordnung. Belegte frühere Position bleibt zur Rücknahme erreichbar; alte Buchung bleibt bis zur ausdrücklichen Rücknahme belegt und kann danach sauber neu zugeordnet werden. |
| Snapshot bei gleicher Uhrzeit | Zuordnen → zurücknehmen verändert den Snapshot trotz identischer Uhrzeit und gleicher Restmenge. Alter Snapshot wird abgewiesen, alter Retry reaktiviert keine Zuordnung. |
| Teilweise Herstellung | Soll sechs Portionen aus 300 g, Ist vier aus 200 g: nur noch 100 g Zutaten für zwei offene Soll-Portionen. Spätere sechs Portionen sind vier tatsächliche plus zwei bedingt geplante. Zweiter Ist-Ansatz mit zwei Portionen aus 100 g → alle sechs real zugeordnet, kein weiterer Zutatenbedarf, Rohbestand 0, kein Verzehr. |
| Tatsächlich gegessene Portionen / Planfassung | Sechs tatsächlich hergestellt, zwei gegessen und zugeordnet: Planmahlzeit erfüllt, Fertigbestand 4, Rohbestand 0. Neue Fassung behält beide Positionsidentitäten und korrekten Kochbezug trotz neuer Zeilen-IDs. Ernährungssnapshot bleibt durch Zuordnung unverändert. |
| Tatsächlicher Verzehrstorno | Derselbe Zweier-Verzehr storniert: Fertigbestand wieder 6, offene Planmahlzeit wieder 2, zwei reale Portionen zugeordnet, Ernährung ohne den stornierten Verzehr. Zuordnungshistorie erhalten und tatsächlicher Storno sichtbar. |
| Tatsächlicher Herstellungsstorno | Sechser-Ansatz storniert: Rohbestand wieder 300 g, Fertigbestand 0, Kochplan wieder sechs offen und tatsächliche Rohwaren decken den Bedarf. Keine Phantomportionen; spätere Portionen sind wieder nur geplant. |
| Abhängige Zuordnungen | Zugeordneter Portionsverzehr verhindert Rücknahme seiner Herstellungszuordnung. Erst beide Zuordnungen in richtiger Reihenfolge zurücknehmen. Danach weiterhin vier echte Portionen, Rohbestand 0 und ein echter Verzehr; keine automatische Ist-Gegenbuchung. |
| Konkreter Herstellungsansatz | Gleiche Rezeptversion zweimal gekocht: Verzehr aus nicht zugeordnetem Ansatz wird abgewiesen. Umlagerung einer Portion des zugeordneten Ansatzes behält den passenden Ursprung und ist zulässig zuordenbar. |
| Vergangene Teilherstellung | Gestern vier von sechs Soll-Portionen tatsächlich gekocht und zugeordnet: heutige/zukünftige Zweier-Mahlzeit nutzt genau zwei vorhandene Portionen. Keine unbekannten oder nur gedachten Portionen nötig, tatsächlicher Fertigbestand bleibt 4 und Rohbestand 100 g. |
| Echter SQLite-Abbruch | Trigger bricht Einfügen der Zuordnung ab: Auditvorgang und Zuordnung vollständig zurückgerollt, Snapshot unverändert. Nach Fehlerbehebung gleicher Schlüssel erfolgreich. |
| Reaktivität / Dateiwiederöffnung | Zuordnung senkt Fehlmenge reaktiv von 50 g auf 0. Echte Datei schließen/öffnen erhält Planbericht, Zuordnungssnapshot, Kandidaten und 150 g Ist-Restbestand; Schema 4. |
| Migration 1/2/3→4 | Drei echte Dateien aus veröffentlichten Schemaexporten, alle 22/25/26 alten Tabellen befüllt, sämtliche alten Spalten/Zeilen unverändert, Fremdschlüssel gültig. Zwei ähnliche alte Planfassungen erhalten getrennte Positionsidentitäten und keine automatische Ausführung. Festes altes Plan-Retry-Golden `c8bfa1…da4b` bleibt gültig; Buchungs-Retry-Schlüssel ebenso. |

Die bisherigen Rollback-, Nebenläufigkeits-, Mengen-, Versions-, Nährwert- und Stornoprüfungen bleiben enthalten; ihre aktuellen Ergebnisse stehen in der Tabelle oben. Historische Details in den jeweiligen früheren Prüfberichten.

## Tatsächliche Bedienprüfungen

- Auf 320×640 dp mit Schriftfaktor 1,3 wird eine tatsächlich gebuchte 50-g-Mahlzeit einem 200-g-Plan zugeordnet. UI zeigt Rest 150 g. Änderung auf 300 g ohne Übernahme wird abgewiesen; mit ausdrücklicher Übernahme bleibt dieselbe Position, Rest 250 g. Rücknahme nur der Zuordnung lässt Ist-Bestand 150 g und den einen echten Verzehr bestehen. Zwei erhaltene Zuordnungsschritte.
- Tatsächlicher Vierer-Kochansatz sowie zwei schon gegessene Portionen werden über die Oberfläche getrennt ihren Sechser-Planpositionen zugeordnet. Restkochbedarf zwei Portionen/100 g, Restmahlzeit vier Portionen aus zwei echten plus zwei bedingt geplanten Portionen. Genau zwei ursprüngliche Chargen, 100 g Rohbestand, zwei Fertigportionen und ein echter Verzehr bleiben erhalten.
- Die sechs bisherigen Bedienprüfungen (Katalog, Umlagerung/Storno, tatsächliche Herstellung/Ernährung, Tageslücken/-bestätigung, gemeinsame Planansichten und verknüpfte Soll-Portionen) sind weiterhin bestanden.

Die neuen Tests verwenden normale Formularaktionen und prüfen danach die gespeicherten Daten, keine simulierten Geschäftsbuchungen. Den frischen Ausschnitt `local-execution-partial-large-text.png` visuell geprüft: bestätigte 50 g, neuer Plan 300 g, Rest 250 g und daraus 100 g Fehlmenge lesbar. Nicht alle bisherigen Demo-Renderbilder erneut vollständig visuell geprüft.

## Workflow und frischer CI-Nachweis

Actions anhand ihrer öffentlichen Release-Tags und tatsächlichen `action.yml`-Runtimes geprüft und per Commit gepinnt (alle Node 24). Gradle-Abhängigkeitscache eingerichtet; erster Lauf wärmt ihn auf. Standard-Linux-Runner, JDK 17, 20-Minuten-Grenze und Artefaktaufbewahrung ein Tag bleiben erhalten. Kein zusätzlicher Lauf nur zur Cache-Messung geplant.

Vor Abschluss den neuen Actions-Lauf auf dem gesicherten App-Code verfolgen, tatsächliche aktuelle XML-Zahlen und Code-SHA vergleichen sowie GitHub-APK herunterladen und prüfen. Bis dahin ist ausschließlich der lokale Abschluss nachgewiesen. Frühere CI-Läufe sind kein Nachweis für 0.6.

## Grenzen

Robolectric/Room/SQLite, kein Smartphone oder Emulator. Eine Ist-Buchung wird nicht auf mehrere Positionen verteilt. Keine automatische Zuordnung/Ausführung, Preis-/Packungs-/Budgetlogik, sichere Kapazitäts-/Haltbarkeitsoptimierung oder Lastprüfung sehr großer Historien. Globale Zuordnungssnapshots können konservativ erneute Bestätigung verlangen. Frühere Grenzen vergangener Bestandskorrekturen, Backups, stabile Release-Signierung und produktive Migration sind weiterhin offen.
