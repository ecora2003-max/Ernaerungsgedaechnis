# Integration: Status und Wiederaufnahme (31.10.2026)

**INTEGRATION_IN_PROGRESS / HOST_POLICY_PORT_JVM_TESTED. Keines der vier Module vollständig Android-integriert.** Haupt-App weiterhin 0.9.0-recognition; Room 7 (35 Tabellen), Testdaten-Sandbox, keine produktive Google-Sheets-Migration.

## Gelesene Ausgangsstände, nur Read-only

| Repository | main HEAD | Abnahmegrenze |
| --- | --- | --- |
| EG-Architecture | d7aa7dda1f0a34b52102b35785195e7ade26ceb4 | v1-Schema bedde4677342817fe6b883962c8dfbbd60100072; v1.1-Draft dca777d219307a90bf3d4dff6189fbf12476c508, **nicht freigegeben** |
| EG-Preisdaten | 5c52bf708a3bfa91e5bfd309bbddc308b3d6766a | PARTIAL_READY; letzter im STATUS genannter CI-Code 21253ef, 108 Offline-Tests; Nutzungsrechte/Liveabdeckung offen |
| EG-Produktdaten | 6a04f65127feb70624d25f16fcb4fc9404c9392f | READY_FOR_INTEGRATION nur Python-Offline; STATUS nennt 131 grüne Tests für 1f85b17; Kotlin-Port offen |
| EG-Einkaufsintelligenz | a3e13b147124ad7c25698a695e2ab42d7299a58a | READY_FOR_INTEGRATION nur Python-Offline; STATUS nennt 134 grüne Tests für 21cfde31; Kotlin-Port/v1.1-Stabilität offen |
| EG-Rezept-KI | 6581eabd5c9c38e387fb0b73f4c9747b75d323e3 | PARTIAL_READY; STATUS nennt 220 JVM-Tests und 74 Schemafälle für 5b6320d; kein Android-LLM-/Gerätetest |
| Haupt-App vor diesem Schritt | 6ba419ed2875c1988f45afee65e2708e7895cacf | Historische Phase-5-Abnahme 183 JVM-Tests am damaligen Code, **kein neuer Integrationsnachweis** |

Modultestzahlen sind deren dokumentierte vergangene CI-Ergebnisse, **nicht** in dieser Host-Sitzung erneut ausgeführte Tests. Fach-Commits und geprüfte Commit-SHAs können abweichen. Neueste CI-Logs vor Freigaben zwingend auswerten.

## In dieser Sitzung im einzigen Schreibrepository vorbereitet

- Neue pure Kotlin-Host-Grenze: app/src/main/java/de/ernaehrung/demo/data/integration/ExternalPriceBridge.kt. Injektionsport ExternalPriceCandidateSupplier; ReadOnlyExternalPricePort verweigert nicht zulässige Händlerfunktionen und private Kontexte vor Provider-Aufruf; unabhängiges Rechtegate default DENY_ALL; geprüfte Produktversion, Menge, EUR-Cents, Pfand, Quelle, Markt, Datum. Kein IO, keine Room-/FoodStore-Schreibfunktion und keine produktive Aktivierung.
- app/src/test/java/de/ernaehrung/demo/ExternalPriceBridgeTest.kt: 10 synthetische JVM-Testmethoden für alle 8 Händler, APP_DISPLAY-Sperre, fehlende Rechte, Bon vs Fremdquelle, Scope/Identität, Hindsight, Altangebote, Null-vs-Zero, Pfand, gemischte/duplizierte Beobachtungen, Masseneingang und kein verbotener Supplier-Aufruf.
- docs/INTEGRATION.md als Architektur- und Adaptermatrix. Keine Room-Migration, Änderungen bestehender Buchungen, vorhandener UI oder externer Livequellen.

### Tatsächlich in dieser Sitzung geprüft

1. Reiner Kotlin-Core mittels kotlinc nach /tmp/eg-integration/bridge.jar: PASS (Kompilierung).
2. Alle 10 synthetischen Testmethoden mit kotlinc / lokalem minimalem JUnit-API-Stub + Coroutines-JAR und eigenem Reflexions-Testtreiber ausgeführt: **10 PASS, 0 FAIL**. Das war ein isolierter JVM-Test ohne Android-Gradle und ohne echten JUnit-Runner.
3. GitHub-Action des Host-Commits ist **erst nach dem Push** auszuwerten; noch nicht als erfolgreich behaupten. Insbesondere lintDebug, testDebugUnitTest, compileDebugAndroidTestKotlin, Schemaexport-/1→7-Migrationsprüfungen und Gerätetests werden durch die lokalen Isolattests nicht ersetzt.

## Nächster Lauf: genaue Wiederaufnahme

- Zuerst main HEAD, docs/STATUS.md und tatsächliche GitHub Actions des neuen App-Commits prüfen. Bei Fehlern Code/Tests korrigieren und erneuten vollständigen Gradle-Lauf bestätigen; gezählte Test-XML auswerten.
- Erst danach v1-DTO-Mapping und vertrauenswürdige Host-Rechte-Registry; kein externer Feed vor belegter Freigabe. Bon→Bestätigung→Kauf→Preisgedächtnis→historischer Vergleich mit Idempotenz, expliziter Produktversion, Rechnung/Pfand und sicherer Stornoverarbeitung synthetisch implementieren.
- Python-Produkt-/Einkaufskern Kotlin-seitig mit paritätischen Fixtures anschließen; Rezept-KI nur offline/unbestätigt mit Host-Allergengates. Externe Händlerrechte/APP_DISPLAY und v1.1-Vertragsfreigabe weiterhin offen.
- App-JVM-/Room-/Migration-/Compose-/Geräte-E2E sowie Privacy/Offline-Nachweise sind Pflicht vor INTEGRATED; keine vorgezogene Freigabe. Ausschließlich Haupt-App verändern; Architektur und alle Fachrepos nie schreiben.
