# Prüfnachweis: tatsächliche Käufe und Ausgaben 0.8

30.10.2026. Version `0.8.0-purchases`, versionCode 8, Room-Schema 6 mit 32 Tabellen. Nur isolierte Testdaten, keine persönlichen Daten/Sheets. Keine APK für diesen Meilenstein erzeugt.

## Lokale Prüfung

Tatsächlich ausgeführt mit vorbereitetem SDK 35/Build Tools 34, JDK 21 (Java-17-Bytecode), Gradle 8.9:

```sh
python /workspace/tooling/run-gradle.py --continue :app:lintDebug :app:testDebugUnitTest
```

Ergebnis: **BUILD SUCCESSFUL**, 1 Minute 8 Sekunden. Alle **16 aktuellen XML-Berichte** aus `app/build/test-results/testDebugUnitTest/` ausgewertet, Zeitstempel **14:02:57–14:03:14 UTC**: **140 Tests, 0 Fehlschläge, 0 Fehler, 0 Skips**. Lint: zehn `GradleDependency`-Updatehinweise, keine Fehler. Alte Schemaexporte 1 bis 5 unverändert; Schema 6 neu versioniert. App-Code und Ressourcen wurden kompiliert, ohne `assembleDebug` oder herunterladbare APK.

| Suite | Tests |
| --- | ---: |
| Amounts | 3 |
| DatabaseMigration | 5 |
| DemoData | 4 |
| LocalDataScreen | 12 |
| LocalDatabase | 17 |
| Navigation | 10 |
| NutritionDatabase | 10 |
| NutritionMath | 4 |
| PlanExecution | 16 |
| PlanningDatabase | 12 |
| Production | 6 |
| PurchaseDatabase | 15 |
| ScreenRender | 2 |
| ShoppingDatabase | 12 |
| ShoppingMath | 4 |
| StockOperations | 8 |

15 neue Datenbank-/Integrationsfälle prüfen tatsächliche gespeicherte Resultate:

- Ein bezahlter Gesamtbetrag trotz ungeklärter Positionen; Positionssumme/Bestandszugang/Preisbeobachtung erzeugen keine weitere Ausgabe. Unbekannt, bekannte Null und fehlendes Datum bleiben verschieden; Originaltext und Quelle erhalten.
- Explizite Teilbuchung einer Position, positive nutzbare Menge, konkrete Version/Ort/Datum/Basis, keine automatische Freigabe. Alte/andere Schlüssel und Parallelaufrufe erzeugen keine weitere Buchung, auch nach Storno. Kein fiktiver Verzehr oder Planabschluss.
- Neue Zahlungen versus Vollständigkeitsbestätigung unter Parallelität, alte Snapshots, Öffnen, Retries und gleicher Uhrzeit zugeordnete Kauf-/Storno-ABA-Historie. Kein falscher vollständiger Nullstand; bekannte Ereignisse außerhalb des Zeitraums werden getrennt behandelt.
- Zahlung erst nach expliziter Bestätigung eines Altbelegs, keine Ableitung aus `Quality.CONFIRMED`. Zukunft/ungültige Daten, unbekannte Werte, Zahlenbereich und aggregierter Überlauf werden geprüft.
- Storno blockiert aktive Lagerzugänge sowie deren Verbrauch; nach geordneten Gegenbuchungen bleiben Zahlung/Positionen unverändert in Historie, Mengen zulässig und alte Bestätigungen ungültig. Wiederholtes Storno liefert dieselbe Referenz wie der erste Aufruf.
- Reaktive Restbedarfsänderung 350 g → 150 g erst durch 200 g freigegebenen echten Zugang, veraltete Listenmarkierung, Zahlung weiter genau 3,98 €. 10-€-Budget → 6,02 € tatsächlicher Rest und 4,03 € nach offenem 1,99-€-Einkauf; neue Zahlung macht den Rest ungeklärt, erneuter Abgleich zeigt auch negative Werte korrekt.
- Echte SQLite-`RAISE(ABORT)`-Trigger in Beleg-, Positions-, Zahlungs-, Chargen-, Bewegungs-, Positionsverknüpfungs-, Status- und Stornotabellen: Operationen und alle bereits geschriebenen Nebenwirkungen rollen zurück. Dateiwiederöffnung erhält Bestätigung, Positionen, Verknüpfung, Mengen und Retry-Ergebnis; Fremdschlüsselprüfung leer.

Fünf Dateimigrationswege aus den veröffentlichten Schemaexporten 1/2/3/4/5: alle 22/25/26/28/29 alten Tabellen befüllt, jede alte Spalte/Zeile verglichen. Schema-5-Preis-/Packungs-/Budget-/Listenmetadaten einschließlich bestätigter Null erhalten, keine Zahlung oder Vollständigkeit erfunden. Room-Schemavalidierung, Fremdschlüssel, alte Retry-Fingerprints, neue Buchungen und Wiederöffnung bestanden.

Zwei neue Bedienprüfungen innerhalb der zwölf UI-Fälle: mehrteiligen Kauf erfassen, fehlende Bestätigung abweisen, genau eine geklärte Position buchen und gespeicherte Menge/Freigabe prüfen; Ausgaben bestätigen, 2-€-Budgetrest anzeigen, fehlerhafte Kaufbuchung stornieren, alten Abgleich veralten lassen und nach frischer Nullbestätigung wieder 10 € anzeigen. Kleindisplay 320×640 dp mit Schriftfaktor 1,3, Screenshot `local-purchases-small-large-text.png` visuell geprüft; lesbar und ohne Überlagerung.

Der erste gezielte Lauf fand einen echten Rückgabe-/Retry-Fehler beim Kaufstorno: erster Aufruf lieferte Beleg-ID, Retry Ursprungsoperation. Implementation korrigiert, Erst- und Wiederholungsresultat weiterhin ausdrücklich geprüft; keine Assertion entfernt/abgeschwächt. Der anschließende vollständige Lauf oben ist der Nachweis.

## Grenzen

Room/SQLite und Compose unter Robolectric, kein Geräte-/Emulatortest. Große Historien, echte Händler-/Bankdaten, OCR, Erstattungen, Belegkorrekturfassungen, Backups, Release-Signierung und produktive Migration nicht geprüft. Die bestehende Grenze komplexer bestandsgebundener Vergangenheitskorrekturen bleibt offen. GitHub-Prüfung wird nach Sicherung des Codes separat dokumentiert; ältere Läufe sind kein Nachweis für 0.8.
