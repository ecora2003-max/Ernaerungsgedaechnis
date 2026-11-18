# NOW – einzige operative Arbeitsübersicht

**Stand 18.11.2026; immer mit GitHub-Main und offenen PRs abgleichen.** Dieses Dokument ist der einzige laufende Kurzstatus, nicht das historische [STATUS.md](STATUS.md).

## Verifizierter Ausgangspunkt
- Main zuletzt **`bdbd66245d2b345d637cf95f39b3d18ab7b706ce`**, PR [#307](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/307) integriert. Vor Fortsetzung aktuellsten Main-Head prüfen.
- #307: 972 JVM-/Room-Fälle (19 Migrationen), 59 API35, 35 API26; fünf echte Android-HTTPS-Quelltests grün.
- **Produktive aktuelle Quellen:** PENNY Markt 4030085, Kaufland DE5220; ALDI Nord und ALDI Süd veröffentlichte Kettenangebote (Filialbezug ungeprüft); REWE Markt 1356345 nur **explizite Produktsuche**, kein allgemeines Live-Prospekt. Reale Quelle/Antwort und kundenseitige Packungszuordnung sind getrennt zu prüfen.
- **Lidl:** erreichbarer Prospekt ohne ausreichend auswertbare Filialproduktpreise im untersuchten Pfad. **Netto:** untersuchter Android-Abruf 403. **EDEKA:** dokumentierte Einschränkungen der automatisierten Inhaltsnutzung, zulässiger Betriebspfad ungeklärt. Keine technischen Sperren umgehen.
- Eigener Artikel-/Gebinde-/Bedarfsvergleich in Android verbunden, marktweite Vollständigkeit nicht nachgewiesen.
- Gemini Nano 4 Fast, Nano 3 und optionale Gemini Cloud sind technisch in der Rezept-UI integriert; echte Modellantworten auf realem kompatiblen Gerät/echte Cloudantwort noch separat offen. [PR #308](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/308) behandelt Cloud-Consent und Modellidentität; erst nach Prüfung aktuellsten Status behandeln.
- Letzte öffentlich signierte Test-APK: [v0.12.0](https://github.com/ecora2003-max/Ernaerungsgedaechnis/releases/tag/watch-test-v0.12.0). **Enthält nicht automatisch die neueren Quellen aus #306/#307.**

## Arbeitspriorität
1. Laufende funktionale PRs zuerst sicher abschließen (aktuell #308), neuen Main und Tests prüfen; keine parallele Branch-Konkurrenz.
2. Bestehende **echte** Händler- und Vergleichsfunktionen im Android-Alltag vervollständigen; vor allem realer Filialbezug/Angebotsgültigkeit, nachvollziehbare Quellen und noch fehlende reguläre Preise. Für Lidl/Netto/EDEKA nur tatsächlich zugängliche Quellen prüfen; blockierte Ketten nicht zum Stillstandsgrund machen.
3. Nano4Fast/Nano3/Cloud auf geeigneter Hardware/mit ausdrücklichen API-Datenfreigaben real abnehmen; Fehlermodi getrennt halten. Bei nicht verfügbaren externen Voraussetzungen andere implementierbare Produktfunktionen fortsetzen.
4. Eine **aktuelle** installierbare Test-APK nach bestandenen relevanten CI-/Gerätetests erstellen und Quelle/Version/Signatur verifizieren. Noch offene Fitness-/Watch-Funktionen anschließend fortsetzen.
5. Knuspr/Picnic erst nach erneuter Priorisierung. Keine erneuten Sperr-/Automations-/Rechtsgrundsatzprojekte.

## Pflege
Bei **wirklich neuem** integriertem Meilenstein: nur Main-SHA, Nutzerwirkung, Testnachweis, echte offene Blocker und nächsten Schritt hier aktualisieren (max. eine Seite). Einzelbelege in PRs/CI, Altchronik in [STATUS.md](STATUS.md); diese nicht immer wieder vollständig einlesen.