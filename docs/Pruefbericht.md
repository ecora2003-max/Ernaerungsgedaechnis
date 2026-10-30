# Historischer Prüfbericht der ursprünglichen UI-Demo

Dieser Bericht beschreibt die frühere Demo. Für den aktuellen Entwicklungs- und Prüfstand siehe [DEVELOPMENT.md](DEVELOPMENT.md) und [LOCAL_DATABASE.md](LOCAL_DATABASE.md).


# Prüfstand – 30. Oktober 2026

Diese ZIP ist der vollständige gesicherte Quellstand der UI-Demo. Sie ist keine produktive Ernährungs-App.

## Nachgewiesener vorheriger Prüflauf

Am 29.10.2026 wurde der Quellstand auf Grundlage von Commit d26d857 erfolgreich mit `assembleDebug`, `lintDebug` und `testDebugUnitTest` gebaut. Beobachtet wurden 16 erfolgreiche JUnit-Testfälle: vier Datenprüfungen, zehn Navigation-/Interaktionsprüfungen und zwei Rendering-Durchläufe. Der Build meldete 2 min 28 s. Es wurden 68 Bildschirmbilder erzeugt: 34 Routen bei 360 × 800 dp und bei 320 × 640 dp mit Schriftfaktor 1,3. Die 34 normalen Ansichten wurden als Kontaktbögen visuell geprüft. Die kleinen Ansichten wurden erzeugt, ihre vollständige visuelle Nachprüfung war noch offen.

Android Lint meldete eine Warnung zur fehlenden Angabe `fullBackupContent` für ältere Android-Versionen. Diese Angabe ist im vorliegenden Stand ergänzt. Keine Prüfung auf einem echten Telefon oder Android-Emulator wurde durchgeführt; die UI-Tests nutzten Robolectric mit API 34 und Native Graphics.

## Wiederhergestellter Stand

Die temporäre Build-Umgebung wurde danach zurückgesetzt. Der dauerhaft gesicherte Quellstand und seine ersten drei Commits wurden aus der Quellprojekt-ZIP wiederhergestellt. Tests, Dokumentation und Workflow wurden ergänzt; der aktuelle Build wurde erneut gestartet. Die endgültige Ausgabe dieses neuen Durchlaufs lag beim kurzfristig angeforderten ZIP-Export noch nicht vor. Die neu erstellten Tests sind deshalb nicht als erneut bestanden ausgewiesen.

Die vorherige APK und die ursprünglichen Test-XML-Dateien waren nach dem Reset nicht mehr vorhanden. Der frühere Erfolg ist im Gespräch dokumentiert, aber kein Ersatz für einen erneuten Build dieses Archivs. Die ZIP enthält keinen behaupteten Geräte- oder GitHub-Testnachweis.

## Prüfungen im Quellpaket

- Produktidentität und unbekannte Werte bei Bestand null.
- Konsistenz von Bedarf, Einkaufssumme und Budget.
- Trennung von Planung und tatsächlicher Ernährung.
- Vollständigkeit der 33 vorgesehenen Routen und fünf Hauptbereiche.
- Navigation, Zurück-Tasten, Produktwahl und Bestandsfilter.
- Bon-, Verbrauchs-, Meal-Prep-, Import- und Korrekturvorschauen ohne Änderungen an Fixtures.
- Messungs- und Trainingsformulare sowie inaktive Sicherungsaktionen.
- Rendering aller Routen in zwei Bildschirm-/Schriftkonfigurationen.

Ein erfolgreicher Test der inaktiven Buchungsschaltflächen beweist keine Transaktionssicherheit einer späteren Datenbank. Eine solche Datenbank ist hier nicht implementiert.

## GitHub und Kosten

Das Nutzerkonto und das leere öffentliche Repository konnten gelesen werden. Benutzerrechte wurden als Admin/Push angezeigt; der tatsächliche Schreibversuch der Integration scheiterte mit HTTP 403 „Resource not accessible by integration“. Keine Datei wurde hochgeladen, kein Workflow ausgelöst, keine GitHub-Actions-Minuten verbraucht.

Der Nutzer nennt GitHub Free als Vorgabe. Kontoweite Abrechnung, vorhandene Zahlungsdaten und Restkontingente wurden nicht eingesehen. Der beigefügte Workflow verwendet deshalb nur einen Standard-Linux-Runner in einem öffentlichen Repository; bei privater Sichtbarkeit läuft er nicht.

Offizielle Kostenquelle, geprüft am 30.10.2026: https://docs.github.com/en/billing/concepts/product-billing/github-actions

Nächster technischer Schritt: ZIP in Android Studio bauen und testen bzw. GitHub-Schreibberechtigung für die Integration herstellen, Quellstand übertragen und den vorbereiteten CI-Build prüfen.
