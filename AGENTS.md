# Weiterarbeit am Ernährungsgedächtnis

- Zuerst `docs/DEVELOPMENT.md` und die betroffenen Dateien lesen; dort stehen Stand, Grenzen und nächste Schritte. Fachliche Referenz: `docs/product/Gesamtplanung-2026-10-30.txt` (PDF daneben).
- Bestehendes Checkout verwenden, kein zusätzliches Worktree ohne ausdrücklichen Auftrag. Nutzeränderungen erhalten.
- In überprüfbaren Meilensteinen entwickeln und bei beauftragter Produktentwicklung auf GitHub sichern. Relevante Commits enthalten eine aktualisierte Übergabenotiz. Bei knappem Kontingent zuerst Code + Übergabe sichern, nicht noch große Builds beginnen. Ungeprüfte Zwischenstände ausdrücklich kennzeichnen.
- Niemals persönliche App-Daten, Zugangsdaten, lokale Datenbanken oder SDK-/Build-Caches committen. GitHub ist Quellcodeablage.
- Demo-/Testdaten bleiben isoliert. Keine Sheets-Migration oder Änderung produktiver Daten ohne ausdrückliche Freigabe. Keine zweite schreibbare Datenwahrheit.
- Produkt != Artikelversion != Charge. Unbekannt != 0. Planung != Ist-Ereignis. Buchungen atomar, idempotent, historisch nachvollziehbar; keine negativen Bestände.
- Datenbankschema exportieren und versionieren; keine destruktive Migration. Bei Schemaänderung nach veröffentlichter Datenbankversion explizite Migration und Erhaltungsprüfung.
- Relevante Prüfungen: `./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` (JDK 17+, Android SDK 35/Build Tools 34). GitHub Actions kann diese Prüfungen ausführen. Nicht behaupten, dass Geräte-/Emulatortests gelaufen sind, wenn nur Robolectric verwendet wurde.
