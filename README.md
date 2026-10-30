# Ernährungsgedächtnis · Android-Demo

Native Android-Oberflächendemo mit Kotlin, Jetpack Compose und Material 3. Alle Daten sind fiktiv. Enthalten sind die 33 vorgesehenen Ansichten und eine zusätzliche Mehr-Übersicht.

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

Funktioniert: Navigation, Such- und Filterfelder, aufklappbare Bereiche, Eingabevorschauen, Einkaufslisten-Häkchen und Diagrammauswahl. Buchungs-, Import-, Export- und Sicherungsaktionen zeigen Demo-Hinweise. Eingaben verändern keine dauerhaften Daten.

Keine Kamera, OCR, Barcodeerkennung, KI-API, Aktivitätsintegration, Datenbank, Netzverbindung oder produktive Buchungslogik. Keine Internet-, Kamera-, Gesundheits- oder Dateizugriffsberechtigung. App-Backups sind deaktiviert. Originalfotos wurden durch lokal gezeichnete Produktillustrationen ersetzt.

Produktinformationen sind unabhängig vom fiktiven Vorratsstand verfügbar. Unbekannte Nährwerte werden als unbekannt angezeigt. Geplante Mahlzeiten zählen nicht zur tatsächlichen Tagesbilanz. Die Vorschau für 50 g Naturtofu ist fest vorbereitet; andere Mengen erzeugen keine scheinbar berechneten Werte.

Der Bedarfsabgleich ist ein erklärter Auszug für vier Produkte. Der Monatsplan enthält vier Beispielwochen, davon eine detailliert. Diagramme zeigen acht fiktive Rohmessungen und vorbereitete Beispielkurven; keine Regression oder Kalibrierung.

## Quellcode

`data/DemoRepository.kt` enthält die unveränderlichen Fixtures. `navigation/Screen.kt` definiert Ansichten und Hauptbereiche. `MainActivity.kt` enthält Navigation und Grundgerüst. `ui/screens/` enthält die Ansichten; `ui/Components.kt` gemeinsame Komponenten. Die Tests liegen in `app/src/test/`.

Abhängigkeiten: AGP 8.7.3, Gradle 8.9, Kotlin/Compose-Compiler 2.0.21, Compose BOM 2024.12.01, Navigation 2.8.5. Der Wrapper prüft die Gradle-Distribution per SHA-256.

## GitHub

Vorgesehenes Repository: https://github.com/ecora2003-max/Ern-hrungsged-chtnis-

Das Repository wurde vom Nutzer erstellt und ist öffentlich. Der Nutzer hat den Upload dorthin ausdrücklich freigegeben. Am 30.10.2026 wurde der erste Schreibversuch jedoch mit HTTP 403 „Resource not accessible by integration“ abgewiesen. Noch kein Code wurde über diese Verbindung hochgeladen und noch kein Actions-Build ausgeführt.

Der vorbereitete Workflow verwendet ausschließlich einen Standard-Linux-Runner und läuft nur in öffentlichen Repositories. Bei Umstellung auf privat wird der Job automatisch übersprungen; für private Nutzung müssen Freikontingent und Kostenbegrenzung vorher geprüft werden. Artefakte werden einen Tag aufbewahrt; kein Gradle-Cache, keine größeren Runner, kein kostenpflichtiger Dienst.

GitHub ist ausschließlich die Entwicklungsablage. Es ist keine Ernährungsdatenbank. Google Sheets und persönliche Daten wurden für diese Demo nicht gelesen oder geändert.

## Sicherung der Entwicklung

Die ZIP enthält zusätzlich `Ernaehrungsgedaechtnis-Android.git.bundle`. Mit `git clone Ernaehrungsgedaechtnis-Android.git.bundle Demo` kann die Commit-Historie wiederhergestellt werden. SDK, JDK, Gradle-Cache, lokale Pfade, Zugangsdaten und private Signierschlüssel werden nicht mitgeliefert.

Den tatsächlichen Prüfstand beschreibt `docs/Pruefbericht.md`.
