# Haupt-App: verbindliche modulübergreifende Integrationsabnahme

Stand 31.10.2026. Die Haupt-App ist der **einzige** schreibende Integrator. Dieses Dokument definiert **zusätzliche** Gates; es ersetzt weder `AGENTS.md` noch die bestehende Phase-5-Regression noch die fachlichen Modulkriterien. Der Stand je Gate steht in `docs/STATUS.md`. **Keine dieser Gates darf aus einem Statusbericht allein als bestanden gelten.**

## 1. Version / unveränderliche Vertrags-SHAs

- [ ] Alle fünf Leserepositories bei jeder Integrationsphase auf aktuellen HEAD und den konkret **getesteten** Commit pinnen; beide SHAs getrennt dokumentieren, falls ungleich.
- [ ] `EG-Architecture/contracts/v1`: `1.0.0-draft`, `contracts/v1/contract.schema.json` Blob `bedde4677342817fe6b883962c8dfbbd60100072`; nicht als STABLE behaupten.
- [ ] `EG-Architecture/contracts/v1.1`: **separater, nicht freigegebener Entwurf** `1.1.0-draft`, aktueller Blob `dca777d219307a90bf3d4dff6189fbf12476c508`. Neue Felder `unknownNeeds[]`, `packageLines[]` und `savingsBasis=MERCHANDISE_ONLY` dürfen v1 nicht still verändern.
- [ ] Jede übernommene Nachrichtenstruktur vollständig gegen exakt die gepinnte Vertragsversion plus semantische Host-Tests prüfen, einschließlich Null vs 0, Integer-Cents, Menge/Basis, Händler-/Filiale, UTC-Offset/Gültigkeit, Quelle/Rechte, Provenienz.
- [ ] Neue Version erst durch ausdrückliche Architekturfreigabe und dokumentierte Abnehmer-/Host-Parität aktivieren. Bei nicht erfülltem Vertragsgate experimentellen Adapter ausschalten; bestehende stabile App-Funktionen nicht blockieren.

## 2. Unabhängig ersetzbare Modulports

- [ ] **Produktdaten:** Python-Offline-Matching, konkrete Produktversion, Barcode-/SKU-/Bonalias, aktuelle/alte Variante, Händler-/Filialscope, Mehrdeutigkeit, bestätigte historische private Bonpreise nach Kotlin portieren und mit offiziellen synthetischen Referenzfixtures **wertgleich** prüfen. Kein zweiter OCR-Dienst. Keine automatische Buchung.
- [ ] **Einkaufsintelligenz:** unabhängiger Kotlin-Kern mit deterministischen Grundpreisen/Bio-/Rot-Orange-Grau-Hinweisen, FEFO, kumulativen 1/7/56-Tage-Bedarfen, Gebinde/Mix, Depot/Pfand, Budget, Haltbarkeit, zeitkorrektem historischen Warenpreisvergleich; exakte Python-vs-Kotlin-Output-Regression mit gleichen Eingaben (einschließlich Fehlern, unbekannten Feldern und Scope).
- [ ] **Preisdaten:** ADR-016-Händlerfähigkeit vor Abruf und ADR-014-Quellenrecht vor Anzeige/Preisvergleich separat durchsetzen; zwei voneinander unabhängige Rechtegates. `APP_DISPLAY` nur mit hostseitig nachgewiesener Genehmigungsreferenz, keine Umdeklaration fremder Rechte. Kein Livefeed ohne quellenbezogenen zulässigen Nutzungsnachweis. Nicht genehmigte Provider bleiben deaktiviert, ohne lokale Funktionen zu blockieren.
- [ ] **Rezept-KI:** geprüften offlinefähigen Kotlin-Kern als `recipe.draft`-Vorschlag einbinden, drei unabhängige Kontextflags in **allen acht Kombinationen**, Nährwert-/Mengen-/Allergen-/Kochzustands- und Sicherheitsprüfung, Zeit-/Versionsvalidierung; synthetische Host-Tests. Optionales On-Device-Modell nur nach echter lokaler Android-Privacy-/Hardwareprüfung, nie als Cloudpflicht.
- [ ] Jeder Port bleibt unabhängig von Room und App-UI austauschbar; jede reale Buchung nur über die bestehenden bestätigten, atomaren/idempotenten `FoodStore`-Methoden. Keine unbestätigte Modulausgabe als neue Datenwahrheit.

## 3. Synthetische End-to-End-Pflichtketten

- [ ] **Produkt/Kauf/Preishistorie:** Erkennung → Bonentwurf (0 Ist-Ereignisse) → Alias-/Produktversionsentscheidung → ausdrückliche Kauf-/Zahlungsbestätigung → genau einmalige Position/Beleg-ID und historischer eigener Preis → Vergleich nur mit zum Kaufdatum bereits bekannten und gültigen Alternativen → Einkaufsbedarfsvorschlag (0 zusätzliche Ist-Ereignisse).
- [ ] Zweiter tatsächlicher Kauf mit ähnlichem Bon erhält eigene IDs/Beobachtungen; Scan-Retry, gleicher Bildhash, gleicher Vorgangs-Key, konkurrierende Klicks erzeugen keine doppelte Zahlung, keinen zweiten Lagerzugang und keine zweite Preisbuchung.
- [ ] Negative Pfade: unbestätigter Bon, mehrdeutiger Alias, alte Produktversion, fehlende Mengenbasis, fehlerhafte Dezimalstelle, gemischter Warenkorb, unbekannter Rabatt/Pfand, Retouren/Storno und Tombstone, zukünftige/abgelaufene Aktion, anderer Store, Coupon-/Online-Bedingung, fehlende Rechte und `null` vs echte `0`.
- [ ] **Rezept:** unbestätigter Entwurf → geprüfte, ausdrücklich bestätigte Rezeptversion → tatsächlich bestätigte Herstellung (nur Zutaten-/Outputbuchung) → separat bestätigter Verzehr. Plan, Rezeptentwurf oder Herstellung allein dürfen keine Verzehrbuchung auslösen.
- [ ] Nach jeder Störung transaktionalen Rollback, Wiederholung, Neueinlesen, App-Neustart und zeit-/versionsrichtige Historie prüfen; nutzbare Vorräte nie negativ.

## 4. Room-Bestands-, Kosten- und Datenschutzschutz

- [ ] Synthetische bestandsgefüllte 1→neu, 2→neu, 3→neu, 4→neu, 5→neu, 6→neu und 7→neu Migrationspfade und Fremdschlüssel prüfen. Bei unverändertem Room 7 vorhandene 1→7-Regression vollständig erneut ausführen.
- [ ] Alte IDs, Produkt-/Rezeptversionen, Chargen, Beleg-/Zahlungs-/Reversal-/Preis-/Planereignisse unverändert erhalten; keine `fallbackToDestructiveMigration`, keine Datenbanklöschung als Workaround. Für neue Felder eine versionierte Schemaexportdatei und explizite Migration.
- [ ] Tests ausschließlich mit synthetischen Daten/Sandbox; kein produktiver Google-Sheets-Import oder Geräte-/Benutzerdatenexport ohne separate ausdrückliche Freigabe.
- [ ] Kernfunktionen kostenlos, offlinefähig und ohne Cloudpflicht; fehlende Daten/Quellen/LLM müssen korrekt deaktivierte Funktionen bzw. nachvollziehbar unvollständige Ergebnisse liefern.

## 5. Pflichtnachweise und Statusklassen

- **OFFLINE_VERIFIED:** Fachmodul selbst auf gepinntem SHA synthetisch getestet; kein Host-/Android-Beleg.
- **JVM_INTEGRATION_VERIFIED:** übernommener App-Kotlin-Code, Referenz-Parität, App-/Room-/Schema-/Migrations-/Regressionstests und passende synthetische E2E-Gates erfolgreich **auf demselben Host-Codecommit** ausgeführt und protokolliert.
- **ANDROID_DEVICE_VERIFIED:** wirkliche Instrumentation/GUI/Kamera/OCR/optional Modell/Offline-Privacy auf unterstütztem Android-Gerät/Emulator ausgeführt und überprüft; bloßes Kompilieren von `androidTest` zählt nicht.
- **INTEGRATED:** erst nach den jeweils relevanten Host-, Daten-, Betriebs-, Rechts- und Android-Nachweisen, ohne beschädigte Altfunktionen. `STABLE`-Kontraktfreigabe ist eine gesonderte Architekturentscheidung.

Bei **jeder** Änderung im Hauptrepository in `docs/STATUS.md` protokollieren: gepinnte Modul-/Vertrags-/Host-SHAs, Dateien und Schnittstellen, tatsächlich ausgeführte Kommandos/Actions-Run-URLs, 0/positive Pass/Fail/Skip-Zahlen, separate Geräteprüfungen, offene Gates und den nächsten ausführbaren Schritt. **Nie eine vorhandene Testdatei oder einen gestarteten CI-Lauf als bestandenen Test ausgeben.**
