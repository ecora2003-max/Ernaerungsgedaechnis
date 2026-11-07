# Nährwertrechnung und tatsächliche lokale Ernährung

## Bedienung

Unter **Mehr → Ernährung** Datum im Format JJJJ-MM-TT wählen und **Tag anzeigen** drücken. Es erscheinen nur aktive tatsächliche Verzehre dieses Tages, keine geplanten Mahlzeiten, Zugänge, Entsorgungen oder bloßen Kochvorgänge. Entwicklungsversion weiterhin nur mit Testdaten verwenden.

**Tatsächlichen Verzehr erfassen** bietet drei Wege:

- **Ohne Lagerbezug:** konkrete Artikelversion wählen, Mahlzeit beschreiben und bekannte Menge eintragen oder bei Unklarheit leer lassen. Beispielsweise unterwegs gegessen; es entsteht keine Lagerbewegung.
- **Aus Charge:** vorhandene Charge wählen und genaue gegessene Menge eintragen. Freigabe, Verbrauchsdatum und ausreichender Bestand werden geprüft. Bei Fertigportionen wird nur deren Bestand entnommen, keine erneute Rohwarenentnahme.
- **Bestand nach FEFO:** konkrete Artikelversion und gegessene Menge wählen, vorgeschlagene geeignete Chargen prüfen und erst dann bestätigen. Fehlmengen oder ungeeignete Chargen geben keine Buchung frei; die endgültige FoodStore-Transaktion prüft den aktuellen Bestand nochmals atomar.

Tatsächlichen Verzehr ausdrücklich bestätigen. Nach erfolgreichem Speichern vor einer weiteren Buchung oben „Neuen Vorgang beginnen“ wählen. Daten und Tagesbilanz aktualisieren sich. Ein fehlerhafter Eintrag lässt sich direkt stornieren; die Historie bleibt erhalten. Für eine Korrektur anschließend den richtigen neuen Verzehr erfassen.

Beim Anwenden eines anderen Tages bleiben die noch ungespeicherten Verzehrfelder erhalten. Die Zustimmung gilt für genau den gewählten Tag und wird zurückgesetzt. Während dessen Tagesbilanz noch nicht lesbar ist, bleibt Buchen gesperrt. Ein bloß bearbeiteter, noch nicht angewendeter Datumstext ändert den Buchungstag nicht; maßgeblich ist das im offenen Verzehrformular ausdrücklich angezeigte Datum.

Ein Neuaufbau der App-Ansicht erhält die noch ungespeicherten Eingaben, übernimmt aber keine Zustimmung zum Verzehr. Ein noch offener Tagesabschluss wird verworfen und muss frisch geöffnet und bestätigt werden. Bereits gespeicherte Verzehre und Tagesbestätigungen bleiben erhalten; ein vollständiger Tag mit fehlenden Mengen/Nährwerten oder ohne berechenbaren Verzehr erhält dadurch keine erfundenen Kalorien-Nullwerte.

Im Katalog sind Energie, Protein, Fett, Kohlenhydrate und Ballaststoffe direkt bearbeitbar. Unter „Weitere Produktnährstoffe“ liegen Zucker, gesättigte Fette, Salz und Mikronährstoffe mit ihren jeweiligen g-/mg-/µg-Einheiten. Die Mengenbasis steht über den Feldern. Leer bedeutet unbekannt; eingegebene 0 ist eine bekannte Null. Änderungen erzeugen neue Artikelversionen. Bestehende Verzehre und Chargen behalten ihre bisherigen Versionen.

Unter Küche → Rezeptkarte → „Nährwerte pro Rezeptportion · Vorschau“ stehen die Soll-Werte des versionierten Rezepts. Ein tatsächlicher Kochansatz kann andere Zutatenmengen und Ausbeuten haben; seine Portionswerte werden deshalb gesondert aus seinen gespeicherten Ist-Bewegungen ermittelt.

## Was die Zahlen bedeuten

`NutritionResult` unterscheidet für jeden Nährstoff bekannte Werte, bekannte Teilsummen und fehlende Beiträge. Ein fehlender Wert und eine ungeklärte Menge werden nie durch 0 ersetzt. Die Anzeige nennt einen vorhandenen bekannten Anteil **Teilsumme**, sobald weitere Beiträge für diesen Nährstoff fehlen. Sind gar keine Beiträge berechenbar, steht **Unbekannt**. Ein leerer Tag wird nicht automatisch als Nullkalorientag ausgewiesen.

Es gibt keine Prozentangabe für Nährwertabdeckung: die Anzahl bekannter Zutaten sagt nicht, welcher Anteil an Energie oder Nährstoffmenge bekannt ist. Unterschiedliche Nährstoffe können unterschiedlich vollständig sein. Ungeprüfte Quellen bleiben als solche erkennbar. Persönliche Zielwerte werden nicht aus dem Demo-Profil übernommen.

Die Rechnung verwendet die konkrete Artikelversion, tatsächliche Menge und gespeicherte Nährwertbasis. g, ml und Portion bleiben getrennt; keine Dichte-/Stück-/Packungsumrechnung wird erraten. Intern BigDecimal mit DECIMAL128; gerundet wird für die Anzeige. Sehr kleine bekannte positive Werte werden bei Bedarf als „< …“ statt als falsche Null dargestellt. Das ersetzt keine Prüfung der ursprünglichen Etikett-/Nutzerangaben.

## Herstellung und Historie

Für Fertigportionen wird die zugrunde liegende Produktion über ihre Ausgabecharge/-artikelversion bestimmt. Die negativen Zutatenbewegungen liefern die tatsächlichen Mengen und Artikelversionen; die tatsächliche Ausbeute liefert den Nenner. Es werden keine aktuellen Rezept-Sollmengen und keine neuesten Produktversionen eingesetzt. Erneutes Kochen, geänderte Rezepturen und spätere Etikettenkorrekturen ändern alte Ansätze nicht.

Auch weiterverarbeitete Portionen werden auf ihre tatsächlichen Eingänge zurückgeführt. Der Cache gilt nur innerhalb derselben Lese-Transaktion, nicht über Datenänderungen hinweg. Ungültige, stornierte oder zyklische Herstellungsbezüge erzeugen Datenlücken statt erfundener Nährwerte. Umlagerungen behalten die ursprüngliche Ausgabeartikelversion. Die Rechnung selbst erzeugt keinerlei Bestandsbewegungen.

Die Tagesbilanz verwendet nur Verzehre ohne zugehöriges Storno. Ein Storno löscht keine historischen Verzehre und reaktiviert keine früheren Buchungen. Das bereits vorhandene Modell reicht für diese Ableitungen aus: keine neue Datenbankversion und keine zweite unabhängig gespeicherte Summenwahrheit. Gespeicherte Quellenwerte in Produktversionen und berechnete Werte eines Herstellungsansatzes sind unterschiedliche Angaben.

## Tagesvollständigkeit ist keine Nährwertvollständigkeit

Ein neuer Tag ist OFFEN, ein neuer Verzehr setzt ihn auf TEILWEISE. „Erfassung als vollständig bestätigen“ erfordert eine ausdrückliche Bestätigung, dass alle Mahlzeiten erfasst sind. Unbekannte Mengen oder Nährwerte bleiben trotzdem offen und sichtbar. Eine vollständige Mahlzeitenerfassung ist kein Nachweis vollständig bekannter Kalorien und keine Freigabe für spätere automatische Kalibrierung.

Das Bestätigungsfenster behält den gerade geprüften Datenstand. Ein neuer oder stornierter Verzehr bzw. ein anderer Erfassungsstatus macht einen alten Bestätigungsstand ungültig. Die App weist ihn ab und verlangt erneute Prüfung. Persistierte Statusvorgänge erhalten die Änderungshistorie und verhindern auch bei identischen Uhrzeiten die Wiederverwendung alter Ansichten. Eine Wiederholung eines bereits gespeicherten Bestätigungsaufrufs bucht nichts erneut und kann einen inzwischen geänderten Tag nicht wieder vollständig setzen.

„Tag wieder öffnen“ nimmt die Bestätigung zurück. Neue Verzehre oder Verzehrstornos setzen den Status wieder auf TEILWEISE. Zukünftige Tage dürfen betrachtet, aber nicht als tatsächlich vollständig erfasst bestätigt werden.

## Grenze bei vergangenen Bestandskorrekturen

Die konservative Rückdatierungssperre aus 0.3 gilt weiter. Nach einem Storno zum aktuellen Datum kann eine bestandsgebundene Neuerfassung für einen früheren Verzehrtag an späteren Chargenbewegungen scheitern. Einfache Neuerfassung ohne Lagerbezug ist davon nicht betroffen. Komplexe Vergangenheitskorrekturen mit gemeinsamem Ernährung-/Bestandsersatz sind noch kein fertiger Bedienablauf und müssen vor Produktivbetrieb gesondert gelöst und getestet werden. Niemals ein falsches Verzehrdatum wählen, nur um eine Buchung durchzulassen. Die tatsächliche Tageszuordnung und die Bestandskorrektur müssen beide nachvollziehbar bleiben.

## Technik, Prüfungen und Grenzen

`NutritionReader` erstellt alle benötigten Daten einer Ansicht innerhalb einer Room-Transaktion. Reaktive Ansichten beobachten die beteiligten Tabellen, ohne während der Rechnung zu schreiben. Der Snapshot-Token berücksichtigt Erfassungsstatus, Statushistorie, aktive Verzehre und deren berechnete Ergebnisse. Referenzen, Historie, alte Produkt-Retry-Fingerprints ohne Zusatznährstoffe und das Datenbankschema 2 bleiben erhalten.

Prüfergebnisse: `TEST_REPORT_NUTRITION.md`. Es werden konkrete Mengen, Teilsummen, unbekannte Werte, Stornos, geänderte Versionen, weiterverarbeitete/umgelagerte Portionen, Dateiwiederöffnung und tatsächliche UI-Buchungen geprüft. Die Migration 1→2 wurde erneut geprüft und um die Auswertung alter Verzehrdaten ergänzt.

Noch kein Produktivbetrieb: die bisherigen Haupt-Fachansichten außerhalb des lokalen Testbereichs bleiben fiktiv. Es gibt noch keine vollständige Mengenbasis-/Herkunftsverwaltung, Planung/Bedarf, Belegerkennung, bestätigte persönliche Ziele, Backups, stabile Release-Signierung oder freigegebene Sheets-Datenmigration. Keine Geräte-/Emulatorprüfung in diesem Meilenstein. Große Datenmengen und sehr tiefe Produktionsketten sind nicht lastgetestet; vor Produktivbetrieb Index-/Paging-/Performancebedarf prüfen.
