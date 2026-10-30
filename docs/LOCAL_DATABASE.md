# Lokales Datenfundament (Schema 1)

## Benutzbarer Einstieg

In der App: **Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen**.

Dieser Bereich startet leer, schreibt ausschließlich in `food-local-sandbox.db` im privaten App-Verzeichnis und benötigt weder Internet noch Anmeldung. Er ist bewusst von den unveränderlichen Beispieldaten der 33 Demoansichten getrennt. Keine realen Sheets-Daten importieren. Eine Deinstallation löscht auch diese Testdaten; sichere Backups sind noch nicht implementiert.

Zum Ausprobieren:

1. Im Katalog „Produkt speichern / neue Version“ öffnen. Namen und Änderungsgrund eingeben; Energie/Protein können leer bleiben. Leere Nährwerte bleiben `NULL`; eine eingegebene 0 bleibt eine bekannte Null.
2. Nach dem Speichern „Neuen Vorgang beginnen“ wählen. Eine Korrektur wird als neue Version derselben Produktidentität gespeichert. Es entsteht noch kein Bestand.
3. Unter Lager einen tatsächlich vorhandenen Testlagerort anlegen und bestätigen. Kapazität/Temperatur bleiben unbekannt.
4. Einen Testzugang mit ausgewählter Produktversion, Lagerort und Menge in der Basiseinheit bestätigen. Ohne gesonderte Freigabe bleibt die Charge ungeklärt und kann nicht als Verzehr ausgebucht werden. Freigabe ist eine ausdrückliche Nutzereingabe, keine automatische Sicherheitsbewertung.
5. Einen Testverzehr buchen. Es werden atomar Verzehrereignis, Chargenzuordnung und negative Bestandsbewegung geschrieben. Nicht ausreichend vorhandene, gesperrte oder ungeklärte Mengen und überschrittene Verbrauchsdaten werden abgewiesen. Es erfolgt keine automatische Prüfung/Bestätigung einer Kühlkette oder Lagerkapazität.
6. Unter Chronik stehen die letzten 30 Bewegungen; die vollständige Historie bleibt gespeichert. Ein App-Neustart erhält den Katalog und die Chargen. Andere Demoansichten verwenden diese Testbuchungen noch nicht.

## Beziehungen und Schreibgrenzen

`ProductEntity` ist die stabile Identität. `ProductVersionEntity` enthält einen unveränderlichen Snapshot mit Name, Einheit, Nährwertbasis, optionalen Nährstoffen einschließlich Mikronährstoffen sowie Quellen-/Qualitätsmetadaten. Historische Chargen, Verzehre und Rezeptzutaten referenzieren konkrete Versionen. Der aktuelle Katalog aggregiert die Bestände aller Versionen einer Identität. Einheitenwechsel einer Identität werden verhindert.

`BatchEntity` referenziert Version, Lagerort und Herkunftsvorgang; Haltbarkeits-/Öffnungs-/Auftauinformationen und Freigabestatus bleiben getrennt. Eine Rezeptversionsreferenz ist für spätere Produktion vorbereitet. Der physische Restbestand ist die Summe von `StockMovementEntity.delta`, keine unabhängig beschreibbare zweite Zahl. Physischer Bestand ist nicht gleich nutzbarer/geprüfter Bestand.

`FoodStore` ist die öffentliche Schreibgrenze. Der interne DAO stellt keine Update-/Delete-/Replace-Operationen für Produktversionen, Chargen oder Bewegungen bereit. Validierte Schreibvorgänge laufen in `Room.withTransaction`; Fremdschlüssel und Eindeutigkeitsindizes ergänzen die Fachprüfungen. Die Mengen-/Sicherheitsprüfungen gelten für diesen Service; beliebige direkte SQL-Schreibzugriffe sind keine unterstützte Schnittstelle.

Vorgangs-IDs sind stabile Idempotenzschlüssel. Ein SHA-256-Fingerprint über längencodierte Eingabefelder verhindert die Wiederverwendung eines Schlüssels für andere Inhalte. Wiederholung liefert dieselbe Ergebnisreferenz. Die UI behält ihre Vorgangs-ID über Konfigurationswechsel und speicherbaren UI-Zustand; nach Erfolg ist Speichern bis zum ausdrücklichen Beginn eines neuen Vorgangs gesperrt. Zwei getrennt bestätigte, gleichartige Mahlzeiten bleiben zwei Ereignisse.

Mengen sind `Long` in 1/1000 von g, ml oder Portion. Keine implizite Umrechnung zwischen Masse und Volumen. kg/l werden exakt umgerechnet; Stück/Packung benötigen eine bestätigte Basis. Ernährung darf bei unbekannter Menge ohne Lagerabgang gespeichert werden (Service, noch keine entsprechende Eingabemaske). Neue Verzehre setzen den Tagesstatus auf TEILWEISE; kein Automatismus bestätigt einen vollständigen Tag. Erfassungszeit und unbekannte tatsächliche Ereigniszeit bleiben unterscheidbar.

## Vorbereitete, noch nicht vollständige Module

Das Schema enthält getrennte Datensätze für Aliase, Rezeptversionen/Zutaten, Tagesstatus, Pläne/Planpositionen, Belege/Positionen, Preisbeobachtungen/Budgets und Körpermessungen/Training/Sätze. Dies ist noch keine vollständige Geschäftslogik oder Bedienoberfläche dieser Module. Insbesondere gibt es keine automatische Aliasauflösung, keine Planübernahme, keine Produktions-, Umlagerungs-, Korrektur- oder Ausgabenbuchung. Rezeptnährwerte und Tagesbilanz werden noch nicht aus der neuen Datenbank berechnet. Profil-/Zielhistorie und weitere Fachdetails folgen mit den jeweiligen Modulen.

## Schema und Migrationen

- Room 2.7.2 mit KSP; JSON-Schema unter `app/schemas/` versionieren.
- Version 1 ist die erste persistente Datenbank. Die frühere UI-Demo hatte keine Nutzerdatenbank, daher existiert keine historische Datenbankversion für eine Upgrade-Migration.
- Keine destruktive Fallback-Migration und kein Löschen bei Lesefehlern. Fehler werden sichtbar angezeigt.
- Nach Freigabe von Schema 1 sind Strukturänderungen als Version 2+ mit expliziter Migration umzusetzen. Migrationen müssen mit dem gespeicherten alten Schema und Beispieldaten getestet werden: IDs, Versionen, Nullwerte, Historie und Idempotenzschlüssel erhalten, `foreign_key_check` bestehen, ohne Duplikate oder Bestandsänderungen.
- Ein Schließen/Öffnen-Test prüft aktuell Dateipersistenz und Room-Schemavalidierung, keine noch nicht existierende Upgrade-Migration.

## Prüfungen

`LocalDatabaseTest`: Null/Nullwert-Unterscheidung, keine Bestände durch Produkt-/Plananlage, Wiederholung/Konflikt, gleichartige getrennte Mahlzeiten, keine Überziehung, vollständiger Rollback auch nach einer ersten Chargenentnahme, Freigabe-/Datumsprüfung, unbekannter Verzehr ohne Lager, Historie nach Produktkorrektur, Fremdschlüssel, konkurrierende Vorgänge und Wiederöffnung einer echten Datenbankdatei.

`AmountsTest`: genaue Dezimalmengen, kg/l/Packungsumrechnung und Ablehnung unbekannter oder unzulässiger Mengen.

`LocalDataScreenTest`: Produkt über die Compose-Oberfläche anlegen, persistierte Werte prüfen und erneutes Speichern bis zu einem ausdrücklich neuen Vorgang sperren. Bestehende Demo-/Navigations-/Renderprüfungen bleiben erhalten. Alle Android-Tests hier sind Robolectric-Tests, keine Geräte-/Emulatortests.
