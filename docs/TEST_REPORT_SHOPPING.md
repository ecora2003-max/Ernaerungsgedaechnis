# Prüfnachweis: Einkaufsvorschläge, Preisfassungen und Budgetrahmen

Stand 30.10.2026, Version 0.7.0-shopping/code 7, Schema 5. Bedienung/Fachregeln: `SHOPPING.md`. Ausschließlich isolierte Testdaten, keine produktive Migration.

## Tatsächlich ausgeführter lokaler Abschlusslauf

`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, Gradle 8.9, JDK 21/Bytecode-Ziel 17, SDK 35/Build Tools 34.0.0. `BUILD SUCCESSFUL` nach **1 Minute 20 Sekunden** mit vorbereiteten Abhängigkeiten. Buildproxy und beschreibbares Testverzeichnis, TLS-/Checksum-Prüfung aktiv.

Alle 15 aktuellen JUnit-XML-Dateien nach Abschluss ausgewertet, Zeitstempel 30.10.2026 13:25:39–13:25:58 UTC. Keine alten Zwischenläufe als Abschlussnachweis verwendet:

| Suite | Tests | Fehlschläge | Fehler | Skips |
| --- | ---: | ---: | ---: | ---: |
| ShoppingDatabaseTest | 12 | 0 | 0 | 0 |
| ShoppingMathTest | 4 | 0 | 0 | 0 |
| DatabaseMigrationTest | 4 | 0 | 0 | 0 |
| LocalDataScreenTest | 10 | 0 | 0 | 0 |
| LocalDatabaseTest | 17 | 0 | 0 | 0 |
| StockOperationsTest | 8 | 0 | 0 | 0 |
| ProductionTest | 6 | 0 | 0 | 0 |
| NutritionDatabaseTest | 10 | 0 | 0 | 0 |
| NutritionMathTest | 4 | 0 | 0 | 0 |
| PlanExecutionTest | 16 | 0 | 0 | 0 |
| PlanningDatabaseTest | 12 | 0 | 0 | 0 |
| AmountsTest | 3 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **122** | **0** | **0** | **0** |

Lint erfolgreich: zehn Bibliotheks-Updatehinweise, keine Fehler oder Fatal-Befunde. Neues Schema 5 exportiert, veröffentlichte Exporte 1/2/3/4 unverändert. Keine Assertion entfernt oder deaktiviert.

Lokale APK geprüft: `de.ernaehrung.demo`, Version `0.7.0-shopping`, versionCode 7, minSdk 26/targetSdk 35. `apksigner verify --verbose` erfolgreich, v2 mit einem Signierer. Debug-Signierung, keine stabile Release-Signierung.

## Konkrete berechnete und gespeicherte Ergebnisse

| Fall | Tatsächliches Ergebnis |
| --- | --- |
| Packungsrundung / Überschuss | 350 g Fehlmenge, 200 g nutzbare bestätigte Menge je Packung zu 1,99 €: zwei Packungen, 400 g Kaufmenge, 50 g Überschuss, 3,98 €. Bei 400 g genau zwei, bei 400,001 g drei Packungen. |
| Unbekannt gegenüber kostenlos | Unbekannter Preis bleibt NULL, bekannte 0,00 € bleibt eine bekannte Null und kann vollständige geschätzte Kosten 0 ergeben. Weitere unbekannte Bedarfsmenge lässt nur einen Packungsmindestbedarf und Teilkosten zu, keine vollständige Gesamtsumme. |
| Alte Preis-/Produktmengen | Vorhandene Produkt-Packungsgröße und alte bestätigte Preis-Mengenangabe ohne neuen Packungs-/Währungsnachweis liefern keine automatische Packungszahl oder aktuelle EUR-Kostensumme. |
| Gültigkeit / Datenqualität / Termin | Überschrittener oder ungeklärter Preiszeitraum und ungeprüfte Preisangabe lassen Kosten ungeklärt. Bestätigte Mengenbasis kann trotzdem zwei Packungen ergeben. Einkauf nach dem ersten Bedarfstermin bleibt ein Konflikt; Rückdatierung auf einen vergangenen Einkaufstag wird abgewiesen. |
| Preisgeschichte und bewusste Auswahl | Ersetzen der 200-g-/1,99-€-Fassung durch 600 g/2,99 € erhält beide Zeilen. Alte Auswahl/Markierung wird veraltet, neue Fassung nicht automatisch übernommen. Nach ausdrücklicher Wahl eine Packung, 250 g Überschuss, 2,99 €. Stale Bearbeitung abgewiesen, Retry unverändert. |
| Keine Buchungswirkung | Auswahl/Abhaken erzeugen keine Charge, Bewegung, Mahlzeit, Ausgabe oder Beleg. Ursprüngliche 350-g-Fehlmenge bleibt bestehen. Abgehakte Zeilen bleiben in der Schätzung. |
| Nebenläufigkeit / Retry / gleiche Uhrzeit | Zurückgenommene Markierung wird durch alten Retry nicht wieder aktiviert. Veralteter Snapshot wird abgewiesen. Zwei parallele Markierungsänderungen mit demselben Ausgangsstand: genau eine erfolgreich. Drei erhaltene Kettenschritte, keine stille Überschreibung. |
| Andere Position / zwischenzeitliche Änderung | Abhaken einer zweiten Position belässt die erste gültig. Plan 350→400→350 g bei gleicher Uhrzeit macht alte Markierungen trotzdem veraltet. Keine Reaktivierung durch wieder gleiche Mengen. |
| Budgetobergrenze, kein Restbudget | Bekannte Kosten 3,98 € gegenüber bestätigtem Budget 3,00 €: Überschreitung 0,98 €. Neue 5-€-Fassung ersetzt die alte für Vergleiche. 0-€-Budget unterscheidbar von unbekanntem Betrag; falscher Zeitraum nicht anrechenbar. Verfügbares Restbudget bleibt in allen Fällen NULL, da bezahlte Ausgaben nicht vollständig abgeglichen sind. |
| Falsche Version / ungültige Angaben | Preisfassung anderer Artikelversion nicht wählbar; Preisfamilie kann nicht in andere Artikelversion verschoben werden. Unbegründete Packungsbestätigung, unbekannter als geprüft bestätigter Preis, negative Beträge, zukünftige Beobachtung und unbekanntes als bestätigt markiertes Budget ohne Speicherung abgewiesen. |
| Geld und Zahlenbereich | Strikte EUR-Cents, keine Rundung von dritten Nachkommastellen, keine negativen/exponentiellen/ungültigen Geldstrings. Nicht darstellbare Kaufmenge/Kosten bleiben unbekannt, kein Überlauf und keine scheinbar kostenlose Gesamtsumme. |
| Echte SQLite-Fehler | Trigger brechen Preis-, Budget- und Markierungseinfügung jeweils nach dem Auditversuch ab: Auditvorgang und fachliche Zeile vollständig zurückgerollt. Erneut derselbe Schlüssel nach Behebung erfolgreich. Fehlerhafte Markierung verändert den Bericht/Snapshot nicht. |
| Reaktivität / Ist-Erfüllung / Wiederöffnung | Nach 50 g bestätigtem Zugang sinkt Fehlmenge 350→300 g; alte Markierung wird veraltet, trotz unverändert zwei Packungen. Tatsächlichen 350-g-Verzehr ohne Lagerbezug zuordnen: Bedarf verschwindet. Nur Zuordnung zurücknehmen: wieder 300 g Fehlmenge, keine reaktivierte alte Markierung, weiterhin genau ein echter Verzehr. Datei schließen/öffnen erhält denselben Bericht, Schema 5. |
| Migration 1/2/3/4→5 | Alle 22/25/26/28 alten Tabellen befüllt und jede alte Spalte/Zeile erhalten, Fremdschlüssel gültig. Schema-4-Fall erhält gleiche Positionsidentität über zwei Fassungen und bestehende 50-g-Ist-Zuordnung. Alte Packungsbestätigungen/Währung/Gültigkeit bleiben NULL; bekannte 200 g/2,49 € der alten Preiszeile unverändert. Frühere Produkt-/Plan-/Buchungs-Retry-Schlüssel funktionieren weiterhin. |

Die Migrationsprüfungen deckten zunächst einen auf den temporären statt endgültigen Tabellennamen zeigenden Preis-Selbstbezug auf. Der Tabellenumbau wurde korrigiert; alle vier erhaltenden Dateimigrationen bestehen anschließend einschließlich Schema-/Fremdschlüsselprüfung. Keine destruktive Umgehung.

## Tatsächliche Bedienprüfungen

- 320×640 dp/Schriftfaktor 1,3: Preis für 200 g/1,99 €, nutzbares Abtropfgewicht, ganze Kaufpackung, Bestätigungen und Gültigkeit über das Formular gespeichert. Danach 3-€-Budgetrahmen, konkrete Auswahl und Abhaken: zwei Packungen, 400 g Kaufmenge, 50 g Überschuss, 3,98 € Gesamtschätzung, 0,98 € Überschreitung. Kein Beleg, Lagerbestand oder Verzehr entstanden. Verbleibendes Budget bleibt ungeklärt.
- Über die Oberfläche eine gewählte und abgehakte Preisfassung durch 100 g/1,49 € ersetzen. Historie erhalten, alte Markierung veraltet, Gesamtsumme ungeklärt. Erst bewusste neue Auswahl ergibt vier Packungen/5,96 € und eine neue Listenfassung; weiterhin keine tatsächliche Einkaufswirkung.
- Die acht vorherigen Bedienprüfungen für Katalog, Lager/Storno, Küche/Ernährung, Planansichten und Ist-Zuordnung bleiben bestanden.

Frischen Ausschnitt `local-shopping-small-large-text.png` visuell geprüft: 350-g-Fehlmenge, ausdrückliche Abgrenzung „kein Kauf“, zwei Packungen, 400 g und 50 g Überschuss auf kleinem Display lesbar. Nicht alle alten Demoansichten erneut vollständig visuell geprüft.

## Frischer CI-Nachweis

Nach Sicherung auf Entwicklungsbranch und `main` den frischen Actions-Lauf auf genauer Code-SHA prüfen, aktuelle XML-Zahlen bestätigen und GitHub-APK herunterladen/prüfen. Bis dahin ist ausschließlich der oben beschriebene lokale Abschluss nachgewiesen. Kein früherer Lauf ist ein Nachweis für 0.7.

## Grenzen

Robolectric/Room/SQLite, kein Smartphone oder Emulator. Keine tatsächliche Einkaufs-/Ausgabenbuchung oder Ausgaben-Vollständigkeit; deshalb kein verfügbares Restbudget. Nur EUR, bewusst ausgewählte Preisfassungen, keine automatische Händler-/Packungsoptimierung oder Bestellung. Konservativer globaler Prüfstand kann Markierungen auch bei unbeteiligten Datenänderungen veralten lassen. Sehr große Historien nicht lastgetestet. Frühere Grenzen von Vergangenheitskorrekturen, Backups, Release-Signierung und produktiver Migration bestehen fort.
