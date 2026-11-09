# Zusätzliche Health-Connect-Quelldaten

## Zweck und Freigabe

`HEALTH_EXTRA_WINDOW_V1` ergänzt die bisherigen Samsung-Schritte/Trainings und den separaten Trainingsfensterwert. Deren v1-Verträge bleiben unverändert. Die Ergänzung hat einen eigenen FoodStore-Lesescope, eine eigene lokale Einwilligungsrevision und eigene Room20-Tabellen. Eine bestehende Basisfreigabe aktiviert keine zusätzlichen Typen oder Quellen.

Unter **Fitness → Puls, Energie, Distanz & weitere Gesundheitsdaten** oder **Mehr → Weitere Gesundheitsdaten** wählt der Nutzer einzelne Typen, Kalenderfenster und Enddatum und bestätigt ausdrücklich. Erst dann werden die tatsächlich benötigten OS-Leserechte angefragt und nochmals vor dem atomaren Commit geprüft. Ein wiederhergestelltes Betriebssystem-Callback ohne lebende Bestätigung aktiviert nichts. Automatische Aktualisierung erfolgt nur beim Öffnen dieser Ansicht und nur für die zuvor gespeicherte Freigabe. Formularänderungen gelten erst nach erneuter Bestätigung.

## Daten und Einheiten

| SDK-Typ | Gespeicherte kanonische Einheit | Bedeutung |
| --- | --- | --- |
| HeartRateRecord | Integer-bpm je Messpunkt | Originalreihe, kein geschätzter Mittelwert |
| DistanceRecord | m | Originalintervall, keine Weg-/Standortdaten |
| SpeedRecord | m/s je Messpunkt | Originalreihe |
| ActiveCaloriesBurnedRecord | kcal | Aktivenergie dieser Quelle und dieses Intervalls |
| TotalCaloriesBurnedRecord | kcal | Gesamtenergie; nicht zur Aktivenergie addieren |
| BodyFatRecord | % | Einzelmessung; Methode ohne Metadaten unbekannt |
| BasalMetabolicRateRecord | W | Quellrate, zusätzlich SDK-Umrechnung in kcal/Tag; weder Energieeintrag noch TDEE |
| StepsRecord.COUNT_TOTAL | nullable Integer-Schritte | SDK-Aggregat nur für das ausgewählte Enddatum, mit gemeldeten mitwirkenden Quellpaketen |

SDK-Doublewerte werden in ihrer kanonischen Einheit als IEEE754-Rohbits in SQLite-Integerfeldern erhalten. Bekannte Null und negative Null bleiben erhalten, fehlende Werte bleiben null. Zeitstempel verwenden wie der bestehende lokale Import Epoch-Millisekunden; mitgelieferte UTC-Offsets und Messpunktreihen bleiben erhalten. Quellpaket, Quell-ID/-Version, Geräteangaben und Aufzeichnungsmethode werden gespeichert, nicht aus Anzeigebezeichnungen erfunden.

Die optionale Auswahl liest **alle von Health Connect freigegebenen Quellpakete** für diese Typen. Eine Samsung-Quelle allein beweist kein Uhrenmodell. Telefonaktivenergie ist kein Watch-Aktivenergienachweis. Tages-/Workoutintervalle, Aktiv-/Gesamtenergie und Rohschritte aus mehreren Quellen werden nicht summiert. Zusatzwerte werden keinem einzelnen Workout automatisch zugeschrieben.

## Historie und Konsistenz

- Fenster: 7/14/28/42/90 Kalendertage bis heute oder einem ausdrücklich ausgewählten früheren Enddatum, mit tatsächlicher lokaler Zeitzone und Sommerzeitgrenzen. Der Schritt-Tageswert gilt ausschließlich für dieses letzte Datum, nicht für das ganze Fenster.
- Liegt der tatsächliche Leseanfang mehr als 30 Tage vor dem Auftrag, sind eigene Historieneinwilligung, tatsächliche SDK-Featureverfügbarkeit und `READ_HEALTH_DATA_HISTORY` erforderlich. Ohne diese Voraussetzungen kein alter Abruf und keine ersatzweise Null. Bei ausschließlich gewähltem Schritt-Tageswert zählt nur dessen tatsächlicher Tag als Leseanfang.
- Vor/zwischen/nach paginierten SDK-Leseaufrufen werden Rechte geprüft. Ein Änderungs-Token über die ausgewählten Typen sichert den Lesestand. Änderungen während des Abrufs führen zum nachvollziehbaren Retry; der letzte lokale Stand bleibt erhalten.
- Nach dem Lesestand endende Originalintervalle bleiben unverändert zurückgestellt. Keine Abschneidung, Hochrechnung oder Zukunftsdauer. Spätere Freigabe und Quellkorrekturen erhalten eigene lokale Versionen; identische Retries erzeugen keine Dubletten.
- Nur vollständig abgedeckte, gewählte Quelldatensätze werden beim Fehlen als nicht mehr sichtbar markiert. Andere gespeicherte Typen/Zeiträume bleiben erhalten und gelten nicht als frisch geprüft. Die Rohwertanzeige lässt sich unabhängig nach Datentyp und tatsächlichem Quellpaket filtern; das ändert keine Lesefreigabe und löst keinen SDK-Aufruf aus. So bleiben auch ältere Waagenwerte neben umfangreichen Pulsreihen auffindbar. Fehlende Sichtbarkeit ist kein behaupteter Quell-Löschbeweis.

## Löschung, Sicherung und Abnahme

Die Zusatzansicht kann nur ihre eigene Kopie löschen/ausschalten. Die gemeinsame Löschung in Fitness löscht und stoppt Basis- und Zusatzkopie atomar. Manuelle Messungen/Trainings, Ernährung, Bestand und Health-Connect-Quelldaten bleiben erhalten. Opaque Retrysperren verhindern, dass ein alter Import Daten wiederbelebt oder eine alte Löschung neuere Daten entfernt. Auch ein bereits vor Room20 abgeschlossener Basis-Löschauftrag darf beim Retry keine neu hinzugekommenen Zusatzdaten löschen.

Alle vier Tabellen gehören zum ausdrücklich wählbaren Gesundheitsumfang einer verschlüsselten manuellen Sicherung. Wiederherstellung prüft Einheiten, Zahlen, Messpunkte, Herkunftsversionen und Lesefenster und setzt die Übernahme aus. Publizierte Room13–19-Sicherungen erhalten explizit leere neue Bereiche; alte Originaldatei-Digests und Retrysperren bleiben maßgeblich. Keine destruktive Migration, kein automatischer Export, kein Hintergrund-/Schreib-/Standortrecht und kein Export dieser Health-Connect-Rohwerte an die Cloud-KI.

Erforderlich sind volle JVM-/Room-/Migrations-/Sicherungs- und native Bedien-/Neustart-/Netzfreiheitsbelege sowie ein echter leerer SDK-Systemprovider. Diese synthetischen bzw. leeren Providerfälle ersetzen keine physische Pixel-/Android17-/Watch-Abnahme. Nutzerbilder bestätigen die Typverfügbarkeit, nicht die fertige App-Übernahme jedes Typs. Neue Watch-Schritte, echte Zusatzdatenübernahme, ältere Waagenhistorie und übrige Gate-B-Lebenszyklusfälle bleiben physisch zu prüfen. K03-/K04-Modellparameter bleiben offen; Quelldatenverfügbarkeit ist keine Statistik- oder TDEE-Abnahme.
