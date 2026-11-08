# Lagerorte prüfen und weiterverwenden

Unter **Mehr → Lagerorte & Kapazität** einen vorhandenen oder geplanten Ort auswählen. **Name oder Status prüfen** öffnet einen eigenen Entwurf. Namen, tatsächliches Vorhandensein und nachvollziehbaren Grund prüfen und ausdrücklich bestätigen. Es entsteht eine neue unveränderte Prüffassung zur selben Lager-ID; ursprüngliche Angaben und bestehende Chargen bleiben erhalten. Geplante Orte benötigen keine doppelte Neuanlage, wenn das Gerät später vorhanden ist.

Die Ortsprüfung bucht weder Lebensmittel noch Kapazität. Nach einer neuen Ortsprüfung freien Platz gesondert über **Aktuelle Angaben vor Ort prüfen** erfassen. Frühere Kapazitätswerte bleiben historisch sichtbar, gelten aber bis zu einer neuen physischen Prüfung nicht als bestätigter freier Platz – auch bei identischen Zeitstempeln. Fehlende Angaben bleiben unbekannt, bekannte Null bleibt Null. Planung reserviert nichts; Verzehr räumt Behälter nicht automatisch aus.

Ein bisher vorhandener Ort mit positivem Bestand kann nicht als geplant/unbestätigt zurückgenommen werden. Zuerst tatsächliche Umlagerung, Entsorgung oder Bestandskorrektur getrennt prüfen und buchen. Keine automatische Mengenverschiebung oder Löschung. Ein späteres Storno bleibt als eigene historische Gegenbewegung sichtbar; ungeklärte Lagerorte werden nicht als nutzbarer Vorrat angeboten.

Zwischenzeitliche Orts-/Kapazitätsprüfungen sperren einen veralteten Entwurf. Formular schließen, aktuellen Stand neu lesen und erneut bestätigen. Neustart oder Schließen verwirft ungespeicherte Ortsprüfungen und Zustimmung. Ein wiederholter bereits gespeicherter Vorgang liefert dasselbe Ergebnis; dieselbe Kennung mit anderen Angaben wird zurückgewiesen.

Technische Abnahme: append-only `storage_location_reviews`, aktuelle Room-Sicht bei unveränderten ursprünglichen `storage_locations`, erhaltendes Room 17→18 und explizite Altbackup-Brücken. Neue Android-/Offline-/Neustart-/SQLite-/Privacy-Belege vor Integration noch erforderlich; siehe [STATUS](STATUS.md).
