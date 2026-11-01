# Prüfe mich, ob du dran bist und was du machen sollst und darfst

**Verbindliche Arbeitsanweisung für JEDEN neu gestarteten Chat, Codex-Lauf und jede der vier zeitversetzten Automatisierungen.** ZUERST vollständig lesen, BEVOR du programmierst, Dateien änderst, einen Branch erstellst oder irgendein Repository beschreibst. Ein anderer Chat hat möglicherweise bereits dieselbe Aufgabe übernommen. Die gemeinsame Arbeit wird nicht durch Chatverlauf, sondern durch GitHub und die nachstehende Sperre koordiniert.

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

## 3. Einfache manuelle Arbeitssperre (ACTIVE/FREE)

**Verbindlich für alle vier Integrations-Chats:** Es gibt eine einzige gemeinsame Sperre im letzten operativen YAML-Block am Ende dieser Datei auf GitHub-`main`. `ACTIVE` bedeutet „Hier arbeitet jetzt ein Chat!“ (in progress); `FREE` bedeutet „Hier arbeitet kein Chat mehr.“ **Die Chats selbst** setzen diese Zustände. Es gibt keine automatische Sperrenübernahme, keine CI-Lease-Verlängerung und keine Run-ID-Pflicht. **Ausnahme:** Der gesonderte GitHub-Wächter prüft alle fünf Minuten eine mögliche Freigabe erst nach **mindestens 35 Minuten ohne verifizierte GitHub-Aktivität**, mit zweimaligem Besitzer-/Token-/SHA-Check und ohne Sperrverlängerung. Die früheren Lease-Verlängerungsworkflows bleiben entfernt. Details: [INTEGRATION_IDLE_RECOVERY.md](docs/INTEGRATION_IDLE_RECOVERY.md).

1. **Vor dem Arbeiten:** Die vollständige Datei frisch von `main` lesen. Bei `ACTIVE` eines anderen Chats: nichts schreiben, keine fremden Branches/PRs übernehmen, diesen Lauf beenden und beim nächsten geplanten Lauf erneut versuchen. Auch ein älterer Zeitstempel berechtigt **andere Chats nicht** zur Übernahme; nur der eng begrenzte GitHub-Wächter darf nach seiner 35-Minuten-Inaktivitätsprüfung freigeben.
2. **FREE übernehmen:** Aus der aktuellen Automatisierungs-/Chatkennung und einer neu erzeugten, eindeutigen Ausführungskennung einen `ownerRunToken` bilden. Eine ChatGPT-interne Run-ID ist **nicht erforderlich**; `ownerRunId` bleibt `null`, falls nicht verfügbar. Im letzten operativen YAML-Block ausdrücklich `state: "FREE"` auf **`state: "ACTIVE"`** umstellen und passende `ownerAutomationId`, `ownerChatId`, `ownerRunToken`, `acquiredAtUtc`, Aufgabe und `workingBranch` eintragen; `lastHeartbeatUtc` und `leaseExpiresAtUtc` bleiben `null`. Den Schlussmarker zugleich auf **„Hier arbeitet jetzt ein Chat!“** ändern. Ausschließlich mit dem **frisch gelesenen GitHub-Datei-SHA** bedingt aktualisieren (CAS); bei 409/Konflikt neu prüfen, nichts überschreiben.
3. **Eigentum verifizieren:** Nach dem Commit `main` erneut lesen. Nur wenn `ACTIVE` und der eigene Token exakt stimmen, darf dieser Chat entwickeln. Den eigenen Arbeitsbranch im Sperrblock registrieren. Branch/PR/CI/Tests nach `AGENTS.md` durchführen; keine Änderungen in Fremdrepositories.
4. **Solange gearbeitet wird:** Vor jedem Push, PR-Merge und anderen wichtigen Schreiboperationen `main` erneut lesen und den eigenen `ACTIVE`-Token prüfen. Es gibt **keinen automatischen Heartbeat** und keine automatische Zeitverlängerung.
5. **Bei jedem Abschluss:** Den tatsächlichen Entwicklungs-/Teststand und den nächsten Schritt soweit möglich in `docs/STATUS.md` sichern. Danach **sofort** nur bei übereinstimmendem eigenem Token den letzten YAML-Block atomar umstellen: ausdrücklich **`state: "ACTIVE"` zu `state: "FREE"`** ändern, Besitzer-, Run-ID-, Token-, Branch-, Heartbeat- und Lease-Felder auf `null`; Freigabezeit und nächster Schritt dokumentieren. Schlussmarker **„Hier arbeitet kein Chat mehr.“**. Von `main` erneut lesen und `FREE` bestätigen. Auch bei Abbruch oder ohne Codeänderung freigeben.
6. **Wenn eine Sperre nach einem Abbruch stehen bleibt:** Andere Integrations-Chats dürfen eine fremde `ACTIVE`-Sperre niemals selbst freigeben. Der GitHub-Wächter darf ausschließlich nach seiner 35-Minuten-Inaktivitäts-/Owner-/Token-/SHA-Prüfung automatisch freigeben; bei Unsicherheit bleibt die Sperre. Manuelle Sonderkorrektur nur nach nachgewiesener Beendigung des Vorbesitzers und ausdrücklicher Nutzerinitiative. Den Nutzer über eine notwendige Blockade informieren.

**Wichtig:** Die GitHub-Dateisperre ist ein koordinierendes Verfahren, keine technisch erzwungene Repository-Berechtigung. Der Schutz gegen gleichzeitige Änderungen erfolgt weiter über eigenen Token, bedingte SHA-Updates, Branch-/PR- und CI-Prüfungen.

## 4. Aktueller Stand und nächste Implementierung

Haupt-App **Room 8, 36 Tabellen**. Letzter unabhängig belegter Integrations-Code-Merge: [PR #36](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/36), `4758dc80421c864df6d44dfc4f2f41656c0873b9`; [Main-CI 41342661798](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41342661798): **383/383 JVM-Tests**, **7/7 Room-Migrationen**, Lint, Schema-/Referenzprüfungen und Android-Testkompilierung bestanden. **0 echte Android-Geräte-/Emulatortests**, keines der vier Fachmodule vollständig `INTEGRATED`. Aktuellere nachgewiesene Code-/Testergebnisse gehen vor.

**Nächster Code-Schritt:** Typisierte echte Room-/Host-Snapshot-Lesestrecke der Rezept-KI anbinden und deren Kontext unmittelbar vor Speichern erneut transaktional prüfen, oder eine konkret begründete wichtigere Artikel-/Filial-/Shopping-UI-Integration. Ohne unabhängig belegte Nutzeraktion keine automatische FoodStore-Buchung. Exakte Vorgaben und offene Abnahmen in `docs/STATUS.md` und `docs/ACCEPTANCE.md`.

## 5. EINZIGER AKTUELLER SPERRSTATUS – am Ende der Datei belassen

Dieser Block enthält den **einzigen operativen Sperrstatus**. Nur hier den YAML-Block und den zugehörigen Schlussmarker ändern, niemals Feldnamen in den Erklärungen global ersetzen. Maßgeblich sind `state` (`ACTIVE` oder `FREE`) und die exakt passende `ownerRunToken`-Kennung. Für die manuelle Sperre werden `ownerRunId`, `lastHeartbeatUtc` und `leaseExpiresAtUtc` nicht benötigt und auf `null` gesetzt. Keine automatische Übernahme oder Fristverlängerung; einzige Ausnahme bei Freigaben ist der separat getestete GitHub-35-Minuten-Inaktivitätswächter (nur für registrierte Integrationsautomaten).

```yaml
state: "ACTIVE"
ownerAutomationId: "6ae64ea269448191ac8f0b8a5fd206cb"
ownerChatId: "Integration Chat scheduled 2026-11-01T22:40:08.353Z"
ownerRunId: null
ownerRunToken: "6ae64ea269448191ac8f0b8a5fd206cb-20261101T224008353Z-285yde"
acquiredAtUtc: "2026-11-01T22:40:08.353Z"
lastHeartbeatUtc: null
leaseExpiresAtUtc: null
task: "Implement authorized OFFERS Room/Host snapshots and regression tests"
workingBranch: "codex/recipe-authorized-offers-20261101-2240"
lastReleasedAtUtc: "2026-11-01T22:36:19.993Z"
lastReleasedCommit: "0741743977e5d585c824c2629bf0d9dceccdf3b4"
nextStep: "Implement authorized OFFERS Room/Host snapshots with transactional review, then authenticated recipe-save action provenance and Android E2E"
```

**Hier arbeitet jetzt ein Chat!**
