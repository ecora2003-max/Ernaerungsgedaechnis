# Prüfnachweis: gemeinsame Planung und Bedarf

Stand 30.10.2026, Version 0.5.0-planning/code 5, Schema 3. Bedienung und fachliche Grenzen: `PLANNING.md`.

## Tatsächlich ausgeführter lokaler Abschlusslauf

`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, Gradle 8.9, JDK 21/Bytecode-Ziel 17, SDK 35/Build Tools 34.0.0. `BUILD SUCCESSFUL` nach 38 Sekunden bei vorbereiteten Abhängigkeiten. Geerbter Buildproxy und beschreibbares Testverzeichnis; TLS-/Checksum-Prüfung bleiben aktiv.

Alle zwölf aktuellen JUnit-XML-Dateien des vollständigen Laufs ausgewertet, Zeitstempel 30.10.2026 11:49:07–11:49:24 UTC; keine alten Berichte gezielter Zwischenläufe:

| Suite | Tests | Fehlschläge | Fehler | Skips |
| --- | ---: | ---: | ---: | ---: |
| PlanningDatabaseTest | 12 | 0 | 0 | 0 |
| DatabaseMigrationTest | 2 | 0 | 0 | 0 |
| LocalDataScreenTest | 6 | 0 | 0 | 0 |
| LocalDatabaseTest | 17 | 0 | 0 | 0 |
| StockOperationsTest | 8 | 0 | 0 | 0 |
| ProductionTest | 6 | 0 | 0 | 0 |
| NutritionDatabaseTest | 10 | 0 | 0 | 0 |
| NutritionMathTest | 4 | 0 | 0 | 0 |
| AmountsTest | 3 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **84** | **0** | **0** | **0** |

Lint erfolgreich: zehn Hinweise zu Bibliotheksversionen, keine Fehler oder Fatal-Befunde. Neue Schema-3-Datei exportiert; veröffentlichte Schemaexporte 1 und 2 unverändert. Keine Assertion entfernt oder Prüfung deaktiviert.

Lokale APK geprüft: `de.ernaehrung.demo`, Version 0.5.0-planning, versionCode 5, minSdk 26/targetSdk 35. `apksigner verify --verbose` erfolgreich, v2 mit einem Signierer. Debug-Signierung, keine stabile Release-Signierung.

## Konkrete fachliche Ergebnisse

| Fall | Tatsächlich berechnete/gespeicherte Ergebnisse |
| --- | --- |
| Konkurrierende Pläne / Reihenfolge | 500 g aus zwei Chargen (200 g mit näherer Grenze, 300 g später) und zwei aktive Pläne zu je 300 g: erste Zuordnung 200+100 g, zweiter Tag 200 g, genau 100 g Fehlmenge. Die Tagesansicht nur für den zweiten Tag ergibt dieselbe Fehlmenge. Physische Bestände bleiben 200/300 g. |
| Zieltermin und Sicherheit | Am zukünftigen Termin werden überschrittenes Verbrauchsdatum, MHD mit Prüfbedarf, fehlende Datumsgrenze, gesperrte/ungeklärte Charge und unbestätigter Lagerort getrennt ausgeschlossen. Nur 100 g geeigneter Vorrat wird auf 200 g Bedarf angerechnet. Keine automatische MHD-Ungenießbarkeit. |
| Heute gegenüber Zukunft | Eine ausdrücklich freigegebene Charge ohne Haltbarkeitsdatum deckt 100 g heute, wird für weitere 100 g morgen nicht versprochen. |
| Kochen / spätere Portionen | Ein Ansatz mit 300 g Zutaten und sechs geplanten Portionen bei 200 g Vorrat: genau 100 g Zutaten fehlen. Spätere Verwendungen zu drei und vier Portionen teilen die Ausbeute als drei+drei und zeigen eine zusätzlich fehlende Portion. Keine neuen Chargen, Produktionen oder Verzehre. Im Ausschnitt nur für spätere Verzehrtage entsteht kein zweiter Zutatenbedarf. |
| Bereits tatsächliche Fertigportionen | Aus sechs tatsächlich hergestellten Portionen werden zwei geplant zugeordnet; sechs bleiben physisch vorhanden. Der bereits verbrauchte Rohwarenbestand bleibt 0 und wird nicht nochmals als Zutatenbedarf angelegt. Kochen/Planen erzeugt keine Ernährung. |
| Unbekannte Mengen | Direkte unklare Menge und Rezept mit unklarer Zutatenmenge bleiben zwei offene Anforderungen; bekannte Fehlmenge 0 ist ausdrücklich kein vollständiges Ergebnis. Bekannter Bedarf wird separat berechnet. Unpassende Einheiten, fehlende Zubereitungen, Portion vor Kochtermin, 0 und ungültige Zeiträume werden ohne Speicherung abgewiesen. |
| Neue Version / Verwerfen / Retry | Überarbeitung erzeugt zweite Fassung mit neuen Eintrags-IDs und auf diese Fassung umgebogenen Portionsbezügen. Verwerfen erzeugt dritte Fassung; sechs historische Eintragszeilen bleiben erhalten. Wiederholungen duplizieren nichts, alte Retry-Schlüssel reaktivieren keinen verworfenen Plan, veraltete oder geänderte Aufrufe werden abgewiesen. |
| Parallele Bearbeitung | Zwei gleichzeitige Änderungen derselben Ausgangsfassung: genau eine erfolgreich, die andere abgewiesen. Zwei Planversionen statt stiller Überschreibung. |
| Echter SQLite-Abbruch | Ein Trigger bricht beim zweiten Planeintrag ab. Neue Planidentität, Planfassung, alle Einträge und Vorgangs-ID vollständig zurückgerollt. Derselbe Schlüssel kann nach Behebung erfolgreich gespeichert werden. |
| Zahlenbereich / Rundung | Ein Drittel der kleinsten unterstützten Zutatmenge wird auf 0,001 Basiseinheit aufgerundet. Ein übergroß skalierter Ansatz bleibt unbekannt mit Mengenbereichshinweis, kein Überlauf und keine falsche Null. |
| Vergangenes / konkrete Versionen | Ein vergangener Kochplan ist kein Nachweis heutiger Portionen. Ein neuer Artikelstand nutzt nicht ungefragt den Vorrat einer alten Version. |
| Reaktivität / Neustart | Tatsächlich 50 g aus 200 g verbraucht: aktueller Restbestand 150 g, Fehlmenge für 300 g Plan steigt reaktiv von 100 auf 150 g. Datei schließen/öffnen erhält Planfassungen, berechneten Bericht und tatsächlichen Bestand. |
| Migration 1→2→3 und 2→3 | Echte Dateien aus veröffentlichten Schemaexporten: alle 22 bzw. 25 alten Tabellen mit Daten, sämtliche alten Spalten/Zeilen unverändert, Fremdschlüssel gültig, NULL/0 und alte Buchungs-Retry-Schlüssel erhalten. Neue Umlagerung/Wiederöffnung und eine neue Planfassung nach Migration funktionieren. Alte Planzeile bleibt Entwurf; neue bekommt Revision 2. |

## Bedienprüfungen und Abschlusslauf

Die neuen Bedienprüfungen speichern über die tatsächlichen Compose-Formulare und prüfen anschließend Room-Daten:

- 320×640 dp, Schriftfaktor 1,3: zwei geplante 300-g-Mahlzeiten bei 500 g Bestand. Wochen-, 56-Tage- und zweite Tagesansicht jeweils genau 100 g Fehlmenge. Überarbeiten auf eine Mahlzeit und anschließendes Verwerfen: drei Planfassungen, vier historische Einträge, Verwerfungsgrund „Termin entfällt“ gespeichert. Bestand weiter 500 g, keine Verzehre.
- Rezept mit sechs geplanten Portionen, anschließend zwei verknüpfte spätere Mahlzeiten, davon eine mit ungeklärter Menge: Zutatenbedarf genau 300 g statt erneut pro Portionsverwendung; bei 200 g Vorrat genau 100 g Fehlmenge und sichtbare Mengenlücke. Weiterhin nur die eine ursprüngliche physische Charge, keine Verzehre.
- Die bestehende Produkt-/Umlagerungs-/Herstellungs-/Ernährungsbedienung bleibt vollständig in der Sechser-Suite enthalten. Die neuen Tests prüfen normale Klicks und tatsächliche Speicherung, keine Umgehung der Geschäftsregeln.

Die Prüfung deckte einen veralteten Speicherzustand im später aufgeklappten Versionsbereich auf. Dieser liest jetzt die aktuelle Speichersperre und Vorgangs-ID über aktuelle Zustandsreferenzen. Derselbe Test ist unverändert hinsichtlich Erfolgs-/Datenbankanforderungen bestanden; die Schaltfläche wird nach „Neuen Vorgang beginnen“ aktiviert und schreibt den richtigen neuen Vorgang. Der abschließende vollständige Lauf enthält beide neuen Bedienprüfungen.

Den erzeugten Ausschnitt `local-planning-small-large-text.png` visuell geprüft: Datumsbereich, Abgrenzung offener Planannahmen und 100-g-Fehlmenge auf kleinem Display lesbar. Keine vollständige erneute visuelle Prüfung aller bisherigen Demoansichten.

## Frischer CI-Nachweis

App-Code auf `main` und `development/planning-demand`: `62370b11be8ea7c8a4445e70282317b76a00679c`. Frischer [GitHub-Actions-Lauf 41051110227](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41051110227) auf JDK 17, erfolgreich abgeschlossen am 30.10.2026 um 11:59:28 UTC. Build-Job 7 Minuten 41 Sekunden. APK-Build, Schema-Abgleich, Lint und alle Tests erfolgreich.

Das [aktuelle Testbericht-Artefakt](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41051110227/artifacts/12975520504) wurde heruntergeladen und ausgewertet. Alle zwölf JUnit-XML-Dateien liegen zeitlich innerhalb dieses Laufs (11:58:45–11:59:21 UTC); ihre Suite-Zahlen stimmen mit der lokalen Tabelle überein: **84 Tests, 0 Fehlschläge, 0 Fehler, 0 Skips**. Die Code-SHA des Laufs stimmt genau mit dem genannten geprüften App-Code überein. Lint-XML: elf Warnungen (`GradleDependency`, `OldTargetApi`), keine Fehler/Fatal-Befunde. Keine früheren Prüfberichte als neuen Nachweis verwendet.

Die [GitHub-APK](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41051110227/artifacts/12974073892) wurde heruntergeladen: Paket `de.ernaehrung.demo`, Version 0.5.0-planning, versionCode 5, minSdk 26/targetSdk 35. `apksigner verify --verbose` erfolgreich, v2 mit einem Signierer. Debug-APK, kein Geräte-/Emulatortest. Artefaktaufbewahrung ein Tag; danach vorhandenen Workflow manuell erneut starten.

Nach der geprüften Code-SHA wurden ausschließlich Abschlussdokumentation und Hinweise zur Wiederaufnahme ergänzt; keine App-/Schema-/Teständerung ohne neuen Prüfnachweis.

## Grenzen

Robolectric/Room/SQLite, kein Smartphone oder Emulator. Keine bestätigte Zuordnung tatsächlicher Durchführung zu Planeinträgen; offene Planannahmen müssen nach Durchführung bewusst angepasst werden. Bedingte geplante Portionen ersetzen keine tatsächliche Herstellung oder Lager-/Haltbarkeitsfreigabe. Keine Preis-/Packungs-/Budgetlogik, Kapazitätsoptimierung oder Nährwert-Zielplanung; keine Lastprüfung großer Datenmengen. Vergangenheitskorrekturen des Bestands, Backups, stabile Release-Signierung und produktive Migration bleiben offen. Dieser Meilenstein ist ein erster prüfbarer Teil der Planungsphase, keine vollständige Phase 4.
