# Wiederaufnahme nach Ausfall der Ausführungsumgebung – 06.11.2026

Einziges Schreibrepository ecora2003-max/Ernaerungsgedaechnis. Eine integrierende Codex-Sitzung, keine Locks/Wächter oder pausierten A/B/III/IV-/Fortsetzungsautomatisierungen wiederherstellen. Keine Force-Pushes, Resets oder fremde Überschreibungen.

## Gesicherter GitHub-Stand

- main: 362bd96840945b7558ea107a89885cb1700d80eb, Merge #270. Exakter Code-/Test-Head 0833fbfa9cc61459453a05911647739263cfc3fd. App-CI 42211325154: originale XML 736/736 JVM, zwölf Migrationen, 0 Fehler/Skips. API35-CI 42211325166: originale XML 29/29, 0 Fehler/Skips. Rohgrafik-Pixelprüfung 4,522 s, Sicherung mit beiden echten SAF-Abbrüchen/Restore/Retry/SQLite/Netzfreiheit 54,136 s, Datenqualität/Katalogkorrektur 9,503 s, tatsächlicher leerer HC-Provider 2,482 s. Fresh-grant-Retry-Zähler 0; keine Ursache des physischen Pixel-Fehlers daraus ableiten.
- Zuvor #269, exakt 2be714c75376af3cb1cd7da58c24cff1f6cc544b, als e96e14eedb9613f975a6df1de8f4d445b35cafd2 integriert. 733 JVM/zwölf Migrationen und 29/29 API35 PASS. Passwortverschlüsselte manuelle Sicherung, Format V1, getrennte Bestätigung, atomarer/idempotenter erhaltender FoodStore-Restore. Keine automatische Cloudübertragung oder Wiederaktivierung alter Gesundheitsrechte.
- Verwendeter bestehender Branch: codex-body-measurement-history-20261105. Letzter normal gepushter Code-Head vor Ausfall 0833fbfa9cc61459453a05911647739263cfc3fd. #269 und #270 geschlossen/gemergt; keine weitere funktionale PR erstellt. Alte Dokumentations-PRs nicht pauschal integrieren.

## Technische Grenze

Nach erfolgreichem GitHub-Merge von #270 konnte der nächste lokale Synchronisationsprozess nicht mehr erzeugt werden: exec-server transport disconnected; failed to resume exec-server session; recovery timed out after 45s. Ein anschließender read-only Probeaufruf ergab: No usable execution environment is available. Der lokale Fetch/Fast-forward ist daher NICHT bestätigt. Kein lokaler Reset oder zweites Checkout.

Die GitHub-Konnektoren blieben verfügbar. Diese Kapsel wird auf demselben Branch gesichert, ohne ungetestete App-Dateien nach main zu übernehmen. Sie ist eine Teilrettung aus dem ausgeführten Werkzeugprotokoll, kein bytegenauer Dump des unzugänglichen Worktrees. Zuerst den vorhandenen Worktree und die eigenen Stashes zurückholen; sie sind genauer als eine Rekonstruktion.

## Lokaler Kapazitätsstand

- Checkout /workspace/Ernaerungsgedaechnis. Eigener sicherer Kern-Stash 26088f5c6ef40a7261eca97407f4736c8fba9148 (nach 24/24 gezielten JVM-Fällen). Älterer eigener Stash 43c0cfa5b85af887e8014015e5d0f3b6f3daeb54 unkompilierter Vorläufer. Beide nicht blind mehrfach anwenden: der neuere wurde vor Ausfall bereits konfliktfrei auf 0833fbf angewandt und danach erweitert. Keine fremden Stashes entfernen.
- /tmp/eg-storage-capacity-room14-core-second.log: BUILD SUCCESSFUL 2 min 38 s. Original-XML /tmp/eg-storage-room14-core-24-jvm: 13 Migrationen, sechs StorageCapacityRoomTest-Fälle, fünf bestehende BackupRoomTest-Fälle, 24/24, 0 Fehler/Skips.
- Room-14-Export lokal erzeugt: 46 Tabellen, alle 45 alten Entitätsdefinitionen und die bestehende Sicht unverändert gegenüber veröffentlichtem Room 13. Neue Tabelle storage_capacity_reviews. Kein destruktiver Pfad.
- Danach neue StorageCapacityViewModel.kt und StorageCapacityScreen.kt angelegt; echte More/Stock/Kitchen-Navigation und strukturierte Herstellungswahl ergänzt. Neueste UI noch UNKOMPILIERT, ohne neue UI-/Gerätetests. Zusätzlich bekannte 0 ml Mehrvolumen für bereits belegte Behälter zugelassen und im bestehenden Produktionsfall ergänzt; diese letzte Änderung noch nicht erneut getestet. Den früheren 24/24-Beleg NICHT auf diese nachfolgenden Änderungen erweitern.

## Vollständige lokale Dateien/Änderungsgruppen

Neue Dateien: app/src/main/java/de/ernaehrung/demo/data/local/StorageCapacity.kt; app/src/main/java/de/ernaehrung/demo/ui/StorageCapacityViewModel.kt; app/src/main/java/de/ernaehrung/demo/ui/screens/StorageCapacityScreen.kt; app/src/test/java/de/ernaehrung/demo/StorageCapacityRoomTest.kt; app/schemas/de.ernaehrung.demo.data.local.FoodDatabase/14.json. VM-Vorlage außerdem /tmp/eg-StorageCapacityViewModel.kt.

Geändert: FoodDatabase.kt, FoodMigrations.kt, FoodStore.kt, KitchenOperations.kt, RecipeRoomStockSnapshotReader.kt, FoodBackupStore.kt, BackupViewModel.kt, BackupScreen.kt, LocalDataViewModel.kt, LocalWorkspaceApp.kt, KitchenEditor.kt, LocalDataScreen.kt, StockEventEditors.kt; Migrationsketten mehrerer Room-/Android-Reopen-Fälle auf MIGRATION_13_14 erweitert; fünf alte reine Versionsassertions 13→14 angepasst; Backup-Tabellenanzahl 45→46; tools/check_junit_xml.py mindestens 13 Migrationen; android-demo.yml Schema-Artefakt 14.json. Beim Wiederaufnehmen zuerst tatsächliches git diff prüfen.

## Fachliche Implementierung / Sicherheitsgrenzen

- Physische Gesamtkapazität und belegtes Behälter-AUSSENVOLUMEN als getrennte nullable Long-Milliliter; kein Volumen aus Lebensmittelmasse/Portionen erfinden. Freier Platz nur wenn beide bekannt und 0 <= occupied <= total. 0 bleibt bekannt; null bleibt unbekannt.
- Temperatur als exakt erhaltener Celsius-Dezimaltext, Vor-Ort-Prüfgrund, Zeit/Zone, Source.USER/Quality.CONFIRMED. Geplante/unbestätigte Lagerorte dürfen keine tatsächliche Kapazität erhalten. Originale storage_locations bleiben unverändert und sind im UI getrennt sichtbar; alte usableCapacityMl ohne bekannte Belegung nicht als freien Platz umdeuten.
- Append-only storage_capacity_reviews mit locationId/revision/previousId/operationId, eindeutigen Indizes und restriktiven FK zu Lagerort, Operation und gleicher Lagerort-Vorversion. CAS auf tatsächlich aktuelle Vorversion, idempotenter STORAGE_CAPACITY_CHECK_V1-Hash, ein FoodStore-Transaktionszeitpunkt. Ansicht erzeugt keine Ledger-Zeile.
- Optionale ProductionCapacityEvidence(reviewId, addedOuterVolumeMl, physicallyChecked) am ENDE von ProductionInput. Ohne Evidence alter PRODUCTION-Hash unverändert; sonst Wrapper PRODUCTION_LOCATION_CAPACITY_V1(legacyHash, reviewId, volume, checked). FoodStore/Kitchen prüft aktuelle unveränderte Basis, bekannte freie Kapazität und Vor-Ort-Bestätigung in derselben Transaktion wie reale Zutatenentnahme/Fertigportionen. Dann neue Kapazitätsversion mit occupied += zusätzliches Außenvolumen, basisKind CONFIRMED_PRODUCTION_ADDITION_V1, derselben Produktions-Operation. Keine zweite FoodStore-Operation; keine Ableitung aus Portionsmenge. Bekannte zusätzliche 0 bei bereits belegten Behältern ist erlaubt; leer/unknown nicht.
- Verzehr, Entsorgung und Umlagerung räumen Behälter nicht automatisch aus. Storno einer Produktion irgendwo in der Kapazitätsableitung macht die freie Kapazität unbekannt; neuer physischer MANUAL-Prüfstand stellt eigenständige Basis her. Temperatur im abgeleiteten Stand ausdrücklich als Angabe aus der ursprünglichen physischen Prüfung anzeigen, keine neue Temperaturmessung behaupten.
- RecipeRoomStockSnapshotReader bleibt v1: ohne Kapazitätsprüfungen unveränderter alter Basis-/Batchreview-Hash. Neue as-of Kapazitätsversionen, nur recordedAt <= capturedAt, in RECIPE_ROOM_STOCK_LOCATION_CAPACITY_V1 einbinden; spätere Prüfungen ändern frühere Kontextkenntnis nicht. Keine v1.1-Freigabe.

## Backup-Kompatibilität ist Teil dieser Funktion

FoodBackupStore.TABLES um storage_capacity_reviews erweitern. Current Room14/46 muss vollständig geprüft/exportiert werden. Explizit genau Room13 mit identityHash ddbdbf0fb475ae9d3a5c9a40b4284aa3 und genau den 45 alten Tabellen akzeptieren; unveränderte alte Spalten/Typen gegen aktuelles Schema prüfen, ausschließlich neue Kapazitätstabelle leer ergänzen. Andere alte/fremde Versionen/Hashes bleiben gesperrt. Genehmigter Digest und idempotenter Restore-Fingerprint bleiben IMMER die Originaldatei, nicht die ergänzte Projektion. Bestehende/spätere Kapazitätsdaten bei Retry nie löschen. BackupViewModel.Preview olderSchema-Flag und sichtbare Erklärung, dass für neue Lagerprüfungen keine Werte erfunden werden. Unitfall dieser Brücke und späterer No-op nach neuer physischer Prüfung gehörte zum bestandenen 24er-Kernlauf.

## UI-Anschluss / nächster konkreter Schritt

Lokale neue WorkspacePage.STORAGE_CAPACITY: Mehr sowie Lager öffnen die rein lesende Prüfhistorie; Küche öffnet sie für den ausgewählten Ort. UI setzt Eingaben/Zustimmung nicht in SavedState; nur opake Ort-/Operation-IDs. Editierbasis in RAM einfrieren, bei geänderter Vorversion/Leseausfall/Stornogrundlage Save sperren, nach Rotation neue Bestätigung. Felder haben storage_capacity_*_input-Tags und useUnmergedTree-Selektoren verwenden. Bekannte Null/unbekannt, Originaltemperatur, Ableitungs-/Korrekturhistorie anzeigen. Neue Operation freundlich als Physische Lagerkapazität geprüft anzeigen.

KitchenEditor nutzt LocalDataViewModel.storageCapacity-StateFlow und optionale strukturierte Kapazitätswahl, zusätzliche ganze ml und separate aktuelle Vor-Ort-Bestätigung. Auswahlwechsel/Eingaben/aktuelle Basisänderung ziehen Zustimmung zurück; Production-Bestätigung/Release/Conditions nicht über rememberSaveable erhalten. FEFO-Check setzt confirmed UND proposalApproved; akzeptierter Zustand erst im Klick neu lesen (confirmed && (proposal == null || proposal.complete && proposalApproved) && (!useCapacity || capacityConfirmed)). Proof-Erzeugung innerhalb model.save, um fehlende Eingaben als kontrollierten Fehler statt UI-Absturz zu behandeln. Ohne strukturierte Wahl bleibt explizite eigene physische Prüfung/Freitext möglich, ohne rechnerische Freiplatzbehauptung.

Zuerst bestehenden Worktree/Stash und tatsächlichen Main synchronisieren, OHNE Reset. Danach aktuell unkompilierte Kapazitätsoberfläche kompilieren, passende echte MainActivity-Tests schreiben (Null/0, RAW-Temperatur, Änderung/Zustimmungsentzug, Rotation, stale CAS, vorhandene Originaldaten) und vollständigen Herstellungsanschluss mit expliziter zusätzlicher Behälterkapazität testen. Voller JVM-/13-Migration-/Lint-/Schema-/Referenzlauf; neue echte Android-/SQLite-Reopen-/Offline-/Privacy-Kette ergänzen, erwartbar mindestens 30 Fälle. Kein Room14-/Kapazitätsmerge ohne diese Belege. Bestehenden Branch fortsetzen, keine Doppelbranches.

## Physisches P0 bleibt offen

Pixel 9 Pro XL / Android 17 / Galaxy Watch 4: Samsung-Schreibrechte Schritte/Training laut Nutzer erteilt, manueller Resyncfehler dauerhaft. Diagnosecode aus separater EG · Watch-Test fehlt weiterhin. Reale neue Watch-Schritte UND Workout mit Quelle/Version/Retry/Revoke/Offline nachweisen; API35 mit leerem Provider ersetzt dies nicht. Keine Statistik-/TDEE-Parameter erfinden und kein Fitness-Meilenstein als fertig markieren.

Originalinstallation niemals zur Signaturumgehung deinstallieren. Bestehende Test-App-Signatur und privaten Schlüssel nur lokal unter /workspace/.eg-runtime-cache/signing/watch-spike-debug.keystore schützen, nicht veröffentlichen. Letzte Diagnose-APK EG-Watch-Test-685d281.apk, SHA256 83d5cc0b16cb59ef9d868035ccb038fbcfcb356dfb60e610b3d5371436faacc4; Zertifikat 03a27323cc90758409fbc5a7d370f222e01b030671a288082b43d09032c72857. Falls Runtime-Dateien fehlen, erst Wiederherstellung prüfen, keine falsche Signatur liefern.

## Runtime

Build JDK /tmp/eg-jdk17, SDK /tmp/eg-android, GRADLE_USER_HOME /tmp/eg-gradle, ANDROID_USER_HOME /tmp/eg-android-user, testHome /tmp/eg-test-home. Inherited proxy http://proxy:8080 und Systemtruststore benötigt. Keine Credential-Dumps. Kein lokales KVM; echte API35-Läufe über vorhandenen Workflow recipe-android-device.yml. Keine neue APK bei jedem Teilziel. Bei wieder verfügbarer Runtime zuerst laufende Sitzungen und aktuelle AGENTS auf main lesen.
