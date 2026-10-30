# Prüfnachweis: Buchungen und Meal Prep

Stand: 30.10.2026, Version 0.3.0-transactions (Code 3), Schema 2.

## Tatsächlich ausgeführter abschließender lokaler Lauf

`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, Gradle 8.9, JDK 21/Bytecode-Ziel 17, SDK 35, Build Tools 34.0.0. Cloud-Helper mit geerbtem Proxy und beschreibbarem Testverzeichnis; keine deaktivierte TLS-/Checksum-Prüfung. `BUILD SUCCESSFUL` nach 50 Sekunden bei bereits installierten Abhängigkeiten.

Nach Ende des vollständigen Laufs wurden die neun aktuellen JUnit-XML-Dateien ausgewertet; keine alten oder nur gezielten Ergebnisse als Gesamtresultat verwendet:

| Suite | Tests | Fehlschläge | Fehler | Skips |
| --- | ---: | ---: | ---: | ---: |
| LocalDatabaseTest | 17 | 0 | 0 | 0 |
| StockOperationsTest | 8 | 0 | 0 | 0 |
| ProductionTest | 6 | 0 | 0 | 0 |
| DatabaseMigrationTest | 1 | 0 | 0 | 0 |
| AmountsTest | 3 | 0 | 0 | 0 |
| LocalDataScreenTest | 3 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **54** | **0** | **0** | **0** |

APK gebaut, Lint ohne Fehler. Zehn Lint-Hinweise betreffen verfügbare neuere Bibliotheksversionen. Vier zusätzliche Hinweise auf veränderliche Collections in Compose-State wurden durch schreibgeschützte Listentypen mit kopierenden Änderungen behoben, nicht unterdrückt. Bestehende Icon-Deprecation-Warnungen bleiben; Bibliotheks-Upgrades gehören nicht zu diesem Meilenstein.

## Geprüfte gespeicherte Ergebnisse

| Fall | Durch Assertions geprüftes Ergebnis |
| --- | --- |
| Umlagerung | 200 g werden zu 125 g Quelle + 75 g Ziel, Summe unverändert. Herkunft, Version und Haltbarkeitsangaben bleiben erhalten. Wiederholung erzeugt keine zweite Zielcharge. Keine stille neue Freigabe. |
| Abgewiesene Umlagerung | Fehlendes/gleiches Ziellager, fehlende Bestätigung oder zu große Menge hinterlassen weder Zielcharge noch Bewegung. |
| Entsorgung / Istzählung | 200 g minus 30 g ergeben 170 g, ohne Verzehr. Bestandszählung auf 160 g erzeugt genau -10 g, ursprünglicher Zugang unverändert. Zählung auf 0 funktioniert; veralteter Ausgangsbestand wird abgewiesen. |
| Storno | Verknüpfte Gegenbewegungen genau einmal, Originale unverändert. Andere zweite Storno-ID sowie Storno eines Stornos werden abgewiesen. |
| Verzehrstorno | 50 g werden zurückgebucht, historischer Verzehr bleibt erhalten, aktiver Verzehr wird ausgeschlossen und Tagesstatus TEILWEISE. |
| Abhängigkeiten | Verzehr aus einer Umlagerungs-/Produktionscharge verhindert das Storno ihrer Herkunft. Erst Verzehr stornieren, dann Umlagerung/Herstellung; beide Seiten werden korrekt zurückgebucht. |
| Konkurrenz | Gleichzeitige Entsorgung und Umlagerung von jeweils 150 g aus 200 g: genau eine erfolgreich, Rest 50 g. Zwei konkurrierende Kochansätze aus denselben Zutaten: genau einer erfolgreich, nicht doppelte Portionen. |
| Chronik | Keine Rückdatierung vor bekannte spätere Chargenereignisse, keine Wiederbelebung stornierter Herkunft durch Istzählung. |
| Herstellung | 300 g Linsen + 500 g Tomaten werden zu 6 Portionen: Rohwaren 0/0, Portionen 6, keine Ernährung. Wiederholung bleibt derselbe Ansatz. Eine gegessene Portion reduziert nur Fertigware auf 5, Rohwaren bleiben 0/0. |
| Fehlende Zutat | Fehlender Restbestand im zweiten Transaktionsteil rollt erste Entnahme, Ausgabeprodukt/-version, Charge, Produktion und Vorgangs-ID zurück. |
| Echter Datenbankfehler | SQLite-Trigger bricht den Fertigwarenzugang nach Zutatenentnahmen ab. Danach ursprüngliche 300 g/500 g erhalten, kein Vorgang/Produktionsdatensatz/Teilbestand zurückgelassen. Nach Entfernen des Testfehlers ist derselbe Schlüssel erfolgreich wiederholbar. |
| Rezept-/Sicherheitsbezug | Ursprüngliche Rezeptversion bleibt an historischen Ansätzen erhalten. Fehlende Lagerprüfung/Notiz, unpassende oder doppelte Chargen, unbestätigte Herstellung oder unbegründete Ausbeuteabweichung werden abgewiesen. Begründete tatsächliche Ausbeute von 5 Portionen wird gespeichert. |
| Migration 1→2 | Echte Dateidatenbank aus dem veröffentlichten Schema 1 mit Datensätzen in allen 22 alten Tabellen. Jede alte Spalte/Zeile vorher/nachher identisch; Fremdschlüssel ohne Befund, NULL/0 unterschieden, alte Retry-Schlüssel funktionsfähig. Neue Buchungen und erneutes Öffnen funktionieren. |
| Legacy-Chronik | Zugang am 29.10., bereits erfasster Verzehr am 30.10.: neue rückdatierte Bewegung am 29.10. wird abgewiesen, ohne Teilbuchung. Alte neue-Datumsfelder bleiben NULL; bekannte Verzehrdaten werden nur gelesen. |
| Bedienoberflächen | Produktformular speichert und sperrt Wiederholung. Kochformular erzeugt 6 Portionen ohne Verzehr. Auf 320×640 dp: Umlagerung 125/75 und Storno 200/0, beide historischen Bewegungen erhalten. Chronik-Auswahl wird ausdrücklich geprüft. |

## Fehlernachweis und Korrektur

Der zunächst offene UI-Test wurde unverändert reproduziert: nach der Speicherung blieb Lager ausgewählt, der Chronik-Chip lag vertikal außerhalb des Ausschnitts. Die horizontale Scrollaktion allein machte ihn nicht anklickbar. Die lokale Bereichsauswahl umbrechend zu gestalten behebt die Erreichbarkeit. Bestands-/Stornoassertionen wurden nicht entfernt; eine zusätzliche Auswahl-Assertion schützt den Bereichswechsel.

Der ergänzte Migrations-Regressionsfall wurde vor der Korrektur ausgeführt und scheiterte konkret mit erwartetem bekannten Verzehrdatum 2026-10-30 gegenüber NULL. Die Datumsabfrage berücksichtigt jetzt per COALESCE das schon vorhandene Schema-1-Verzehrdatum. Danach bestanden gezielte Migration-/Mengentests und der gesamte Lauf.

## Grenzen und frischer CI-Nachweis

Alle Android-Tests hier sind Robolectric-/Room-/SQLite-Prüfungen, keine Smartphone- oder Emulatorläufe. Der Migrationstest prüft Dateipersistenz und einen echten Schemawechsel, keinen vollständigen Geräteabsturz. Die sichtbaren Ausschnitte der lokalen Herstellungsmaske und der kleinen Stornomaske wurden visuell nachgeprüft; die übrigen Renderbilder wurden nicht vollständig erneut visuell geprüft.

Portionsnährwerte sind bewusst noch unbekannt; es wird keine fertige Tagesbilanz oder vollständige Produktiv-App behauptet. Sichere Backups, stabile Release-Signierung und persönliche Datenmigration folgen später. Die bestehenden Fachansichten bleiben getrennte Demoansichten.

## Abschließender frischer GitHub-Lauf

[Lauf 41039703367](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41039703367), Code `68c80ba911517b0a90a974af2834f198d13d47c7`, JDK 17: erfolgreich nach 9 min 8 s. Die neun zugehörigen JUnit-XML-Dateien wurden heruntergeladen, ihre Zeitstempel gegen den Laufbeginn geprüft und die Code-SHA abgeglichen. Dieselben Tests wie in der obigen Tabelle: **54 ausgeführt, 0 Fehlschläge, 0 Fehler, 0 Skips**. Kein Null-Test- oder nur teilweiser Lauf.

APK-Erstellung und exakter Abgleich der generierten Room-Schemata mit den eingecheckten Dateien erfolgreich. CI-Lint ohne Fehler, 11 nicht fatale Hinweise. Die heruntergeladene APK ist Version 0.3.0-transactions, Code 3, minSdk 26, targetSdk 35; aapt-Metadaten und APK-v2-Signatur erfolgreich geprüft. [APK-Artefakt](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41039703367/artifacts/12970112194) und [Prüfberichte](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41039703367/artifacts/12969214561) werden einen Tag aufbewahrt.

Diese Freigabe gilt für den angegebenen Code-Commit. Die nachfolgende reine Dokumentationssicherung ändert die geprüfte Anwendung nicht. Neue Funktionsänderungen benötigen ihre eigenen relevanten Prüfungen.
