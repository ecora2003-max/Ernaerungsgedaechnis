# Verbindlicher Integrations-Ausführungsplan – funktions- und abnahmeorientiert

Stand: 03.11.2026. Diese Datei ist die **einzige operative Prioritäten- und Zuständigkeitsliste** für die Integration der vier Fachmodule in die Android-Haupt-App. Sie ergänzt `AGENTS.md`, `docs/ACCEPTANCE.md`, `docs/INTEGRATION_ROADMAP.md` und die verbindlichen Anweisungen im Repo-Root. **Keine neue Architektur, kein neuer Vertrag, keine neue Sperrmechanik.** Bei Konflikten haben fachliche Architekturverträge, Datenintegrität und Sicherheitsgates Vorrang. Alle vier Modul-Repositories und EG-Architecture bleiben strikt NUR LESEN.

## 1. Betriebsmodus und Rollen

| Rolle | Automatisierung (bestehende ID) | Exklusiver funktionaler Zuständigkeitsbereich | Laufplan |
| --- | --- | --- | --- |
| **Integrator A – Einkaufssystem** | `6ae64ea269448191ac8f0b8a5fd206cb` (ehemals Integration (Chat)) | Bon/OCR, Produkt- und Filialzuordnung, Barcode/Version, bestätigter Kauf, private Preishistorie, historischer Vergleich, Bedarf, Gebinde und Einkaufsvorschlag samt UI | Stündlich, bestehendes Fenster Minute :41 UTC |
| **Integrator B – Rezeptsystem** | `6ae659b2cf2081918f3414dce24a090e` (ehemals Integration II) | Rezeptgenerator-/Revalidator-Anbindung, Kontext, Allergene, explizite Prüfung/Bestätigung, Rezeptsave, bestätigte Herstellung und separater Verzehr samt UI | Stündlich, bestehendes Fenster Minute :11 UTC; **30 Minuten Abstand** |
| **Meilenstein-Abnahme** | Keine eigene stündliche Automatisierung | Nach erreichtem Meilenstein Architektur-/Host-/Room-/FoodStore-/Android-/Offline-/Privacy-Gates anhand tatsächlicher Testnachweise prüfen; kein repetitives Voll-Audit | Bedarfsabhängig, als Teil des betreffenden Code-PRs oder gezielter Abnahme |
| **Legacy III / IV** | `6ae65fdd040081919b0a5fb1c8962104` / `6ae66058b40c8191b8efb843b437b0ca` | Keine neue Entwicklungszuständigkeit; deaktiviert lassen | Keine Ausführung |

Die **separate** `integration-lock.json` auf `main` bleibt einzige operative Sperre, vollständiges schemaVersion-1-JSON, CAS mit aktuellem Datei-Blob-SHA, eigene Tokenkontrolle und aktive manuelle Freigabe. `docs/INTEGRATION_IDLE_RECOVERY.md` und Wächter nach mindestens 30 Minuten belegter GitHub-Inaktivität bleiben unverändert bindend. Wächter kennt auch historische Automation-IDs, damit gegebenenfalls alte verwaiste Sperren sicher geprüft werden können. **Sperre schützt nicht vor divergierenden Branches**: deshalb die PR- und HEAD-Regeln unten.

### Schreibhoheit innerhalb der App

- A ändert primär Erkennung, Produkt- und Preisports, Kauf-/Einkaufs-/Planungs-Host und zugehörige UI/Tests. B ändert primär Rezepte, Kontext/Revalidierung, Küche, Herstellung/Verzehr und zugehörige UI/Tests. **Beide schreiben ausschließlich ins Hauptrepository.**
- Gemeinsame Dateien (insbesondere `FoodStore`, Room-Entities/DAOs, Navigation, Gradle, gemeinsame Test-Fixtures) werden **nicht parallel auf voneinander abweichenden Bases** entwickelt: Vor Bearbeitung Haupt-HEAD und fremde offene PRs prüfen, das betreffende Teilstück im eigenen Arbeitsauftrag dokumentieren und inkompatible Änderungen sequenziell integrieren. Bei Überschneidung: betroffene Gegenpartei/PR zuerst rebasen oder abwarten, alternativ konfliktfreie Teilaufgabe fortsetzen.
- Jede Buchung bleibt ausdrückliches, atomisches, idempotentes Ist-Ereignis **ausschließlich über FoodStore**. Kaufvorschläge, Preisbeobachtung und Rezeptentwürfe führen nie selbst Buchungen aus. Unbekannt bleibt verschieden von 0; Integer-Cents, Mengen-/Produktversions-/Filial-/Zeit-/Rechte-/Provenienzgrenzen bleiben erhalten.
- Kein Wechsel der freigegebenen gemeinsamen Arbeitsbasis `1.0.0-draft` (v1-Schema-Blob `bedde4677342817fe6b883962c8dfbbd60100072`) zu `1.1.0-draft` ohne Architekturfreigabe. Keine ungefragte separate Rechtsprüfung, keine neuen kostenpflichtigen Pflichtdienste, keine privaten Daten, keine Routine-APK.

## 2. Genau ein aktiver nächster Funktionsmeilenstein je Integrator

**A – aktives Ziel A1: vollständig bedienbare Bon→Einkaufsvorschlag-Strecke.**

Bon erfassen (Kamera/OCR oder lokale Testeingabe) → unsichere Produkt-/Filial-/Barcodezuordnung manuell bestätigen → Kauf ausdrücklich bestätigen → genau einmal über FoodStore buchen → versionierten privaten Bezahlpreis historisch korrekt speichern/lesen → zulässige historische oder offline verfügbare Angebote vergleichen → Bedarf/Gebinde/Budget/FEFO berücksichtigen → tatsächlich bedienbaren Einkaufsvorschlag anzeigen (**keine neue Ist-Buchung durch Vorschlag**). Bestehende UI, Room und Host-Kerne wiederverwenden. Den **nächsten fehlenden Anschluss innerhalb genau dieser Kette** am realen App-Code identifizieren und implementieren; nicht autonom auf Unicode-/Zeitstempelrandfälle ohne aktuellen Defekt ausweichen. Für diesen Meilenstein: positive/negative UI-/Room-/JVM-E2E, Retry/Storno, unbekannte Werte, Store-/Version-/Rechte-/Zeitgates; Geräte-Kamera/OCR/Offline/Privacy separat belegen.

**B – aktives Ziel B0: offene Rezept-PRs in einen konsistenten Stand überführen.**

Zuerst [#222](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/222), [#224](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/224), [#225](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/225) und die rein dokumentarische [#227](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/227) auf tatsächlichen HEAD, CI, Testfehler, Kotlin-Kompilierung und Mergekonflikte prüfen. Abhängige Änderung gezielt reparieren/rebasen und **erst bei PASS auf aktuellem Code-SHA** konfliktfrei mergen; überholte oder redundant gewordene PRs nachvollziehbar schließen, nie ungeprüft mergen. Weitere verwaiste Status-PRs nur bei konkretem Konflikt/Bedarf konsolidieren, **keine neuen Status-PRs als Routine**. Nach belegter Bereinigung **B0 als erledigt kennzeichnen und unmittelbar auf B1 wechseln**, nicht endlos PR-Inventar wiederholen.

**B – nächstes Ziel B1 (nach B0): vollständig bedienbare Rezept→Verzehr-Strecke.**

Tatsächlichen Generator und Revalidator über vorhandene v1-Host-Adapter anschließen → acht Kontextkombinationen und Mengen/Allergene/Kochzustand/Versionen validieren → Entwurf zur ausdrücklichen Nutzerprüfung anzeigen → Zustimmung und Provenienz **unabhängig an der FoodStore-Schreibgrenze** validieren → Rezeptversion speichern → Herstellung ausdrücklich buchen → Verzehr separat und ausdrücklich buchen. Ohne freigegebene lokale KI oder Live-API: nutzbaren deterministischen/offlinefähigen Pfad anbieten, fehlende Funktionen ehrlich deaktivieren. Keine Annahme, dass UI-Credential-Dialog oder Entwurfs-Checkpoint bereits die vollständige Save-Attestation belegt. Positive/negative E2E einschließlich Abbruch, Neustart, Retry und geänderten Kontexten; Android-/Offline-/Privacy-Test getrennt belegen.

### Danach (nicht vorzeitig bearbeiten)

- **A2:** externe Händlerquellen technisch an die zuvor bedienbare Einkaufsstrecke anschließen, ausschließlich nach unabhängigen Händlerfähigkeits-/Quellen-/APP_DISPLAY-Gates; fehlende Live-Quellen **blockieren A1 nicht**.
- **B2:** vollständige Generator-/Revalidator-Parität, optionale On-Device-KI und Geräte-/Hardware-Varianten nach belegtem B1-Grundpfad; nur freigegebene lokale/offline Implementierungen.
- Neue Funktionsteile aus der bestehenden Roadmap werden **erst nach abgeschlossenem aktivem Ziel** oder bei explizit belegter Sicherheitsblockade priorisiert. Keine starre alte Acht-Phasen-Roadmap.

## 3. Chronologie für jeden automatisierten Lauf

1. **Vor jeglicher Schreibaktion:** Repo-Root-Anweisungen **vollständig** lesen; `integration-lock.json` und dessen frischen Datei-Blob-SHA abrufen; gesamtes JSON streng prüfen. Bei fremdem ACTIVE/Fehler ohne Schreibaktion aussteigen. Nur FREE per CAS mit eigener Automation-ID/neu erzeugtem Run-Token zu ACTIVE setzen, unmittelbar rücklesen und beweisen. Keine fremde Recovery.
2. **Nach Eigentumsbestätigung:** `AGENTS.md`, diesen Ausführungsplan, `docs/ACCEPTANCE.md`, `docs/INTEGRATION_ROADMAP.md`, kurzen aktuellen Statusausschnitt, relevante Architektur-/Modulverträge nur lesend prüfen. Eigenen **offenen** Branch/PR und exakten CI/HEAD **vor neuer Branchanlage** suchen. Priorität: bestehende eigene Arbeit fortsetzen, nicht duplizieren; nur die eigene aktive A-/B-Aufgabe bearbeiten.
3. **Vor Codeänderung:** konkreten fehlenden End-to-End-Anschluss und dessen reproduzierbaren Abnahmenachweis bestimmen. Bei Fortsetzung bestehenden PRs aktuellen main-HEAD abgleichen. Ohne eigenen PR einen kurzen Branch vom frischen main anlegen, unter `workingBranch` bei bestätigtem Token registrieren. Keine parallel offenen neuen Branches für dasselbe Ziel.
4. **Implementieren:** echte Android-/Kotlin-/Room-/UI-Verbindung herstellen, begrenzte positive/negative Regression dazu. Nur echte Defekte, Gateverletzungen oder notwendige Sicherheitsprobleme innerhalb des aktiven Ziels beheben; kein unmotiviertes Mikro-Hardening. Bei gemeinsamer Datei mit fremder offener Änderung zuerst konfliktfrei sequenzieren.
5. **Testen und integrieren:** gezielte Tests + Routine-Lint/JVM/Room/Migrations-/Schema-/Referenzgates sowie Android-Testkompilierung, CI auf dem **exakten** Code-/Test-HEAD; für abgeschlossenen Nutzermeilenstein zusätzlich **tatsächlich ausgeführte** Android-Emulator-/Geräte-UI-, Kamera-/OCR-, Persistenz-/Neustart-, Offline- und Privacy-E2E. Wenn kein Gerät verfügbar, Gate **OFFEN** markieren; nicht als fertig bezeichnen. Bei geänderter Base rebase/merge ohne Force-Push, erneut prüfen. Nur grüne mergefähige PRs mergen. Keine separaten Routine-Status-PRs.
6. **Fortsetzung und Abschluss:** In demselben Code-PR nur bei echtem Fortschritt/Blockade kurz Code-/CI-/Geräte-SHAs, aktuelles Ziel, Branch/PR, **genau nächsten ausführbaren Implementierungsschritt**, offenem Gate und ggf. klarer Blockade festhalten. Nach zwei ergebnislosen **inhaltlichen** Läufen (nicht Sperrkollisionen) Ursachen diagnostizieren, Meilenstein priorisieren oder bei echter Blockade Entscheidung anfordern, statt Prüfungen zu wiederholen. **Manuell eigenen** JSON-Lock unter frischem SHA/token zu FREE serialisieren, Rücklesen bestätigen, niemals auf Wächter warten. Bei Toolausfall kein erfundener Erfolg.
7. **Abnahmeentscheidung:** Ziel erst `ANDROID_DEVICE_VERIFIED`/gegebenenfalls `INTEGRATED` markieren, wenn genau der oben beschriebene, vollständig bedienbare Nutzerfluss und die einschlägigen Architektur-/Datenintegritäts-/Offline-/Privacy-Gates auf nachgewiesener Android-Laufzeit bestanden sind. 562 JVM-Tests oder bloß grüne CI belegen **nicht** den Gesamtabschluss.

## 4. Koordination offener Arbeit

Eine einmalige Liste pro Integrator mit **einem** aktiven Ziel reicht. Offene Branches/PRs sind der fortsetzbare Arbeitsstand; künftig im zugehörigen Code-PR und bei wichtigem Fortschritt in `docs/STATUS.md` den nächsten **konkreten Codeanschluss** statt Statusprosa dokumentieren. Vor jedem Merge `main`-HEAD, eigene Sperre, fremde überlappende PRs und geänderte Testbasis prüfen. Änderungen anderer Integratoren niemals verwerfen; weder blindes Merge noch Force-Push.

**Abnahmekontrolle ist ereignisgetrieben, nicht stündlich:** nach den Meilensteinen A1, B1 und dem späteren A2/B2 die tatsächlichen Datenflüsse und Testbelege prüfen; keine neue Kontrollautomatisierung. Die bestehenden 4 Automationen werden **nicht** wieder eingeschaltet: ausschließlich A/B laufen, III/IV bleiben deaktiviert.
