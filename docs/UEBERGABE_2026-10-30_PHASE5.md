# Übergabe für die Wiederaufnahme nach der fünftägigen Pause

Stand: 30.10.2026. Zuerst diese Datei, dann `AGENTS.md` und bei Bedarf `DEVELOPMENT.md` lesen. Nicht die bisherigen Entwicklungsphasen erneut durchführen. Keine persönlichen Daten übernehmen.

## 1. Gesicherter Stand und Auftrag

Repository: https://github.com/ecora2003-max/Ernaerungsgedaechnis

- Nutzerauftrag: in einem Stück bis einschließlich Phase 5 der Integrationsfolge in Punkt 30 des Produktplans arbeiten. Dieser Funktionsumfang ist implementiert; die unten genannten Geräte-/Produktionsgrenzen bleiben ausdrücklich offen.
- Version **0.9.0-recognition**, versionCode **9**, Android-Paket `de.ernaehrung.demo`, minSdk 26, targetSdk 35. App-Label weiterhin „Ernährung · Demo“.
- Room-Schema **7**, **35 Tabellen**. Datenbank weiterhin ausschließlich `food-local-sandbox.db`, kein Produktivdatenbestand.
- Branch `development/phase5-recognition` und `main` enthalten denselben gesicherten App-Stand. Spätere reine Dokumentationscommits können hinzukommen.
- Eigentlicher App-/Schema-/Testcode: `cc062efde6a93261be0501fcdb30af9021b70566`.
- APK-Lauf verwendet **`4bb5b0f957e4d936a711e045f57431bef4ca0f90`**. Gegenüber dem App-Commit nur Workflow-Aufbewahrung und Dokumentation geändert, kein App-/Schema-/Testcode.
- Nutzer meldete zunächst 35, dann 28 verbleibende Credits; anschließend ungefähr fünf Tage Pause. Jetzt keine neue Phase beginnen. Nach Rückkehr auf Nutzerauftrag fortsetzen.
- Nutzer wünscht normalerweise gebündelte GitHub-Sicherung an Phasengrenzen und kurze nützliche Notizen. Dieses ausführliche Protokoll wurde zusätzlich ausdrücklich beauftragt.
- APKs grundsätzlich nur an ausgewählten Meilensteinen. Für diesen Abschluss hat der Nutzer die APK-Erstellung ausdrücklich verlangt.

Verbindlicher Produktplan: `docs/product/Gesamtplanung-2026-10-30.txt` beziehungsweise PDF daneben. Phase 5 ist **Erkennung**. JSON-Austausch gehört zu Phase 6, Körper/Training zu Phase 7 und Backups/Produktionsreife zu Phase 8.

## 2. APK und automatischer Prüflauf

Maßgeblicher manueller Lauf mit `build_apk=true`:

https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41080374241

**Abschluss überprüft: SUCCESS.** JDK 17, Job 30.10.2026 15:24:12–15:36:10 UTC (11 Minuten 58 Sekunden), exakt auf `4bb5b0f957e4d936a711e045f57431bef4ca0f90`. Alle 21 heruntergeladenen aktuellen XML-Berichte (15:31:57–15:32:56 UTC) ausgewertet: **183 Tests, 0 Fehlschläge, 0 Fehler, 0 Skips**. Lint 14 Hinweise (13 Bibliotheksupdates, einmal OldTargetApi), keine Fehler. Versionierter Schemaabgleich und Gerätetest-Kompilierung erfolgreich; APK-Build und beide Artefakt-Uploads erfolgreich.

APK selbst heruntergeladen und geprüft: `de.ernaehrung.demo`, **0.9.0-recognition / code 9**, minSdk 26, targetSdk 35, **APK-v2-Signatur gültig**. Dies ersetzt keine physische Geräte-/OCR-Prüfung.

- [APK-ZIP direkt öffnen](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41080374241/artifacts/12990056668), Artefakt **12990056668**, ZIP 38.225.380 Bytes. Ablauf laut GitHub **06.11.2026, 16:35:51 Uhr Europe/Berlin** (15:35:51 UTC).
- [Prüfberichte](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41080374241/artifacts/12989672099), Artefakt **12989672099**, Ablauf 06.11.2026, 16:35:55 Uhr Europe/Berlin.
- SHA-256 der entpackten `app-debug.apk`: `d3aa431b5dc05a42a0fb2ed93006522dc3278eb2528265c11ae9e5e2d6f05379`.
- Lokale Prüfkopien in dieser Umgebung: `/tmp/phase5-verified-apk/app-debug.apk` und `/tmp/phase5-verified-ci-reports`. Sie sind keine dauerhaften GitHub-Dateien; bei neuer Umgebung über den Lauf herunterladen.
- Nachfolgende Übergabe-/Dokumentationscommits ändern keinen App-/Schema-/Testcode und benötigen keinen weiteren APK-Lauf.

- Der frühere Lauf **41080103685** wurde bewusst abgebrochen, um die Aufbewahrung rechtzeitig zu verlängern. Er ist kein Prüfnachweis.
- APK und Prüfberichte werden jetzt **7 Tage** aufgehoben, damit sie die fünftägige Pause überstehen. Ablaufdatum nach Upload im tatsächlichen Artefakt prüfen.
- APK-Artefaktname: `Ernaehrungsgedaechtnis-Demo-APK`. ZIP öffnen, darin `app-debug.apk`.
- Prüfberichte: `Android-demo-test-reports`, mit aktuellen XML-Ergebnissen und Lint.
- Workflow: `.github/workflows/android-demo.yml`, Anzeigename `Android checks and optional APK`.
- Automatische Code-Push-/PR-Läufe führen Tests/Lint/Schemaabgleich und Gerätetest-Kompilierung aus; keine automatische APK. Eine manuelle APK kommt erst nach erfolgreichen Prüfungen.
- Die beiden Abschlusscommits haben `[skip ci]`, damit ein einziger bewusst gestarteter manueller Lauf prüft und die APK baut. `workflow_dispatch` wurde tatsächlich gestartet; nicht allein wegen des Committexts für übersprungen halten.
- Dokumentationsänderungen benötigen keinen weiteren App-Build und sollen den laufenden APK-Lauf nicht ersetzen.

Praktische Prüfung nach Rückkehr:

```sh
git status --short --branch
git log -4 --oneline
gh run view 41080374241 --json status,conclusion,headSha,jobs,url
gh run download 41080374241 -n Ernaehrungsgedaechtnis-Demo-APK -D /tmp/phase5-apk
gh run download 41080374241 -n Android-demo-test-reports -D /tmp/phase5-reports
```

APK ist ein Debug-Build, keine Produktionsfreigabe. Stabile Release-Signierung und Backups fehlen noch. Ein Update über eine ältere Debug-APK kann an anderer Signatur scheitern; eine Deinstallation würde lokale Testdaten löschen. Nicht ohne Prüfung zum Löschen/Neuinstallieren raten.

## 3. Was die App tatsächlich kann

**Scan** öffnet jetzt die funktionierende lokale Erkennung. Alternativ: Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen. Der Arbeitsbereich enthält Katalog, Lager, Ernährung, Küche, Planung, Einkauf, Käufe, Erkennung und Chronik. Andere Hauptansichten bleiben überwiegend vorbereitete Demobeispiele.

| Phase | Implementierter Kern |
| --- | --- |
| 1 | Room/SQLite, Produktidentitäten und unveränderliche Versionen, Chargen/Lager/Bewegungen, Relationen, Migrationen, Transaktionen und tatsächliche Tests. |
| 2 | Zugänge, tatsächlicher Verbrauch, Umlagerung, Entsorgung/Zählkorrektur, Meal-Prep-Herstellung, bezahlte Käufe und getrennte Positionszugänge, nachvollziehbare Stornos. |
| 3 | Rezeptversionen, Nährwerte aus tatsächlichen Zutaten/Ausbeuten, tatsächlicher Verzehr und Tagesbilanz, getrennte Lücken je Nährstoff, explizite Tagesvollständigkeit. |
| 4 | Gemeinsame Tages-/Wochen-/56-Tage-Planung, stabile Positionen, explizite Ist-Erfüllung, offener Bedarf mit gemeinsamer Bestandszuordnung, Packungen/Preise/Listenmarkierungen, tatsächlicher Ausgabenabgleich und bedingter Budgetrest. |
| 5 | Barcodefoto/Bildauswahl/Zifferneingabe, Prüfziffer und Offline-Katalog, ausdrückliche OFF-Produktabfrage und editierbare Prüfung, Bonfoto/OCR/manueller Text, Entwürfe, Händleraliase und einmalige kontrollierte Kaufübernahme. |

Es gibt keine automatische Buchung durch Scan, Preisabruf, Listenhäkchen, Rezeptanlage, Planung oder Kochen als vermeintlichen Verzehr. Nach erfolgreicher Schreibaktion oben **Neuen Vorgang beginnen** wählen. Wiederholungen mit gleicher Vorgangs-ID erzeugen keine Duplikate.

## 4. Architektur: Einstiegspunkte statt erneuter Gesamtsuche

Alle Pfade unter `app/src/main/java/de/ernaehrung/demo/`:

| Bereich | Relevante Dateien / Aufgabe |
| --- | --- |
| Daten/Schreibgrenze | `data/local/FoodStore.kt`: öffentliche validierte API, Transaktionen, ProductInput, neue Produktprüfung und optionale atomare Barcodezuordnung. DAO bleibt intern. |
| Schema | `data/local/Entities.kt`, `PurchaseEntities.kt`, `RecognitionEntities.kt`, `FoodDatabase.kt`, `FoodMigrations.kt`; versionierte Exporte unter `app/schemas/de.ernaehrung.demo.data.local.FoodDatabase/`. |
| Grundregeln | `Values.kt`, `WriteRules.kt`: Mengen in 1/1000, Grenzen, Datumsregeln, stabile Fingerprints und Replay vor dynamischen Prüfungen. |
| Ist-Buchungen | `StockOperations.kt`, `KitchenOperations.kt`, `PurchaseOperations.kt`; Zahlung, Bestand, Herstellung und Verzehr bleiben getrennt. |
| Ernährung | `Nutrition.kt`, `NutritionReader.kt`: keine zweite gespeicherte Nährwertsumme; aktuelle Ergebnisse aus historischen konkreten Versionen/Ansätzen. |
| Planung | `PlanningModels/Operations/Reader.kt`, `PlanExecutionModels/Operations/Reader.kt`: stabile Position, versionierte Pläne, ausdrückliche Zuordnung vorhandener Ist-Ereignisse. |
| Einkauf/Budget | `ShoppingModels/Operations/Reader.kt`, `ExpenseReader.kt`: Preise sind Beobachtungen, Geld als Cents; ein ausgewähltes Budget, Rest erst nach ausdrücklichem passenden Ausgabenabgleich. |
| Erkennung fachlich | `RecognitionModels/Entities/Operations/Reader.kt`: Aliasentscheidungen, Scanentwürfe, aktuelle Vorschläge/Snapshots und Annahme/Verwerfen. |
| Erkennung technisch | `data/recognition/Identifiers.kt`, `OpenFoodFacts.kt`, `PhotoRecognition.kt`, `ReceiptParser.kt`. |
| Oberfläche | `ui/LocalDataViewModel.kt`, `ui/screens/LocalDataScreen.kt`, `LocalRecognitionScreen.kt`, `RecognitionProductEditor.kt`, `RecognitionReceiptEditor.kt`. Bestehende lokale Fachmasken bleiben erhalten. |
| Hauptnavigation | `ui/screens/ProductsReceipts.kt`: P01 zeigt lokale Erkennung; E01 hat Einstieg zur echten Bonerkennung neben Beleg-Demo. M03-Einstieg in `SystemScreens.kt`. |
| Fotofreigabe | `app/src/main/AndroidManifest.xml`, `res/xml/recognition_file_paths.xml`: FileProvider nur für privaten Cache-Unterordner `recognition/`. |

`LocalDataViewModel.save` sperrt parallele/wiederholte Formularspeicherung bis zu „Neuen Vorgang beginnen“. Neue Formulare verwenden `rememberUpdatedState` für Sperre/Vorgangs-ID und gezielte Compose-Keys. `Action` hat als zweiten Boolean nur den Stil, **keine Aktivierungssperre**; für echte Sperren `SaveButton` verwenden. Zahlen als Text bearbeiten und erst beim Speichern parsen.

## 5. Phase-5-Details, die erhalten bleiben müssen

### Barcode und Produktdaten

- `Gtin.parse`: nur 8/12/13/14 Ziffern mit gültiger Prüfziffer; Nullcode abweisen. Gedruckte führende Nullen erhalten, Vergleich über GTIN-14. UPC-A und äquivalente EAN sind derselbe Schlüssel.
- Foto-Barcodeverarbeitung nutzt **ZXing core 3.5.3**, keine verpflichtende Google-Barcode-API. Mehrere Codes werden zur Auswahl angeboten. Vier Orientierungen und zuschneidbare Luminanzquellen bleiben wichtig.
- `OpenFoodFactsClient`: expliziter HTTPS-GET an `world.openfoodfacts.org`, nur Barcode, eingeschränkte Felder, 10/15-Sekunden-Timeouts, Antwort höchstens 512 KB, keine Redirects, Antwortcode/GTIN prüfen. Keine Fotos/Bontexte/Vorräte übertragen.
- OFF-Werte sind zunächst bearbeitbare Quellenangaben. Fehlend bleibt NULL, Null bleibt 0; Serving-Felder nicht als `_100g` verwenden. Standardisierte Mineral-Grammwerte zu mg, Jod/B12/D zu µg umrechnen. Kein ausgedachtes Dichte-/Portionsmodell.
- Variante, Basis/Einheit und Speichern getrennt bestätigen. Barcodezuordnung und zusätzlich geprüftes Etikett ebenfalls getrennt. Quelle/SOURCED ist nicht automatisch CONFIRMED.
- `rememberRecognizedProduct` prüft erwartete aktuelle Zielversion. Produktversion und optionaler Barcodealias werden atomar geschrieben; ein Aliasfehler rollt auch Identität/Version/Schlüssel zurück.
- `ProductInput.metadata == null` erhält bisherige Metadaten und alte Fingerprints. Explizite Metadata kann Angaben setzen oder löschen; historische Versionen bleiben unverändert. Neue Metadaten-Fingerprints sind separat versioniert.
- Späte Ergebnisse **und Fehler** abgebrochener Abfragen dürfen neuere Abfragen nicht überschreiben. Die `ensureActive`-Prüfungen nicht entfernen.

### Aliase

- Originale `product_aliases` bleiben unverändert. `alias_decisions` ergänzt Vorgänger, aktive/abgewählte Entscheidung, Grund und Vorgang. Nur aktuelle Blätter verwenden.
- Normalisierung: NFKC, Locale.ROOT, Leerraum zusammenfassen. Keine Variantenziffern oder wichtige Satzzeichen entfernen.
- Bonalias gilt nur beim passenden Händler oder ausdrücklich ohne Händlerbindung. Barcodealias ist ohne Händlerbindung. Konkrete Artikelversion erforderlich für automatische Wiederverwendung.
- Zustände: CONFIRMED, REVIEW, AMBIGUOUS, SUGGESTED, UNKNOWN. Nur eine eindeutige bestätigte **aktuelle** Version ist wiederverwendbar. Alte Versionen nicht still auf neue Rezepturen/Nährwerte umbiegen.
- Ein erneut bestätigter Barcode bei verschiedenen aktiven Versionen kann absichtlich Mehrdeutigkeit ergeben. Alte Aliasentscheidung ausdrücklich berichtigen/zurücknehmen; keine versteckte Auswahl der jüngsten Zuordnung.
- Händler-/Textänderungen im Bonformular entfernen automatische Vorschläge. Eine manuelle konkrete Produktwahl bleibt eine ausdrückliche Nutzerentscheidung.

### Fotos, OCR und Bonentwürfe

- Fotos über Systemkamera (`TakePicture`) oder System-Fotoauswahl. Kein breiter Galerie-/Kamerazugriff. FileProvider gibt nur eine temporäre private Datei frei; Originalbilder der Fotoauswahl nicht löschen.
- Eingabe höchstens 12 MB, bekannte Bilddimensionen, längste Seite nach Sampling höchstens 2400 Pixel. EXIF-Ausrichtung anwenden. Keine beliebigen HTTP-Bild-URLs öffnen.
- **ML Kit Text Recognition 16.0.1**, gebündeltes lateinisches Modell, lokale OCR. `OcrRows` führt getrennte Text-/Preisspalten anhand Bildgeometrie zusammen.
- SDK-Task läuft bei Coroutine-Abbruch gegebenenfalls weiter. Bitmap/Recognizer erst bei Task-Abschluss freigeben; sonst native Nutzung freigegebener Bilder riskieren. Temporäre Aufnahme nach Verarbeitung entfernen.
- `ReceiptParser.VERSION = de-receipt-1`. Rohtext maximal 30.000 Zeichen, höchstens 200 Positionsvorschläge. Nur eindeutiges Datum/Gesamtbetrag vorbefüllen; keine ausgedachte Summe aus Positionen.
- Bar gegeben, Rückgeld einschließlich `RUECKGELD`, Steuer, Pfand/Rabatt/Retouren nicht als normale bezahlte Ware umdeuten. Negative Unicode-Vorzeichen und widersprüchliche/teilweise unlesbare Gesamtzeilen offen lassen.
- OCR kann Komma verlieren: `199A` ist **kein bewiesener Preis 1,99 €**. Artikel/Originaltext erhalten, Preis NULL. Gedruckte Menge ist kein bestätigter nutzbarer Lagerzugang.
- `receipt_scans`: unveränderlicher Text, Herkunft, Parserfassung, optional eindeutiger Bild-SHA-256. MANUAL_TEXT ohne Bildhash; CAMERA_OCR/GALLERY_OCR mit geprüftem Hash.
- Dasselbe Bild führt auch unter Parallelität zum vorhandenen Entwurf. Ein Entwurf ist keine bestätigte Ausgabe und kein tatsächlicher Beleg-/Bestandsvorgang.
- `receipt_scan_resolutions` erlaubt genau eine Annahme oder ein Verwerfen. Annahme prüft aktuellen Produkt-/Alias-Snapshot, schreibt Zahlung/Beleg/Positionen/Auflösung in einer Transaktion. Retry liefert Ergebnis, reaktiviert aber nichts.
- Gleicher aktiver Händler/Tag/Betrag verlangt zusätzliche ausdrückliche Bestätigung eines wirklich weiteren Einkaufs. Zwei tatsächlich getrennte identische Einkäufe bleiben möglich.
- Originalscan bleibt auch nach bearbeiteten Angaben oder Storno erhalten. Nur die bestätigte Gesamtzahlung zählt als Ausgabe; Positionsbetrag und Lagerbewegung nicht zusätzlich zählen.

## 6. Ausgeführte Prüfungen und Belege

Letzter vollständiger lokaler Lauf:

```sh
python /workspace/tooling/run-gradle.py --continue :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

**BUILD SUCCESSFUL**, 44 Sekunden mit warmem Cache. Alle **21 aktuellen XML-Berichte**, 30.10.2026 **15:16:23–15:16:44 UTC**, ausgewertet: **183 Tests, 0 Fehlschläge, 0 Fehler, 0 Skips**. Lint 13 Bibliotheks-Updatehinweise, keine Fehler. Zwei zusätzliche Gerätetests erfolgreich kompiliert, nicht ausgeführt und nicht zu den 183 gezählt.

Neue besonders relevante Testdateien unter `app/src/test/java/de/ernaehrung/demo/`:

- `RecognitionDatabaseTest`: 16 Integrationsfälle, tatsächliche gespeicherte Alias-/Scan-/Beleg-/Zahlungsergebnisse, Konkurrenz, echte SQLite-Abbruchtrigger, Historie, alte Retries und Dateiwiederöffnung.
- `RecognitionParsingTest`: 11 Fälle für GTIN/Normalisierung, OFF-Werte/Basis/Grenzen, Bonkonflikte und tatsächlich erzeugten Host-OCR-Text mit verlorenem Komma.
- `RecognitionPhotoTest`: 5 Fälle mit echten Barcode-Bitmaps, mehreren Codes/Drehung, PNG/JPEG/EXIF, Begrenzung, FileProvider und vollständigem Content-URI-Lesepfad.
- `RecognitionLifecycleTest`: 4 Fälle für späte Antworten/Fehler, Offline-/Prüfzifferfehler, Aufräumen und keine automatische Zahlung durch ein OCR-Ergebnis.
- `RecognitionScreenTest`: 6 tatsächlich ausgeführte Bedienfälle mit Speicherungskontrolle, Offline-Katalog, Quellenkorrektur, manueller Alternative, mehrzeiligem Bon, Händlerwechsel und Aliasrücknahme.
- `DatabaseMigrationTest`: jetzt 6 Wege aus Schema 1–6, alle 22/25/26/28/29/32 alten Tabellen befüllt, jede alte Spalte/Zeile verglichen. Frühere Zahlungen, Lagerbuchungen, Ausgabenstatus und Retry-Schlüssel erhalten.

Die bisherigen Tests für Bestände/Produktion/Ernährung/Planung/Ist-Zuordnung/Einkauf/Ausgaben/Navigation laufen weiter. Keine Assertion abgeschwächt, keine Tests deaktiviert. Screenshots bei 320×640 dp und Schriftfaktor 1,3 visuell geprüft.

`RecognitionPhotoTest` bindet den FileProvider in `@Before` über die öffentliche Android-API an die jeweilige Robolectric-Testanwendung. Hintergrund: wechselnde private Testverzeichnisse bei sonst fortbestehendem Provider-Pfadcache. Diese Isolation nicht entfernen; kein Produktions-Workaround.

Synthetische Bildfixture: `app/src/androidTest/assets/recognition/receipt-test.png`. Host-OCR-Ausgabe: `app/src/test/resources/recognition/host-ocr-receipt.txt`. Die tatsächliche Tesseract-Auswertung wurde lokal ausgeführt; der CI-Test liest die gespeicherte Ausgabe. Dies ist **kein** Nachweis für native ML-Kit-Ausführung auf Android.

## 7. Geräteprüfung: ausdrücklich noch offen

In der Cloud kein Android-Gerät, kein Emulator und kein `/dev/kvm`. Native Kamera und ML Kit wurden hier **nicht ausgeführt**. Vorhanden sind zwei kompilierte Instrumentationstests:

`app/src/androidTest/java/de/ernaehrung/demo/RecognitionOnDeviceTest.kt`

1. Gebündelte ML-Kit-OCR erkennt den synthetischen Testbon und liefert Datum/Gesamtbetrag/Positionen.
2. Vollständiger Foto-URI-Barcodepfad auf Android liefert tatsächlichen Code.

Auf angeschlossenem Gerät oder Emulator ausführen:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Zusätzlich sinnvoll: reale Kamera-App öffnen/abbrechen, Bildauswahl, unscharfer/gedrehter Bon, Flugmodus für Barcode/OCR/gespeicherten Katalog, explizite OFF-Abfrage mit/ohne Netz, Zurück/Rotation bei Bildverarbeitung, keine zusätzliche Ausgabe/Bestandsbuchung durch erneutes Öffnen. Keine bestandenen Hardwaretests behaupten, solange diese nicht tatsächlich ausgeführt wurden.

## 8. Lokale Werkzeuge und Wiederaufnahme ohne unnötige Kosten

Arbeitsverzeichnis bisher `/workspace/Ern-hrungsged-chtnis-`. Bestehendes Checkout verwenden, keine zusätzlichen Worktrees ohne Auftrag. Vor Git-Aktualisierung auf Nutzeränderungen prüfen. Nicht blind resetten oder löschen.

- SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, Helper `/workspace/tooling/run-gradle.py`, Test-JVM-Home `/workspace/tooling/test-home`.
- JDK lokal 21 mit Java-17-Bytecode, CI Temurin 17; SDK 35/Build Tools 34.0.0, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Room 2.7.2/KSP.
- Helper und Caches liegen absichtlich nicht im Repository. Falls die Umgebung neu ist, normalen Gradle-/Android-Setup verwenden oder vorhandenen Actions-Lauf nutzen; nicht persönliche Schlüssel kopieren.
- Geerbte Proxy-/CA-/Anmeldekonfiguration verwenden, keine Geheimnisse ausgeben. Managed-Cloud-Runtime-Skill bei Wiederaufnahme lesen; Cloud-Umgebung nicht eigenständig veröffentlichen.
- Gezielt relevante Tests ausführen; vollständige Routine am nächsten Phasenabschluss. Kein `assembleDebug` nach jedem kleinen Schritt. Kein weiterer Build nur zum Ergänzen einer Notiz.
- Automatischer Prüflauf kann bei neuer Code-Sicherung sinnvoll sein; bestehende erfolgreiche Ergebnisse für unveränderten Code nicht ohne Anlass erneut erzeugen.

## 9. Nächster fachlicher Schritt nach der Pause

Auf Nutzerauftrag **Phase 6: strukturierter ChatGPT-Datenaustausch**. Noch nicht implementiert, die I01–I03-Ansichten sind weiterhin Demo. Keine neue kostenpflichtige API voraussetzen.

Empfohlene Reihenfolge:

1. Gezielt exportierbare Datenbereiche und einen explizit versionierten JSON-Vertrag entwerfen. Konkrete Produkt-/Rezeptversionen, stabile Planpositionen, Quellen, unbekannte Werte, Einheiten, Datum und Datenstand erhalten. Nicht einfach die SQLite-Datenbank als externes Schreibformat freigeben.
2. Read-only-Export aus einem konsistenten Transaktionsstand erstellen. Datenumfang bewusst wählen; ohne Auftrag keine persönlichen Daten/Sheets lesen oder versenden.
3. Import zuerst begrenzt parsen und vollständig validieren: Schema, Pflichtfelder, Zahlenbereiche, Identitäten/Referenzen, Versionen, Mengen/Einheiten, veralteter Exportstand, Bestände/Haltbarkeit, ungeklärte Lagerkapazität, Budget und Planbeziehungen. Unbekannt nicht in Null umwandeln, fremde Versions-IDs nicht erraten.
4. Vorschau mit konkreten Konflikten/Unsicherheiten; erst nach ausdrücklicher Nutzerentscheidung atomar **als Planung** übernehmen. Import darf niemals tatsächliche Käufe, Verzehr, Ist-Bestandsbewegungen oder sichere Lebensmittelfreigaben auslösen.
5. Idempotenz, gleiche-Uhrzeit-ABA, parallele Änderungen, Teilwissen, ungültige Imports ohne Teilwirkungen und Migration gezielt testen. Vorhandene 183 Tests erhalten.

## 10. Bekannte Grenzen nicht verlieren

- Keine Produktionsreife/Backups/stabile Release-Signierung; keine persönlichen Datenmigration erfolgt. Sheets bis gesonderter Freigabe nicht ändern.
- Ergänzende Belegkorrekturfassungen, echte Rückerstattungen/Retouren und Verwerfen unbezahlter Altbelege fehlen. Kaufstorno korrigiert eine fehlerhafte Erfassung, keine reale Erstattung.
- Die konservative Bestandssperre kann vergangene Ersatzkorrekturen nach einem heutigen Storno blockieren. Vor Produktionsbetrieb eine atomare Korrektur mit erhaltenem tatsächlichem Datum entwerfen; nie ein falsches Datum als Umgehung empfehlen.
- Physischer Bestand ist keine bestätigte Nutzbarkeit. Keine automatische Haltbarkeit, Temperatur-/Kapazitätsfreigabe, Dichte-/Packungs-/Abtropfumrechnung oder Allergiesicherheit.
- Keine kontinuierliche Live-Kamera, QR-/GS1-Anwendungsdaten oder allgemeine Web-Produktsuche; manuelle Eingabe bleibt Alternative.
- Ungewohnte Bonlayouts und echte Geräte noch prüfen; OCR bleibt fehlbar. Große Historien nicht lastgetestet.
- Phase 7 Körper/Training und Phase 8 Backups/Migration/Produktionsreife noch nicht aus der Demo produktiv umgesetzt.

Weitere Details nach Bedarf: `RECOGNITION.md`, `TEST_REPORT_PHASE5.md`, `LOCAL_DATABASE.md`, `PURCHASES.md`, `SHOPPING.md`, `PLAN_EXECUTION.md`, `PLANNING.md`, `NUTRITION.md`. Historische Prüfberichte bleiben erhalten; alte Läufe sind kein Nachweis für neu veränderten Code.
