# Bestätigte Ist-Zuordnung und Restbedarf (0.6)

## Ausprobieren

Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen → **Planung**. Weiterhin ausschließlich Testdaten. Die 33 bisherigen Haupt-Fachansichten bleiben getrennte Demoansichten.

1. Einen Plan speichern. Tatsächlichen Verzehr unter **Ernährung** oder tatsächliche Herstellung unter **Küche** separat buchen. Eine Planung führt keine dieser Aktionen aus.
2. Unter Planung „Ist-Ausführung zuordnen oder zurücknehmen“ öffnen und die konkrete Planposition wählen. Angezeigt werden bekannte ausgeführte Menge und noch offene Menge.
3. Ein bereits gebuchtes passendes Ist-Ereignis auswählen. Artikel-/Rezeptversion und bei verknüpften Portionen der tatsächlich zugeordnete Kochansatz müssen stimmen. Bereits zugeordnete oder stornierte Ereignisse stehen nicht als neue Kandidaten zur Verfügung.
4. Begründung eingeben. Teilmengen, Mehrmengen, abweichender Termin oder unbekannte Mengen brauchen eine gesonderte ausdrückliche Bestätigung. Danach die konkrete Zuordnung bestätigen und speichern. Es entsteht ausschließlich eine Zuordnung, kein zweiter Verzehr, Kochvorgang oder Bestandsabzug.
5. Vor einer weiteren Schreibaktion oben **Neuen Vorgang beginnen** wählen.

Für eine Fehlzuordnung in derselben Maske die Zuordnung mit Grund ausdrücklich zurücknehmen. Das lässt den ursprünglichen Verzehr/die Herstellung unverändert. Die Menge wird wieder als offen geplant berücksichtigt. Eine Herstellungszuordnung mit noch aktiv zugeordneten abhängigen Portionsverzehren lässt sich erst nach deren Zuordnungen zurücknehmen.

Ein tatsächliches Ereignis kann genau einer Planposition zugeordnet sein. Eine Planposition kann mehrere tatsächlich gebuchte Teilereignisse aufnehmen. Es gibt noch keine Aufteilung eines einzigen Ist-Ereignisses auf mehrere Planpositionen. Ein erneuter technischer Aufruf mit derselben Vorgangs-ID dupliziert oder reaktiviert keine zurückgenommene Zuordnung.

## Mengen und Änderungen

Restbedarf = geplante Menge minus bekannte wirksam zugeordnete Ist-Mengen, mindestens 0. Mehrmengen bleiben als tatsächlich ausgeführte Menge sichtbar; sie erzeugen keinen negativen Bedarf. Unbekannte geplante oder ausgeführte Mengen lassen den Rest **ungeklärt**. Bekannte Ist-Teilsummen werden getrennt angezeigt. Kein unbekannter Beitrag wird zu 0 oder zu einer vollständigen Ausführung erklärt.

Beispiel: 200 g geplant, tatsächlich 50 g aus 200 g Vorrat gegessen. Bestand jetzt 150 g. Vor Zuordnung scheint für den weiterhin offenen 200-g-Plan eine Fehlmenge von 50 g zu bestehen. Nach bestätigter Zuordnung sind noch 150 g geplant und durch die 150 g Restbestand gedeckt. Der tatsächliche Verzehr und seine Ernährung bleiben genau einmal gespeichert.

Eine überarbeitete Planfassung behält die Identität unverändert fortgeführter Positionen. Deren Zuordnungen bleiben erhalten, obwohl die Zeilen-ID der neuen Fassung anders ist. Termin und Menge können im Formular bearbeitet werden; bei wirksamen Ist-Zuordnungen muss deren Übernahme ausdrücklich bestätigt werden. Eine geänderte Menge wird dann gegen dieselben echten ausgeführten Mengen neu verglichen. Ein anderer Artikel, eine andere Rezeptversion, Einheit, Planart oder verknüpfte Zubereitung benötigt eine neue Position. Alte historische Bezüge werden nicht überschrieben.

Entfernen oder Verwerfen eines Plans löscht weder Ist-Ereignisse noch Zuordnungshistorie. Noch belegte frühere Positionen bleiben in der Zuordnungsauswahl erreichbar, um ihre Zuordnungen ausdrücklich zurückzunehmen. Ein verworfener Plan erzeugt keinen aktiven Bedarf. Eine bewusst wieder aktivierte fortgeführte Fassung behält die bereits zugeordneten echten Mengen. Keine automatische Zuordnung anhand ähnlicher Namen oder Mengen.

## Herstellung und fertige Portionen

Für einen teilweise ausgeführten Kochplan werden nur die noch offenen Soll-Portionen in weiteren Zutatenbedarf umgerechnet. Tatsächlich schon eingesetzte Zutaten werden nicht nochmals als zukünftiger Bedarf behandelt. Beispiel: Soll sechs Portionen aus 300 g, Ist vier aus 200 g → noch zwei Soll-Portionen aus 100 g. Bei anderen tatsächlichen Mengen/Ausbeuten bleibt der zusätzliche Bedarf auf das Soll-Rezept der noch offenen Portionen bezogen, statt tatsächliche Abweichungen rückwirkend zu überschreiben.

Verknüpfte spätere Mahlzeiten nutzen zuerst den wirklich vorhandenen Restbestand der ausdrücklich zugeordneten Herstellungsansätze. Mehrere Ansätze und Umlagerungschargen sind möglich; die tatsächlichen Ausgabeartikelversionen bleiben entscheidend. Die gemeinsame Bestandszuordnung, Freigabe, Haltbarkeitsgrenzen und Zieltermine aus 0.5 gelten weiter. Dieselben Portionen können nicht zugleich einem anderen Plan nochmals versprochen werden.

Danach können offene zukünftige Kochmengen weiterhin bedingte **geplante** Portionen liefern. Diese sind kein vorhandener Bestand und keine Sicherheitsfreigabe. Beispiel: vier tatsächliche plus zwei noch geplante Portionen decken rechnerisch sechs geplante Portionen, wobei die letzten zwei Herstellung und Lagerprüfung noch benötigen. Tatsächlich schon gegessene, zugeordnete Portionen werden vom Mahlzeitenrest abgezogen; Rohwaren werden dabei nicht erneut entnommen.

Ein vergangener offener Kochtermin erzeugt keine angenommenen Portionen. Seine zugeordnete tatsächliche Herstellung kann dagegen vorhandene Portionen für spätere Mahlzeiten belegen. Überfällige nicht ausgeführte Kochmengen müssen bewusst neu terminiert werden; sie werden nicht automatisch mit heutigem Rohwarenbestand rückgerechnet.

## Storno und Historie

Storno einer tatsächlichen Buchung erfolgt weiterhin über den getrennten tatsächlichen Stornoweg. Ihre Zuordnung bleibt historisch gespeichert, die stornierte Menge zählt jedoch nicht mehr als ausgeführt. Der entsprechende Planrest öffnet sich. Die reale Bestandswirkung stammt ausschließlich vom eigentlichen Storno, nicht von der Zuordnungsauswertung.

Ein tatsächlich gebuchter Verzehr bleibt ein Verzehr, auch wenn sein Herstellungsbezug später geändert/storniert wird. In diesem Sonderfall bleibt die ausgeführte Verzehrmenge berücksichtigt und die geänderte Herkunft wird als Konflikt angezeigt; eine neue vermeintlich notwendige Mahlzeit wird nicht erfunden. Zugehörige Nährwertlücken bleiben Aufgabe der tatsächlichen Ernährungsrechnung. Korrekturen tatsächlicher Daten und bloße Zuordnungskorrekturen sind getrennt zu prüfen.

## Architektur, Migration und Grenzen

Schema **4**, 28 Tabellen. `plan_positions` hält dauerhafte Positionsidentitäten; jede neue Planfassung besitzt weiterhin eigene unveränderliche `planned_entries`. Migration 3→4 gibt jeder vorhandenen alten Eintragszeile eine eigene Identität. Frühere Zusammenhänge zwischen kopierten Versionen werden nicht geraten; es entstehen keine automatischen Zuordnungen. Alle alten Spalten/Zeilen und Retry-Schlüssel bleiben erhalten. App registriert Migrationen 1→2→3→4; veröffentlichte Schemaexporte bleiben unverändert.

`plan_executions` ist eine unveränderliche Zuordnungs-/Rücknahmekette pro tatsächlichem Vorgang. Nur die letzte Kettenfassung kann wirksam sein; ursprüngliche Planzeile und Positionsidentität bleiben referenziert. Datenbanktransaktionen und eindeutige Indizes verhindern parallele Doppelzuordnung. `ExecutionLedger` liest Mengen immer aus den ursprünglichen tatsächlichen Buchungen und Stornoständen, keine zweite gespeicherte Mengenwahrheit. Der Snapshot enthält Zuordnungshistorie und Stornozustände; veraltete Ansichten und gleiche-Uhrzeit-Zustandsfolgen werden erkannt. Er kann auch nach Änderungen an anderen Zuordnungen erneute Prüfung verlangen.

`PlanningReader` verwendet dieselbe konsistente Lese-Transaktion und dasselbe temporäre Mengenbuch für offene Produktbedarfe und tatsächliche Portionen. Rechenversion `demand-2`. Die Zuordnungs-Schreibgrenze schreibt keinerlei Bestandsbewegung, Verzehr oder Tagesbestätigung. Unklare Mengen bleiben offen. Prüfbericht: `TEST_REPORT_EXECUTION.md`.

Weiterhin kein Produktivbetrieb und keine Geräte-/Emulatortests. Keine persönliche Zieloptimierung, Preis-/Packungs-/Budgetlogik, automatische Ersatzprodukte, Zuordnung von Einkäufen, automatisierte Herstellung oder sichere Haltbarkeits-/Kapazitätsberechnung. Bestehende Grenzen vergangener Bestandskorrekturen, Backups, Release-Signierung und produktiver Sheets-Migration gelten weiter. Sehr große Plan-/Zuordnungshistorien sind nicht lastgetestet; eine globale Snapshotänderung kann konservativ auch unbeteiligte offene Bestätigungen veralten lassen.
