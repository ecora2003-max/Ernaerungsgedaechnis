# ACCEPTANCE – verbindliche knappe Abnahme

**Prinzip:** Nur tatsächlich getestete Fähigkeiten als verfügbar melden. Der vollständige ältere Anforderungskatalog liegt unter [archive/governance-2026-11-18/ACCEPTANCE_FULL.md](archive/governance-2026-11-18/ACCEPTANCE_FULL.md); er ist eine **historische Nachschlagequelle**, keine zweite operative Freigabekette.

## Allgemeine Kriterien
- [ ] Der konkrete Nutzerablauf funktioniert Ende-zu-Ende in der App: Auswahl/Eingabe → Berechnung/Abfrage → lesbare Anzeige → bewusste Bestätigung → erwartete Persistenz, wo vorgesehen.
- [ ] Positive, negative, Grenz-/Unbekanntfälle und Wiederöffnung/Abbruch werden passend zur Änderung getestet. Fehlschläge nicht durch Stubdaten, weggelassene Assertierungen oder einen neuen Namen „PASS“ verdecken.
- [ ] Code-/Test-SHA, überprüfte Teststufe und echte Einschränkungen im PR festhalten. Nicht nach jeder kleinen Dokuänderung komplette Android-Gerätesuiten wiederholen; bei Daten-/UI-/Persistenzänderungen relevante Regression ausführen.
- [ ] Keine unbeabsichtigten FoodStore-Schreibvorgänge, keine verborgene Cloudweitergabe, keine neue Zustimmung/zahlungspflichtige Aktion und keine veröffentlichten Secrets/Bons. Room-Schema, Altdaten und Migrationen bleiben gültig.
- [ ] Schema v1 `1.0.0-draft`/Blob `bedde4677342817fe6b883962c8dfbbd60100072` sowie Android-Mindestversion API26 unverändert, sofern ausdrücklich beschlossener und getesteter Vertrags-/Migrationswechsel vorliegt.

## Angebots-/Preisfunktion
- [ ] Echter Endpoint/Quelle und Anbieter, gültiges Abrufdatum, tatsächliche Markt-/Region-/Kettenzuordnung und Quelle der Preisart sind getrennt dokumentiert. Kein Kettenangebot als bewiesener Filialpreis, keine gezielte Suche als vollständiger Katalog.
- [ ] Reale HTTPS-Antwort mit Standard-App-Pfad, passendem Android-Test und beobachtetem Ergebnis nachgewiesen, bevor **LIVE_VERIFIED** für diesen Scope gilt. Offline-Parser- oder synthetische Tests belegen nur ihre jeweilige Stufe.
- [ ] Packungs-/Grundpreise, Pfand, Aktions-/Coupon-/Onlinebedingungen, unbekannte Werte und Abdeckung korrekt; nur bestätigte vergleichbare Artikel/Produktversionen in Bedarfs-/Kaufvorschau.
- [ ] Abruffrequenz, Host- und Daten-/Cachegrenzen eingehalten. Kein 403/429-/Authentisierungs-/Schutzmechanismus-Bypass und keine künstliche Genehmigung. Fehlende Quellen beeinträchtigen andere Händler oder Offlinefunktionen nicht.

## Rezept-KI und Fitness
- [ ] Nano4Fast, Nano3 und Gemini Cloud **pro Modus** mit realer Modellidentität und tatsächlich erzeugter gültiger Rezeptantwort auf kompatibler Hardware/API getrennt verifiziert; Cloud nur nach aktueller Daten-/Kostenfreigabe, keine ungefragte Modell-/Cloudersetzung.
- [ ] Modellantwort durch unabhängigen Rezept-v1-/Produkt-/Allergen-/Mengencheck; Entwurf, Speichern, Herstellung und Verzehr bleiben unterschiedliche Bestätigungen.
- [ ] Health-Connect-/Watch-Daten nur bei tatsächlicher Quelle und erteilter Geräteeinwilligung; eigene Messwerte/Historie, Zeitfenster und unbekannte Daten korrekt.

## Zusammenführung
- [ ] Nur nicht überlappende Änderungen und überprüften aktuellen Main/PR-Head mergen; CI auf genau diesem Head grün. Ggf. signierte Test-APK aus identischem Code mit richtiger Signatur und Installation testen.
- [ ] Extern blockierte Features als **offen** belassen; alle anderen sinnvollen Aufgaben ohne zusätzliche Kontroll-/Freigabeschleifen fortsetzen. Knuspr/Picnic bleiben zurückgestellt.

**Bericht:** Ein PR nennt knapp „funktioniert / geprüft / offen / nächster Schritt“. Detaillierte Nachweise bleiben in Original-Tests/CI.