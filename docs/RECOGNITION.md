# Phase 5: Barcode, Produktabfrage und Bonerkennung

Version **0.9.0-recognition**, Schema **7**. **Scan** öffnet jetzt die lokale Erkennung. Alternativ: Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen → Erkennung. Weiterhin nur Testdaten; persönliche Datenmigration, Backups und Produktionsfreigabe gehören zu späteren Phasen.

## Barcode und Produktgedächtnis

Barcode fotografieren, ein Bild über den Systemdialog wählen oder Ziffern eingeben. EAN-8, UPC-A, EAN-13 und GTIN-14 werden mit Prüfziffer geprüft; führende Nullen bleiben erhalten. Mehrere erkannte Codes werden zur Auswahl angeboten. Die Bilddekodierung mit ZXing läuft offline, einschließlich Bilddrehung und EXIF-Ausrichtung.

Zuerst im lokalen Katalog prüfen. Nur eine eindeutige bestätigte Zuordnung zur aktuellen konkreten Artikelversion wird wiederverwendet. Fehlende Bestätigung, mehrere passende Produkte und alte Versionen bleiben prüfpflichtig. Ähnliche Namen sind lediglich Vorschläge. Bestehende Produkte bleiben auch bei leerem Lager offline verfügbar. Unter Katalog nach Name, Marke, Barcode oder Kategorie suchen; manuelle Produktanlage bleibt verfügbar.

Open Food Facts wird nur über **Produktdaten bei Open Food Facts abfragen** angesprochen. Der HTTPS-Aufruf übermittelt den Barcode, keine Fotos, Bontexte oder Vorräte. Kein Konto und kein API-Schlüssel. Quelle und Abrufzeit bleiben erhalten; Datenlizenz ODbL, Open Food Facts und Mitwirkende. Bei Fehler, Zeitüberschreitung, Rate-Limit oder fehlendem Produkt manuell weiterarbeiten. Antworten sind begrenzt und müssen zum angefragten Barcode gehören.

Quellenwerte ausdrücklich in die Prüfung übernehmen. Name, Marke, Basis/Einheit, Packungsangaben, alle 14 Nährstoffe, Zutaten und Allergene lassen sich bearbeiten. Fehlende Quellenwerte bleiben unbekannt; bekannte Null bleibt 0. OFF-`_100g`-Werte werden gemäß Quellenformat gelesen: mineralische Grammwerte in mg, Jod/B12/D in µg. Portionswerte werden nicht als 100-g-Werte eingesetzt. Keine erfundene Dichte-, Stück- oder Portionsumrechnung. Die Katalog-Packungsmenge ist weiterhin kein Nachweis einer nutzbaren Kaufpackung.

Produktvariante und Nährwertbasis/Einheit bestätigen. Barcodezuordnung und zusätzlich geprüfter Etikettenstatus sind separate Entscheidungen. Externe Angaben bleiben sonst „aus Quelle übernommen“. Speichern erzeugt eine unveränderliche Produktversion und optional atomar eine bestätigte Barcodezuordnung, niemals Einkauf/Verzehr/Lagerzugang. Eine inzwischen geänderte Zielversion wird abgewiesen. Historische Verzehre behalten ihre damaligen Daten.

## Bon fotografieren und prüfen

Unter **Bon & OCR** fotografieren oder Bild wählen. Das mitgelieferte lateinische ML-Kit-Modell verarbeitet Bilder auf dem Gerät; kein Cloud-OCR-Dienst. Alternativ mehrzeiligen lesbaren Bontext eingeben/einfügen. Der Systemkamera wird nur eine temporäre private Bilddatei freigegeben. Keine allgemeine Kamera-/Galerie-Leseberechtigung. Bilder werden begrenzt eingelesen, verkleinert und ausgerichtet; temporäre Kameradateien nach Verarbeitung entfernt. Ausgewählte Originalbilder werden nicht gelöscht. Die SDK-Abhängigkeiten ergänzen Internet-/Netzwerkstatusberechtigungen; kein eigener Server und keine Anmeldung.

Zunächst nur einen **Bonentwurf** speichern. Rohtext, Herkunft, Parserfassung und bei Bildern ein SHA-256-Prüfwert bleiben erhalten. Das ist kein Beleg mit bestätigter Zahlung. Derselbe Bildinhalt führt auch nach erneutem Auswählen zum vorhandenen Entwurf. Abbruch, unlesbares Bild und Fehler erzeugen keine Kauf-/Bestandsereignisse.

Die Textanalyse schlägt Händler, ein eindeutiges Datum, Gesamtbetrag und Warenpositionen vor. Spalten werden anhand der OCR-Geometrie zusammengeführt. Bar gegeben, Rückgeld, Steuer und Rabatte werden nicht als normale Waren oder Gesamtzahlung übernommen. Widersprüchliche/negative/unlesbare Gesamtbeträge bleiben offen; die Positionssumme ersetzt sie nicht. Ein fehlendes Dezimalkomma wird nicht erfunden: erkennbarer Artikel bleibt mit ungeklärtem Preis erhalten. Gedruckte Mengen bleiben Rohangaben, keine nutzbaren Lagerzugänge.

Jede vorgeschlagene Angabe korrigieren oder leer lassen, Positionen ausschließen/ergänzen und Produktversionen prüfen. Händlergebundene bestätigte Aliase werden nur bei passendem Händler/Text/aktueller Version vorbefüllt. Ändert sich die Aliasgrundlage im Formular, werden automatische Zuordnungen entfernt. Neue Katalog-/Aliasdaten verlangen eine neue Prüfung anhand des unveränderten Ausgangstexts. Aliase können getrennt ausdrücklich gelernt, berichtigt oder zurückgenommen werden; alte Retries reaktivieren sie nicht.

Nur nach **Bonangaben geprüft und diesen Einkauf tatsächlich bezahlt** übernimmt die App den Entwurf atomar als einen Einkauf. Bekannter Händler/Preis darf trotz ungeklärter Produkt-/Lagermengen gespeichert werden. Gleicher Händler, Datum und Zahlbetrag eines bereits aktiven Kaufs verlangen die zusätzliche Bestätigung eines wirklich weiteren Einkaufs. Ein Entwurf kann auch unter Parallelität nur einmal übernommen oder verworfen werden. Wiederholungen erzeugen keine zweite Zahlung. Der Rohtext bleibt unverändert erhalten, bearbeitete Belegpositionen getrennt davon.

Die tatsächliche Zahlung zählt weiterhin genau einmal. Lagerzugänge unter **Käufe** je ausreichend geklärter Position ausdrücklich bestätigen; keine automatische Mengen-, Lager- oder Lebensmittelfreigabe. Regeln zu tatsächlichen Ausgaben, Vollständigkeit und Budget: [PURCHASES.md](PURCHASES.md).

## Architektur, Prüfungen und Grenzen

Schema 7 ergänzt ausschließlich `alias_decisions`, `receipt_scans` und `receipt_scan_resolutions` (35 Tabellen). Migration 6→7 verändert keine alten Werte. Alle sechs Wege aus Schema 1–6 werden mit vollständig befüllten alten Tabellen erhaltend geprüft. Alte Ereignis-Fingerprints bleiben gültig; zusätzliche Produktmetadaten haben eigene versionierte Fingerprints.

Schreibgrenze bleibt `FoodStore`, alle Wirkungen in Room-Transaktionen. Aliasentscheidungen sind unveränderliche Vorgängerketten, Bonauflösungen eindeutig. Externe Antworten bleiben bis zum Prüfen flüchtige Vorschläge. Veraltete asynchrone Antworten/Fehler dürfen keine neueren Abfragen überschreiben. Keine automatische Änderung der Phasen-1–4-Ereignisse.

Automatisierte Prüfungen: [TEST_REPORT_PHASE5.md](TEST_REPORT_PHASE5.md). Physische Kamera, native ML-Kit-Ausführung und Gerätewechsel hier noch nicht auf Hardware geprüft. Zwei zusätzliche Gerätetests werden kompiliert und sind mit `./gradlew :app:connectedDebugAndroidTest` auf angeschlossenem Gerät/Emulator ausführbar; sie zählen nicht zu den ausgeführten Robolectric-Tests. OCR kann irren, ungewohnte Bonlayouts erfordern manuelle Korrektur. Noch keine Live-Kameravorschau mit kontinuierlichem Scan, QR-/GS1-Anwendungsdaten, globale Produktsuche im Netz, automatische Packungsumrechnung oder allgemeine Belegkorrektur-/Erstattungsverwaltung. Die früher dokumentierte Sperre komplexer bestandsgebundener Vergangenheitskorrekturen bleibt bestehen.
