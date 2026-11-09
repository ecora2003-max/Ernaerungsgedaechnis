# Android-Testversion herunterladen

[**Aktuelles Health-Update herunterladen**](EG-Watch-Test-health-c46a948.apk?raw=true)

Auf GitHub: Branch `codex-body-measurement-history-20261105` → `downloads` → `EG-Watch-Test-health-c46a948.apk` → **Download raw file**.

Dieses Update behebt den reproduzierten Importabbruch `HEALTH_RECORD_AFTER_CAPTURE`. Später endende Quellintervalle bleiben unverändert zurückgestellt, während abgeschlossene gültige Daten übernommen werden. Tageswerte stammen weiterhin aus dem begrenzten Health-Connect-Aggregat.

Nur **EG · Watch-Test** als Update installieren. Bestehende Apps und persönliche Daten nicht zur Umgehung einer Signaturmeldung löschen. Paket `de.ernaehrung.demo.watchspike`, gleiche bisherige Testsignatur.

App-Code: [`c46a948e1de3f5ea1b295fe9c626c8ffea8aafd8`](https://github.com/ecora2003-max/Ernaerungsgedaechnis/commit/c46a948e1de3f5ea1b295fe9c626c8ffea8aafd8). Prüfsumme und Herkunft stehen in den gleichnamigen `.sha256`-/`.json`-Dateien; commitgenaue Testergebnisse im [STATUS](../docs/STATUS.md).

Der echte neue Watch-Schritt- **und** Workoutimport auf Pixel 9 Pro XL / Android 17 muss nach dem Update noch geprüft werden. Der [vorbereitete Geräteablauf](../docs/FITNESS_WATCH4_DEVICE_SPIKE.md) beschreibt diesen getrennten Nachweis. Die Gesamt-App ist damit nicht vollständig abgenommen.

Die ältere Datei `EG-Watch-Test-gemini-e622c1c.apk` bleibt erhalten und enthält diesen Health-Fix noch nicht.
