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

## 3. Exklusive Sperre: Regeln für alle vier Chats

Die Sperre steht **ausschließlich im letzten Abschnitt dieser Datei auf dem GitHub-`main`-Branch**. Der lesbare Zustand lautet entweder exakt **„Hier arbeitet kein Chat mehr.“** oder **„Hier arbeitet jetzt ein Chat!“**. Dazu gehören Maschinenfelder. Jede Ausführung benötigt eine **eindeutige Kennung** `ownerRunToken` (Automatisierungs-ID + tatsächliche Run-ID; wenn unbekannt + selbst erzeugte eindeutige Ausführungskennung), `ownerChatId`, `acquiredAtUtc`, `lastHeartbeatUtc`, `leaseExpiresAtUtc`, `task`.

### A. Vor dem Arbeiten / Sperre übernehmen

1. **Frisch** die Sperrdatei von `main` mit GitHub-FILE-SHA abrufen, niemals von altem Cache/Chatkopie. Bei `ACTIVE` und gültiger, noch nicht abgelaufener `leaseExpiresAtUtc`: **KEINE Entwicklung/Schreibaktionen starten**, nicht an fremdem Branch weiterarbeiten, nicht auf fremde PRs pushen. Die eigene Ausführung beenden; bei nächster geplanter Stunde erneut versuchen. Nur belegte notwendige Blockade melden, nicht routinemäßig.
2. Bei `FREE`: sofort `ACTIVE` eintragen, **beide** Text- und Maschinenzustände anpassen, eindeutigen Run-Token, Chat-/Automatisierungskennung, UTC-Zeit und eine **30 Minuten gültige Lease** setzen. Verwende eine atomare **bedingte** GitHub-Dateiaktualisierung mit dem gerade gelesenen Blob-SHA (`update_file` mit `sha`) **und** vorheriger Kontrolle des live `main`-HEADs, nicht blind überschreiben. Alternativ ein Git-Commit mit geprüftem erwartetem Branch-HEAD (CAS/fast-forward), **niemals force**.
3. Falls der Schreibversuch einen Konflikt/409 ergibt oder ein anderer inzwischen die Sperre setzte: nichts weiter schreiben; Zustand erneut lesen und nur dann neu versuchen, wenn weiterhin `FREE`. **Erst wenn der eigene ACTIVE-Eintrag auf `main` erneut gelesen wird und `ownerRunToken` exakt stimmt, besitzt du die Sperre.** Eine eigene lokale Absicht/Antwort reicht nicht.
4. Wenn bei `ACTIVE` die Lease abgelaufen ist, ist ein Absturz möglich, aber nicht bewiesen. Vor Übernahme aktuelle Automatisierungs-Läufe, PRs, CI und jüngste GitHub-Commits prüfen. Ist der vorige Besitzer nachweislich noch tätig oder die Lage unklar, **nicht stehlen** und keinen Parallel-Write riskieren; Nutzerentscheidung oder nächsten Lauf abwarten. Ist die vorige Ausführung nachweislich beendet/fehlgeschlagen und nicht mehr tätig, den abgelaufenen Eigentümer mit begründetem, dokumentiertem CAS-Update ablösen. Falls kein Runstatus prüfbar ist, keine ungeprüfte Übernahme.
5. Dieser Schutz gilt **für alle App-/Architektur-Integrationsarbeiten im selben Hauptrepository**, nicht als vier getrennte Sperren. Externe Entwicklungs-Chats an anderen Modulen sind von diesen App-Integratoren strikt read-only.

### B. Solange du arbeitest

- **Startlease: 30 Minuten.** Ab Aktivierung und nach bestätigtem erfolgreichem Test des Workflows `.github/workflows/integration-lease-renewal.yml` verlängert GitHub Actions bei `workflow_run` (`requested` oder `completed`) des bestehenden Android-CI die noch gültige `ACTIVE`-Sperre automatisch, aber **nur** für den exakt eingetragenen einmalig laufbezogenen `workingBranch`. Neuer Ablauf = `max(alter Ablauf, aktuelle UTC-Zeit + 15 Minuten)`; `lastHeartbeatUtc` wird aktualisiert. Kein automatisches Verlängern bei `FREE`, abgelaufener Lease, falschem/pending Branch, fehlendem Eigentümer oder einem vor Übernahme gestarteten CI-Lauf. Jede Änderung verwendet ein frisches GitHub-Blob-SHA als Konfliktschutz. GitHub erkennt den Arbeitsbranch, nicht kryptografisch den Chat. Reine Leseaktivität, ein Push ohne auslösenden Android-CI-Lauf oder längeres Codieren ohne CI verlängern nichts. Der Besitzer muss den eindeutigen Branch unverzüglich im Sperrblock registrieren und bei langen CI-losen Phasen die Lease rechtzeitig selbst verlängern. **Solange der Workflow noch nicht erfolgreich verifiziert ist**, bleibt das bisherige manuelle 10-Minuten-Heartbeat verpflichtend. In jedem Fall beim Abschluss sofort `FREE` setzen, ohne die Restlease abzuwarten; GitHub Actions darf eine freigegebene Sperre niemals reaktivieren.
- **Vor jedem Commit/Push/PR-Merge/STATUS-Schreiben:** Sperre erneut aus `main` lesen und prüfen, ob `ACTIVE`, `ownerRunToken` exakt gleich und Lease nicht abgelaufen ist. Danach den neuesten Ziel-HEAD prüfen; Konflikte neu abgleichen und erforderliche Tests wiederholen. Wenn Eigentum nicht mehr nachweisbar ist: **sofort jede Schreibarbeit beenden**, niemals die Sperre eines anderen löschen.
- Eine bloße CI-Pipeline oder offene PR ist kein ausreichender Beweis für einen aktiv arbeitenden Chat; aber vor Übernahme und Merge müssen diese Ergebnisse berücksichtigt werden. Starte nicht durch unnötige Pushes dauernd neue CI-Abbrüche.

### C. Immer am Ende / Freigabe

- Vor dem regulären Ende, auch nach technischem Fehler, soweit die Werkzeuge noch erreichbar sind: aktuellen Code, konkrete Testresultate und Wiederaufnahmeort in `docs/STATUS.md` dokumentieren, unvollständige CI als **PENDING** belassen, eigene Branch-/PR-Arbeit nachvollziehbar hinterlassen. **Danach** Sperre aktualisieren.
- Sperre **nur dann** per frischem File-SHA freigeben, wenn `ownerRunToken` auf `main` noch exakt dein Token ist. Setze `state: FREE`, Text **„Hier arbeitet kein Chat mehr.“**, `ownerRunToken: null`, `ownerChatId: null`, `leaseExpiresAtUtc: null`; letzter Arbeits-/Release-SHA, `lastReleasedAtUtc` und `nextStep` für Nachfolger dokumentieren. Kein fremdes Eigentum löschen.
- Bei Absturz kann diese abschließende Freigabe **nicht garantiert** werden; deshalb Lease + geprüfte Recovery. Eine Datei allein ersetzt keine serverseitig erzwungene Schreibsperre oder technische GitHub-Berechtigungsbeschränkung.

## 4. Gemeinsamer Arbeitsstand und offene Pflichtgates

Letzter bei Einrichtung tatsächlich geprüfter Hoststand: **Room 8, 36 Tabellen; 318/318 JVM-Tests, 7/7 gefüllte Altversionsmigrationen**; Hauptapp-Code `7ad35dc3957b1838ae6a5647ae7bed77b9486d70`; [Actions 41281746187](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/41281746187) grün. Spätere Commits/CI gehen stets vor. Die vier Liefermodule sind **nicht vollständig in Android integriert**. Reale Geräte-/OCR-/GUI-/Offline-Privacy-Prüfungen fehlen. Nächster Schwerpunkt laut STATUS: Rezeptvorschau-Digest, dauerhafter und unabhängig belegter Nutzer-Consent mit Operation-Key/Entwurfsidentität, vollständige Rezept-Revalidator-Parität und Host-Quellen-/Filialscope; danach weitere Integrations-E2E. Kein unbestätigtes Modellfeld darf realen Verzehr, Lager, Käufe oder Bestätigungen erzeugen.

## 5. EINZIGER AKTUELLER SPERRSTATUS – am Ende der Datei belassen

Dieser Block ist der operative Zustand und muss beim Sperren und Freigeben **zusammen** geändert werden. Die Erläuterungen oben unverändert erhalten. Der unten eingetragene erste Owner ist ein schon vor Einrichtung der Sperre gestarteter Automatisierungslauf; er kennt den neuen Mechanismus noch nicht und kann seine Sperre nicht selbst freigeben. Sobald dieser alte Lauf nachweislich beendet ist, darf die Sperre per geprüftem SHA freigegeben werden.

```yaml
state: FREE
ownerAutomationId: null
ownerChatId: null
ownerRunToken: null
acquiredAtUtc: "2026-11-01T07:30:10.695Z"
lastHeartbeatUtc: "2026-11-01T07:37:58.321Z"
leaseExpiresAtUtc: null
task: "Keine aktive Integrationsarbeit"
workingBranch: null
lastReleasedAtUtc: "2026-11-01T07:43:14.426Z"
lastReleasedCommit: "d212e4608ad948be3d00c780643bac1695a9cd89"
nextStep: "Main-Push-CI für PR-5-Merge a710e325 unabhängig prüfen; danach UI-gebundene Rezept-Consent-Aktion, Kontext-Digests und Rezept-Revalidator-Parität"
```

**Hier arbeitet kein Chat mehr.**
