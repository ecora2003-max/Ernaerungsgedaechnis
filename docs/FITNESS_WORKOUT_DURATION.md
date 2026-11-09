# Trainingszeit im Quellzeitfenster

Der physische Watch-Test vom 09.11.2026 belegt einen neuen Workoutimport in EG. Die Workout-Dauer in Samsung Health weicht von der Zeitspanne zwischen den gelieferten Originalzeitstempeln ab. Eine Ursache ist damit nicht belegt; Beginn/Ende werden weder gekürzt noch um geschätzte Pausen ergänzt.

## Bedienung und Bedeutung

In Fitness und Trainingsjournal liest **Trainingszeit im Quellzeitfenster prüfen** den Samsung-Health-Wert `ExerciseSessionRecord.EXERCISE_DURATION_TOTAL` aus Health Connect für genau den Originalzeitraum. Die bisherigen Rechte `READ_STEPS`/`READ_EXERCISE` genügen. Journalöffnung und normale Synchronisierung starten keine zusätzlichen Dauerabfragen.

Das Ergebnis ist ein **Zeitfenster-Aggregat**, keine separat überlieferte aktive Einzeldauer oder Energieschätzung. System-Datensatzabfragen filtern nach Beginn, Aggregate dagegen nach Überschneidung. Deshalb liest der Adapter vor der Aggregation auch früher begonnene Samsung-Trainings im bestehenden 28-Tage-Lesefenster. Bei gefundener Überschneidung übernimmt er keinen Wert. Ältere Einheiten außerhalb dieses Fensters lassen sich nicht ausschließen; die Anzeige bleibt ausdrücklich ein Fensterwert. Keine erweiterten Historienrechte, Pausenberechnung oder Hochrechnung.

Fehlend bleibt unbekannt, bekannte Null bleibt `0`. Nach erfolglosem Abruf ist ein früherer Wert mit Zeitpunkt **aktuell nicht bestätigt** lesbar. Herkunft, Originalzeitraum und gelesener Quellstand bleiben sichtbar. Geänderte/fehlende Quelle erfordert normalen Resync; ein Dauerabruf verändert weder Rohhistorie noch Tageswert.

## Eigener Vertrag und Speicherung

- `HEALTH_WORKOUT_DURATION_V1`/Format1 ist unabhängig von unverändertem `HEALTH_WINDOW_V1`/RohfingerprintV1. FoodStore leitet den Zeitraum aus der aktuellen sichtbaren Quellzeile ab und bindet ihn an ID, Revision und neu geprüften Fingerprint.
- Adapter: Samsung-Filter, alle Seiten, Limit, Rechte und abschließende Änderungsbarriere. Host: unabhängige Format-/Scope-/Frische-/Rohdaten-/Status-/Wertprüfung. Keine Zuordnung bei Überlappung, fremden/geänderten/fehlenden Daten oder unzulässigem Zeitraum.
- Lokale Freigabe vor IO; gleiche Autorisierungsrevision, aktuelle Quellversion und tatsächliche Rechte nochmals vor atomarem Commit. Stoppen, Widerruf, Korrektur oder Purge verhindern späte Writes. Gleicher zufälliger Vorgangsschlüssel liest bei Retry nicht erneut; fremde Wiederverwendung wird abgelehnt.
- Room19 ergänzt sechs nullable Evidenzfelder in `health_import_runs`: Quell-ID, Quellrevision/-fingerprint, Integer-Millisekunden, Status und fortlaufende Versuchsversion. Alle bisherigen Werte/Tabellen/Views bleiben erhalten. Versuchsversionen ordnen auch Abrufe in derselben Millisekunde. Neue Rohversionen erben keine alten Fensterwerte.
- Dauerprüfung ändert weder vollständigen Import-Erfolg noch Schritte, Ernährung, Lager, Preise oder Ziele. Kein KI-/Cloudexport. Purge redigiert alle sechs Felder, Zeiten und Hashes; opake Retrysperren bleiben gegen spätes Wiederherstellen erhalten.
- Ernährungssicherungen schließen diese Daten aus; ausdrücklich bestätigte Gesundheitssicherungen erhalten sie. Restore lässt Import deaktiviert. Gepinnte Sicherungen13–18 erhalten ausschließlich NULL für neue Felder, ihr bestätigter Original-Digest bleibt maßgeblich.

## Nachweisgrenzen

Synthetische Port-/Room-/UI-/Neustartfälle prüfen Integrität und Privacy; der reale leere API35-Systemprovider prüft auch den SDK-Aggregattyp, ohne Samsung-Datensätze zu erfinden. Der neue Fensterwert muss anschließend am physischen Ziel verglichen werden. Neue Watch-Schritte im EG-Tageswert und übrige Gate-B-Lebenszyklusfälle bleiben offen. Persönliche Werte/Nutzerbilder werden nicht veröffentlicht.

SDK: AndroidX Health Connect1.1.0, Duration-Konverter aus Integer-Millisekunden. Unterschiedliche Systemfilter: [Android RecordHelper](https://android.googlesource.com/platform/packages/modules/HealthFitness/+/refs/heads/main/service/java/com/android/server/healthconnect/storage/datatypehelpers/RecordHelper.java), `getReadTableWhereClause` versus Aggregatabfrage. Dies ist eine geprüfte Implementierungsbeobachtung, keine neue Plattformgarantie.
