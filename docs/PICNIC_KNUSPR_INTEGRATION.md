# Verbindliche Zusatzintegration: Picnic und Knuspr (DE)

Stand: 08.11.2026. **Nutzerauftrag:** Beide Online-Lebensmittelhändler **Picnic und Knuspr** gehören zusätzlich zu den bisher geplanten Supermarktketten zum langfristigen Umfang. **Aktuelle Priorisierung vom 08.11.: Knuspr-Liveangebote jetzt als P0 integrieren; Picnic bei technischen/API-/Rechteblockaden vorläufig zurückstellen und nicht als Blocker dieses P0-Meilensteins behandeln.** Picnic bleibt späteres Ziel. [Aktueller P0-Auftrag](P0_GEMINI_KNUSPR.md). Fehlende externe Fähigkeiten dürfen die bestehenden Offline-/Einkaufsfunktionen nicht blockieren.

## Verbindliche Referenz und belegter Iststand

- **Nur lesen:** [`ecora2003-max/piknik`](https://github.com/ecora2003-max/piknik), initial gesichteter HEAD `c7dcf9f0159af862ae76d5aab40b7f36d0bacc4f`. Zuerst [README](https://github.com/ecora2003-max/piknik/blob/main/README.md), [Picnic-API-Recherche](https://github.com/ecora2003-max/piknik/blob/main/docs/API-RECHERCHE.md) und [Knuspr-API-Recherche](https://github.com/ecora2003-max/piknik/blob/main/docs/KNUSPR-API.md) lesen. Bei Weiterarbeit aktuellen und tatsächlich geprüften HEAD dokumentieren.
- **Kein fertiger Connector nachgewiesen:** Das Referenzrepo enthält derzeit Recherche und Integrationspläne, keinen authentifiziert getesteten Android-API-Adapter. Recherche ist keine Live-Daten-, Rechte- oder Checkout-Abnahme.
- **Knuspr:** dokumentierte offizielle MCP-Schnittstelle `https://mcp.knuspr.de/mcp` mit OAuth; tatsächliche Toolnamen, Schemas und Bestellfähigkeiten erst nach autorisierter Anmeldung mittels `tools/list` bestimmen. Ein offizieller Warenkorbzugang beweist keinen verbindlichen Bestellabschluss.
- **Picnic:** dokumentierter **inoffizieller** TypeScript-Wrapper `MRVDH/picnic-api` für DE mit Such-/Katalog-, Preis-, Promotions-, Warenkorb- und Checkout-Methoden; noch kein authentifizierter DE-Livetest, keine zugesicherte öffentliche Partner-API, keine nachgewiesene vollständige Katalogabdeckung. Die Wrapper-Lizenz erlaubt nicht automatisch die Übernahme fremder Produktdaten.

## Zielumfang für beide Anbieter

1. **Eigenständige Provider-Adapter:** klare Trennung Picnic/Knuspr, sichere Anmeldung bzw. OAuth/2FA, Token-/Widerrufs-/Fehlerbehandlung; keine Credentials in Git, Logs oder Testartefakten. Erst Read-only-Suche/Angebote nachweisen, danach optionale Schreibaktionen.
2. **Reale Einkaufssuche und Angebotsübersicht:** Produktsuche, Produktsortiment im belegten API-Umfang, angezeigte Normal-/Aktionspreise, Preis-/Grundpreis-Basis, Packungsmenge, Verfügbarkeit, Angebotseinschränkungen, Erfassungszeit und Liefergebietsbezug. Keine Vollständigkeit oder Gültigkeitsdauer erfinden; unbelegte EAN, Grundpreise, Rabatte und Preise bleiben unbekannt. **Online-Liefergebiete und Lieferfenster sind keine stationären Filialen.**
3. **Anbindung der tatsächlichen App-Einkaufsliste:** nur nachvollziehbar zugeordnete Artikel/Varianten vergleichen, Menge/Packung/Grundpreis und aktuellen Lieferkontext prüfen; Picnic und Knuspr neben den anderen Händlern sichtbar, getrennt auswählbar und nach Preis/Budget vergleichbar. Vorschläge lösen keine Käufe oder Lebensmittelbuchungen aus.
4. **Warenkorb- und Bestellpfad:** nach ausdrücklicher Einzelbestätigung Artikel/Mengen in den jeweiligen Warenkorb übernehmen, vorhandene Bestell-/Lieferstatus nur im belegten API-Umfang lesen. **Kein autonomer Checkout, keine Zahlung und keine verbindliche Bestellung.** Vor Abschluss Gesamtsumme, Gebühren, Lieferzeit und Ersatzartikel zeigen; nur bei nachgewiesener offizieller/zulässiger Fähigkeit und gesonderter finaler Freigabe auslösen, andernfalls zum belegten offiziellen Anbieter-Checkout übergeben. Keine Deep Links erfinden.
5. **Schrittweise Bereitstellung:** zunächst testbarer Provider-Adapter und UI-Pfad mit synthetischen Fixtures, dann nutzerautorisierter Live-Read-only-Test pro Anbieter, erst danach freigegebene Warenkorb-/Checkout-Funktionen. Ein blockierter Händler darf weder erfundene Preise liefern noch den Rest der App blockieren. Unterschiede und Blockaden pro Anbieter sichtbar dokumentieren.

## Daten-, Rechte- und Qualitätsgrenzen

- Bestehende `ADR-016`-Händlerfähigkeits- und `ADR-014`-Quellen-/`APP_DISPLAY`-Gates bleiben für jeden Provider und jede Funktion getrennt verbindlich. Für Picnic ist laut Referenzrepo die mögliche Datennutzung/Archivierung durch Nutzungsbedingungen begrenzt; bei Knuspr folgt aus dem offiziellen MCP **keine pauschale Erlaubnis zum vollständigen Katalogexport oder unbegrenzten Preisarchiv**. Keine Umgehung technischer Sperren oder Authentifizierung.
- Nur angefragte/berechtigte Daten erfassen; keine stillen Bulk-Crawler und keine vorgetäuschten Partnerlizenzen. Region, Quelle, Beobachtungszeit, Währung, Nutzungsrechte, Produktidentität und Vergleichbarkeit erhalten; unbekannt ist nicht Null. Persistente Fremd-/Nutzerdaten nach vorhandenen FoodStore-/Datenschutzregeln schützen.
- Technische Integration **ausschließlich im Hauptrepo**, `piknik` sowie die bisherigen fünf Architektur-/Fachrepos **nur lesen**. Keine neue schreibende Parallelsitzung und keine Änderungen an gesperrten Architekturschemata.

## Nachweis vor einer Fertigmeldung

- [ ] **Knuspr jetzt P0:** offiziellen OAuth-MCP-Read-only-Adapter für reale Angebote, Preise und Einkaufsliste funktionsfähig machen und separat abnehmen. Picnic-Adapter nachrangig; bei belegter Blockade als DEFERRED kennzeichnen. Getrennte Schnittstellen und verwendete Capability-/Tool-Schemas dokumentieren.
- [ ] Für **jeden** Anbieter Nutzeranmeldung, Read-only-Suche, Preis/Angebot/Verfügbarkeit und Einkaufsliste→Artikelauswahl im echten Android-UI getrennt geprüft; Offline, Fehler, Ablauf, Widerruf und Neustart getestet.
- [ ] Grundpreis/Gebinde/Region/Gültigkeit/Personalisierung, Dubletten/Mehrdeutigkeit und unbekannte Felder mit Tests abgesichert.
- [ ] Warenkorbmutationen ausschließlich nach expliziter Zustimmung getestet; Checkout/Handoff separat freigegeben oder als echte Providergrenze dokumentiert. Keine Testbestellung ohne gesonderte Nutzeranweisung.
- [ ] Einheitliche Einkaufsvorschläge mit bisherigen Händlern, unveränderte Bestands-/Zahlungshistorie und FoodStore-Grenze; nachgewiesene CI und echte Gerätetests auf exakt geprüftem SHA.
- [ ] Fehlende API-, Nutzungs- oder Gerätefreigaben als **BLOCKIERT/OFFEN** statt **INTEGRATED** kennzeichnen. **Die Aufnahme in den Gesamtauftrag ist ab sofort verbindlich, nicht die Behauptung einer bereits erfolgten Integration.**
