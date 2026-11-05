# Prüfe mich, ob du dran bist und was du machen sollst und darfst

## Aktiver Auftrag und Schreibgrenze (Nutzerentscheidung 05.11.2026)

**Ausschließlich Codex** entwickelt und integriert die Android-App im Hauptrepository `ecora2003-max/Ernaerungsgedaechnis`. Die früheren A/B/III/IV-Automatisierungen bleiben pausiert und dürfen nicht selbständig reaktiviert werden. Die GitHub-Sperrdateien, Token-/Heartbeat-Protokolle und automatische Lock-Recovery sind abgeschafft; niemals erneut anlegen oder als Schreibvoraussetzung verwenden.

Nur lesen: `EG-Architecture`, `EG-Preisdaten`, `EG-Produktdaten`, `EG-Einkaufsintelligenz` und `EG-Rezept-KI`. Nicht in Fremdrepositories schreiben, keine Force-Pushes, keine Überschreibung fremder Arbeit, keine produktive Datenmigration.

## Chronologischer Einstieg und Fortsetzung

1. `AGENTS.md`, `docs/INTEGRATION_EXECUTION_PLAN.md`, `docs/ACCEPTANCE.md`, `docs/INTEGRATION_ROADMAP.md`, die relevanten neuesten Abschnitte von `docs/STATUS.md` und den bestehenden Code lesen. Historische Sperranweisungen in alten Commits/Statusberichten sind obsolet.
2. Offene PRs, Branches, aktuelle GitHub-main-Basis, noch laufende CI und überlappende Änderungen prüfen. Die bestehende funktionale Arbeit fortsetzen, keinen doppelten Entwicklungsbranch oder parallelen schreibenden Codex-Run erzeugen.
3. Funktionsorientiert entwickeln und sinnvolle Tests ausführen. Bei umfangreicher Arbeit durchgehend auf die App-Fertigstellung hinarbeiten, keinen unnötigen Status-/Mikro-PR-Zyklus erzeugen.
4. Vor einem Merge den konkreten PR-Head, die tatsächliche Mergefähigkeit und grüne CI auf exakt diesem Code-/Teststand überprüfen. Bei neuer Main-Basis ohne Force-Push aktualisieren und die erforderlichen Tests wiederholen.
5. Am Sitzungs-/Kontingentende Arbeitsstand in Codecommit/PR und knapp in `docs/STATUS.md` sichern (Tests, konkrete nächste Schritte und Blockaden). Keine Sperren erwerben oder freigeben, kein Wächter, keine automatischen Entwicklungs- oder Recovery-Läufe starten.

## Fachliche Grenzen

Einkauf: Bon/Kamera/OCR → Artikel-/Versions-/Filialkorrektur → ausdrücklicher Kauf → FoodStore → Bestand/private historische Preise → zeit-/rechtekorrekter Vergleich → FEFO-/Bedarf-/Gebinde-/Budgetvorschlag. Vorschläge buchen nichts.

Rezepte: Offlinegenerator → Kontext-/Allergen-/Mengen-/Zustands-/Versionsprüfung → Vorschau → authentifizierte Bestätigung → FoodStore-validierter Save → bestätigte Herstellung → separat bestätigter Verzehr. Alle acht Kontextkombinationen, Änderung, Abbruch, Retry, Neustart, Offline und Datenschutz prüfen.

Fitness: K01–K04 und echter automatischer Galaxy-Watch-4-Schritt-/Workoutimport gemäß [Issue #253](https://github.com/ecora2003-max/Ernaerungsgedaechnis/issues/253); kein Mock-/Emulatorbeleg als physische Watch-Abnahme.

FoodStore bleibt atomare/idempotente Ist-Schreibgrenze. Unbekannt != 0, Integer-Cents, historische Identitäten und erhaltende Room-Migrationen. Keine unfreigegebene Vertragsänderung, kein nicht genehmigter Livefeed, kein kostenpflichtiger Pflichtdienst.

## Nachweise

Unit-/Room-/Migration-/Integrations-/Referenztests, Lint, Schemavergleich und reale Android-/Offline-/Neustart-/Privacy-/OCR-Tests gemäß Abnahme, auf exaktem Code-/Teststand dokumentieren. Android-Testkompilierung ist kein Gerätetest. Kein Gesamtmodul `INTEGRATED` bei offenen Gates. Der Wegfall der Lock-Verwaltung verändert keine fachlichen oder Datenschutzkriterien.
