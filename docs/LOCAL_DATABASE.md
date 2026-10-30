# Lokale Testdaten: Buchungen, Ernährung, Planung und Einkäufe (Schema 6)

## Einstieg und Grenzen

In der App: **Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen**. Die Bereiche Katalog, Lager, Ernährung, Küche, Planung, Einkauf, Käufe und Chronik umbrechen auf schmalen Displays, statt horizontal aus dem sichtbaren Bereich zu rutschen.

Der Bereich startet bei Erstinstallation leer und speichert in `food-local-sandbox.db` im privaten App-Verzeichnis. Testdaten aus Schema 1 bis 5 werden beim Update migriert. Planung und Bedarf: `PLANNING.md`; bestätigte Ist-Zuordnung/Restbedarf: `PLAN_EXECUTION.md`; Einkaufsvorschläge und Packungen: `SHOPPING.md`; bezahlte Käufe, Positionszugänge und Ausgaben-/Budgetabgleich: `PURCHASES.md`. Die 33 bisherigen Demoansichten bleiben getrennte unveränderliche Beispiele. Keine persönlichen Sheets-Daten übernehmen; keine automatische Netzwerk-/KI-Verbindung.

Ein App-Neustart erhält die Daten, eine Deinstallation löscht sie. Backups sind noch nicht implementiert. GitHub-APKs sind Debug-Builds, keine Store-Releases; Updates ohne Deinstallation benötigen auch eine übereinstimmende Android-Signatur. Stabile Release-Signierung folgt später. Keine App-Datenbanken oder privaten Signierschlüssel ins Repository aufnehmen.

## Ausprobieren

1. **Katalog:** Produkt und Änderungsgrund speichern. Leere Nährwerte bleiben unbekannt, eingegebene 0 bleibt eine bekannte Null. Eine Korrektur erzeugt eine neue unveränderliche Artikelversion und keinen Zugang.
2. Nach Erfolg vor der nächsten Buchung **Neuen Vorgang beginnen** wählen. Derselbe Vorgang bleibt sonst gesperrt; auch ein technischer Wiederholungsaufruf mit derselben ID erzeugt kein Duplikat.
3. **Lager:** Tatsächlich vorhandenen Testlagerort bestätigen. Das behauptet keine bekannte Kapazität/Temperatur. Danach Zugang für eine konkrete Artikelversion mit Menge und Ort bestätigen. Ohne zusätzliche ausdrückliche Freigabe bleibt die Charge ungeklärt.
4. **Umlagern, entsorgen oder zählen:** Charge, Menge, Datum und Grund angeben und bestätigen. Umlagerung erzeugt eine Zielcharge und zwei zusammengehörige Bewegungen. Entsorgung ist kein Verzehr. Die Istzählung akzeptiert Restbestand 0; bei inzwischen verändertem Ausgangsbestand muss sie neu geprüft werden.
5. **Küche:** Rezept mit konkreten Artikelversionen, Mengen für den gesamten Ansatz, Soll-Ausbeute, Schritten und Änderungsgrund speichern. Leere Rezeptmengen bleiben ungeklärt. Rezeptanlage bucht weder Vorräte noch Ernährung.
6. **Tatsächliche Herstellung:** Rezeptversion, wirklich verwendete Chargen und genaue Mengen sowie tatsächliche Portionszahl angeben. Mehrere Chargen derselben Zutat sind möglich. Andere Artikelversionen erfordern eine neue Rezeptversion; andere Mengen/Ausbeuten gegebenenfalls eine Abweichungsnotiz.
7. Die erste Herstellungsoberfläche unterstützt nur Ansätze mit ausdrücklich geprüften Lagerbedingungen, Behältern und verfügbarem Platz samt Notiz. Das ist eine manuelle Aussage, keine automatische Kapazitäts-/Kühlkettenprüfung. Ohne separate Freigabe bleiben Fertigportionen ungeklärt. Haltbarkeitsfristen werden nicht erfunden.
8. Bestätigte Herstellung entnimmt Zutaten und erzeugt atomar eine neue Portionscharge. **Kochen ist kein Essen.** Später unter Lager aus der Fertigportionencharge essen; dadurch werden die Rohwaren nicht erneut entnommen. Portionsnährwerte werden in Version 0.4 für die Tagesbilanz aus dem tatsächlichen Ansatz berechnet; unvollständige Zutatenwerte bleiben als Lücken sichtbar.
9. **Chronik:** Letzte 30 Bewegungen ansehen oder einen der letzten 30 Buchungsvorgänge stornieren. Die Vorschau enthält alle Gegenbewegungen des Vorgangs, auch wenn sie nicht vollständig in den letzten 30 Bewegungen stehen. Vollständiges Storno mit Datum/Grund bestätigen.

## Fachliche und technische Regeln

`ProductEntity` ist die stabile Identität, `ProductVersionEntity` der unveränderliche Artikel-/Nährwertsnapshot. Chargen, Verzehre und Rezeptzutaten referenzieren konkrete Versionen. Der aktuelle Katalog aggregiert Bestände aller Versionen einer Identität; Basiseinheitenwechsel sind verboten. Rezeptänderungen erzeugen ebenfalls neue Versionen.

`BatchEntity` referenziert Version, Lagerort, Herkunftsvorgang und optionale Sicherheitsdaten. Umlagerungschargen verweisen über `parentBatchId` auf ihre Quelle. Zugangsdatum, Öffnung/Auftauen, Haltbarkeit und Rezeptbezug bleiben erhalten. Der Restbestand ist ausschließlich die Summe der Bewegungen. Physischer Bestand ist keine geprüfte Nutzbarkeit.

Ohne erneute Prüfung der Zielbedingungen wird eine bisherige Freigabe bei Umlagerung auf ungeklärt gesetzt. Gesperrte/ungeklärte Ware wird dadurch nie freigegeben. Verzehr/Herstellung prüfen Freigabe, verfügbare Menge und bekannte Verbrauchsdaten; Entsorgung bleibt auch für gesperrte/abgelaufene Ware möglich.

Eine Istzählung schreibt eine begründete Differenzbewegung, keinen überschriebenen Altbestand. Erwarteter Ausgangsbestand und aktueller Bestand müssen noch übereinstimmen. Restbestand 0 ist zulässig, eine normale Ereignismenge von 0 dagegen nicht.

`OperationReversalEntity` verbindet ein einmaliges Storno mit dem Ursprung. Alle Gegenbewegungen entstehen gemeinsam und verweisen über `reversesId` auf die ursprünglichen Bewegungen. Stornierter Verzehr bleibt historisch gespeichert, zählt aber nicht mehr zur aktiven Ernährung; der Tagesstatus wird TEILWEISE. Ein Storno des Stornos ist nicht vorgesehen: nach Prüfung einen neuen korrekten Vorgang buchen.

Bei Zugängen, Umlagerungen und Produktionen zuerst aktive abhängige Buchungen stornieren, beispielsweise Portionsverzehr vor Herstellung. Kein Storno darf negative/unzulässige Bestände erzeugen. Eine Charge mit stornierter Herkunft wird nicht durch eine Istzählung wiederbelebt.

Neue Bewegungen dürfen nicht vor bekannten späteren Chargenereignissen liegen. Bei alten Schema-1-Verzehren berücksichtigt die Sperre deren vorhandenes Verzehrdatum, obwohl das neue Bewegungsdatumsfeld NULL bleibt. Diese konservative Regel verhindert rückwirkend unmögliche Chroniken; spätere Korrekturen begründet zum aktuellen Datum buchen.

Eine Produktion verbindet Zutatenabgänge und Fertigportionenzugang über eine Vorgangs-ID. Fehler rollen alle Teile zurück. Ein stabiles Ausgabeprodukt pro Rezeptidentität erhält pro Ansatz eine neue Produktversion und Charge. Rezept, tatsächliche Artikelversionen, Mengen und Ausbeute bleiben nachvollziehbar. Portionsnährwerte werden in Version 0.4 aus diesen unveränderlichen Ist-Daten abgeleitet, nicht als zweite Summenwahrheit gespeichert.

Mengen sind `Long` in 1/1000 von g, ml oder Portion. kg/l werden exakt umgerechnet; Stück/Packung benötigen eine bestätigte Basis. Keine implizite Masse-/Volumenumrechnung. Unbekannter Verzehr ohne Lagerbezug ist im Service möglich, hat noch keine entsprechende Maske.

`FoodStore` ist die öffentliche Schreibgrenze mit internen Buchungs-/Küchendiensten und Room-Transaktionen. Kein Update/Delete/Replace der Historie. Fachprüfungen gelten für diese Dienste; direktes SQL-Schreiben ist keine unterstützte Anwendungsschnittstelle. Persistierte IDs/Fingerprints verhindern Doppelbuchungen auch nach Neustart. Andere Inhalte mit altem Schlüssel werden abgewiesen. Wiederholungen werden vor dynamischen Zeit-/Bestandsprüfungen erkannt und reaktivieren keine Stornos.

## Schema und Migration

Room 2.7.2/KSP exportiert Schema 1 bis 6 unter `app/schemas/`; alte Exporte bleiben unverändert. Schema 2 ergänzt Chargenherkunft/-datierung und drei Buchungs-/Herstellungstabellen (25 Tabellen), Schema 3 Planidentitäten/-verknüpfungen (26), Schema 4 Positionsidentitäten und Ist-Zuordnungen (28). Schema 5 ergänzt Preis-/Budgetmetadaten mit unbekannt-fähigen Geld-/Mengenspalten sowie `shopping_marks` (29 Tabellen). Schema 6 ergänzt `purchase_events`, `receipt_stock_bookings` und `expense_status_events` (32 Tabellen).

Alle fünf Migrationsschritte 1→2→3→4→5→6 sind registriert. Keine Löschung/Umdeutung alter Werte, keine destruktive Fallback-Migration. Fünf Dateitests befüllen alle 22/25/26/28/29 alten Tabellen, vergleichen jede alte Spalte/Zeile und prüfen Fremdschlüssel, Retry-Schlüssel, neue Buchungen/Planversionen und Wiederöffnung. Schema 4 erhält fortgeführte Positionsidentitäten/Ist-Zuordnungen; Schema-5-Metadaten bleiben unverändert. Altbelege werden nicht allein aufgrund von Qualität oder Lagerbewegungen zu bezahlten Einkäufen. Nach Veröffentlichung von Schema 6 benötigen Strukturänderungen Version 7+ mit Erhaltungsmigration.

## Prüfungen und nächste Module

Aktuelle Resultate: `TEST_REPORT_PURCHASES.md` und `DEVELOPMENT.md`; frühere Meilensteine in den entsprechenden Prüfberichten. Room-/SQLite-Tests laufen unter Robolectric; keine Geräte-/Emulatortests. Produktion und Einkaufsbuchungen werden auch mit echten SQLite-Fehlern nach begonnenen Schreibvorgängen geprüft. UI-Tests kontrollieren gespeicherte Bestände, Zahlungen, Stornos und Vollständigkeitsstatus.

Seit 0.4: Nährwert-/Tagesrechnung; seit 0.5: Planversionen/Bedarf; seit 0.6: bestätigte Ist-Zuordnung; seit 0.7: erste Einkaufsvorschläge; seit 0.8: tatsächliche Käufe, Positionszugänge und expliziter Ausgaben-/Budgetabgleich. Noch offen: Belegkorrekturfassungen/Erstattungen, komplexe Vergangenheitskorrekturen, Anbindung der Haupt-Demoansichten, Profil-/Zielhistorie, Belegerkennung/Aliase, JSON-Austausch, Körper/Training, Backups und freizugebende produktive Migration. Vorbereitete Tabellen sind keine fertigen Module.
