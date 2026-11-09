# Android-Testversion herunterladen

[**Aktuelles Fitness-Update herunterladen**](EG-Watch-Test-extra-bae0044.apk?raw=true)

Auf GitHub: **Code → downloads → EG-Watch-Test-extra-bae0044.apk → Download raw file**. Alternativ auf der [Release-Seite](https://github.com/ecora2003-max/Ernaerungsgedaechnis/releases/tag/watch-test-v0.11.0) **APK herunterladen** wählen. Veröffentlichung auf Main/Release erfolgt nach grüner exakter CI; Prüfstand: [PR294](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/294).

Version11 (`0.11.0-health-extra`) ergänzt **Mehr → Weitere Gesundheitsdaten**, auch über Fitness und Trainingsjournal erreichbar. Dort Puls, Distanz, Geschwindigkeit, Aktiv-/Gesamtenergie, Körperfett, Grundumsatzrate und gemeinsamen Health-Connect-Schritt-Tageswert einzeln wählen und ausdrücklich freigeben. Die bisherige Basisfreigabe liest weiterhin nur Samsung-Schritte und Trainings. Quellen und Energiearten bleiben getrennt; es werden keine Ernährungsziele oder Buchungen erzeugt.

Für ältere Waagenwerte einen passenden Zeitraum (zum Beispiel90 Tage) und die separate Historieneinwilligung wählen. Health Connect muss das Historienrecht unterstützen und tatsächlich freigeben. Gespeicherte Rohwerte können unabhängig nach Typ/Quelle gefiltert werden. Das fragt keine Quelle ab und macht auch ältere behaltene Werte auffindbar. Zurückgestellte Originalintervalle werden später erneut geprüft, nicht gekürzt oder hochgerechnet.

Als Update von **EG · Watch-Test** installieren: gleiches Paket `de.ernaehrung.demo.watchspike`, gleiche bisherige Testsignatur. Bestehende Apps und persönliche Daten nicht zur Umgehung einer Signaturmeldung löschen. Room20 erhält die bisherigen Daten; nach dieser Aktualisierung Version11 oder neuer verwenden.

Quelle: [`bae00449da11d8e422fecfb4a816b34ff39d9309`](https://github.com/ecora2003-max/Ernaerungsgedaechnis/commit/bae00449da11d8e422fecfb4a816b34ff39d9309). Prüfsumme, öffentliche Signatur und App-Baum stehen in den gleichnamigen `.apk.sha256`-/`.json`-Dateien. Endgültiger CI-Head, ursprüngliche XML-/Prozess-/APK-Belege und Main-Merge werden im [PR294](https://github.com/ecora2003-max/Ernaerungsgedaechnis/pull/294) und in den Releasehinweisen nachgehalten. App-/Test-/Buildbaum der APK-Quelle muss mit dem endgültig geprüften Head übereinstimmen.

Der Nutzer hat mit der vorherigen Health-APK einen neuen Watch-Workoutimport bestätigt. Neue Watch-Schritte im EG-Tageswert, die zusätzliche reale Typübernahme samt älterer Waagenhistorie und übrige physische Lebenszyklusfälle bleiben separat zu prüfen; [Geräteablauf](../docs/FITNESS_WATCH4_DEVICE_SPIKE.md). Die Gesamt-App ist noch nicht vollständig abgenommen.

Archiv: [vorheriges Trainingsfenster-Update](EG-Watch-Test-duration-17e4d04.apk?raw=true), [Health-Importfix](EG-Watch-Test-health-c46a948.apk?raw=true), [früherer Gemini-Teststand](EG-Watch-Test-gemini-e622c1c.apk?raw=true).
