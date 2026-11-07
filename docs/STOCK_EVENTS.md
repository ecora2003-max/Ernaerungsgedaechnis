# Tatsächliche Lagerereignisse und nachvollziehbare Gegenbuchung

Unter **Lager → Umlagern, entsorgen oder zählen** die genaue Quellcharge wählen. Artikelversion, Einheit, Ort und physischer Restbestand gehören zu dieser Charge. Datum und Begründung des tatsächlichen Vorgangs sind erforderlich; die gesonderte Bestätigung wird vor dem Buchen geprüft.

**Umlagern:** Menge und bestätigtes Ziellager wählen. Nur die zusätzliche Prüfung geeigneter Zielbedingungen darf eine vorhandene Freigabe übernehmen. Die Teilmenge wird atomar aus der Quelle aus- und in eine neue, historisch verknüpfte Zielcharge eingebucht. Ohne diese Prüfung bleibt die neue Charge ungeklärt; eine zuvor gesperrte Charge wird durch die Prüfung nicht freigegeben.

**Entsorgen:** Tatsächlich verworfene Menge und Grund erfassen. Die bestätigte Entsorgung mindert den Lagerbestand. Sie erzeugt keinen gegessenen Verzehr und keine ernährungsbezogenen Nährwerte.

**Istzählung:** Nach Auswahl der Charge zeigt die Vorschau den dabei geprüften Ausgangsbestand. Den tatsächlich gezählten Rest eingeben; `0` bedeutet ausdrücklich leer, eine leere Eingabe bleibt ungültig. Hat sich der Bestand inzwischen geändert, die Charge erneut auswählen und den Zählstand prüfen. FoodStore prüft den Ausgangsbestand erneut atomar und speichert ausschließlich die begründete Differenz als neue Bewegung.

Eingaben bleiben nach Activity-Neuerstellung erhalten. Die Bestätigung des Lagerereignisses und die Prüfung der Zielbedingungen gelten nur im Arbeitsspeicher für diesen Vorgang und den gerade geprüften Chargenstand. Nach Neustart, neuer Vorgangskennung, geänderter Charge oder geänderten Bestätigungsgrundlagen müssen sie erneut erfolgen. **Neuen Vorgang beginnen** erhält die Eingaben, erteilt aber keine neue Zustimmung. Ein Retry derselben bereits bestätigten Vorgangskennung erzeugt keine zusätzliche Bewegung.

Unter **Mehr → Chronik** einen Vorgang aus der [vollständigen Lager- und Verzehrchronik](STOCK_HISTORY.md) zur Gegenbuchung auswählen. Die zusätzliche Schnellauswahl enthält die letzten 30 betreffenden Vorgänge und einen gegebenenfalls gerade aus älterer Historie gewählten Eintrag. Gegenbuchungen prüfen und Stornodatum sowie Grund angeben. Erst die gesonderte Bestätigung und **Vorgang stornieren** erzeugen Gegenbewegungen mit Bezug auf die unverändert erhaltenen Ursprungsbewegungen. FoodStore weist unzulässige Abhängigkeiten und negative Bestände zurück. Ein stornierter tatsächlicher Verzehr bleibt historisch erhalten und der betroffene Ernährungstag wird wieder unvollständig.

Gerätenachweise und exakte Teststände stehen im [STATUS](STATUS.md). Activity-Neuerstellung und unabhängiges SQLite-Reopen belegen für sich allein keinen vollständigen Prozessabbruch.
