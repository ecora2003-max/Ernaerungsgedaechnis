# Haupt-App: verbindliche modulübergreifende Integrationsabnahme

Stand 05.11.2026; aktive Priorisierung gemäß [Ausführungsplan](INTEGRATION_EXECUTION_PLAN.md). Die Haupt-App ist das **einzige** Schreibrepository; ausschließlich Codex entwickelt, ohne GitHub-Sperren oder automatische Lock-Recovery. A/B/III/IV bleiben pausiert; parallele Schreibsitzungen sind weiterhin zu vermeiden. Dieses Dokument definiert **zusätzliche** Gates; es ersetzt weder `AGENTS.md` noch die bestehende Phase-5-Regression noch die fachlichen Modulkriterien. Der Stand je Gate steht in `docs/STATUS.md`. **Keine dieser Gates darf aus einem Statusbericht allein als bestanden gelten.**

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

## 3a. Abnahme der priorisierten bedienbaren Nutzerketten (ab 03.11.2026)

- [ ] **A1 Einkaufsablauf:** tatsächlicher Einstieg in der Android-Oberfläche mit Bon/Kamera/OCR oder lokalem Ersatz, Nutzerkorrektur der Artikel-/Barcode-/Filialversion, explizitem Kauf-Commit über FoodStore, dauerhaftem privaten historischen Preis, korrektem Vergleich und konkret sichtbarem Einkaufsvorschlag. Negativ/Retry/Storno/Offline/Neustart/Rechte-/Zeit-/Unbekannt-Gates aus §3 und §4; ein synthetischer Unit-Test ohne UI reicht **nicht**. Fehlende Livequellen führen zu einem brauchbaren Offlinepfad.
- [ ] **B1 Rezeptablauf:** reale Generator-/Revalidator-Hostanbindung, alle acht Kontextkombinationen, aktuelle Nährwert-/Allergen-/Kochstatus-/Versionsprüfung, ausdrückliche und unabhängig bis zur FoodStore-Grenze belegte Nutzeraktion, gespeicherte Rezeptversion, bestätigte Herstellung und **separater** Verzehr. Negativ-/Abbruch-/Retry-/Neustart-/Offline-/Privacy-Gates; kein Speichern allein auf Entwurf/OS-Dialog/Checkpoint.
- [ ] **Android-Gerätebeleg:** `connectedDebugAndroidTest` oder nachvollziehbarer gleichwertiger Emulator-/Geräte-GUI-Lauf auf **exaktem Code-/Test-Commit** mit protokolliertem Gerät/API, getesteter Strecke, Pass/Fail/Skip und Artefakt/Run; explizit Kamera/OCR, Persistenz/Neustart, Offline und Datenschutz. Bei fehlender Hardware/Emulatorinfrastruktur als **BLOCKIERT/OFFEN** kennzeichnen, keine fiktiven Testresultate.
- [ ] **Meilenstein-Gate:** Erst wenn der gesamte Nutzerablauf und die relevanten vorhandenen Architektur-/FoodStore-/Schema-/Integritätsgates nachgewiesen sind, A1 oder B1 als abgeschlossen kennzeichnen. JVM-Tests bleiben Pflicht, ihre Anzahl ist kein Fortschritts-Prozentsatz. Keine Modulfreigabe allein durch Dokumentation.

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

Bei **jeder materiellen Code-/Teständerung** möglichst im selben Code-PR kurz in `docs/STATUS.md` protokollieren (kein separater Status-PR als Routine): gepinnte Modul-/Vertrags-/Host-SHAs, Dateien und Schnittstellen, tatsächlich ausgeführte Kommandos/Actions-Run-URLs, 0/positive Pass/Fail/Skip-Zahlen, separate Geräteprüfungen, offene Gates und den nächsten ausführbaren Schritt. **Nie eine vorhandene Testdatei oder einen gestarteten CI-Lauf als bestandenen Test ausgeben.**

## 6. Fitness K01–K04 – zusätzliche Gates A–E aus Nutzerauftrag #253

Quelle: [Verbindlicher Zielauftrag #253](https://github.com/ecora2003-max/Ernaerungsgedaechnis/issues/253), basierend auf Nutzer-PDF Version 1.0 / 01.11.2026. Diese Ergänzung schwächt keine bisherigen Einkaufs-, Rezept-, Daten- oder Gerätekriterien ab.

- [ ] **A – Architektur/Datenhoheit:** getrennte reine Fitness-DTOs, Quelladapter, persistenter Import und UI; Originalwerte/ID/Paket/Gerät/Einheit/UTC/gelieferter Offset/Qualität/Korrekturversion erhalten. Fitness liest freigegebene bestätigte Ernährungssnapshots und erzeugt niemals Verzehr-/Vorrats-/Kaufbuchungen. Erhaltende Room-Migrationen und alte IDs/Retry-Schlüssel vollständig prüfen.
- [ ] **B – realer Watch-4-Import [N]:** auf echtem gekoppeltem Smartphone neue Watch-Schritte **und** mindestens ein reales Watch-Training über Samsung Health und Health Connect programmgesteuert nach ausdrücklicher Berechtigung bis EG nachweisen. Quellen-/Geräte-/Appversionen und Sync-Zeit sichtbar; erneuter Import ohne Dubletten, Änderungen/Löschung, Widerruf, fehlende Daten und Offlinebestand geprüft. Manuelle Abschrift, CSV, Mock, Connect-Button und kompilierte Tests genügen nicht; kein „Watch integriert“ ohne diesen Nachweis.
- [ ] **C – K01–K03:** Rohmessung und BIA-/Geräteschätzung unterscheiden, gleiche/verschiedene Geräte und Messbedingungen kenntlich machen, nachvollziehbare Korrekturhistorie; überlappende manuelle/Watch-Trainings nicht blind addieren. Rohpunkte und methoden-/quellenbewusste Trends mindestens für 7/28/90 Tage mit echten Lücken, Ausreißern und Zeitzonen-/Sommerzeitwechseln prüfen.
- [ ] **D – K04:** reproduzierbare versionierte Energieschätzung aus tatsächlicher EG-Zufuhr, belastbarem Körpertrend und eindeutig definierter aktiver Energie; Gesamt- und Workout-/Tageswerte ohne Doppelzählung. Bei unzureichender Abdeckung Kalibrierung sperren oder transparent vorläufig halten, keine autonome Kalorienzieländerung. Offene Methoden-/Variabilitäts-/Unsicherheitsparameter nicht als beschlossene Nutzerwerte ausgeben.
- [ ] **E – Nachweise/Privacy:** genaue Host-/Vertrags-SHAs, Befehle/Run-URLs, Umgebung und Pass/Fail/Skip; JVM, echte Android- und physische Watch-Tests getrennt. Synthetische Daten in CI, keine Gesundheitsrohwerte/Secrets in GitHub/Logs, kein ungefragter Export. Alle historischen Migrationspfade, Neustart, Offline, Permission-Revocation, History-/Background-Grenzen, DST, Replay und Quellenwechsel prüfen. Ein fehlendes Uhrfeld bleibt unbekannt.

SDK-Fallback, optionale Herzfrequenz-/Schlaf-/BIA-Berechtigungen, genaue Krafttrainingsdetailtiefe und Statistik-/TDEE-/Exportschema bleiben [O]. Der minimale primäre Quellweg ist zu prüfen, bevor weitergehende Felder als tatsächlich verfügbar dargestellt werden.
