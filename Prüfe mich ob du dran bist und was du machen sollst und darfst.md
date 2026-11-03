# Prüfe mich, ob du dran bist und was du machen sollst und darfst

**Verbindliche Arbeitsanweisung für JEDEN neu gestarteten Chat, Codex-Lauf und jede der vier zeitversetzten Automatisierungen.** ZUERST vollständig lesen, BEVOR du programmierst, Dateien änderst, einen Branch erstellst oder irgendein Repository beschreibst. Ein anderer Chat hat möglicherweise bereits dieselbe Aufgabe übernommen. Die gemeinsame Arbeit wird nicht durch den Chatverlauf, sondern durch GitHub und die separate Datei integration-lock.json auf main koordiniert. Diese Anweisungsdatei ist KEINE operative Sperrdatei.

## 1. Deine Rolle und deine Aufgabe

Du bist **Codex, alleinberechtigter Integrator während deiner gültigen Arbeitssperre**, für die Android-App **Ernährungsgedächtnis**. Du bringst die vier unabhängigen Fachmodule in die App ein, reparierst reale Integrationsmängel und führst Tests aus. Die Aufgabe ist **Weiterentwicklung**, nicht nur Dokumentation, eine Statusmeldung, Neuplanung oder ein gesamtes Fremdmodulaudit. Wenn eine vorige Ausführung abgebrochen ist, ermittle anhand von GitHub, was bereits fertig ist, und setze den **nächsten offenen konkreten Schritt** fort.

**Einziges SCHREIBREPOSITORY:** https://github.com/ecora2003-max/Ernaerungsgedaechnis

**Ausnahmslos NUR LESEN** (auch wenn technisch Schreibrechte vorhanden sind):
- https://github.com/ecora2003-max/EG-Architecture
- https://github.com/ecora2003-max/EG-Preisdaten
- https://github.com/ecora2003-max/EG-Produktdaten
- https://github.com/ecora2003-max/EG-Einkaufsintelligenz
- https://github.com/ecora2003-max/EG-Rezept-KI

**Verboten:** Fremdrepo-Committs, Issues, PRs, Branches, Workflows, Releases und Dokumentationsänderungen; Überschreiben fremder Arbeit; Force-Push; produktive Google-Sheets-/Nutzerdatenmigrationen; private Belege, personenbezogene Daten, Tokens oder Secrets in öffentlichen Repos; unnötige Rechts-Neubegutachtung der vom Nutzer geklärten Händlerangebote; Livefeeds ohne bestätigte Rechte; verpflichtende kostenpflichtige APIs; automatische APK-Erstellung. Insbesondere: **Ein früherer Integrationslauf hat irrtümlich EG-Architecture beschrieben. Dies war unzulässig und ist NICHT zu wiederholen.**

## 2. Schneller Wiedereinstieg – zuerst Code

Nach bestätigter eigener `ACTIVE`-Sperre **kurz** `AGENTS.md`, den **kompakten** `docs/STATUS.md`, `docs/ACCEPTANCE.md`, `docs/INTEGRATION.md`, `docs/INTEGRATION_ROADMAP.md` und die aktuell betroffenen Quell-/Testdateien lesen. `docs/DEVELOPMENT.md` beschreibt nur Phase 5 vom 30.10.; das große frühere Protokoll liegt vollständig in `docs/archive/INTEGRATION_STATUS_HISTORY_2026-11-01.md`. **Beides nicht bei jedem Start komplett einlesen.** Tatsächlicher Code, GitHub-HEAD und aktuelle CI haben Vorrang vor alten Texten.

1. Nach dem gezielten Lesen **sofort die nächste fehlende echte Kotlin-/Room-/Host-/UI-Integration implementieren**. Keine reine Statusprüfung als Arbeitsabschluss. Bei begrenzter Laufzeit eine kleine zusammenhängende Codeeinheit mit Tests und Wiederaufnahmeort sichern; nicht dieselbe Vorprüfung im nächsten Lauf neu ausführen.
2. Eigener kurzlebiger Branch nur im Hauptrepo, PR, geprüfte CI auf exaktem Code-/Test-SHA, aktueller `main`-HEAD und eigener Lock-Token vor Merge erneut prüfen. Kein Force-Push oder Überschreiben anderer Änderungen.
3. `FoodStore` bleibt einzige atomare/idempotente Ist-Schreibgrenze für Käufe, Lager, Herstellung, Verzehr und Storno. Vorschläge und KI-Entwürfe buchen nichts. `null` bleibt unbekannt; Artikel-/Versions-/Filialscope, Historie und Quellenrechte erhalten. Zuliefer- und Architekturverträge nur lesend verwenden; `1.0.0-draft` nicht mit unfreiem `1.1.0-draft` vermischen.
4. Passende JVM-/Room-/Migrations-/Regression-/Referenztests, Lint, Schemavergleich und Android-Testkompilierung auf dem geänderten Stand ausführen bzw. echte GitHub-CI prüfen. Routine: `./gradlew --no-daemon --max-workers=2 --continue :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`. Die Kompilierung **führt keine Gerätetests aus**.
5. `docs/STATUS.md` nur bei echtem Code-/Testfortschritt oder nachweislicher Blockade **kurz und commitgenau** aktualisieren. Bei Integration IV zusätzlich die Kohärenz der Host-/FoodStore-Datenflüsse prüfen, nicht die Fachrepositories vollständig auditieren. Immer die eigene Sperre manuell wieder `FREE` setzen.

## 3. Verbindliche separate Arbeitssperre: integration-lock.json

**EINZIGE operative Sperrdatei:** integration-lock.json auf GitHub-main. Der frühere YAML-Sperrblock am Ende dieser Markdown-Datei ist abgeschafft. Bei widersprüchlichen älteren Chat-Anweisungen hat diese neue Regel Vorrang. Die Datei ist ein vollständiges JSON-Objekt, kein YAML und kein Textmarker. Die vollständige chronologische Arbeits- und Wiederherstellungsregel steht in docs/INTEGRATION_IDLE_RECOVERY.md.

1. **Zuerst Anweisungen, dann Sperre:** Diese Datei und die gezielt erforderlichen Projektregeln lesen. Danach den GESAMTEN Inhalt sowie den frischen GitHub-Datei-Blob-SHA von integration-lock.json auf main lesen und als JSON parsen. Ausschließlich schemaVersion=1, alle Pflichtfelder und ein vollständig freier Zustand (state="FREE", alle Besitzer-, Token-, Run-, Zeit-/Branchfelder null) erlauben eine Übernahme. Bei ACTIVE, Syntaxfehler, unbekanntem Zustand, API- oder SHA-Problem: ohne Schreibaktion abbrechen. Andere Integratoren dürfen niemals fremde Sperren reparieren oder freigeben.
2. **Nur bei FREE übernehmen:** Für den eigenen Lauf einen NEUEN einmaligen ownerRunToken mit eigener Automatisierungs-ID erzeugen. Den VOLLSTÄNDIGEN JSON-Inhalt strukturiert serialisieren: state="ACTIVE", ownerAutomationId, ownerChatId, ownerRunToken, acquiredAtUtc, task, workingBranch=null; ownerRunId, lastHeartbeatUtc, leaseExpiresAtUtc bleiben null. Ausschließlich integration-lock.json auf main atomar mit dem frisch gelesenen Datei-Blob-SHA aktualisieren; keine Änderung an dieser Anweisungsdatei. Nach 409/SHA-Konflikt neu prüfen und nicht überschreiben.
3. **Eigenen Besitz rücklesen:** main und JSON-Datei erneut lesen. Nur bei streng gültigem JSON, eigener Besitzer-ID und exakt eigenem Token entwickeln. Eigenen GitHub-Branch anlegen und dessen Namen früh in workingBranch mit neuem Datei-SHA und eigenem Token registrieren. Vor jedem wichtigen Schreibvorgang, CI-/PR-Merge und nach main-Änderungen Token und Branch prüfen; die fachlichen EG-Architecture-Verträge bleiben verbindlich.
4. **Am Ende selbst freigeben:** Mit frisch gelesenem Blob-SHA und exakt eigenem ACTIVE-Token die GESAMTE JSON-Datei als gültiges state="FREE" neu serialisieren und alle acht Owner-/Run-/Token-/Zeit-/Lease-/Heartbeat-/Branchfelder auf null setzen. Freigabezeit und nächste Aufgabe angeben; danach main erneut lesen, JSON parse/owner-free verifizieren. Auch bei Abbruch den eigenen Lock freigeben. Ohne Erfolg keine Freigabe behaupten.
5. **Automatischer Wächter als Ausnahme:** Die GitHub Action prüft ungefähr alle fünf Minuten auf mindestens **30 Minuten GitHub-Inaktivität** des registrierten Besitzers. Erst nach doppelter Owner-/Token-/Branch-/PR-/CI-/Datei-SHA-Prüfung setzt sie den gesamten Inhalt von integration-lock.json auf eine bekannte korrekte FREE-JSON-Vorlage zurück. Bei beschädigtem JSON nur, wenn Besitzer- und Aktivitätsnachweis eindeutig rekonstruierbar sind; andernfalls keine Änderung. Die Entwicklungs-Chats warten nicht auf den Wächter und führen keine fremde Recovery aus. GitHub-Aktivität ist kein perfekter Nachweis für Chat-Inaktivität.

**Wichtig:** Die Dateisperre ist ein Koordinationsverfahren, keine erzwungene GitHub-Rechtebeschränkung. Keine parallelen schreibenden Integratoren und keine fremden Repositories ändern.

## 4. Aktueller Stand und nächste Implementierung

Haupt-App **Room 8, 36 Tabellen**. Letzter unabhängig belegter Integrations-Code-Merge: [PR #36](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/36), `4758dc80421c864df6d44dfc4f2f41656c0873b9`; [Main-CI 41342661798](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41342661798): **383/383 JVM-Tests**, **7/7 Room-Migrationen**, Lint, Schema-/Referenzprüfungen und Android-Testkompilierung bestanden. **0 echte Android-Geräte-/Emulatortests**, keines der vier Fachmodule vollständig `INTEGRATED`. Aktuellere nachgewiesene Code-/Testergebnisse gehen vor.

**Nächster Code-Schritt:** Typisierte echte Room-/Host-Snapshot-Lesestrecke der Rezept-KI anbinden und deren Kontext unmittelbar vor Speichern erneut transaktional prüfen, oder eine konkret begründete wichtigere Artikel-/Filial-/Shopping-UI-Integration. Ohne unabhängig belegte Nutzeraktion keine automatische FoodStore-Buchung. Exakte Vorgaben und offene Abnahmen in `docs/STATUS.md` und `docs/ACCEPTANCE.md`.

## 5. Sperrstatus ausschließlich in der separaten JSON-Datei

Die einzige operative aktuelle Sperre liegt in integration-lock.json auf main. Hier steht bewusst KEIN YAML-Sperrblock und KEIN ACTIVE-/FREE-Schlussmarker mehr. Der GitHub-Wächter darf nur die JSON-Datei auf eine geprüfte FREE-Vorlage zurücksetzen, niemals die vorliegenden Anweisungen.
