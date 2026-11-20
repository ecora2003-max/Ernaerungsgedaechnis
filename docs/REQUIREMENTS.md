# REQUIREMENTS – funktionsorientierte Zieldefinition

## Ziel
Eine alltagstaugliche **Android-App „Ernährungsgedächtnis“** für Produktidentität, Vorräte, Einkauf, Rezepte, Ernährungsplanung und Fitness. Eigene Daten bleiben lokal und nachvollziehbar; externe Angebote/Modelle sind optionale Dienste. Fertige nutzbare End-to-End-Funktionen sind wichtiger als zusätzliche Architekturdokumente.

## Nutzerfunktionen
1. **Katalog & Erkennung:** GTIN/EAN, Händler-SKU, Bonbezeichnung, OCR, Marke/Eigenmarke/Bio/Variante/Packung; unbekannte oder mehrdeutige Matches zeigen. Historische Artikelversionen und individuell bestätigte Aliase erhalten.
2. **Vorrat, Lager, Einkauf & Chronik:** Lagerorte und Mengen, Chargen/Haltbarkeit, Kauf, Verbrauch, Herstellung, Entsorgung, Korrektur, rückverfolgbare Historie, lokale Sicherung. Einkaufsliste und Planung sind Vorschläge, keine automatisch erfolgten Käufe.
3. **Angebote & Preisvergleich:** Acht Ketten – ALDI Nord/Süd, Lidl, REWE, PENNY, Kaufland, Netto Marken-Discount, EDEKA. Bei realen zulässigen Quellen aktuelle/kommende Wochenangebote und verfügbare reguläre Preise; Filiale/Region, Einkaufstag, Preisart, Quelle, Aktualität, Gebinde, Pfand, Coupon/Online-Bedingungen und Datenlücken sichtbar. Gezielte Preisabfrage und vollständiger Angebotskatalog sind **unterschiedliche Fähigkeiten**. Vergleich pro €/kg, €/l oder Stück nur bei belastbarer Artikel-/Mengenidentität. Nutzung der Bedarfsliste für Mengen-/Budget-/Gebindevorschau, keine vorgetäuschte vollständige Händlerabdeckung.
4. **Rezepte & KI:** Offline-Rezeptkern, eigener Rezeptkatalog und planbare Portionen; generative Optionen **Gemini Nano 4 Fast**, **Nano 3**, **Gemini Cloud mit eigenem Schlüssel**. Modelle sind wählbar, kein erzwungener Cloudfallback. Produktversionen, Mengen, Nährwerte, Ausschlüsse/Allergene und tatsächlich erfolgte Herstellung/Verzehr strikt prüfen. Kontext aus Vorrat, Einkaufsliste oder Angeboten nur getrennt und bewusst einschließen.
5. **Ernährung & Planung:** Tagesziele, Nährwerte, Mahlzeiten, 1/7/56-Tage-Bedarf, FEFO-Chargen, Aufbewahrung, offene/plangebundene Bedarfe, bestätigter Verzehr, Budget und Kapazität nur auf belegten Fakten.
6. **Fitness:** Gesundheit-/Bewegungsdaten via Health Connect, Samsung-Watch-Quellen soweit tatsächlich verfügbar, Historie/Quellzeitfenster und optionale eigene Zusatzdaten. Keine unbelegte aktive Trainingsdauer/Kalorien oder automatische ernährungsbezogene Istbuchung.
7. **Später:** Picnic und Knuspr als optionale Dienste; Bestellungen, Transfers und Zahlungen nur nach gesonderter Freigabe.

## Nicht verhandelbare Semantik
- Geld als Integer-Cents; genaue Einheiten, Menge/Gebinde, Zeitpunkt/Zeitzone, Produktversion und bekannte/fehlende Evidenz. `null`/unbekannt niemals als Nullwert oder falschen Preis ausgeben.
- Reale Ist-/Kassen-/Nährwert-/Bestandsbuchungen nur mit separater Nutzerbestätigung und über `FoodStore`, transaktional/idempotent. Entwürfe und KI-Ausgaben dürfen keine Fakten schaffen.
- Schütze Privatsphäre, Geheimnisse, Room-Altdaten und Vertragsversion. Erst reale Tests belegen reale Datenzugriffe oder Modelle. Vertrags-v1 bleibt stabil; separate v1.1-Drafts nicht ungeprüft aktivieren.
- Öffentliche Anbieterinfos sind nicht automatisch frei lizenzierte Daten. **Keine pauschale interne schriftliche Händlergenehmigung verlangen**, aber konkrete Nutzungsbedingungen, Host-/Filial-/Anfragegrenzen und tatsächliche Sperren respektieren.

**Detailreferenzen** nur nach Bedarf: [LIVE_OFFERS](LIVE_OFFERS.md), [DIREKTE_REZEPT_KI](DIREKTE_REZEPT_KI.md), [LOCAL_DATABASE](LOCAL_DATABASE.md), [RETAILER_PUBLIC_SOURCE_POLICY](RETAILER_PUBLIC_SOURCE_POLICY_2026-11-16.md), weitere konkrete Feature-Dokumente. Frühere Roadmaps sind historisch.