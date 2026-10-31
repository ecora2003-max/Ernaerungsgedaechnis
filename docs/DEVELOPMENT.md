# Entwicklungsstand / Fortsetzung

> **Aktueller Integrationsstand (31.10.2026):** Dieses Dokument ist die historische Übergabe der Phase 5 vom 30.10.2026. Die nachfolgenden Angaben zu Room 7, 35 Tabellen und 183 Tests beschreiben den damaligen Stand, nicht den neuesten Host. Die Integration verwendet mittlerweile **Room 8 (36 Tabellen)**; aktueller Code-/Test-/Modulstatus und nächste Schritte stehen verbindlich in [STATUS.md](STATUS.md), Abnahmegrenzen in [ACCEPTANCE.md](ACCEPTANCE.md), Portarchitektur in [INTEGRATION.md](INTEGRATION.md). Keines der vier Fachmodule ist vollständig Android-integriert; Gerätetests bleiben offen.

Stand 30.10.2026. Ausführliche Wiederaufnahme: [UEBERGABE_2026-10-30_PHASE5.md](UEBERGABE_2026-10-30_PHASE5.md). Zuerst diese Übergabe und `AGENTS.md` lesen. Fachlicher Produktplan: `product/Gesamtplanung-2026-10-30.txt`.

## Auftrag und Arbeitsweise

Aktueller Auftrag: zusammenhängend bis einschließlich **Phase 5** der Integrationsfolge in Punkt 30 entwickeln. Der funktionale Kern bis Phase 5 ist implementiert. Phase 5 bedeutet **Barcode, Produktdatenabfrage, Bonfoto, OCR und Aliase**; JSON-Austausch ist Phase 6, Körper/Training Phase 7 und Produktionsreife Phase 8.

GitHub-Sicherungen/CI an fachlichen Phasengrenzen oder auf ausdrückliches Nutzersignal bündeln. Keine aufwändigen Zwischensicherungen und Wiederholungsberichte pro Teilschritt; kurze nützliche Übergabe genügt. Gezielte Tests während der Entwicklung, vollständige passende Prüfung zum Abschluss. Bei Kontingent-/Unterbrechungsauftrag schnell Code + knappen Stand sichern. APKs nur für bewusst ausgewählte Meilensteine, nicht automatisch bei „weiter“. Automatisches CI bleibt ohne Download-APK; manuell `build_apk=true` nur bei ausgewähltem APK-Meilenstein.

Weiterhin isolierte Testdatenbank `food-local-sandbox.db`; keine persönlichen Daten übernehmen und keine produktiven Sheets-Daten ändern. Kein eigener Server, keine verpflichtende kostenpflichtige KI-API. Cloud-Umgebung nicht eigenständig veröffentlichen.

## Aktueller Stand: 0.9.0-recognition

VersionCode 9, Schema **7**, **35 Tabellen**. Entwicklung auf `development/phase5-recognition`, Sicherung zum Phasenabschluss auf diesem Branch und `main`. Der automatische Actions-Status hängt am Code-Commit; kein weiterer App-Build nur für eine CI-Ergebnisnotiz.

- Phase 1: Room/SQLite, konkrete Versionen, historische IDs, Migrationen und Transaktionen. Keine zweite schreibbare Datenwahrheit.
- Phase 2: tatsächliche Käufe, Zugänge, Verbrauch, Umlagerung, Zählkorrektur/Entsorgung, Herstellung und nachvollziehbare Stornos. Zahlung zählt einmal, Lagerzugänge separat.
- Phase 3: Rezepte, tatsächliche Zutaten/Ausbeute, Verzehr und Tagesbilanz mit getrennten Nährstofflücken und explizitem Tagesstatus.
- Phase 4: gemeinsame 1-/7-/56-Tage-Planung, dauerhafte Positionen und Ist-Zuordnung, offener Restbedarf, Packungen/Preise/Listenentscheidungen sowie Budget nach bestätigtem Ausgabenabgleich.
- Phase 5: Scan öffnet die lokale Erkennung. Systemkamera/Fotoauswahl/manuelle Eingabe; ZXing-Barcodebilder mit Prüfziffer/Drehung; Offline-Katalog und ausdrückliche OFF-Abfrage; editierbare Produktwerte mit Quelle, Basisprüfung und atomarer optionaler Barcodezuordnung. ML-Kit-OCR mit gebündeltem Modell, ursprünglicher Bontext und Entwürfe, Händleraliase mit bestätigten aktuellen Versionen, mehrdeutige/alte Zuordnungen offen. Einmalige atomare Annahme oder Verwerfen; Bildhash/Parallelitäts-/Doppelzahlungs-/Snapshot-Schutz. Kein Scan bucht automatisch Nahrung, Bestand oder Zahlung.

UI: **Scan** oder Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen. Bereiche Katalog/Lager/Ernährung/Küche/Planung/Einkauf/Käufe/Erkennung/Chronik. Andere Hauptansichten bleiben überwiegend Demobeispiele. Regeln/Bedienung: `RECOGNITION.md`, `PURCHASES.md`, `SHOPPING.md`, `PLAN_EXECUTION.md`, `PLANNING.md`, `NUTRITION.md`, `LOCAL_DATABASE.md`.

## Aktuelle Prüfung

Manueller [APK-/Prüflauf 41080374241](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41080374241) auf `4bb5b0f957e4d936a711e045f57431bef4ca0f90` **erfolgreich**: 21 heruntergeladene aktuelle XML-Berichte ausgewertet, **183 Tests, 0 Fehlschläge/Fehler/Skips**; Schema/Lint/Gerätetest-Kompilierung und APK erfolgreich. APK-Version 0.9.0/code 9 und v2-Signatur geprüft. [APK-Artefakt](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41080374241/artifacts/12990056668) bis 06.11.2026, 16:35 Uhr Europe/Berlin verfügbar. Vollständige Metadaten/Hash im ausführlichen Übergabeprotokoll. App-Code gegenüber `cc062ef` unverändert; Folgecommits betreffen nur Workflow-Aufbewahrung/Dokumentation. Keinen neuen Build für diese Ergebnisnotiz starten.

Vollständiger lokaler Abschluss: **183 Tests, 0 Fehlschläge/Fehler/Skips**, 21 aktuelle XML-Berichte (15:16:23–15:16:44 UTC) ausgewertet. Lint 13 Updatehinweise, keine Fehler. Routine einschließlich Gerätetest-Kompilierung erfolgreich in 44 Sekunden mit vorbereiteten Abhängigkeiten. Kein Test abgeschwächt. Bericht: `TEST_REPORT_PHASE5.md`.

Tatsächliche Barcode-Pixel, Drehung/mehrere Codes, EXIF und Content-URI-Pfad geprüft. Reale Host-OCR eines synthetischen Testbons prüft Parserverhalten bei verlorenem Dezimalkomma; kein Ersatz für Android-OCR. Alias-/Bontransaktionen einschließlich SQLite-Abbruch, Parallelität, Stornos/alte Retries, veraltete Netzantworten, Wiederöffnung und Bedienung geprüft. Kleindisplay/Schriftfaktor 1,3 visuell geprüft. Alle bestehenden Phasen-1–4-Tests weiterhin aktiv.

**Kein Android-Gerät/Emulator vorhanden:** native ML-Kit-OCR und physische Kamera hier nicht ausgeführt. Zwei Instrumentationstests erfolgreich kompiliert, nicht als bestanden gezählt. Auf Gerät/Emulator: `./gradlew :app:connectedDebugAndroidTest`. Auf ausdrücklichen Nutzerwunsch wird zum Phasenabschluss ein manueller Actions-Lauf mit `build_apk=true` gestartet. APK-Ergebnis/Download unter Actions am Code-Commit prüfen; Gerätetests bleiben davon getrennt.

## Migration und Werkzeuge

Schemaexporte 1–6 unverändert. Migrationen 1→2→3→4→5→6→7 registriert, kein destruktiver Fallback. Sechs Dateitests aus veröffentlichten Exporten mit allen 22/25/26/28/29/32 alten Tabellen befüllt, jede alte Spalte/Zeile erhalten, Fremdschlüssel/Retry-Schlüssel/neue Buchungen/Wiederöffnung geprüft. Schema 7 ergänzt nur Aliasentscheidungen, Scanentwürfe und eindeutige Auflösungen. Neue Strukturänderungen nach Veröffentlichung als Schema 8+.

SDK `/workspace/tooling/android`, Gradle-Cache `/workspace/tooling/gradle`, lokaler Helper außerhalb des Repositories:

```sh
python /workspace/tooling/run-gradle.py --continue :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

JDK 17+ (lokal 21, CI 17), SDK 35/Build Tools 34, Gradle 8.9. Proxy/CA/Anmeldung geerbt verwenden; keine Geheimnisse/SDK-Caches/App-Daten committen. In Migration 4→5 muss der Selbst-FK der umgebauten Preistabelle auf den endgültigen Namen `price_observations` zeigen.

## Pause / Als Nächstes

Nutzer meldet nur noch 35 Credits und anschließend etwa fünf Tage Pause. Phase 5 jetzt sichern und die ausdrücklich angeforderte APK-Erstellung anstoßen. APK und Testberichte deshalb sieben Tage aufbewahren. Der erste manuelle Lauf 41080103685 wurde für diese Aufbewahrungsänderung bewusst abgebrochen; der nachfolgende manuelle Lauf mit `build_apk=true` ist maßgeblich. Keine weitere Phase vor dieser Pause beginnen. Nach Rückkehr zuerst diesen Stand und den neuesten manuellen Actions-Lauf prüfen, dann auf Nutzerauftrag fortsetzen.

Phase 6 auf Nutzerauftrag: versionierter gezielter JSON-Export, validierter Import mit Identitäten/konkreten Versionen, Mengen/Einheiten und Aktualitäts-/Bestands-/Budgetkonflikten, kontrollierte Übernahme ausschließlich als Planung. Import darf nie tatsächlichen Verzehr, Einkäufe oder Ist-Bewegungen erzeugen. Quellen und Unsicherheit erhalten; kein API-Zwang.

Offene Grenzen für spätere Schritte: echte Geräteprüfung/ungewohnte Bonlayouts, ergänzende Belegkorrekturfassungen/Erstattungen und Umgang mit unbezahlten Altbelegen, Anbindung weiterer Hauptansichten, Backups/Release-Signierung/Produktionsmigration. Die konservative Bestandsrückdatierungssperre kann historische Ersatzkorrekturen nach einem heutigen Storno verhindern; vor Produktivbetrieb atomare Korrekturen mit erhaltenem tatsächlichem Datum lösen. Niemals ein falsches Datum als Umgehung empfehlen.

Historische Stände und Prüfberichte bleiben im Repository: 0.8 `TEST_REPORT_PURCHASES.md`, 0.7 `TEST_REPORT_SHOPPING.md`, 0.6 `TEST_REPORT_EXECUTION.md`, 0.5 `TEST_REPORT_PLANNING.md`, 0.4 `TEST_REPORT_NUTRITION.md`, 0.3 `TEST_REPORT_TRANSACTIONS.md`, 0.2 `TEST_REPORT_FOUNDATION.md`. Keine früheren Läufe als Nachweis für aktuellen Code verwenden.
