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

## 2. Unterlagen, Fortsetzung und technische Regeln

Nach **erfolgreicher Sperrenübernahme** und vor der eigentlichen Entwicklung:
1. Im Hauptrepo `AGENTS.md`, `docs/ACCEPTANCE.md`, `docs/STATUS.md`, `docs/INTEGRATION.md` und die betroffenen Quelldateien/Tests lesen. `docs/DEVELOPMENT.md` enthält teilweise historische Angaben und ersetzt nicht den aktuellen STATUS. Bei widersprüchlichen Aussagen entscheiden tatsächlicher HEAD, unveränderliche Implementierung, Tests und CI-Artefakte, nicht ein älterer Prosatext.
2. Aktuellen `main`-HEAD und ausstehende PRs/Branches/GitHub-Actions-Läufe ermitteln. Nicht erneut entwickeln, was ein anderer Lauf bereits implementiert oder getestet hat. Bei laufender CI den genauen Commit und die Testergebnisse prüfen, statt sofort konkurrierende neue Pushes auszulösen.
3. Fünf Zuliefer-/Architektur-HEADs und die tatsächlich geprüften Fach-Code-SHAs **nur lesend** vergleichen. Vertragsbasis: `1.0.0-draft` ist NICHT STABLE, `1.1.0-draft` ist unabhängig und nicht automatisch freigegeben. Bereits genutzte Schema-Blobs im STATUS prüfen; niemals ungefragt auf neue Drafts upgraden.
4. Eine klar begrenzte, sachlich nötige Integrationseinheit implementieren und auf einem **eigenen kurzlebigen Branch im Hauptrepository** testen; bei PR/Merge nach `main` den dann aktuellen HEAD und die Sperreneigentümerschaft neu prüfen. Keine fremden Commits verdecken. Regel aus `AGENTS.md`: Branch, PR, CI auf exaktem SHA, HEAD-Prüfung vor Merge. Die Sperrdatei selbst bleibt auf `main` und wird nicht durch Branch-Merges überschrieben.
5. `FoodStore` ist einzige atomare/idempotente Ist-Schreibgrenze für Käufe, Bestand, Herstellung, Verzehr und Storno. Erkennung, Bonentwurf, Preisvergleich, Einkaufslistenstatus und KI-Rezeptentwürfe sind Vorschläge und niemals automatische Buchungen. Null bleibt unbekannt und wird nie als 0 erfunden. Altdaten, IDs, Versionen und historische Belege erhalten.
6. Teste die Änderung mit passenden JVM-, Room-, Migrations-, Regression-, Python↔Kotlin-Paritäts- und synthetischen E2E-Tests. Im CI mindestens `./gradlew --no-daemon --max-workers=2 --continue :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`, aktuellen JUnit-XML-Bericht, versionierte Room-Schemas und gepinnte Referenzprüfungen auswerten. `compileDebugAndroidTestKotlin` **führt keine Gerätetests aus**. Nur tatsächlich ausgeführte Tests als PASS melden; keine allgemeine `INTEGRATED`-Freigabe ohne volle Abnahme und Android-Gerätebelege.
7. `docs/STATUS.md` knapp, **commitgenau** aktualisieren: geänderter Code, tatsächlicher grüner/roter/ausstehender CI-Run, geprüfte SHAs, OFFLINE/JVM/ANDROID-Gates, Hindernisse, **unmittelbarer nächster ausführbarer Schritt**. Auch beim Abbruch nach einer abgeschlossenen Teiländerung nachvollziehbar sichern.
8. Wenn du zur Automatisierung **Integration IV** gehörst, zusätzlich die Kohärenz **deiner eigenen Haupt-App-Integration** sowie ihrer Schnittstellen und Datenflüsse überprüfen. **Keine** vollständigen internen Audits der vier anderen Fachmodule. Andere drei Automatisierungen betreiben nur notwendige gezielte Schnittstellenkontrollen.

## 3. Einfache manuelle Arbeitssperre (ACTIVE/FREE)

**Verbindlich für alle vier Integrations-Chats:** Es gibt eine einzige gemeinsame Sperre im letzten operativen YAML-Block am Ende dieser Datei auf GitHub-`main`. `ACTIVE` bedeutet „Hier arbeitet jetzt ein Chat!“ (in progress); `FREE` bedeutet „Hier arbeitet kein Chat mehr.“ **Die Chats selbst** setzen diese Zustände. Es gibt keine automatische Sperrenübernahme, keine GitHub-Actions-Verlängerung, keine zeitgesteuerte Freigabe, keine Run-ID-Pflicht und keine automatisierte Recovery. Die dafür zeitweise eingeführten Workflows/Hilfsskripte sind entfernt.

1. **Vor dem Arbeiten:** Die vollständige Datei frisch von `main` lesen. Bei `ACTIVE` eines anderen Chats: nichts schreiben, keine fremden Branches/PRs übernehmen, diesen Lauf beenden und beim nächsten geplanten Lauf erneut versuchen. Auch ein älterer Zeitstempel macht `ACTIVE` nicht automatisch zu `FREE`.
2. **FREE übernehmen:** Aus der aktuellen Automatisierungs-/Chatkennung und einer neu erzeugten, eindeutigen Ausführungskennung einen `ownerRunToken` bilden. Eine ChatGPT-interne Run-ID ist **nicht erforderlich**; `ownerRunId` bleibt `null`, falls nicht verfügbar. Im letzten operativen YAML-Block ausschließlich `state: "ACTIVE"`, passende `ownerAutomationId`, `ownerChatId`, `ownerRunToken`, `acquiredAtUtc`, Aufgabe und `workingBranch` eintragen; `lastHeartbeatUtc` und `leaseExpiresAtUtc` bleiben `null`. Den Schlussmarker zugleich auf **„Hier arbeitet jetzt ein Chat!“** ändern. Ausschließlich mit dem **frisch gelesenen GitHub-Datei-SHA** bedingt aktualisieren (CAS); bei 409/Konflikt neu prüfen, nichts überschreiben.
3. **Eigentum verifizieren:** Nach dem Commit `main` erneut lesen. Nur wenn `ACTIVE` und der eigene Token exakt stimmen, darf dieser Chat entwickeln. Den eigenen Arbeitsbranch im Sperrblock registrieren. Branch/PR/CI/Tests nach `AGENTS.md` durchführen; keine Änderungen in Fremdrepositories.
4. **Solange gearbeitet wird:** Vor jedem Push, PR-Merge und anderen wichtigen Schreiboperationen `main` erneut lesen und den eigenen `ACTIVE`-Token prüfen. Es gibt **keinen automatischen Heartbeat** und keine automatische Zeitverlängerung.
5. **Bei jedem Abschluss:** Den tatsächlichen Entwicklungs-/Teststand und den nächsten Schritt soweit möglich in `docs/STATUS.md` sichern. Danach **sofort** nur bei übereinstimmendem eigenem Token den letzten YAML-Block atomar auf `FREE` ändern: Besitzer-, Run-ID-, Token-, Branch-, Heartbeat- und Lease-Felder auf `null`; Freigabezeit und nächster Schritt dokumentieren. Schlussmarker **„Hier arbeitet kein Chat mehr.“**. Von `main` erneut lesen und `FREE` bestätigen. Auch bei Abbruch oder ohne Codeänderung freigeben.
6. **Wenn eine Sperre nach einem Abbruch stehen bleibt:** Keine automatische Freigabe und keine eigenmächtige Übernahme durch andere Integrations-Chats. Nur nach getrennt geprüfter Beendigung des Vorbesitzers und ausdrücklicher Nutzerinitiative kontrolliert mit GitHub-SHA-Konfliktschutz bereinigen. Den Nutzer über eine notwendige Blockade informieren.

**Wichtig:** Die GitHub-Dateisperre ist ein koordinierendes Verfahren, keine technisch erzwungene Repository-Berechtigung. Der Schutz gegen gleichzeitige Änderungen erfolgt weiter über eigenen Token, bedingte SHA-Updates, Branch-/PR- und CI-Prüfungen.

## 4. Gemeinsamer Arbeitsstand und offene Pflichtgates

Letzter bei Einrichtung tatsächlich geprüfter Hoststand: **Room 8, 36 Tabellen; 318/318 JVM-Tests, 7/7 gefüllte Altversionsmigrationen**; Hauptapp-Code `7ad35dc3957b1838ae6a5647ae7bed77b9486d70`; [Actions 41281746187](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41281746187) grün. Spätere Commits/CI gehen stets vor. Die vier Liefermodule sind **nicht vollständig in Android integriert**. Reale Geräte-/OCR-/GUI-/Offline-Privacy-Prüfungen fehlen. Nächster Schwerpunkt laut STATUS: Rezeptvorschau-Digest, dauerhafter und unabhängig belegter Nutzer-Consent mit Operation-Key/Entwurfsidentität, vollständige Rezept-Revalidator-Parität und Host-Quellen-/Filialscope; danach weitere Integrations-E2E. Kein unbestätigtes Modellfeld darf realen Verzehr, Lager, Käufe oder Bestätigungen erzeugen.

## 5. EINZIGER AKTUELLER SPERRSTATUS – am Ende der Datei belassen

Dieser Block enthält den **einzigen operativen Sperrstatus**. Nur hier den YAML-Block und den zugehörigen Schlussmarker ändern, niemals Feldnamen in den Erklärungen global ersetzen. Maßgeblich sind `state` (`ACTIVE` oder `FREE`) und die exakt passende `ownerRunToken`-Kennung. Für die manuelle Sperre werden `ownerRunId`, `lastHeartbeatUtc` und `leaseExpiresAtUtc` nicht benötigt und auf `null` gesetzt. Keine automatische Übernahme, Fristverlängerung oder Freigabe.

```yaml
state: "ACTIVE"
ownerAutomationId: "Integration (Chat)"
ownerChatId: "manual-trigger"
ownerRunId: null
ownerRunToken: "integration-chat-mvyawr4c-fgz7nsacn4"
acquiredAtUtc: "2026-11-01T20:47:16.812Z"
lastHeartbeatUtc: null
leaseExpiresAtUtc: null
task: "Continue four-module integration and roadmap"
workingBranch: null
lastReleasedAtUtc: "2026-11-01T20:45:05.140Z"
lastReleasedCommit: "901929fc481be3ba8a6765adebc326dc78093ba2"
nextStep: "Next scheduled integration run manually claims FREE-to-ACTIVE with a unique owner token, implements the next feature, then releases to FREE."
```

**Hier arbeitet jetzt ein Chat!**
