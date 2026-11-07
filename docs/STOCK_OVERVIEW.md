# Vorräte nach Artikelversion und Charge finden

Unter **Lager → Artikelbestände und Chargen** lassen sich eigene Vorräte offline nach Artikelname, Marke, bekanntem Barcode, Chargenkennung und Lagerort durchsuchen. Mehrere Suchwörter können auf Name und Ort verteilt sein. Lagerort, verbleibende Mengen und heute nach Bestandsregeln angerechnete Chargen zusätzlich filtern. Die Suche speichert keine Mengen und erzeugt keine Buchung.

Jede genaue gespeicherte Artikelversion behält eine eigene Summe und ihre Chargen. Eine neue Rezeptur ersetzt ältere Bestände nicht. Gramm, Milliliter und Portionen werden nicht vermischt oder umgerechnet. Alle Summen beziehen sich ausschließlich auf die gerade angezeigten Chargen; Filter sind kein Nachweis vollständiger Vorratserfassung. Mengen bleiben bis auf die gespeicherte Tausendstel-Basiseinheit exakt.

**Physische Summe** und **heute nach Bestandsregeln angerechnet** sind getrennte Angaben. Derselbe FEFO-Kern wie in der Verzehr- und Herstellungsvorschau prüft die jeweilige genaue Fassung, Freigabe, bestätigten Ort, Zugangszeitpunkt und Haltbarkeitsdaten. Unbekannte Prüfgrundlagen bleiben unbekannt; bekannte ausgeschlossene Mengen erzeugen keine nutzbaren Mengen. Eine teilweise bekannte Summe wird ausdrücklich so bezeichnet. Das ist keine neue Allergen-, Lebensmittel- oder Lagerfreigabe und keine Reservierung für sämtliche Pläne.

Innerhalb einer Fassung stehen Chargen nach ihrer frühesten bekannten Haltbarkeitsgrenze; fehlende Termine folgen zuletzt. MHD und Verbrauchsdatum bleiben einzeln sichtbar. Ein überschrittenes MHD verlangt eine gesonderte Prüfung und bedeutet keine automatische Entsorgung. Aufgebrauchte und stornierte Chargen bleiben in der ungefilterten Historie erhalten. **Chargenangaben prüfen** öffnet genau die gewählte Charge; die bestehenden Korrektur- und Bestätigungsregeln gelten weiterhin.

Die vorhandene **Chargenentnahme** benötigt eine eigene Auswahl, tatsächliche Menge, Datum und ausdrückliche Zustimmung. Suchfilter allein wählen keine Buchungscharge. Nach Neuaufbau der Ansicht oder geänderten Chargendaten/-mengen muss die Zustimmung erneuert werden; Eingaben und Suchfilter können erhalten bleiben. Ein gespeicherter Verzehr aktualisiert die Summen aus dem tatsächlichen Ledger. Kauf, Preisbeobachtung und bloßes Lesen zählen nicht als zusätzliche Entnahme.

Schema und öffentlicher Rezeptvertrag bleiben unverändert. Den genauen JVM-/Room-/Android-/Offline-Belegstand führt [STATUS](STATUS.md).
