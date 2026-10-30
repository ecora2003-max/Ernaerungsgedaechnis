# Nährwertrechnung und lokale Ernährung (0.4)

## Bedienung

Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen. Unter **Ernährung** Datum im Format JJJJ-MM-TT wählen und „Tag anzeigen“ drücken. Es erscheinen nur aktive tatsächliche Verzehre dieses Tages, keine geplanten Mahlzeiten, Zugänge, Entsorgungen oder bloßen Kochvorgänge.

„Tatsächlichen Verzehr erfassen“ bietet zwei Wege:

- **Ohne Lagerbezug:** konkrete Artikelversion wählen, Mahlzeit beschreiben und bekannte Menge eintragen oder bei Unklarheit leer lassen. Beispielsweise unterwegs gegessen; es entsteht keine Lagerbewegung.
- **Aus Charge:** vorhandene Charge wählen und genaue gegessene Menge eintragen. Freigabe, Verbrauchsdatum und ausreichender Bestand werden geprüft. Bei Fertigportionen wird nur deren Bestand entnommen, keine erneute Rohwarenentnahme.

Tatsächlichen Verzehr ausdrücklich bestätigen. Nach erfolgreichem Speichern vor einer weiteren Buchung oben „Neuen Vorgang beginnen“ wählen. Daten und Tagesbilanz aktualisieren sich. Ein fehlerhafter Eintrag lässt sich direkt stornieren; die Historie bleibt erhalten. Für eine Korrektur anschließend den richtigen neuen Verzehr erfassen.

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

## Technik, Prüfungen und Grenzen

`NutritionReader` erstellt alle benötigten Daten einer Ansicht innerhalb einer Room-Transaktion. Reaktive Ansichten beobachten die beteiligten Tabellen, ohne während der Rechnung zu schreiben. Der Snapshot-Token berücksichtigt Erfassungsstatus, Statushistorie, aktive Verzehre und deren berechnete Ergebnisse. Referenzen, Historie, alte Produkt-Retry-Fingerprints ohne Zusatznährstoffe und das Datenbankschema 2 bleiben erhalten.

Prüfergebnisse: `TEST_REPORT_NUTRITION.md`. Es werden konkrete Mengen, Teilsummen, unbekannte Werte, Stornos, geänderte Versionen, weiterverarbeitete/umgelagerte Portionen, Dateiwiederöffnung und tatsächliche UI-Buchungen geprüft. Die Migration 1→2 wurde erneut geprüft und um die Auswertung alter Verzehrdaten ergänzt.

Noch kein Produktivbetrieb: die bisherigen Haupt-Fachansichten außerhalb des lokalen Testbereichs bleiben fiktiv. Es gibt noch keine vollständige Mengenbasis-/Herkunftsverwaltung, Planung/Bedarf, Belegerkennung, bestätigte persönliche Ziele, Backups, stabile Release-Signierung oder freigegebene Sheets-Datenmigration. Keine Geräte-/Emulatorprüfung in diesem Meilenstein. Große Datenmengen und sehr tiefe Produktionsketten sind nicht lastgetestet; vor Produktivbetrieb Index-/Paging-/Performancebedarf prüfen.
