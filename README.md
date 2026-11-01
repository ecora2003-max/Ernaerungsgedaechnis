# Ernährungsgedächtnis · Android-Entwicklung

Android-App mit Kotlin, Jetpack Compose und Material 3. Version **0.9.0-recognition** implementiert den funktionalen Kern bis einschließlich **Phase 5** der Gesamtplanung: lokale Daten, tatsächliche Buchungen, Ernährung/Rezepte, Planung/Einkauf und Erkennung. Weiterhin isolierter Testbetrieb; nur Testdaten verwenden.

**Scan** öffnet jetzt die lokale Erkennung: Barcode fotografieren/Bild wählen/manuell eingeben, offline im Katalog prüfen, optional Open Food Facts abfragen und bearbeitbare Produktangaben bestätigen. EAN-/GTIN-Prüfziffern, konkrete Artikelversionen, Quellen und unbekannte Werte bleiben erhalten. Produktanlage bucht keinen Vorrat. Unter **Bon & OCR** Kamera/System-Fotoauswahl oder mehrzeiligen Text verwenden, einen Entwurf speichern und Vorschläge prüfen. Erst eine ausdrückliche Zahlungsbestätigung erzeugt einen Einkauf; geklärte Lagerzugänge werden separat bestätigt. Händleraliase lassen sich ausdrücklich lernen, korrigieren und zurücknehmen. Anleitung/Grenzen: [docs/RECOGNITION.md](docs/RECOGNITION.md).

Alle gespeicherten Funktionen sind auch unter **Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen** erreichbar: **Katalog / Lager / Ernährung / Küche / Planung / Einkauf / Käufe / Erkennung / Chronik**. Der Katalog ist durchsuchbar und unabhängig vom Bestand. Die übrigen Hauptansichten enthalten weiterhin vorbereitete Demobeispiele; die Datenbereiche bleiben getrennt. Persönliche Sheets-Daten wurden nicht übernommen.

Tatsächliche Zugänge, Verbrauch, Umlagerung, Zählkorrektur, Entsorgung und Herstellung sind atomar und idempotent. Stornos ergänzen Gegenbuchungen und bewahren Historie. Ernährung und Portionsnährwerte verwenden konkrete damalige Versionen/Ansätze; fehlende Mengen oder Werte bleiben Lücken. Regeln: [docs/LOCAL_DATABASE.md](docs/LOCAL_DATABASE.md), [docs/NUTRITION.md](docs/NUTRITION.md).

Gemeinsame Tages-/Wochen-/56-Tage-Planung mit expliziter Ist-Zuordnung berechnet offenen Restbedarf. Einkaufsvorschläge berücksichtigen geeigneten Bestand, bestätigte Kaufpackungen, Preise und Überschüsse. Listenhäkchen sind kein Kauf. Verfügbares Budget erscheint erst nach explizit vollständigem Ausgabenabgleich. Regeln: [docs/PLANNING.md](docs/PLANNING.md), [docs/PLAN_EXECUTION.md](docs/PLAN_EXECUTION.md), [docs/SHOPPING.md](docs/SHOPPING.md), [docs/PURCHASES.md](docs/PURCHASES.md).

Die veröffentlichte Phase-5-Basis nutzte **Room 7 (35 Tabellen)**. Der aktuelle Entwicklungszweig nutzt **Room 8 (36 Tabellen)** mit befüllten Migrationstests von 1–7 nach 8 und zusätzlichen **isolierten, nicht produktiv angeschlossenen** Fachmodul-Ports. Sichere Backups, Produktionsmigration, vollständige Modulparität und Android-Gerätetests bleiben offen. **Das GitHub-Repository ist derzeit öffentlich**: keine persönlichen Bons, Profile, Datenbanken, Zugangsdaten oder privaten Schlüssel committen. Aktuelle Nachweise: [docs/STATUS.md](docs/STATUS.md), Schnittstellen: [docs/INTEGRATION.md](docs/INTEGRATION.md); die historische Phase-5-Übergabe steht in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Produktplan unter [docs/product/](docs/product/).

## Schnellstart

Projektordner in Android Studio öffnen und Gradle synchronisieren. Benötigt werden JDK 17, Android SDK 35 und Build Tools 34.0.0. Der Gradle-Wrapper ist vollständig enthalten.

```bash
chmod +x gradlew
./gradlew --no-daemon --max-workers=2 --continue :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

Das letzte Prüftask kompiliert die zwei zusätzlichen Gerätetests; es führt sie nicht aus. Auf einem angeschlossenen Android-Gerät/Emulator mit `./gradlew :app:connectedDebugAndroidTest` separat ausführen. Native Kamera/OCR wurden in der Cloud ohne Android-Gerät nicht ausgeführt.

Für ausgewählte APK-Meilensteine zusätzlich `./gradlew :app:assembleDebug` ausführen. Die installierbare Debug-APK entsteht unter `app/build/outputs/apk/debug/app-debug.apk`. Mindestversion: Android 8.0 (API 26), Zielversion: API 35. Paketname: `de.ernaehrung.demo`.

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

Scan und lokale Erkennung sind funktionsfähig: Barcodeverarbeitung mit ZXing, gebündelte lokale ML-Kit-Texterkennung und ausdrücklicher HTTPS-Produktabruf bei Open Food Facts. Systemkamera und Fotoauswahl verwenden einzelne freigegebene Bild-URIs; keine breite Kamera-/Medien-Leseberechtigung. App/SDK deklarieren Internet und Netzwerkstatus. Der Produktabruf braucht eine Verbindung; Bildauswertung und gespeicherte Daten funktionieren offline. Keine KI-API, Aktivitäts-/Gesundheitsintegration oder Anmeldung. App-Backups bleiben deaktiviert. Die übrigen Demo-Buchungs-, Import-, Export- und Sicherungsaktionen bleiben Vorschauen; die lokalen Produktillustrationen sind unverändert.

Produktinformationen sind unabhängig vom fiktiven Vorratsstand verfügbar. Unbekannte Nährwerte werden als unbekannt angezeigt. Geplante Mahlzeiten zählen nicht zur tatsächlichen Tagesbilanz. Die Vorschau für 50 g Naturtofu ist fest vorbereitet; andere Mengen erzeugen keine scheinbar berechneten Werte.

Der Bedarfsabgleich in den bisherigen Hauptansichten ist ein erklärter Auszug für vier Produkte. Deren Monatsplan enthält vier Beispielwochen, davon eine detailliert. Die neue persistente Berechnung liegt im lokalen Testbereich. Diagramme zeigen acht fiktive Rohmessungen und vorbereitete Beispielkurven; keine Regression oder Kalibrierung.

## Quellcode

`data/DemoRepository.kt` enthält die unveränderlichen Fixtures. `navigation/Screen.kt` definiert Ansichten und Hauptbereiche. `MainActivity.kt` enthält Navigation und Grundgerüst. `ui/screens/` enthält die Ansichten; `ui/Components.kt` gemeinsame Komponenten. Die Tests liegen in `app/src/test/`.

Abhängigkeiten: AGP 8.7.3, Gradle 8.9, Kotlin/Compose-Compiler 2.0.21, Compose BOM 2024.12.01, Navigation 2.8.5, Room 2.7.2, KSP 2.0.21-1.0.28, ZXing 3.5.3, ML Kit Text Recognition 16.0.1 und ExifInterface 1.3.7. Der Wrapper prüft die Gradle-Distribution per SHA-256. Auf eingeschränkten Build-Rechnern kann mit `-PtestHome=/beschreibbarer/pfad` ein eigenes JVM-Testverzeichnis für Robolectric verwendet werden.

## GitHub

Vorgesehenes Repository: https://github.com/ecora2003-max/Ern-hrungsged-chtnis-

Das Repository wurde vom Nutzer erstellt; Quellcode-Uploads und Actions-Builds sind eingerichtet. Der aktuelle Repositoryname ist `ecora2003-max/Ernaerungsgedaechnis`; der ursprüngliche Link wird von GitHub weitergeleitet.

Der Workflow **Android checks and optional APK** prüft bei Code-Push/PR automatisch Tests, Lint und versioniertes Room-Schema. Es wird dabei keine herunterladbare APK erzeugt. Tests kompilieren weiterhin den benötigten App-Code und Ressourcen; ihre Aussagekraft wird nicht reduziert.

Eine APK entsteht nur für bewusst ausgewählte Meilensteine: **Actions → Android checks and optional APK → Run workflow → `build_apk` aktivieren**. Der manuelle Lauf führt die Prüfungen aus und baut/lädt erst nach Erfolg die APK hoch. Ohne aktivierte Option bleibt auch ein manueller Lauf ein reiner Prüflauf. Unter dem erfolgreichen APK-Lauf → Artifacts liegt die Debug-APK als ZIP. Die Workflow-Datei bleibt `.github/workflows/android-demo.yml`.

Standard-Linux-Runner, unabhängig von Repositorysichtbarkeit, 20-Minuten-Grenze und sieben Tage Artefaktaufbewahrung, damit APK und Berichte die angekündigte fünftägige Pause überstehen. Private Läufe verwenden das Actions-Kontingent des GitHub-Kontos. Actions per Commit gepinnt und Gradle-Abhängigkeitscache eingerichtet; keine größeren Runner oder verpflichtenden externen Dienste. Testberichte werden auch bei fehlgeschlagenen Prüfungen hochgeladen.

GitHub ist ausschließlich die Entwicklungsablage. Es ist keine Ernährungsdatenbank. Google Sheets und persönliche Daten wurden für diese Demo nicht gelesen oder geändert.

## Sicherung der Entwicklung

Entwicklungsstände werden an fachlichen Phasengrenzen oder auf ausdrücklichen Wunsch auf GitHub gesichert. Zusammengehörige Änderungen und CI-Läufe werden gebündelt; gezielte Tests laufen weiterhin während der Entwicklung. Übergabenotizen bleiben kurz und dienen der Wiederaufnahme.

Die ursprüngliche Quellprojekt-ZIP enthielt zusätzlich ein Git-Bundle als historische Sicherung. Für den aktuellen Entwicklungsstand ist das GitHub-Repository maßgeblich. SDK, JDK, Gradle-Cache, lokale Pfade, Zugangsdaten, App-Datenbanken und private Signierschlüssel gehören nicht ins Repository.

Den aktuellen Prüfstand beschreibt `docs/DEVELOPMENT.md`; `docs/Pruefbericht.md` ist der historische Bericht der ursprünglichen Demo.
