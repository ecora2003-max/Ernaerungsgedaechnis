# Ernährungsgedächtnis · Android-Entwicklung

Native Android-Oberflächendemo mit Kotlin, Jetpack Compose und Material 3. Alle Daten sind fiktiv. Enthalten sind die 33 vorgesehenen Ansichten und eine zusätzliche Mehr-Übersicht.

Version **0.4.0-nutrition** enthält einen **persistenten lokalen Testbereich**: Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen. Dort lassen sich Produkte, Bestände, Rezepte, tatsächliches Kochen und Verzehr verwalten. Stornos ergänzen Gegenbuchungen, ohne Historie zu löschen.

Neu unter **Ernährung**: tatsächliche Verzehre mit oder ohne Lagerbezug, bekannte Nährwertsummen und ausdrücklich gekennzeichnete Datenlücken, Tagesbestätigung und Storno. Fertigportionen werden aus tatsächlichen Zutaten und Ausbeuten berechnet. Rezeptvorschauen bleiben von diesen Ist-Werten getrennt. Im Katalog sind auch weitere Nährstoffe manuell erfassbar; leer bleibt unbekannt, 0 bleibt eine bekannte Null.

Schema 2 bleibt unverändert; bestehende Schema-1-Testdaten werden ohne Löschung migriert. Die bisherigen 33 Fachansichten bleiben Demoansichten. Keine persönlichen Daten übernehmen: sichere Backups und die kontrollierte Übernahme aus Sheets fehlen noch.

Entwicklungsstand und Fortsetzung: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Bedienung, Datenmodell und Grenzen: [docs/LOCAL_DATABASE.md](docs/LOCAL_DATABASE.md). Der vollständige Produktplan liegt unter [docs/product/](docs/product/).

## Schnellstart

Projektordner in Android Studio öffnen und Gradle synchronisieren. Benötigt werden JDK 17, Android SDK 35 und Build Tools 34.0.0. Der Gradle-Wrapper ist vollständig enthalten.

```bash
chmod +x gradlew
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Die installierbare Debug-APK entsteht unter `app/build/outputs/apk/debug/app-debug.apk`. Mindestversion: Android 8.0 (API 26), Zielversion: API 35. Paketname: `de.ernaehrung.demo`.

Installation über Android Studio oder `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Beim manuellen Öffnen einer APK kann Android eine Freigabe für die installierende App verlangen. Diese Demo ist kein Play-Store-Release.

## Bedienung

Unten stehen Start, Bestände, Scan, Planung und Mehr. Unter Mehr → Demo-Rundgang lassen sich alle 33 Ansichten öffnen. Körper & Training ist von Start und Mehr erreichbar. Zurück funktioniert über App-Leiste und Systemtaste.

- Start und Ernährung: S01–S03, T01–T03.
- Produkte, Katalog und Vorräte: P01–P03, L01–L03.
- Kassenbon: E01–E03.
- Rezepte, Meal Prep, Woche, Monat, Bedarf und Budget: R01–R03, G01–G03, H01–H03.
- Simulierter ChatGPT-Austausch, Profil und Sicherungen: I01–I03, M01–M03.
- Körpermessungen, Training und Entwicklung: K01–K03.

## Umfang und Grenzen

In den bisherigen Demoansichten funktionieren Navigation, Such- und Filterfelder, aufklappbare Bereiche, Eingabevorschauen, Einkaufslisten-Häkchen und Diagrammauswahl. Dort zeigen Buchungs-, Import-, Export- und Sicherungsaktionen Demo-Hinweise und verändern keine dauerhaften Daten. Der neue lokale Testbereich speichert dagegen ausdrücklich bestätigte Testeingaben.

Außerhalb des lokalen Testbereichs sind Buchungs-, Import-, Export- und Sicherungsaktionen weiterhin Vorschauen. Keine Kamera, OCR, Barcodeerkennung, KI-API oder Aktivitätsintegration. Keine Internet-, Kamera-, Gesundheits- oder Dateizugriffsberechtigung. App-Backups sind deaktiviert. Originalfotos wurden durch lokal gezeichnete Produktillustrationen ersetzt.

Produktinformationen sind unabhängig vom fiktiven Vorratsstand verfügbar. Unbekannte Nährwerte werden als unbekannt angezeigt. Geplante Mahlzeiten zählen nicht zur tatsächlichen Tagesbilanz. Die Vorschau für 50 g Naturtofu ist fest vorbereitet; andere Mengen erzeugen keine scheinbar berechneten Werte.

Der Bedarfsabgleich ist ein erklärter Auszug für vier Produkte. Der Monatsplan enthält vier Beispielwochen, davon eine detailliert. Diagramme zeigen acht fiktive Rohmessungen und vorbereitete Beispielkurven; keine Regression oder Kalibrierung.

## Quellcode

`data/DemoRepository.kt` enthält die unveränderlichen Fixtures. `navigation/Screen.kt` definiert Ansichten und Hauptbereiche. `MainActivity.kt` enthält Navigation und Grundgerüst. `ui/screens/` enthält die Ansichten; `ui/Components.kt` gemeinsame Komponenten. Die Tests liegen in `app/src/test/`.

Abhängigkeiten: AGP 8.7.3, Gradle 8.9, Kotlin/Compose-Compiler 2.0.21, Compose BOM 2024.12.01, Navigation 2.8.5, Room 2.7.2 und KSP 2.0.21-1.0.28. Der Wrapper prüft die Gradle-Distribution per SHA-256. Auf eingeschränkten Build-Rechnern kann mit `-PtestHome=/beschreibbarer/pfad` ein eigenes JVM-Testverzeichnis für Robolectric verwendet werden.

## GitHub

Vorgesehenes Repository: https://github.com/ecora2003-max/Ern-hrungsged-chtnis-

Das Repository wurde vom Nutzer erstellt; Quellcode-Uploads und Actions-Builds sind eingerichtet. Der aktuelle Repositoryname ist `ecora2003-max/Ernaerungsgedaechnis`; der ursprüngliche Link wird von GitHub weitergeleitet.

Der Workflow verwendet einen Standard-Linux-Runner, läuft unabhängig von der Sichtbarkeit und hat ein 20-Minuten-Zeitlimit. Private Builds verwenden das Actions-Kontingent des GitHub-Kontos. Artefakte werden einen Tag aufbewahrt; keine größeren Runner oder verpflichtenden externen Dienste. Unter Actions → Android demo APK → erfolgreicher Lauf → Artifacts liegt die Debug-APK als ZIP. APK-Erstellung und Lint-/Testresultate sind getrennte Schritte.

GitHub ist ausschließlich die Entwicklungsablage. Es ist keine Ernährungsdatenbank. Google Sheets und persönliche Daten wurden für diese Demo nicht gelesen oder geändert.

## Sicherung der Entwicklung

Die ursprüngliche Quellprojekt-ZIP enthielt zusätzlich ein Git-Bundle als historische Sicherung. Für den aktuellen Entwicklungsstand ist das GitHub-Repository maßgeblich. SDK, JDK, Gradle-Cache, lokale Pfade, Zugangsdaten, App-Datenbanken und private Signierschlüssel gehören nicht ins Repository.

Den aktuellen Prüfstand beschreibt `docs/DEVELOPMENT.md`; `docs/Pruefbericht.md` ist der historische Bericht der ursprünglichen Demo.
