# Tatsächliche Käufe, Belegpositionen und Ausgaben (0.8)

Im isolierten Testbereich **Käufe** öffnen. Nur Testdaten verwenden. Die bisherigen 33 Fachansichten bleiben Demoansichten; keine Anbindung an Sheets oder persönliche Daten.

Seit 0.9 lassen sich unter Scan → Bon & OCR ungeprüfte Erkennungsentwürfe speichern und anschließend ausdrücklich übernehmen. Sie zählen bis zur Zahlungsbestätigung nicht als Ausgabe; derselbe Entwurf wird höchstens einmal übernommen. Anleitung: [RECOGNITION.md](RECOGNITION.md).

## Bezahlten Einkauf erfassen

Händler, tatsächliches Kaufdatum und tatsächlich bezahlten Betrag in EUR eingeben. Unbekannte Angaben leer lassen. Das Datum wird nicht aus dem heutigen Tag erfunden. Ein bestätigter Nullbetrag ist von einem unbekannten Betrag verschieden. Quelle/Grund angeben und den tatsächlich bezahlten Einkauf ausdrücklich bestätigen. Der Betrag bezieht sich auf den erfassten Ausgabenbereich; keine automatische Aufteilung gemischter Käufe.

Optional mehrere Belegpositionen hinzufügen. Lesbaren Originalbontext getrennt von der eigenen Interpretation erfassen; bei unbekanntem Originaltext bleibt dieser leer. Positionspreise dürfen fehlen oder von der Zahlung abweichen. Sie werden nicht zur Zahlung addiert und ersetzen diese auch nicht. Vollständig leere Formularpositionen werden nicht gespeichert. Der Service kann außerdem unbestätigte Produkt-/Mengenvorschläge erhalten; daraus entstehen keine Lagerzugänge.

Nach Erfolg **Neuen Vorgang beginnen** wählen. Technische Wiederholungen derselben Vorgangs-ID erzeugen keine zweite Zahlung. Eine absichtliche neue Eingabe mit neuer ID bezeichnet ein neues Ereignis; zwei gleich teure Einkäufe am selben Tag sind nicht automatisch Duplikate.

## Einzelne Positionen ins Lager buchen

Beim gespeicherten Einkauf eine Position öffnen. Konkrete Artikelversion, tatsächlich vorhandenen bestätigten Lagerort und nutzbare Menge in deren Basiseinheit wählen. Mengenbasis beschreiben und ausdrücklich bestätigen. Brutto-, Abtropf-, Stück- und Packungsgewicht werden nicht gleichgesetzt. Eine bisherige Interpretation darf dabei ausdrücklich geklärt werden; Originaldaten bleiben unverändert erhalten. Das Kaufdatum muss bekannt sein und ist zugleich Zugangsdatum.

Haltbarkeitsangaben bleiben optional; ohne zusätzliche ausdrückliche Freigabe bleibt die Charge ungeklärt. Den tatsächlichen Positionszugang separat bestätigen. Position, Zugangsvorgang, Charge und Bewegung sind atomar verknüpft. Ungeklärte andere Positionen bleiben offen und hindern diesen Zugang nicht. Eine Position kann über alle Wiederholungen und Stornos hinweg höchstens einmal gebucht werden. Ein Zugang erfasst keine weitere Ausgabe und keinen Verzehr. Freigegebener Zugang verändert den verbleibenden Einkaufsbedarf; alte Listenbestätigungen verlangen erneute Prüfung.

## Ausgaben vollständig abgleichen

Zeitraum von einem bis 366 Tagen bis höchstens heute wählen. Die erfasste Teilsumme enthält nur aktive, bestätigte Zahlungen mit bekanntem Datum innerhalb dieses Zeitraums und bekanntem EUR-Betrag. Undatierte Einkäufe bleiben in jedem Zeitraum prüfpflichtig und werden keinem Zeitraum fälschlich zugerechnet. Alte Belege ohne Zahlungsereignis sind ebenfalls sichtbar und gelten nicht allein aufgrund ihrer Datenqualität als bezahlt. Eine tatsächliche Zahlung zu einem solchen Beleg kann ausdrücklich bestätigt werden, ohne seine alten Daten zu überschreiben.

Erst wenn alle relevanten Beträge/Daten/Zahlungen geklärt sind und der Nutzer alle Ausgaben im Zeitraum ausdrücklich als vollständig erfasst bestätigt, erscheint eine vollständige Gesamtausgabe. Das gilt auch für 0 €. Ein leerer Ledger beweist keine vollständige Erfassung. Die Bestätigung ist eine Nutzeraussage, keine automatische Prüfung externer Bank- oder Händlerdaten.

Neue Käufe und Stornos im Zeitraum sowie undatierte Einkäufe veralten frühere Bestätigungen. Ein Kauf mit anschließendem Storno reaktiviert keine alte Bestätigung, auch bei derselben Uhrzeit und wieder gleicher Summe. Erneut prüfen und bestätigen oder den Erfassungsstatus ausdrücklich öffnen. Alte Retries ändern den aktuellen Status nicht. Ein zusätzlicher Lagerzugang allein verändert die Zahlung und deren Vollständigkeitsnachweis nicht.

## Budgetvergleich

Unter **Einkauf** genau eine aktuelle bestätigte Budgetfassung wählen. Für einen verfügbaren Budgetrest muss der Zeitraum **Budgetbeginn bis heute**, höchstens bis zum Budgetende, vollständig abgeglichen sein. Bei einem erst künftig beginnenden oder für diesen Abgleich zu langen Zeitraum bleibt der Rest ungeklärt. Nach einem Tageswechsel erneut anzeigen und den erweiterten Zeitraum prüfen.

Budgetrest = bestätigte Obergrenze minus vollständig abgeglichene tatsächliche Ausgaben. Negative Werte zeigen eine Überschreitung. Danach wird zusätzlich der Spielraum nach den geschätzten offenen Einkaufskosten angezeigt, sofern diese vollständig berechenbar sind. Beispiel: 10,00 € Budget, 3,98 € tatsächlich bezahlt → 6,02 € Rest; zusätzlich geplanter Einkauf 1,99 € → 4,03 € erwarteter Spielraum. Noch ungeklärte Ausgaben verhindern diese Restbeträge. Bekannte Ausgaben und bekannte Einkaufskosten können dennoch bereits eine Überschreitung belegen. Überlappende Budgets werden nicht addiert.

## Fehler korrigieren und Grenzen

Ein fehlerhafter Positionszugang kann mit Datum/Grund storniert werden. Aktive abhängige Buchungen zuerst prüfen/stornieren; keine negativen Bestände. Die Originalposition bleibt gegen erneutes Buchen gesperrt. Zum Stornieren einer fehlerhaften Kaufbuchung müssen deren gebuchte Lagerzugänge zuvor storniert sein. Zahlung, Originalbeleg, Positionen, Verknüpfungen und Gegenbuchungen bleiben historisch erhalten. Technische Retries reaktivieren nichts; bei Bedarf eine korrekte neue Erfassung anlegen.

Dieses Storno korrigiert eine fehlerhafte Erfassung. Tatsächliche Rückgaben, Erstattungen, andere Währungen, Nachträge/Korrekturfassungen zu bestehenden Belegen und das Verwerfen unbezahlter Altbelege sind noch nicht implementiert. Insbesondere fehlen bequeme Ergänzungen unbekannter Beträge/Kaufdaten; derzeit erforderlichenfalls fehlerhaften Einkauf stornieren und korrekt neu erfassen. Kein falsches Kaufdatum eingeben. Die bestehende konservative Rückdatierungssperre bei komplexen bestandsgebundenen Vergangenheitskorrekturen bleibt bestehen. Kein OCR/Foto-/Barcodeimport, keine automatische Händler-/Packungsoptimierung, keine produktive Migration. Große Datenmengen und echte Geräte noch nicht geprüft.

## Architektur und Migration

`receipts.totalCents` ist der einzige Zahlungsbetrag des bestätigten Ereignisses. `purchase_events` bestätigt genau einen Beleg einmal als bezahlten EUR-Einkauf. Weder Bonpositionen, Preisbeobachtungen, Listenhäkchen noch Bewegungen werden zusätzlich summiert. Historische Belege ohne Ereignis werden nicht automatisch zu Ausgaben.

`receipt_stock_bookings` verknüpft eine Originalposition eindeutig mit einem eigenen Zugangsvorgang. `expense_status_events` speichert unveränderliche Vollständigkeits-/Öffnungsaussagen mit Vorgänger, Revision und konkretem Datenstand. Datenstände enthalten Ereignis-/Storno-IDs, keine bloßen Uhrzeiten. Schreibgrenze `FoodStore` → `PurchaseOperations`; `ExpenseReader` liest einen konsistenten Transaktionsstand und liefert denselben Ausgabenabgleich an den Budgetvergleich. Geld als ganze Cents, BigInteger-Aggregation mit Bereichsprüfung, unbekannt als NULL.

Schema **6**, 32 Tabellen. Migration 5→6 ergänzt nur diese drei Tabellen und Fremdschlüssel/Indizes. Alle alten Werte, Verweise und Retry-Schlüssel bleiben erhalten; keine automatische Zahlungs- oder Vollständigkeitsbestätigung. Migrationen 1→2→3→4→5→6 registriert, kein destruktiver Fallback. Prüfnachweis: [TEST_REPORT_PURCHASES.md](TEST_REPORT_PURCHASES.md).
