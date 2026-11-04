# Prüfe mich, ob du dran bist und was du machen sollst und darfst

## Aktiver Auftrag und Schreibgrenze

Seit 04.11.2026 übernimmt **ein exklusiver Codex-Entwickler** die Gesamtentwicklung der Android-App. Die Nutzerautorisierung ersetzt die früheren parallelen A/B-Rollenregeln. A/B/III/IV bleiben pausiert. Keine dieser Automatisierungen aktivieren oder ihre Identität verwenden. Der separate einmalige Fortsetzungsauftrag darf nur nach Unterbrechung und regulär freier Sperre übernehmen.

Einziges Schreibrepository: https://github.com/ecora2003-max/Ernaerungsgedaechnis

Ausschließlich lesen: EG-Architecture, EG-Preisdaten, EG-Produktdaten, EG-Einkaufsintelligenz und EG-Rezept-KI unter demselben GitHub-Konto. Auch dort keine Issues, PRs, Branches, Workflows oder Dokumentation schreiben. Keine Force-Pushes, kein Überschreiben fremder Arbeit und keine produktive Datenmigration.

## Chronologischer Einstieg

1. Diese Datei, AGENTS.md, docs/INTEGRATION_IDLE_RECOVERY.md, docs/INTEGRATION_EXECUTION_PLAN.md, docs/ACCEPTANCE.md, docs/INTEGRATION_ROADMAP.md und die aktuellen relevanten STATUS-Abschnitte lesen. Alte Phase-5-Pausen und starre Phasenpläne gelten nicht.
2. **Vor jedem Schreibzugriff alle drei operativen Dateien auf GitHub-main lesen:** integration-lock-A.json, integration-lock-B.json, integration-merge-lock.json. Ausschließlich drei vollständige schemaVersion-1-FREE-Dateien mit acht leeren Besitzfeldern erlauben eine neue Übernahme. Bei fremdem ACTIVE, unbekanntem Zustand oder Lesefehler keine Entwicklungsschreibaktion. Auch ein anderer Codex-Run ist fremd.
3. `python3 tools/integration_codex_lock.py acquire --session-file /tmp/eg-session.json --branch <bestehender-Branch> --task <Funktionsziel>` verwendet ausschließlich Besitzer `codex-exclusive-ernaehrung-20261104`, neue Session und je Datei neuen Token. Merge-Lock zuerst, dann A und B per frischem Blob-SHA/CAS; alle Rücklesungen müssen passen. Teilübernahme erlaubt keine Codearbeit. Nie ACTIVE übernehmen oder Tokens aus GitHub kopieren. Legacy integration-lock.json unberührt lassen.
4. Vor jedem Schreiben/Commit/Push/Merge `check --session-file ...` ausführen. Alle drei müssen derselben ursprünglichen Session und ihren getrennten Tokens gehören. `refresh --session-file ... --branch ...` registriert Branchwechsel und Heartbeat; spätestens vor 30 Minuten ohne weitere belegte Aktivität auffrischen.
5. Bestehenden funktionalen Branch/PR zuerst fortsetzen, offene überlappende PRs und aktuelle main-Basis abgleichen. Code entwickeln, vollständige Nutzeranschlüsse herstellen, passende Tests ausführen. Keine alleinige Bestandsaufnahme oder Dokumentationsarbeit als Entwicklungsabschluss.
6. Nur mit eigenen drei Sperren, frischem main-/PR-Head und exakter grüner CI kontrolliert mergen. Geänderte Basis ohne Force-Push integrieren und erforderliche Tests wiederholen. Sperren bleiben im Einzelentwicklermodus auch während CI dem selben Entwickler zugeordnet; bei Unterbrechung sofort selbst freigeben.
7. Bei Abschluss oder Fehler `release --session-file ... --commit <gesicherter-Code-SHA> --next-step <konkrete-Fortsetzung>`: zuerst eigene Merge-, dann A-/B-Sperre mit ursprünglichem Token und frischem SHA zu gültigem FREE, unabhängig rücklesen. Fremde ACTIVE bleiben unangetastet. Bei Teilübernahme nur eigene Tokens freigeben. Kein automatisches Übernehmen eines noch aktiven Chats.

## Fachliche Regeln

Einkauf: Bon/Kamera/OCR → Produkt-/Versions-/Filialkorrektur → ausdrücklicher Kauf → FoodStore → Bestand und private historische Preise → zeit-/rechtekorrekter Vergleich → FEFO-/Bedarf-/Gebinde-/Budgetvorschlag. Vorschläge buchen nichts.

Rezepte: Offlinegenerator → Kontext-/Allergen-/Mengen-/Zustands-/Versionsprüfung → Vorschau → ausdrücklich authentifizierte Nutzeraktion → FoodStore-validierter persistenter Rezeptsave → bestätigte Herstellung → separat bestätigter Verzehr. Alle acht Kontextkombinationen, Änderung, Abbruch, Retry, Neustart, Offline und Datenschutz prüfen.

FoodStore bleibt einzige atomare/idempotente Ist-Schreibgrenze. Unbekannt != 0, Integer-Cents, historische Quellen/Identitäten und nichtdestruktive Room-Migrationen erhalten. Kein stiller Vertragswechsel, kein nicht genehmigter Livefeed oder kostenpflichtiger Pflichtdienst. Nach A1/B1 die weiteren dokumentierten Gesamt-App-Ziele bearbeiten, keine fehlenden Fitness-/Watch-Spezifikationen erfinden.

## Nachweise

Passende Unit-/Room-/Migrations-/Integrations-/Referenztests, Lint und Schemavergleich auf dem wirklichen Code-/Test-SHA ausführen. Android-Testkompilierung ist kein Gerätetest. Frühere belegte Geräteprüfungen erhalten; fehlende reale Android-/Offline-/Neustart-/Privacy-/OCR-Fälle ergänzen. Kein Gesamtmodul INTEGRATED bei offenen Abnahmegates. Code und knappen genauen Test-/Commit-/PR-/Blockadestand an sinnvollen Grenzen sichern. Keine Routine-Status-PRs oder Routine-APKs.
