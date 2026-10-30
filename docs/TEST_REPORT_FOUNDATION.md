# Prüfergebnisse: lokales Datenfundament

Datum: 30.10.2026. Geprüfte Entwicklungsversion: 0.2.0-foundation, Datenbankschema 1.

## Tatsächlich ausgeführte Prüfungen

Lokaler abschließender Lauf: `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, Gradle 8.9, JDK 21 (Bytecode-Ziel 17), Android SDK 35 / Build Tools 34.0.0. `BUILD SUCCESSFUL` nach 45 Sekunden mit bereits installierten Abhängigkeiten. Cloud-spezifisch wurde `-PtestHome=/workspace/tooling/test-home` und der geerbte HTTPS-Proxy verwendet; keine TLS-/Checksum-Prüfung deaktiviert. GitHub verwendet separat JDK 17.

Die JUnit-XML-Dateien wurden unmittelbar nach Ende dieses vollständigen Laufs ausgewertet, nicht die Dateien vorheriger fehlgeschlagener/gezielter Läufe. Die folgende Tabelle hält diese Auswertung vor dem ergänzenden Uhren-Regressionsfall fest:

| Testsuite | Ausgeführt | Fehlgeschlagen | Fehler | Übersprungen |
| --- | ---: | ---: | ---: | ---: |
| LocalDatabaseTest | 16 | 0 | 0 | 0 |
| AmountsTest | 2 | 0 | 0 | 0 |
| LocalDataScreenTest | 1 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **35** | **0** | **0** | **0** |

Anschließend wurde ein zusätzlicher Regressionstest für eine nachträglich verstellte Geräteuhr ergänzt und gezielt mit der gesamten Datenbanksuite ausgeführt: **17 Tests, 0 Fehler, 0 Fehlschläge, 0 Skips**, erfolgreicher Gradle-Lauf nach 26 Sekunden. Bereits gebuchte Zugänge/Verzehre müssen bei gleichem Schlüssel erfolgreich wiedererkannt werden, auch wenn die Uhr zurückgestellt wurde. Neue zukünftige Ereignisse bleiben abgewiesen. Dafür erfolgt die dynamische Datumsprüfung erst nach dem Abgleich vorhandener Vorgangs-IDs.

## Abschließender frischer GitHub-Lauf

[Lauf 41032992779](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41032992779), Code-Commit `39ea5ada7da979e00595a22dc21f9cc54a202436`, JDK 17, 30.10.2026: erfolgreich nach 8 min 5 s. Die sechs XML-Reports aus dem zugehörigen Artefakt wurden heruntergeladen, Zeitstempel mit diesem Lauf abgeglichen und separat ausgewertet:

| Testsuite | Ausgeführt | Fehlgeschlagen | Fehler | Übersprungen |
| --- | ---: | ---: | ---: | ---: |
| LocalDatabaseTest | 17 | 0 | 0 | 0 |
| AmountsTest | 2 | 0 | 0 | 0 |
| LocalDataScreenTest | 1 | 0 | 0 | 0 |
| DemoDataTest | 4 | 0 | 0 | 0 |
| NavigationTest | 10 | 0 | 0 | 0 |
| ScreenRenderTest | 2 | 0 | 0 | 0 |
| **Gesamt** | **36** | **0** | **0** | **0** |

APK-Erstellung, exakter Vergleich des generierten mit dem versionierten Room-Schema und Lint erfolgreich. CI-Lint enthält 11 nicht fatale Hinweise, keine Fehler. Die heruntergeladene APK weist Version `0.2.0-foundation` / Code 2 / minSdk 26 / targetSdk 35 auf; `apksigner verify` bestätigt ihre v2-Signatur. Es handelt sich um eine Debug-APK, keinen Store-Release.

Im lokalen Lauf: Lint mit 0 Fehlern und 10 Hinweisen auf neuere Bibliotheksversionen. Im frischen CI kam zusätzlich der Hinweis auf das bestehende targetSdk 35 hinzu (11 Hinweise insgesamt). Keine Fachprüfung wurde deaktiviert, keine Assertion entfernt. Die alten Icon-Deprecation-Warnungen bestehen weiter; Bibliotheks-/Target-SDK-Upgrades sind kein Bestandteil dieses Meilensteins.

## Fachliche Ergebnisse, anhand gespeicherter Daten geprüft

| Fall | Geprüfte Erwartung / Ergebnis |
| --- | --- |
| Produkt merken | Keine Charge, keine Bewegung, Bestand 0; fehlende Energie bleibt NULL, eingegebene Energie 0 bleibt 0. |
| Wiederholter Zugang | Ein Zugang von 200 g bleibt auch nach Wiederholung 200 g und genau eine Bewegung; geänderte Angaben mit gleicher Vorgangs-ID werden abgewiesen. |
| Doppelklick vs. zweite Mahlzeit | Gleiche Verzehr-ID wird nur einmal wirksam. Zwei getrennte IDs für je 50 g reduzieren 200 g korrekt auf 100 g. |
| Zu große Entnahme | 300 g aus 200 g werden abgewiesen: Bestand bleibt 200 g, kein Verzehr, keine Vorgangs-ID und kein Tagesstatus zurückgelassen. |
| Fehler im zweiten Transaktionsteil | Erste Charge 100 g, zweite 20 g; Entnahme von 100 g + 50 g scheitert vollständig. Danach noch 100 g + 20 g und keine Teilbuchung. |
| Gleichzeitige Zugänge | Acht parallele Wiederholungen derselben ID liefern dieselbe Charge, keine acht Zugänge. |
| Konkurrierende Entnahmen | Vier parallele Entnahmen von je 100 g aus 200 g: genau zwei erfolgreich, zwei abgewiesen, Endbestand 0. |
| Historische Version | Korrektur von 125 auf 140 kcal erzeugt neue Version; alter Verzehr und alte Rezeptzutat referenzieren weiterhin die 125-kcal-Version. |
| Dateipersistenz | Dateidatenbank nach Buchung schließen und neu öffnen: 150 g erhalten, IDs/Fremdschlüssel und Wiederholungsschutz erhalten, kein doppelter Verzehr. |
| Fremdschlüssel | Alias einer falschen Produkt-/Versionskombination wird von SQLite abgewiesen; foreign_key_check ohne Befund. |
| Plan ist kein Ist | Plananlage erzeugt weder Verzehr noch Charge noch Bewegung. |
| Teilwissen | Verzehr ohne bekannte Menge und ohne Lagerbezug wird gespeichert, ohne fiktive Bestandsbewegung. |
| Vollständiger Tag mit Nachtrag | Neuer Verzehr setzt Status auf TEILWEISE zurück und entfernt die frühere Vollständigkeitsbestätigung. |
| Keine fiktiven zukünftigen Tatsachen | Zukünftige Zugänge/Verzehre werden abgewiesen. |
| Sicherheit / Einheiten | Ungeklärte/gesperrte Charge, überschrittenes Verbrauchsdatum, falsche Produktversion oder widersprüchliche Chargenmengen werden abgewiesen. kg/l/Packungsumrechnungen exakt; ohne bestätigte Basis keine Stück-/Packungsumrechnung. |
| Bedienoberfläche | Produkt über Compose-Formular gespeichert, Datenbankinhalt und unbekannte Nährwerte geprüft. Erneutes Speichern ist bis „Neuen Vorgang beginnen“ gesperrt. |

## Grenzen der Aussage

Android-Tests laufen mit Robolectric und SQLite, nicht auf einem angeschlossenen Smartphone oder Android-Emulator. Ein Neustart der Datenbankdatei ist geprüft; er simuliert keinen vollständigen Geräteabsturz. Schema 1 ist die erste Datenbank, daher wurde keine erfundene Upgrade-Migration behauptet. Der erste echte Schema-Upgrade benötigt einen eigenen Erhaltungstest.

Die bestehenden Renderprüfungen erzeugten 68 Bilder für 34 Routen in zwei Bildschirm-/Schriftkonfigurationen. M03 wurde in beiden Größen visuell nachgeprüft; die übrigen Bilder wurden nicht erneut vollständig visuell geprüft. Diese Renderbilder ersetzen keine Tests des späteren Produktivbetriebs.

GitHub Actions erzeugt die APK und lädt HTML-/JUnit-XML-Berichte hoch. Der Workflow prüft zusätzlich, dass das regenerierte Room-Schema genau den eingecheckten Dateien entspricht. Ergebnis und APK-Link stehen auch in `DEVELOPMENT.md`. Dieser Nachweis gilt für den oben genannten Code-Commit; spätere Änderungen brauchen ihre eigenen relevanten Prüfungen.
