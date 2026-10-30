# Lokale Planung und Bedarf (0.5)

Ergänzung ab 0.6: bestätigte Ist-Zuordnung und Restbedarf mit dauerhaften Positionsidentitäten, siehe `PLAN_EXECUTION.md`. Die Beschreibung unten dokumentiert die Grundlagen aus 0.5. Dessen damalige Grenze des fehlenden Erfüllungsabgleichs ist durch den gesondert beschriebenen ausdrücklichen Zuordnungsweg erweitert; es gibt weiterhin keine automatische Ausführung oder Zuordnung.

## Bedienung

Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen → **Planung**. Nur Testdaten. Die bisherigen Hauptansichten für Tages-/Wochen-/Monatsplanung bleiben fiktive Beispiele; dieser Bereich arbeitet mit Room.

Ansicht ab einem Datum für einen, sieben oder 56 Tage wählen und „Planungszeitraum anzeigen“ drücken. Diese Ansichten filtern dieselben Einträge; es werden keine zusätzlichen Wochen-/Monatsmengen angelegt. Strategische Ziele sind in diesem Meilenstein keine zusätzlichen Bedarfseinträge.

Unter „Plan anlegen oder überarbeiten“ einen Titel, Zeitraum bis 56 Tage und Änderungsgrund eingeben. Neue Einträge zunächst zum Formularentwurf hinzufügen, anschließend die ganze **Planversion lokal speichern**. Der Formularentwurf ist noch nicht dauerhaft gespeichert und kann beim Verlassen verloren gehen. Leere Mengen bleiben unbekannt. Entfernen eines Kochplans aus dem Entwurf entfernt auch seine verknüpften geplanten Portionen; die neue Fassung wird erst mit Speichern wirksam.

- **Direkter Produktverzehr:** konkrete Artikelversion, Termin und Menge in deren Basiseinheit. Keine erratene Stück-, Packungs- oder Dichteumrechnung.
- **Zubereitung:** konkrete Rezeptversion, Kochtermin und geplante Ausbeute in Portionen. Die Zutatenmengen des ganzen Soll-Rezepts werden entsprechend skaliert.
- **Fertigportionen verzehren:** entweder eine bereits vorhandene Portions-Artikelversion wählen oder auf eine Zubereitung in demselben Planentwurf verweisen. Der Kochtermin darf nicht nach dem Verzehrtermin liegen. Spätere Portionen desselben Kochplans erzeugen keine erneuten Zutatenmengen.

Nach erfolgreichem Speichern „Neuen Vorgang beginnen“ wählen. Zum Überarbeiten die neueste Planfassung auswählen; alle Einträge werden übernommen. Speichern erzeugt eine vollständige neue Fassung. Unter „Planversionen und Verwerfen“ bleibt die Versionshistorie sichtbar. Verwerfen verlangt einen Grund und erzeugt eine neue verworfene Fassung. Frühere Fassungen werden nicht gelöscht oder erneut aktiv. Ein veralteter Bearbeitungsstand wird beim Speichern abgewiesen.

## Bedarf und Bestandsanrechnung

Alle neuesten bestätigten Planidentitäten werden gemeinsam berechnet. Frühere Versionen, Entwürfe und verworfene Fassungen erzeugen keinen aktuellen Bedarf. Eigenständige aktive Pläne konkurrieren um denselben Bestand. Bestehende Pläne aus Schema 1/2 erhalten jeweils eine eigene Identität: gemeinsame Herkunft oder Ersetzung wird nicht nachträglich geraten.

Die Rechnung verwendet den tatsächlich aktuellen Chargenbestand und ausschließlich Termine ab dem aktuellen Bestandsstand. Vergangene Plantermine werden nicht mit heutigem Bestand rückgerechnet. Innerhalb einer konsistenten Lese-Transaktion werden zukünftige Einträge nach Datum verarbeitet; Zubereitungen stehen am selben Datum vor Portionsverzehren. Weitere Gleichstände haben eine stabile Reihenfolge nach Planidentität/Eintrags-ID. Ein gemeinsames Mengenbuch verhindert, dass dieselbe Charge mehrfach angerechnet wird. Zuerst werden geeignete Chargen mit näherer Haltbarkeitsgrenze verwendet.

Auch eine Ansicht nur für den zweiten Tag berücksichtigt vorherige Planverwendungen außerhalb ihres Ausschnitts. Deshalb kann sie denselben Vorrat nicht erneut vollständig versprechen. Die Fehlmengenübersicht summiert anschließend nur die Bedarfstermine im angezeigten Zeitraum. Zutaten für einen vorherigen Kochtermin bleiben bei diesem Termin; spätere Portionen erzeugen keine zweite Einkaufsliste.

Es zählen nur passende **konkrete Artikelversionen** und Einheiten. Bestand einer anderen Version desselben Produkts wird ohne ausdrückliche fachliche Austauschregel nicht automatisch eingesetzt. Artikel-/Rezeptkorrekturen ändern frühere Planversionen nicht.

## Freigaben und Unsicherheit

Nicht angerechnet werden gesperrte/ungeklärte Chargen, stornierte Herkunft, unbestätigte Lagerorte, Zugänge nach dem Zieltermin und ein vor dem Zieltermin liegendes Verbrauchsdatum. Ein vor dem Termin liegendes MHD führt zu „gesonderte Prüfung nötig“, nicht zur Behauptung, das Lebensmittel sei automatisch ungenießbar. Für zukünftige Termine wird eine Charge ohne irgendeine bekannte Haltbarkeitsgrenze nicht versprochen. Am aktuellen Tag kann eine ausdrücklich freigegebene Charge ohne Datum rechnerisch berücksichtigt werden; auch das ersetzt keine tatsächliche Prüfung.

Diese Rechnung prüft vorhandene Freigaben und erfasste Datumsgrenzen, berechnet keine sichere Haltbarkeit aus Temperatur, Öffnung oder Auftauen. Sie ist keine Sicherheitsfreigabe. Unbekannter Lagerplatz und Bedingungen geplanter Portionen bleiben ungeprüft. Eine neue geplante Herstellung erzeugt ausschließlich **bedingte geplante Portionen**, keine physische Charge. Fehlende Zutaten, Kapazitäts-/Sicherheitsprüfung und tatsächliche Ausführung sind weiterhin zu klären. Mengen ihrer späteren Verwendungen teilen sich die geplante Ausbeute; Überbelegung zeigt zusätzliche fehlende Portionen statt erneuter Rohwarenmengen.

Ein vergangener Kochplan beweist keine vorhandenen Portionen: dafür muss die tatsächliche Herstellung erfasst und die Planung bewusst angepasst werden. Es gibt noch keine automatische oder bestätigte Zuordnung eines Ist-Ereignisses zur Erfüllung eines Planeintrags. Nach tatsächlicher Ausführung deshalb offene Planannahmen durch eine neue Version anpassen oder verwerfen; anderenfalls können sie weiterhin Bedarf erzeugen. Dieser Meilenstein behauptet keinen vollständigen Ausführungs-/Erfüllungsabgleich.

Fehlende Mengen oder unpassende alte Bezüge erzeugen Datenlücken. Bekannte Fehlmengen bleiben als Teilergebnis sichtbar. Unbekannter konkurrierender Bedarf erhält keine erfundene Mengenreservierung; die Gesamtübersicht warnt ausdrücklich, dass weitere Mengen offen sind. Ein leerer Bedarfsausschnitt beweist keine vollständige Ernährung oder Versorgung.

Zutatenbedarf wird ohne Gleitkomma mit ganzen 1/1000-Basiseinheiten gerechnet. Falls die Rezeptskalierung noch kleinere Bruchteile ergäbe, wird der Bedarf auf die nächste darstellbare Menge **aufgerundet**, damit keine Fehlmenge zu null abgerundet wird. Nicht darstellbare Größen bleiben offen. Berechnete Fehlmengen sind noch keine gerundeten Kaufpackungen: bestätigte Mengenbasis, Preise, Budget, Händler und Kaufbestätigung folgen gesondert.

## Architektur und Migration

`PlanningOperations` ist Teil der einzigen Schreibgrenze `FoodStore`: append-only Planversionen, atomare Speicherung aller Einträge, unveränderte Retry-Schlüssel und Prüfung gegen die letzte Fassung. Verknüpfte Eintrags-IDs werden beim Kopieren auf die neue Version abgebildet. Datenbank-Fremdschlüssel und eindeutige (Identität, Revision)-Indizes schützen Referenzen. Die neue `plan_series` hält stabile Identitäten; `plans` bleiben vollständige unveränderliche Versionen.

`PlanningReader` liest Pläne, Versionsbezüge, Chargen und Bewegungen in einer Room-Transaktion. Sein Mengenbuch gilt nur für diese Berechnung. Reaktive Ansichten beobachten die beteiligten Tabellen und berechnen nach Ist-Buchungen/Planänderungen neu. Es gibt keine gespeicherte zweite Bestands- oder Summenwahrheit, keine tatsächliche Reservierungsbuchung und keine automatische Planung-zu-Verzehr-Konvertierung. Rechenversion `demand-1`.

Schema **3**, 26 Tabellen. Erhaltende Migration 2→3 ergänzt Planidentitäten, Titel/Änderungsgrund und Verknüpfungen zwischen Zubereitung und Portionen. Die bisherigen Spalten und Zeilen bleiben erhalten; fehlende alte Titel/Gründe bleiben NULL. Migration 1→2 bleibt unverändert; App registriert beide Schritte. Schemaexporte 1/2 nicht verändern.

Prüfnachweis: `TEST_REPORT_PLANNING.md`. Grenzen neben den genannten: keine Lastprüfung sehr großer Pläne, höchstens 200 Einträge je Fassung; keine vollständige persönliche Ziel-/Nährwertplanung, Kalenderzeiten innerhalb eines Tages, gemeinsame Ausführungszuordnung, Preis-/Budget-/Packungslogik, Kapazitätsoptimierung oder Einkaufsbuchungsmaske. Frühere Einschränkung mehrtägiger Bestandskorrekturen bleibt bestehen. Backups, stabile Release-Signierung, Gerätetests und produktive Sheets-Migration stehen weiterhin aus.
