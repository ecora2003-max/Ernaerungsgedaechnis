# Integration der Fachmodule in die Android-Haupt-App

Stand 31.10.2026. Verbindliche Abnahmekriterien: [ACCEPTANCE.md](ACCEPTANCE.md); laufender, commitgenauer Nachweis: [STATUS.md](STATUS.md). Diese Datei beschreibt **Schnittstellen und Grenzen**, keine produktive Freigabe.

## Architektur und Schreibhoheit

- Einziges Schreibrepository: `ecora2003-max/Ernaerungsgedaechnis`. `EG-Architecture`, `EG-Preisdaten`, `EG-Produktdaten`, `EG-Einkaufsintelligenz` und `EG-Rezept-KI` bleiben nur lesbar.
- Vertragsbasis: `EG-Architecture@18794be978cc324e5366996178d368929a4a0fa4`, `contracts/v1` = `1.0.0-draft`, Schema-Blob `bedde4677342817fe6b883962c8dfbbd60100072`. `contracts/v1.1` = `1.1.0-draft`, Schema-Blob `dca777d219307a90bf3d4dff6189fbf12476c508`, **nicht automatisch aktiviert oder STABLE**.
- Reine Kotlin-Fachkerne und lesende Vorschläge dürfen keine Ist-Buchungen auslösen. Zahlung, Lager, Produktion, Verzehr und Storno laufen ausschließlich über bestätigte, transaktionale und idempotente `FoodStore`-Operationen. Kein privater Google-Sheets-Import, keine zweite schreibbare Datenwahrheit.
- Die Haupt-App besitzt inzwischen **Room 8 (36 Tabellen)**. Das additive private `paid_price_observations`-Ledger bleibt von der bestehenden `price_observations`-Tabelle für andere Preisbeobachtungen getrennt. Versionierte Schemas 1–8, befüllte 1→8 bis 7→8-Migrationstests; keine destruktiven Migrationen.

## Aktuell vorhandene Ports

| Fachbereich | Tatsächlich vorhandene Host-Grenze | Verifikation / offene Grenze |
| --- | --- | --- |
| Produktdaten / Bon | `ProductReceiptClassifier` (synthetische Python↔Kotlin-TSV-Parität), unbestätigter `RecognitionReader.review`-Vorschlag; `HistoricalPaidPriceKernel`, `PaidReceiptPriceReader` und `FoodStore.saveHistoricalPaidPrice` für bestätigte private Bonpositionen | Bestätigter, idempotenter Room-8-Schreibpfad und synthetische Migrationen JVM-geprüft. Vollständige Produktidentifikation, Händler-/Filialkanonisierung und Geräte-OCR weiterhin offen |
| Private Preisgeschichte | `HistoricalPaidComparisonReader` mit zeitkorrekter Audit-Sicht, Produktversions-ID und Kenntniszeitpunkt | Rein historisch, `HISTORICAL_PAID`, `isCurrentOffer=false`; kein heutiges Angebot, keine belegte frühere externe Kaufalternative |
| Einkaufsintelligenz | `ShoppingUnitRateKernel`: exakte rationale Grundpreise/Mengen; `ShoppingValidityKernel`: Angebotstage, beobachtete Zeit, Kanal, bestätigter Filialscope als separater Vorschlagsfilter | Grundpreis 18/18 Python-Fixtures und 213/213 Host-JVM-Tests auf Code-SHA `70e684465e263d55522ab4ccd67112e6f0160e05` bestätigt. Validity-Port auf Folgecommit zunächst **CI offen**. Gesamter Vergleich, Bio, FEFO, Bedarf, Budget, Gebinde und Host-E2E fehlen |
| Externe Preisdaten | `RetailerPolicy` (ADR-016/018) und `ExternalPriceBridge.project` mit getrennten Host-Quellenrechts-/APP_DISPLAY-Gates, standardmäßig `denyAll` | Keine Livequelle, keine produktive Anzeige oder Provideranbindung. Ein `PERMITTED`-Flag vom Feed allein ist kein Freigabenachweis |
| Rezept-KI | Bestehende manuelle Rezept-/Herstellungs-/Verzehrfunktionen im Host, Fachmodul offline separat | Noch kein vollständiger Rezept-KI-Kotlin-Entwurfsport mit allen acht Kontextkombinationen, Allergengates und E2E-Bestätigung; kein On-Device-Modelltest |

## Händlerfähigkeiten, Quellrechte und Scope

ADR-018 / Policy 1.0.3: `ALDI_NORD`, `ALDI_SUED`, `REWE`, `LIDL` unterstützen fachlich gezielte reguläre Preisabfrage, veröffentlichte Einzelangebote und Angebotskatalog-Discovery; `PENNY`, `KAUFLAND`, `EDEKA`, `NETTO_MD` nur veröffentlichte Einzelangebote. `PUBLISHED_OFFER_CATALOG_DISCOVERY` ist bereits als dritte Capability in `RetailerPolicy` enthalten, wird aber im **Preis**-Port ausdrücklich mit `NON_PRICE_CAPABILITY` abgewiesen. Ein eigener Katalog-Indexadapter fehlt noch.

Die Händlerfähigkeit ist keine Quellenrechtsfreigabe. Für APP_DISPLAY müssen sowohl die fachliche Fähigkeit als auch eine unabhängige, vom Host bestätigte quellen- und zweckgebundene Genehmigung erfüllt sein. Der reine Shopping-Zeit-/Store-Filter ersetzt keines dieser Gates: Die Python-Referenz `engine._valid` lehnt explizit `RESTRICTED` ab, erteilt aber bei `UNKNOWN` allein **keine** Rechtefreigabe. Eine nicht kanonisierte freie Bon-Händlerbezeichnung ist keine bestätigte Retailer-/Store-ID. Keine unbestätigten Händlerpreise oder privaten historischen Bonpreise als heutige Angebote behandeln.

ADR-019: Keine ungefragten zusätzlichen separaten Rechtsrecherchen; vorhandene technische Zugriffs- und Anzeigesperren bleiben erhalten.

## Fortsetzung und Abnahmeregeln

1. Neu eingereichte isolierte Ports erst nach aktuellem Gradle-/JUnit-XML-/Python-Fixture-/Room-Schema-CI-Lauf auf exakt dem Code-SHA als JVM-geprüft bezeichnen. Keine alten CI-Ergebnisse auf neuen Code übertragen.
2. Den bestätigten Händler-/Filial-Identitätsport, Produktversions- und Preisquellen-Gates mit Zeit-/Gültigkeitsprüfung **explizit** verketten; anschließend Rot-/Orange-/Grau-Vergleich und historische Alternativen mit identischen Python-/Kotlin-Fixtures, ohne Zukunftsdaten oder ungeprüfte Vollabdeckung.
3. FEFO, 1-/7-/56-Tage-Bedarfe, Gebinde-/Budget-/Pfandprüfung und Einkaufsvorschläge als **nicht buchende** Host-Ports ergänzen.
4. Offline-Rezeptentwurf → explizite Bestätigung → Herstellung → separater Verzehr als synthetische E2E-Kette mit Allergen-/Mengenprüfung und acht Kontextflags absichern.
5. Echte Android-Geräte-/Emulator-Instrumentation für Kamera, OCR, UI und Offline-/Privacy-Eigenschaften gesondert durchführen. `:app:compileDebugAndroidTestKotlin` ist **nur Kompilierung**, kein Gerätetest; kein automatischer APK-Meilenstein.

Statusklassen: `OFFLINE_VERIFIED` für Fachreferenz, eng begrenzte JVM-Portprüfungen nur als solche, `JVM_INTEGRATION_VERIFIED` erst bei vollständiger Modul-/Host-Parität und E2E, `ANDROID_DEVICE_VERIFIED` erst nach wirklicher Instrumentation, `INTEGRATED` erst nach sämtlichen relevanten Gates. **Aktuell ist keines der vier Fachmodule insgesamt INTEGRATED.**
