# Angebote und Preisvergleich – P0-Anschluss

Stand 08.11.2026. Verbindlicher Auftrag: [Issue 286](https://github.com/ecora2003-max/Ernaerungsgedaechnis/issues/286), [P0 vor P1](P0_GEMINI_KNUSPR.md). **Implementierter Anschluss ist keine Liveabnahme und keine Quellfreigabe.**

In der Standard-App: **Mehr → Angebote & Preisvergleich**, ebenso aus **Einkaufsvorschläge → Aktuelle Angebote & Preisvergleich**. Anbieter, getrennte Filiale/Region bzw. Knuspr-Liefergebiet und Einkaufstag wählen, Auswahl anwenden. Namen aus der aktuellen lokalen Bedarfsliste filtern ausschließlich bereits geladene Angebote; sie werden dadurch nicht übertragen. Angebot, Vergleich und Navigation buchen keine Lebensmittel und ändern keine bezahlten Preise.

## Tatsächlicher Quellenstand

Die vorhandene abgeschlossene Quellenbewertung [EG-Preisdaten@5c52bf7/SOURCES](https://github.com/ecora2003-max/EG-Preisdaten/blob/5c52bf708a3bfa91e5bfd309bbddc308b3d6766a/docs/SOURCES.md) wird übernommen. Keine erneute pauschale Rechtsrecherche und kein Freigabe-Checkbox-Ersatz. Aktuell ist **kein produktiver Preisfeed aktiviert**:

| Anbieter | App-Anzeige | Technischer Anschluss / konkreter fehlender Nachweis |
|---|---|---|
| ALDI Nord | BLOCKED / UNVERIFIED | Gepinnter Next-Data-Parser vorbereitet; kettenweite nächste Woche belegt keine konkrete Filiale. Autorisierter Endpoint, Quelle und genaue Geltung fehlen. |
| ALDI Süd | BLOCKED / UNVERIFIED | Keine in dieser Integration verifizierte veröffentlichte Filial-JSON-Antwort bzw. aktivierte Quelle. |
| Lidl | BLOCKED / UNVERIFIED | Gepinnter Store-v4-Parser und HTTP-Anschluss; unabhängig bestätigte offizielle storeKey-Zuordnung und Anzeigefreigabe fehlen. Online-/Coupon-/Mehrkaufformen werden nicht in Regalpreise umgedeutet. |
| REWE | BLOCKED / UNVERIFIED | Keine hier verifizierte veröffentlichte Filial-Antwort bzw. aktivierte Quelle. |
| PENNY | BLOCKED / UNVERIFIED | Gepinnte Regionskacheln/HTTP vorbereitet; exakte Markt→Verkaufsregion und Quellenfreigabe fehlen. Wochenroute belegt kein individuelles Ablaufdatum. |
| Kaufland | BLOCKED / UNVERIFIED | Gepinnte Angebotszyklen plus gesonderte Filialverfügbarkeit/HTTP vorbereitet; bestätigter Filialanschluss und Nutzungsfreigabe fehlen. Disjunkte Gültigkeitsfenster werden nicht verbunden. |
| Netto Marken-Discount | BLOCKED / UNVERIFIED | Vorliegende Referenz hat keinen verifizierten strukturierten filialbezogenen Preisendpoint. Prospektlink oder Webshoppreis ersetzt ihn nicht. |
| EDEKA | BLOCKED / RESTRICTED | Gepinnter Markt-Parser/HTTP vorhanden; fortlaufende Anzeige nicht freigegeben. `national=true` bleibt nationale Ersatzangabe, keine Marktbestätigung. |
| Knuspr | BLOCKED / LIVE_UNVERIFIED | Offizieller Geräte-OAuth-/MCP-Anschluss vorbereitet. Tatsächlicher Login, Toolnamen/Antwortschema, Quellen-/Anzeigerecht und Liefergebiet noch unbelegt. Umgebungssperre1010 nicht erneut angefragt/umgangen. |
| Picnic | DEFERRED | Zugangs-/Rechteblockade; langfristiges Ziel bleibt erhalten. Kein P0-Hindernis. |

Nur ein **unabhängiger Host-Nachweis** darf einen bestimmten Quellen-/Capability-/HTTPS-Endpoint-/Filial- oder Liefergebiets-/Zeitraumkontext aktivieren. UI-Kennungen, Lieferantenfelder, OAuth und erfolgreiche HTTP-Antworten erfüllen diesen Nachweis nicht. Mehrere benötigte Endpoints werden alle vor dem ersten Abruf geprüft. Fehlende Quelle startet keinen Netzabruf. Keine Bulk-Katalogspiegelung.

## Aktualisierung, Anzeige und Vergleich

- Manuelle Abfrage und ausdrückliche Automatik verwenden dieselbe Quelle und denselben erlaubten Mindestabstand. Automatik läuft nur bei sichtbarer, fortgesetzter Angebotsseite; keine Hintergrundabfrage. Ohne bestätigten Rhythmus keine Aktivierung. Private Abrufzeitpunkte bleiben über Neustart erhalten; Fehler/Abbruch pausieren automatische Folgeabfragen bis zu einem erfolgreichen ausdrücklichen Abruf. Kein stilles Provider-Fallback.
- Nur ausdrücklich erlaubter Sitzungscache darf bei einem Abruffehler oder zum Anbieterwechsel erhalten bleiben. Rechte/Scope werden erneut geprüft. Alter Stand wird als solcher angezeigt; keine erfundene erfolgreiche Aktualisierung. Preise werden in diesem Anschluss nicht dauerhaft gespeichert. Auswahl und Abrufsteuerung liegen separat in `noBackupFilesDir`, außerhalb von Room-/Android-Sicherungen.
- Warenpreis, Pfand, Zahlungsbetrag, Mengenbasis, Preisart, Angebotsgültigkeit, tatsächlicher Abruf- und optionaler Quellzeitpunkt bleiben getrennt. `null` bleibt unbekannt; 0 ist ein bekannter Wert. Freitext zur Packung ist kein bestätigtes Gewicht oder Abtropfgewicht. Grundpreisberechnung rundet einmal und konvertiert keine Masse in Volumen.
- Zwei geladene Angebote oder eine zum Cache freigegebene Sitzungsreferenz können geprüft verglichen werden. Bestehende EG-Einkaufsintelligenz-Kerne für Artikelvergleich und exakte rationale Grundpreise werden wiederverwendet. Unabhängige Produktversions-/Artikel-/Mengen-/Bedingungs-/Scope-Nachweise und weiterhin gültige Quellfreigabe sind erforderlich. Bei fehlender Feedmenge darf ausschließlich eine zusätzlich unabhängig bestätigte genaue Angebotsgebinde→Artikelfassung-Zuordnung die Vergleichsmenge liefern; widersprechende vorhandene Mengen/Einheiten/Basen werden abgewiesen, ursprüngliche Unbekannte bleiben unverändert. Beide tatsächlich verglichenen Werte samt Mengenbasis werden angezeigt; im aktuellen Produktionsstand fehlen diese Live-Nachweise. Neu beobachtete Preise bewerten keinen vergangenen Einkauf. Versand/Pfand/Mindestbestellwert/gesamter Warenkorb und Marktbestpreis sind damit nicht belegt. Keine automatische Ersatzproduktentscheidung.

**Mengenbrücke zum privaten Katalog:** FoodStore/Room speichern Packungsmengen in Millieinheiten, der gepinnte Einkaufsvertrag verwendet ganze g/ml pro Einzelgebinde. Nur eine exakte Umrechnung ist zulässig:500.000 im Katalog bedeutet500g, nicht500.000g. Nichtganzzahlige Katalogmengen bleiben für diesen v1-Vergleich gesperrt; keine Rundung und kein Einheitenwechsel. Packungszahl multipliziert erst die Vergleichsmenge, nicht die separat geprüfte Einzelpackung. Quellen-, Identitäts- und Preisbedingungen bleiben davon unabhängig. Regression und echte Android-Ausführung siehe aktuellen STATUS.

**Produktiver Artikelanschluss weiterhin offen:** Die Standard-App besitzt noch keine echten unabhängig authentifiziert bestätigten Händler-SKU→Produktversions-/Gebinde-/Bedingungsreviews. `LiveOfferComparison` bleibt deshalb mit leeren Reviews/`denyAllArticles` gesperrt. Der positive Androidvergleich nutzt ausdrücklich eine synthetische unabhängig deklarierte Registry und belegt keine produktive Zuordnung. Nach real bekannten Tool-/Artikelprofilen den tatsächlichen UI-/Registry-Provider an `HostCatalogArticleRegistry` anschließen und separat abnehmen; weder Login noch Quellenfreigabe allein erlauben eine erfundene Identität oder FoodType-Semantik.

## Knuspr-Geräteweg und Datenschutz

Ausdrückliche Verbindung → offizieller401-MCP-OAuth-Challenge → tatsächliche geschützte Resource-/Issuer-Metadaten → öffentliches Client-Registration/S256-PKCE → externer Browser → einmaliger privater Callback. 403/429 beenden den Versuch; keine Umgehung, Cookies, Redirectweitergabe von Tokens oder automatische Wiederholung. Falls der offizielle Server die unterstützte öffentliche Clientregistrierung nicht anbietet, bleibt dieser Anschluss gesperrt; kein eingebettetes Clientsecret.

Zustand/PKCE/Token werden ausschließlich appprivat mit AndroidKeyStore AES-GCM und AtomicFile unter `noBackupFilesDir` gespeichert, nie in SavedState, Room-Backups, Git oder Diagnosemeldungen. Frische zufällige State-/PKCE-Werte; begrenzte Laufzeit, genauer Redirect, Issuer-/Replayprüfung. Ein verspätetes Loginresultat darf eine getrennte oder neu begonnene Verbindung nicht wiederbeleben. Verbindungsdaten lassen sich ohne Löschen der Lebensmittelhistorie verwerfen.

Nach Anmeldung liest **Lesefunktionen prüfen** tatsächlich `initialize`/`tools/list`; es werden keine Toolnamen erfunden. Tatsächlich empfangene Toolnamen und Ein-/Ausgabeschemas lassen sich nach eigener Schaltfläche lokal für die Anschlussprüfung kopieren; weder Tokens/Sessionheader noch Tool-Ergebnisse oder persönliche Verläufe werden aufgenommen. Ausschließlich tatsächlich entdeckte kompatible read-only Suchschemata sind technische Kandidaten. Produktdaten benötigen zusätzlich einen unabhängigen Host-Nachweis; Produktaufrufe sind standardmäßig gesperrt. Unbekanntes Antwortformat und bloße Prosa erzeugen keine Preisbeobachtungen. Kein Warenkorb, Checkout, Zahlung oder Ernährungs-/Körper-/Trainingsdatenexport.

## Offene Abnahme

Synthetische Protokoll-/Parser-/Hosttests und echte Emulatorfälle sind getrennt von **echten Händler-/OAuth-/Preis-/Liefergebietsbelegen** zu dokumentieren. Alle neun Live-Anschlüsse und P0 bleiben offen, bis reale autorisierte Ergebnisse nachgewiesen sind. Nächster konkreter externer Schritt: autorisierter Knuspr-Login auf dem Testgerät, tatsächliche Tool-/Antwortschemata und freigegebenen gezielten Leseumfang prüfen; für klassische Händler jeweils konkret freigegebenen Feed und kanonischen Filialkontext bereitstellen. Danach dieselben vorbereiteten Gate-/Anzeige-/Vergleichspfade mit echten Daten abnehmen. P1 erst nach Abschluss bzw. belegter externer Blockade des P0-Vertikalschnitts gemäß Auftrag.
