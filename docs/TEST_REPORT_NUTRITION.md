# Prüfnachweis: Nährwertrechnung und Ernährung

Stand: 30.10.2026. Version 0.4.0-nutrition, versionCode 4, Schema 2 unverändert. Fachliche Erklärung und Bedienung: `NUTRITION.md`.

## Tatsächlich ausgeführter lokaler Abschlusslauf

`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, Gradle 8.9, JDK 21/Bytecode-Ziel 17, SDK 35/Build Tools 34.0.0. `BUILD SUCCESSFUL` nach 32 Sekunden bei vorbereiteten Abhängigkeiten. Geerbter Buildproxy und beschreibbares Testverzeichnis, keine deaktivierte TLS-/Checksum-Prüfung.

Nach Abschluss die elf aktuellen XML-Dateien ausgewertet, nicht Resultate vorheriger gezielter Läufe:

| Suite | Tests | Fehlschläge | Fehler | Skips |
| --- | ---: | ---: | ---: | ---: |
| NutritionMathTest | 4 | 0 | 0 | 0 |
| NutritionDatabaseTest | 10 | 0 | 0 | 0 |
| LocalDataScreenTest | 4 | 0 | 0 | 0 |
| LocalDatabaseTest | 17 | 0 | 0 | 0 |
| StockOperationsTest | 8 | 0 | 0 | 0 |
| ProductionTest | 6 | 0 | 0 | 0 |
| DatabaseMigrationTest | 1 | 0 | 0 | 0 |
| AmountsTest | 3 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **69** | **0** | **0** | **0** |

APK-Build und Lint erfolgreich, zehn Hinweise auf neuere Bibliotheksversionen, keine Lint-Fehler. Bestehende Icon-Deprecation-Warnungen sind unverändert. Schemaexporte 1 und 2 sind unverändert; kein ungetesteter neuer Schemawechsel. Keine Assertion entfernt oder Prüfung deaktiviert.

## Geprüfte fachliche Ergebnisse

| Fall | An berechneten und gespeicherten Ergebnissen geprüft |
| --- | --- |
| Mengenbasis | 50 g eines Artikels mit 125 kcal/13 g Protein je 100 g ergeben 62,5 kcal und 6,5 g Protein. 200 ml skalieren eine ml-Basis korrekt; halbe Portionen eine Portionsbasis. Masse-/Volumenverwechslung ergibt eine Lücke, keine erfundene Dichte. |
| Zusatznährstoffe | 50 g mit 5 g Ballaststoffen und 0,5 µg B12 je 100 g ergeben 2,5 g und 0,25 µg. Nullwerte, fehlende Felder und zusätzliche Versionswerte bleiben unterscheidbar. |
| NULL / bekannte Null | Energie 0 bleibt ein vollständig bekannter Nullbeitrag. Fehlende Energie bleibt NULL. Gemeinsame Summe behält bekannte 0 plus Lückenstatus. Keine Verzehre erzeugen keine automatische Nullbilanz. |
| Teilsummen | Bekannter Verzehr, Artikel mit fehlender Energie und Verzehr ohne bekannte Menge: Energie-Teilsumme 125 kcal mit zwei Lücken; Protein 19,5 g mit einer Lücke. Es entstehen keine fiktiven Lagerbewegungen. |
| Historische Artikelversion | Neue Energieangabe von 200 statt 125 kcal ändert den alten 50-g-Verzehr und seinen Bestätigungssnapshot nicht. |
| Soll vs. tatsächlich gekocht | Rezeptvorschau: 300 g Linsen/500 g Tomaten, 6 Portionen → 195 kcal je Soll-Portion. Tatsächlicher Ansatz: 150 g/500 g, 5 Portionen → 132 kcal und 8,6 g Protein je tatsächlicher Portion. Die Rechnung liest die Ist-Bewegungen, nicht die Soll-Zutaten. |
| Umlagerung und spätere Änderungen | Zwei gegessene tatsächliche Portionen ergeben 264 kcal, auch nach Umlagerung einer Portion sowie späteren Artikel-/Rezeptänderungen. Ursprüngliche Linsen bleiben bei 150 g, Tomaten bei 0; keine weitere Rohwarenentnahme durch die Rechnung oder das Portionenessen. |
| Weiterverarbeitung | Zwei hergestellte Portionen gehen in einen neuen Ansatz ein. Bekannte Energie bleibt 170 kcal je gegessener neuer Portion mit weiterhin einer Lücke aus fehlender Tomatenenergie. Protein bleibt vollständig bekannt bei 79/6 g. Keine doppelte Rohwarenentnahme. |
| Tatsächliche Ernährung | Stornierte Mahlzeiten bleiben historisch gespeichert, fehlen aber in der aktiven Bilanz. Pläne, Zugänge und Entsorgung erhöhen keine Ernährungswerte. Kochen erzeugt noch keinen Verzehr. |
| Tagesbestätigung | Neuer Tag offen, neuer Verzehr teilweise. Unbestätigter oder veralteter Snapshot wird ohne Statusvorgang abgewiesen. Explizite Vollständigkeit darf trotz bekannter Datenlücken bestehen, ohne diese zu verstecken. Zusätzlicher Verzehr setzt teilweise; alter Retry bestätigt den geänderten Tag nicht erneut. |
| Gleiche Uhrzeit / erneute Statusfolge | Vollständig → offen → vollständig mit identischem Zeitwert erzeugt verschiedene Snapshots. Alter Snapshot wird abgewiesen. Zukünftiger Tag kann nicht als tatsächlich vollständig bestätigt werden. |
| Reaktive Ansicht | Laufende Tagesansicht aktualisiert sich nach neuem tatsächlichem Verzehr von leer auf 62,5 kcal. |
| Wiederöffnung | Echte Datenbankdatei schließen/neu öffnen: abgeleitete 132 kcal, historische Referenzen und bestätigter Tagesstand unverändert; weiterhin Schema 2. |
| Rückwärtskompatibilität | Produkt-Fingerprint aus 0.3 gegen festes Golden-Result geprüft; Wiederholung bleibt dieselbe Version. Neue Zusatznährstoffe werden versioniert, geänderte Inhalte mit gleicher ID abgewiesen. |
| Alte Daten | Migrationstest erhält alle alten Spalten/Zeilen aus 22 Tabellen. Alter bestätigter Tag bleibt bestätigt; fehlende Energie bleibt unbekannt, altes Protein wird korrekt als 6,75 g ausgewertet. |

Die bisherigen Architekturprüfungen bleiben enthalten: konkurrierende Buchungen, echte SQLite-Fehlerinjektion mit vollständigem Rollback, keine negativen Bestände, erhaltende Migration und Idempotenz. Details des vorherigen Meilensteins: `TEST_REPORT_TRANSACTIONS.md`.

## Tatsächliche Bedienprüfungen

- Produktformular: unbekannte Energie, eingegebenes Protein 13,5 g, bekannte Fett-Null und Calcium 120 mg werden korrekt gespeichert. Wiederholung bleibt gesperrt.
- Küche: Rezeptvorschau zeigt 170 kcal je Portion. Bestätigtes Kochen erzeugt sechs Portionen ohne Ernährung. Erst Verzehr über die neue Maske zeigt 170 kcal/12 g Protein, reduziert die Fertigcharge auf fünf Portionen und belässt Rohwaren bei 0.
- Ernährung auf 320×640 dp mit Schriftfaktor 1,3: 50 g Tofu zeigen 62,5 kcal. Weitere Mahlzeit ohne Menge erzeugt eine erkennbare Teilsumme. Explizite Tagesbestätigung lässt diese Teilsumme sichtbar. Storno des bekannten Eintrags belässt den unbekannten Verzehr, zeigt Unbekannt statt 0 und setzt den Tag auf teilweise. Keine Lagercharge durch diese Eingaben.
- Bereits vorhandene Umlagerungs-/Storno-Navigation und beide Bestandsseiten bleiben geprüft.

Bestätigungsdialoge besitzen einen begrenzten scrollbaren Textbereich, damit ihre Schaltflächen auch bei großer Schrift erreichbar bleiben. Bereichs-/Quellauswahl im lokalen Bereich bricht um, statt horizontal außerhalb des sichtbaren Ausschnitts zu liegen. Die zwei erzeugten Ernährungsansicht-Ausschnitte wurden visuell nachgeprüft; nicht alle übrigen Renderbilder wurden vollständig erneut visuell geprüft.

## Grenzen und CI-Abschluss

Android-Prüfungen laufen unter Robolectric/Room/SQLite, nicht auf einem Smartphone oder Emulator. Keine vollständige klinische Plausibilitätsprüfung der Nutzer-/Etikettangaben, keine bestätigten persönlichen Ziele, keine automatische Kalibrierung. Die bisherigen 33 Fachansichten außerhalb des lokalen Testbereichs bleiben Demoansichten. Sehr große Datenmengen und tiefe Produktionsketten sind nicht lastgetestet. Backups, stabile Release-Signierung und freigegebene produktive Migration fehlen noch.

Der frische GitHub-Actions-Lauf auf JDK 17, seine geprüfte Code-SHA, aktuellen XML-Zahlen und APK-Link werden nach Abschluss in `DEVELOPMENT.md` festgehalten. Der lokale Nachweis allein behauptet keinen erfolgreichen frischen CI-Lauf.
