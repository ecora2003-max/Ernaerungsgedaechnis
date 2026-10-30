# Einkaufsvorschläge, Packungen, Preisfassungen und Budgetrahmen (seit 0.7, Budgetabgleich 0.8)

## Einstieg und Bedienung

Mehr → Lokale Testdaten & Sicherungen → Lokalen Testbereich öffnen → **Einkauf**. Weiterhin nur Testdaten. Die Haupt-Fachansichten außerhalb dieses Bereichs bleiben fiktive Beispiele.

Bedarfsausschnitt für einen, sieben oder 56 Tage, geplanten Einkaufstag und optional ein Budget wählen, anschließend „Einkaufsliste anzeigen“. Angezeigt werden die tatsächlich angewendeten Termine und der aktuelle Bestandsstand. Vergangene Einkäufe werden nicht aus heutigem Bestand rekonstruiert. Ein Einkauf nach dem ersten Bedarfstermin bleibt als Terminkonflikt sichtbar. Erneutes „anzeigen“ berechnet auch dieselben Filter frisch; vor Speichern werden Daten und Datum nochmals geprüft.

Die Liste stammt aus dem offenen Restbedarf der gemeinsamen Planung einschließlich bestätigter Ist-Zuordnungen. Geeigneter vorhandener Bestand wird vorher einmalig angerechnet. Bereits gekochte Zutaten und bereits gegessene, zugeordnete Portionen werden nicht erneut verlangt. Ungeklärte Planmengen bleiben offene Anforderungen.

Unter **Packungsbasis und Preis speichern** eine konkrete Artikelversion wählen. Erfasst werden Händler (optional), Beobachtungsdatum, nutzbare Menge in der Basiseinheit, Preis in EUR, Bezug auf eine ganze Kaufpackung, Beschreibung und Bestätigung der Mengenbasis sowie Preisgültigkeit und Bestätigung der Preisangabe. Quelle/Änderungsgrund angeben. Leer bleibt unbekannt, 0,00 € ist eine bekannte Null. Eine unklare Packungsbasis verhindert nicht das Speichern einer bekannten Preisangabe; sie verhindert eine unberechtigte Packungsrechnung.

Für die Packungsrechnung muss die Menge genau einer ganzen Kaufpackung in g, ml oder Portion entsprechen und bestätigt sein. Beispielsweise ist Abtropfgewicht von Gesamtfüllmenge zu unterscheiden. Die Beschreibung der nutzbaren Mengenbasis ist deshalb erforderlich. Eine alte Preis-Mengenangabe oder Katalog-Packungsgröße beweist diese Basis nicht. Es gibt keine erratene Dichte-, Stück- oder Abtropfumrechnung.

Pro Listenposition eine Packungs-/Preisfassung bewusst wählen. Schon vor dem Speichern zeigt die Vorschau die Packungszahl, Kaufmenge, Überschuss und Kosten dieser Auswahl. Erst **Auswahl / Markierung speichern** übernimmt die Auswahl dauerhaft in die Gesamtsumme. Es wird kein Angebot automatisch gewählt, auch nicht die größere oder nominell günstigere Packung.

Abhaken ist eine manuelle Listenmarkierung, kein bestätigter Kauf, keine bezahlte Ausgabe und kein Lagerzugang. Abgehakte Positionen bleiben sichtbar und in Bedarf/Kostenschätzung enthalten. Vor einer weiteren Schreibaktion oben **Neuen Vorgang beginnen** wählen. Eine andere Position abzuhaken verändert vorhandene gültige Markierungen nicht.

## Rechnung und Unsicherheit

Beispiel: 350 g bekannte Fehlmenge, 200 g nutzbare Menge je bestätigter Kaufpackung, gültiger geprüfter Preis 1,99 € je Packung → zwei ganze Packungen, 400 g Kaufmenge, 50 g Überschuss, 3,98 € geschätzte Kosten.

Intern ganze 1/1000-Basiseinheiten, BigInteger für Packungsrundung und Produkte, Geld als ganze EUR-Cents. Kein Gleitkomma und kein Abrunden fehlender Ware. Nicht darstellbare Mengen/Kosten bleiben ungeklärt statt überzulaufen. EUR ist die bisher unterstützte Währung; alte Angaben ohne Währungsnachweis werden nicht automatisch als EUR bestätigt.

Bei zusätzlichen unbekannten Bedarfsmengen ist die berechnete Packungszahl nur ein Minimum für den bekannten Anteil. Eine vollständige Gesamtsumme bleibt ungeklärt. Ebenso bei fehlender Packungsbasis, unbekanntem/unbestätigtem Preis, fehlender Währung, ungeklärter oder am Einkaufstag ungültiger Preisgültigkeit und erkannten Terminproblemen. Bekannte berechnete Teilkosten bleiben sichtbar. Eine bekannte kostenlose Packung bleibt von unbekanntem Preis unterscheidbar.

Preisgültigkeit bezieht sich auf den geplanten **Einkaufstag**, nicht auf das spätere Verzehrdatum. Ein gültiger Preis bescheinigt weder Lebensmittelhaltbarkeit bis zum Bedarf noch tatsächlich verfügbaren Lagerplatz. Vor Kauf diese Voraussetzungen und den sichtbaren Überschuss gesondert prüfen. Die Liste ist kein optimierter oder automatisch ausführbarer Einkaufsauftrag.

## Neue Fassungen und Markierungen

Preis- und Budgetänderungen erzeugen neue unveränderliche Fassungen mit Vorgänger, Quelle/Grund und Vorgang. Nur aktuelle Endfassungen können für neue Entscheidungen verwendet werden. Eine Preisfassung bleibt bei ihrer Artikelversion; für eine andere Version eine eigene Beobachtung anlegen. Ein anderer Budgetzeitraum benötigt ein eigenes Budget. Veraltete Bearbeitungsstände werden abgewiesen.

Ersetzt eine neue Preisfassung die gewählte alte, wird die neue nicht stillschweigend eingesetzt. Die alte Auswahl wird als überholt erkennbar und erzeugt keine scheinbar aktuelle Kostensumme. Neue Fassung prüfen und ausdrücklich auswählen. Auch frühere Bestätigungen werden beim Bearbeiten nicht ungeprüft als neue Bestätigung übernommen.

Ändern sich Plan-, Bestands-, Ist-Zuordnungs-, Preis- oder andere relevante Daten, verlangt eine frühere Markierung erneute Prüfung. Die historische Markierung bleibt gespeichert. Ein zwischenzeitlich geänderter und wieder gleicher Bedarf reaktiviert sie nicht. Das gilt auch bei identischen Uhrzeiten. Technische Wiederholungen derselben Vorgangs-ID erzeugen keine neue Markierung und reaktivieren keine inzwischen zurückgenommene.

Die aktuelle Umsetzung benutzt einen konservativen globalen Datenstand für diese Prüfung: Auch eine Änderung an anderer Stelle kann Markierungen veralten lassen. Reine Listenmarkierungen sind davon ausgenommen, damit Abhaken einer Position die anderen nicht ungültig macht. Ein veralteter Speicherversuch wird abgewiesen; nichts wird als Kauf gebucht.

## Budgetobergrenze und tatsächliche Ausgaben

Unter **Budgetobergrenze speichern** Zeitraum, Namen, Betrag in EUR, ausdrückliche Bestätigung und Grund erfassen. Ein leerer Betrag bleibt ungeklärt; ein bewusstes 0-€-Budget bleibt 0. Überlappende Budgets werden nicht addiert. Für den Vergleich wird genau die ausgewählte aktuelle Fassung verwendet, und der Einkaufstag muss in deren Zeitraum liegen.

Seit 0.8 kommen ausdrücklich bestätigte tatsächliche Einkäufe hinzu. Bekannte Ausgaben und bekannte offene Einkaufskosten können bereits eine Überschreitung belegen. Ein Unterschreiten allein beweist keinen freien Spielraum: Der Budgetrest erscheint erst nach vollständigem Ausgabenabgleich unter **Käufe**, für Budgetbeginn bis heute (höchstens Budgetende). Unbekannte Beträge/Kaufdaten und unbestätigte Altbelege verhindern die vollständige Summe. Preisbeobachtungen, Listenhäkchen und Vorratsbewegungen zählen nicht als zusätzliche Zahlungen. Nach vollständigem Abgleich: Rest = Obergrenze minus tatsächliche Ausgaben, danach gegebenenfalls zusätzlicher Spielraum nach geschätztem offenem Einkauf. Bedienung, Berechnungsgrenzen und Stornos: `PURCHASES.md`.

## Architektur und Migration

Schema **5** führte die 29 Tabellen ein; aktuelles Schema **6** ergänzt drei Tabellen für echte Käufe und Ausgabenabgleich. `price_observations` hat nullable Mengen/Preise und zusätzliche Bestätigungs-/Gültigkeits-/Währungs-/Versionsmetadaten; `budgets` hat begründete Fassungen. `shopping_marks` speichert ausschließlich Listenentscheidungen mit Vorgänger, Revision und geprüftem Inhaltsstand. Eine eindeutige Kette je Artikelversion, Bedarfsausschnitt und Einkaufstag verhindert parallele Überschreibung.

Migration 4→5 übernimmt sämtliche alten Werte der Preistabelle beim erhaltenden Tabellenumbau. Alte Währung, Packungsbezug, Bestätigungen und Gültigkeit bleiben NULL, keine erfundene Freigabe. Vorhandene Pläne, Positionsidentitäten, Ist-Zuordnungen und bisherige Retry-Schlüssel bleiben erhalten. Migrationen 1→2→3→4→5 registriert, keine destruktive Fallback-Migration. Alte Schemaexporte unverändert.

`ShoppingReader` liest Restbedarf, Preise, Budgets und Markierungen in derselben Room-Transaktion. `ShoppingMath` ist die gemeinsame Berechnung für Vorschau und gespeicherte Auswahl; keine unabhängige zweite Mengen-/Preissummenwahrheit. `ShoppingOperations` schreibt atomar und idempotent über `FoodStore`. App-Ereignisse werden über IDs statt nur Uhrzeiten in den Prüfstand einbezogen; reine Einkaufslistenmarkierungen verändern diesen Datenstand nicht selbst. Rechenversion `shopping-1`.

Prüfnachweis der Einkaufsgrundlagen: `TEST_REPORT_SHOPPING.md`; aktueller Kauf-/Ausgabenabgleich: `TEST_REPORT_PURCHASES.md`. Grenzen: keine Händler-/Packungsoptimierung, Produktalternativen oder automatische Bestellung. Große Historien nicht lastgetestet. Belegkorrekturfassungen/Erstattungen, Backups, stabile Release-Signierung, Gerätetests, komplexe Vergangenheitskorrekturen und kontrollierte produktive Migration stehen weiter aus.
