# Host-Integration der Fachmodule (31.10.2026)

Dieses Dokument beschreibt die Android-Integrationsgrenzen, keine Produktivfreigabe. Der Architekturvertrag v1.0.0-draft bleibt vom noch nicht freigegebenen Shoppingvertrag v1.1.0-draft (ADR-017) getrennt.

## Modulports und Schreibgrenzen

| Zulieferer | Reiner Host-Eingang | Erlaubte App-Schreibgrenze | Offenes Gate |
| --- | --- | --- | --- |
| Produktdaten | GTIN/SKU/Bonalias und bestätigte Version über RecognitionReader, product.match und receipt.line | FoodStore.rememberRecognizedProduct, saveAlias, acceptReceiptScan; nur explizit bestätigt | Python-Fachkern auf Kotlin/Room portieren, historische Preisposition belegen |
| Einkaufsintelligenz | Snapshot von Planung, Mengen, Filiale, Angebotsbelegen, Budget, Datum und Historie; v1.1 ausschließlich opt-in | Kauf nur über FoodStore, Einkaufswunsch ist niemals Ist-Ereignis | Vergleichs-/Budget-/FEFO-Kotlin-Parität und App-Regressionsbeleg |
| Preisdaten | Preisbeobachtung / Angebot mit Quellen-, Rechte-, Mengen-, Regions- und Zeitmetadaten | Keine direkte Buchung; Legacy-FoodStore.savePrice bleibt manuell und separat | Vorhandene technische Quellen-/APP_DISPLAY-Sperren, keine neue separate Rechtsrecherche (ADR-019) |
| Rezept-KI | recipe.draft, Host-Mengen- und Rezeptversionen, drei unabhängige Flags, Allergen-/Nährwertprüfung | FoodStore.saveRecipe/savePlan erst nach Hostbestätigung; produce/consume nie aus Entwurf | Voller Kotlin-Kern/Hostschema/Room/Compose-/Hardwaretest |

## Implementierter erster Host-Adapter

Die neue reine Kotlin-Datei unter app/src/main/java/de/ernaehrung/demo/data/integration/ExternalPriceBridge.kt enthält eine austauschbare CandidateSupplier-Schnittstelle und den ReadOnlyExternalPricePort. **Kein Händlerfeed, keine Live-Anfrage, keine UI-Anzeige und kein Room-Write wurden verbunden.** Die vertrauenswürdige DisplayPermissionRegistry wird allein vom Host bereitgestellt und ist standardmäßig DENY_ALL. Angeblich vom Feed gelieferte PERMITTED-Tags reichen nicht.

ADR-016: ALDI_NORD, ALDI_SUED, REWE, LIDL können fachlich gezielte Preisabfragen und veröffentlichte Angebote liefern; PENNY, KAUFLAND, EDEKA und NETTO_MD nur veröffentlichte Angebote. Diese Funktionen sind **keine** Live-Rechtsfreigabe.

ADR-014: APP_DISPLAY verlangt zusätzlich zur Source-Rechteangabe PERMITTED eine unabhängige zweckbezogene, belegte Host-Berechtigung. RESEARCH_ONLY und PRIVATE_USER_HISTORY können nie als externer Produktpreis im Display-Port erscheinen. Die private eigene Bonhistorie muss separat behandelt werden.

Unbekannte Identität, Filialzuordnung, Menge, Währung, Preisbedingungen oder Gültigkeit sowie Quellen aus der Zukunft werden gesperrt, nicht als Nullwerte ersetzt. Warenpreis und Pfand bleiben getrennt; bei unbekanntem Pfand wird kein vollständiger Checkout-Cashbetrag behauptet. Ein Teil-Snapshot kann niemals den absoluten Marktbestpreis belegen. Kein Rot-/Orange-/Grau-Ersparnisvergleich wird in diesem ersten Schritt erzeugt.

**Legacy-Schutz:** Das bestehende FoodStore-/Room-7-Schema, die 1→7-Migrationen und die bisherigen Shopping-Ansichten wurden nicht verändert. Die vorhandenen manuellen Preisfassungen besitzen keine ausreichende externe Rechte-/Store-/SKU-Provenienz: Sie dürfen nicht still als freigegebene externe Feed-Beobachtungen umetikettiert werden.

## Fortsetzung in dieser Reihenfolge

1. Vertrags-v1-DTOs auf Host-IDs, Mengen, Versionen, Store-Scopes und Zeitpunkte mappen. Erst nach unabhängiger Quellenfreigabe die echte Host-Rechte-Registry einführen. Keine v1.1-Produktion ohne Architekturfreigabe.
2. Synthetische End-to-End-Kette: Bonentwurf → Version/Alias → bestätigter Kauf → genau eine historische positionierte Preisbeobachtung, idempotente Retries und sichere Storno-/Tombstones. Zwei verschiedene reale Käufe dürfen nicht zusammenfallen.
3. Vergleich/Bedarf aus Python-Snapshots in eigenständiges Kotlin portieren und gegen dieselben synthetischen Referenzfixtures prüfen; Quelle/Filiale/Bio/Pfand/Coupon/Zeithorizont und Nichtbuchung als Pflichtgates.
4. Rezept-KI als getrennte geprüfte Offline-Vorschlagsfunktion mit 8 unabhängigen Kontextflags, Host-Mengen- und Allergenprüfungen integrieren; lokal gehostetes Modell erst nach Geräte-/Datenschutznachweis.
5. Bei einer späteren Schemaänderung Room-Version erhöhen und alle historischen IDs und Versionen durch explizite 1→neu-Migration erhalten. Keine produktive Sheet-Migration ohne Freigabe.

## Aktualisierte Händlerfähigkeiten und zweiter Offline-Port (31.10.2026)

ADR-018 trennt drei Fähigkeiten: gezielte reguläre Preisabfrage (vier Händler), veröffentlichte Einzelangebotspreise (alle acht Händler) und Angebotskatalog-Discovery (vier Händler ALDI Nord/Süd, Lidl, REWE). Referenz: EG-Architecture@18794be978cc324e5366996178d368929a4a0fa4, policies/retailer-source-capabilities.v1.json Version 1.0.3, Blob 7ed2ef6918c8fdcdb9c482cb0a38eb38e5f6852c. Die dritte Fähigkeit ist im Hostcode noch zu ergänzen. ADR-019 verlangt die Fortsetzung technischer Integration ohne ungefragte neue Rechtsrecherchen; bestehende technische Quellen- und Anzeigegates bleiben erhalten.

HistoricalPaidPriceKernel.kt prüft nur synthetische bestätigte Bonpreisfakten in einem reinen read-only Kotlin-Kern gegen die gepinnte Original-Python-Referenz; 35 gemeinsame Fixtures. Noch keine Room-/FoodStore-/UI-Anbindung, kein heutiges Marktangebot, keine vollständige Integration. Aktueller Teststand in docs/STATUS.md.

Die neue `PUBLISHED_OFFER_CATALOG_DISCOVERY`-Fähigkeit ist nur eine Händler-Funktionsentscheidung. Der vorhandene `ExternalPriceBridge`-Preisport lehnt sie bewusst mit `NON_PRICE_CAPABILITY` ab und ruft keinen Preisdaten-Supplier auf. Ein gesonderter Katalogindex-Port mit eigenen Datums-/Quellen-/Rechteprüfungen ist noch nicht implementiert.
