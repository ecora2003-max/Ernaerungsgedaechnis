# Prüfung des Phasenabschlusses 1–5

Version `0.9.0-recognition`, versionCode 9, Schema 7 (35 Tabellen), 30.10.2026. Nur synthetische Testdaten. APK zum Phasenabschluss auf ausdrücklichen Nutzerwunsch erfolgreich gebaut und separat geprüft; Download/Hash/Ablauf im Übergabeprotokoll.

Prüfbefehl mit SDK 35/Build Tools 34, Gradle 8.9, JDK 21/Java-17-Bytecode:

```sh
python /workspace/tooling/run-gradle.py --continue :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
```

Lokaler Abschluss: **BUILD SUCCESSFUL**, 44 Sekunden mit vorbereiteten Abhängigkeiten. Alle **21 aktuellen XML-Berichte** (15:16:23–15:16:44 UTC) ausgewertet: **183 Tests, 0 Fehlschläge, 0 Fehler, 0 Skips**. Lint 13 Bibliotheks-Updatehinweise, keine Fehler. Gerätetests erfolgreich kompiliert. Schemaexporte 1–6 unverändert, Schema 7 neu versioniert. GitHub führt denselben Befehl mit JDK 17 aus und prüft das versionierte Schema. Das Actions-Ergebnis ist am Code-Commit abrufbar; kein zusätzlicher App-Build nur zum Ergänzen dieser Notiz.

Die Prüfungen decken insbesondere ab:

- Tatsächliche EAN-Pixel dekodieren, PNG-Rundlauf, 90°-Drehung, mehrere Codes, leeres Bild, begrenzte Bilddaten, echte JPEG-EXIF-Ausrichtung und vollständigen privaten Content-URI-Pfad. FileProvider gibt nur die vorgesehene temporäre Datei frei.
- GTIN-Prüfziffer/führende Nullen, UPC/EAN-Gleichheit, fremde Antwortidentität, HTTP-Antwortgrenzen, unbekannte Werte versus Null, keine Verwechslung von Portions- und 100-g-Werten, Mineral-/Vitaminumrechnung. Öffentliche OFF-Antworten zusätzlich lesend stichprobenartig geprüft; keine Live-Netzwerkabhängigkeit der Tests.
- Barcode-/Händleraliase: exakte Varianten, Mehrdeutigkeit, unbekannte/alte Versionen, Korrektur/Rücknahme, alte Retries und parallele Entscheidungen. Produktversion plus Barcodezuordnung rollen bei SQLite-Fehler gemeinsam zurück; frühere Verzehre behalten alte Nährwerte.
- Entwurf ohne Zahlung/Bestand, Originaltext erhalten, Bild-Prüfwert verhindert doppelte Entwürfe auch bei Parallelität. Genau eine Auflösung, Annahme versus Verwerfen unter Konkurrenz, Doppelzahlungsprüfung, ungültige/ungeprüfte Angaben und veraltete/zwischenzeitlich zurückgeänderte Aliasdaten. SQLite-Abbruch nach begonnenem Beleg-/Zahlungsschreiben hinterlässt keine Teilwirkung oder verbrauchten Schlüssel.
- Real erzeugter Host-OCR-Text eines synthetischen Testbons: verlorenes Dezimalkomma bleibt unbekannter Positionspreis, lesbarer Gesamtbetrag bleibt 4,97 €. Bar/Rückgeld/Steuer, negative Unicode-Beträge und teilweise widersprüchliche Gesamtzeilen werden nicht zu erfundenen Zahlungen.
- Spät zurückkehrende Antworten und Fehler nach abgebrochener Anfrage überschreiben kein neueres Ergebnis. Bildfehler räumen temporäre Dateien auf; OCR-Ergebnis allein speichert keine Ausgabe.
- Sechs neue Bedienfälle: lokaler Offline-Barcode ohne externen Aufruf, editierbare OFF-Daten samt Herkunft/Null/Lücke, manueller Offline-Ersatz, mehrzeiliger Bon mit offenen Positionen und korrigierter Zahlung, geänderter Händler entfernt automatische Zuordnung, Aliasrücknahme nach neuem Vorgang. 320×640 dp mit Schriftfaktor 1,3 und Screenshots visuell geprüft.
- Sechs Dateimigrationswege aus veröffentlichten Exporten 1–6; alle 22/25/26/28/29/32 alten Tabellen befüllt, jede alte Spalte/Zeile verglichen. Bezahlte Belege, Positionszugänge, Ausgabenstatus, Pläne und frühere Retry-Fingerprints erhalten. Fremdschlüssel und Wiederöffnung geprüft. Bestehende Tests der Phasen 1–4 bleiben aktiv.

Gefundene und korrigierte Fehler: gedrehte Barcodequellen konnten nicht zugeschnitten werden; `RUECKGELD` wurde als Ware gelesen; unsichere OCR-Preise gingen als Position verloren; alte asynchrone Fehler konnten neuere Abfragen ersetzen. Die zusätzliche Content-URI-Prüfung fand außerdem einen zwischen Robolectric-Testanwendungen verbliebenen FileProvider-Pfadcache. Der Testprovider wird nun pro Test über die öffentliche Android-API korrekt neu angebunden; die vollständigen Freigabe-/Pfadassertions bleiben erhalten. Keine Assertions abgeschwächt und keine Tests deaktiviert.

**Abgrenzung:** Native Kamera/ML Kit hier ohne Android-Gerät/Emulator nicht ausgeführt. Zwei zusätzliche Instrumentationstests für OCR-Testbon und echten URI-Barcodepfad wurden erfolgreich kompiliert; ausdrücklich kein Ausführungsnachweis. Host-OCR mit Tesseract ist ein Test für realistische OCR-Eingaben des Parsers, kein Ersatznachweis für ML Kit auf Android. Große Historien, reale Händler-/Allergendaten, Produktionsmigration, Backups und Signierung bleiben spätere Aufgaben.

GitHub-Abschluss: [41080374241](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41080374241), SHA `4bb5b0f957e4d936a711e045f57431bef4ca0f90`, JDK 17, **success**. Alle 21 aktuellen heruntergeladenen XML-Dateien (15:31:57–15:32:56 UTC) ausgewertet: **183 Tests, 0 Fehlschläge/Fehler/Skips**. Lint 14 Hinweise/keine Fehler; Schema und Kompilierung der zwei Gerätetests erfolgreich. APK 0.9.0/code 9, minSdk 26/targetSdk 35, v2-Signatur nach Download überprüft. APK und Berichte bis 06.11.2026 verfügbar. Details: `UEBERGABE_2026-10-30_PHASE5.md`. Nachfolgende reine Dokumentationsänderung benötigt keine erneute App-Prüfung.
