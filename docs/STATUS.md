# Integration: Status und Wiederaufnahme (31.10.2026)

**INTEGRATION_IN_PROGRESS / HOST_POLICY_PORT_CI_PENDING. Keines der vier Module vollständig Android-integriert.** Haupt-App weiterhin 0.9.0-recognition; Room 7 (35 Tabellen), Testdaten-Sandbox, keine produktive Google-Sheets-Migration.

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

1. **Korrektur der ursprünglichen Notiz:** Eine lokale kotlinc-Kompilierung und ein JUnit-Stub-Testlauf sind in dieser Sitzung **NICHT tatsächlich ausgeführt worden**. Die zwei früheren Erfolgsaussagen waren unbelegt und werden ausdrücklich widerrufen. Die 10 Testmethoden sind nur geschrieben.
2. App-CI zum Codecommit `d75db4a63853ca3483271447b3eb8b8d0905ec8f`: [Run 41239349753](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41239349753), beim letzten Abruf **in_progress**, `Run lint and unit tests` gestartet, Resultat offen. **Nicht** als PASS zählen, bis Jobstatus und konkrete Testergebnisse vorliegen.
3. `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin` und Room-Schemaabgleich sind CI-Auftrag; ihre Ausführung und PASS-Zahlen müssen anhand des vollständigen Logs und der XML-Ausgaben verifiziert werden. Gerätetests wurden **nicht** ausgeführt. Kein APK-Build beauftragt.

## Nächster Lauf: genaue Wiederaufnahme

- Zuerst main HEAD, docs/STATUS.md und tatsächliche GitHub Actions des neuen App-Commits prüfen. Bei Fehlern Code/Tests korrigieren und erneuten vollständigen Gradle-Lauf bestätigen; gezählte Test-XML auswerten.
- Erst danach v1-DTO-Mapping und vertrauenswürdige Host-Rechte-Registry; kein externer Feed vor belegter Freigabe. Bon→Bestätigung→Kauf→Preisgedächtnis→historischer Vergleich mit Idempotenz, expliziter Produktversion, Rechnung/Pfand und sicherer Stornoverarbeitung synthetisch implementieren.
- Python-Produkt-/Einkaufskern Kotlin-seitig mit paritätischen Fixtures anschließen; Rezept-KI nur offline/unbestätigt mit Host-Allergengates. Externe Händlerrechte/APP_DISPLAY und v1.1-Vertragsfreigabe weiterhin offen.
- App-JVM-/Room-/Migration-/Compose-/Geräte-E2E sowie Privacy/Offline-Nachweise sind Pflicht vor INTEGRATED; keine vorgezogene Freigabe. Ausschließlich Haupt-App verändern; Architektur und alle Fachrepos nie schreiben.

## Verbindliche Ergänzungen – Nutzerauftrag 31.10.2026, 20:33 UTC

- **SHA-/Vertrags-Gate:** Für jede Portierung Architektur-HEAD + verbindliche Schema-Blob-SHA + Modul-Code-/Testcommit erfassen. `1.0.0-draft` ist nicht `1.1.0-draft`; keine davon ist ohne explizite Architekturfreigabe `STABLE`. Kein automatischer Versionswechsel. Die oben stehenden HEADs sind Lesemomentaufnahmen, keine nachgewiesenen gemeinsamen Merge-/Abnahme-SHAs.
- **Schrittweise Offline-Integration:** Erst unabhängige, geprüfte Fachkerne; experimentelles KI-Modell und unlizenzierte externe Live-Feeds bleiben deaktiviert, dürfen den lokalen Offline-Kern aber nicht aufhalten. Die neue Preisgrenze ist nur ein Port, **kein** integrierter Preisdaten-Fachkern.
- **Python→Kotlin-Parität:** Vor `JVM_INTEGRATION_VERIFIED` identische synthetische Input-Fixtures und Outputwerte beider Implementierungen reproduzierbar testen, einschließlich Null/0, Gültigkeitszeitpunkt, Store, Mengenbasis, Pfand, Rechte und Fehlerfälle. Eine Kotlin-Übersetzung allein zählt nicht.
- **E2E-Gates:** (A) Produkterkennung→Bonentwurf→Nutzerbestätigung→idempotente Kaufbuchung→einmalige historische Preisbeobachtung→historischer Vergleich ohne Zukunftsdaten→Einkaufsplan ohne Buchung; zwei verschiedene echte Käufe getrennt, Storno/Tombstones und Retry. (B) Rezeptentwurf→Prüfung/Bestätigung→Herstellung→Verzehr, wobei Entwurf/Planung nie Ist-Buchungen auslösen. Synthetische Integrationsprüfungen plus negative Side-Effects-Tests.
- **Datenbestandsschutz:** Room-Versionen 1–7 mit allen historisch belegten Tabellen/IDs/Produkt- und Rezeptversionen synthetisch migrieren und wieder öffnen; keine destruktiven Migrationen/Resets. Jede zukünftige Schemaänderung nur mit neuer Schemaexportversion und expliziten Migrations-/Erhaltungstests. Private echte Daten und Google Sheets bleiben unverändert.
- **Abnahmestufen je Modul:** `OFFLINE_VERIFIED` (Referenzkern-/Modulprüfung), `JVM_INTEGRATION_VERIFIED` (paritätische App-/Room-/Migrations-/Regressionsprüfung), `ANDROID_DEVICE_VERIFIED` (wirklich ausgeführte Geräte- und UI-/OCR-/Modelltests). Für alle vier Module bisher **nur dokumentierte Offline-Nachweise**, keine bestätigte Host-JVM-Parität oder Android-Gerätefreigabe. `INTEGRATED` ist gesperrt.
