# Rezept mit Gemini oder einer anderen KI entwerfen

Unter **Mehr → Rezepte & Küche → Rezeptvorschläge und JSON-Import → Rezept mit ChatGPT oder anderer KI entwerfen** kannst du direkt mit Gemini arbeiten oder eine vorbereitete Anfrage mit einer anderen KI-App teilen. Der Offline-Generator ist im selben Rezeptbereich verfügbar.

## Direkt mit Gemini

1. Gib deinen Rezeptwunsch ein und wähle unter **Direktes Rezeptmodell** eine Variante. Für **Nano 3** führt **Gewählte lokale Modellvariante prüfen** zur AICore-Statusprüfung. Google führt das Pixel 9 Pro XL unter Nano 3. Die App zeigt den tatsächlich gemeldeten Modellnamen an, sobald AICore bereit ist. [Google-Geräteliste](https://developers.google.com/ml-kit/genai/#custom_generation_and_agent_device_support)
2. **Nano 4 Fast** benötigt ein Gerät mit dieser Modellvariante und Zugriff auf die AICore-Vorschau. Die App prüft die gewählte Variante. Wenn ein Systemdownload angeboten wird, braucht er eine eigene Bestätigung. [AICore Developer Preview](https://developers.google.com/ml-kit/genai/aicore-dev-preview)
3. Für **Gemini Cloud API** einen eigenen Schlüssel in der App privat speichern, die offizielle Modellliste prüfen und ein Modell auswählen. Cloud-Anfragen können je nach Google-Tarif Kosten verursachen. Die App verlangt nach der Vorschau für jede einzelne Anfrage eine ausdrückliche Freigabe. [Gemini API](https://ai.google.dev/gemini-api/docs/api-key)
4. Verwende **KI-Anfrage lokal vorbereiten**, lies die angezeigte Anfrage und tippe auf **Mit ausgewähltem Modell direkt generieren**. Prüfe danach jede Zutat im Entwurf. Speichern, Herstellen und Verzehr sind jeweils eigene Bestätigungen.

Die physische Abnahme erfordert für beide Nano-Varianten je eine echte Rezeptantwort mit gemeldetem Modellnamen und separat belegter Netzfreiheit während der Inferenz. Für Cloud braucht sie eine echte, einzeln freigegebene API-Antwort. Die bisherigen Android-Tests prüfen den Standard-UI-Pfad, tatsächliche SDK-Verfügbarkeit auf dem Emulator, lokalen Schlüsselschutz und Cloud-Zustimmung mit synthetischem Transport. [Originaler API-26-Gerätetest](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/45865750640/artifacts/15140259893)

## Mit einer anderen KI-App teilen

1. **Mehr → Rezepte & Küche → Rezeptvorschläge und JSON-Import** öffnen. Portionen, Ausschlüsse und bei Bedarf Rezeptvorgaben einstellen.
2. Optional den eigenen lokalen Bestand/Einkaufsbedarf auswählen und **Aktuellen lokalen Kontext prüfen** drücken. Angebote sind für den KI-Export gesperrt: eine Anzeigefreigabe ist keine Weitergabefreigabe.
3. **Rezept mit ChatGPT oder anderer KI entwerfen** öffnen und einen freien Rezeptwunsch eingeben. Optional einzelne Produktversionen hinzufügen. Die Auswahl ist zunächst leer; der übrige Katalog wird nicht angehängt.
4. **KI-Anfrage lokal vorbereiten** drücken und den vollständigen angezeigten Text prüfen. Bei gewähltem Kontext verlassen nur Zeilen der ausgewählten Produktversionen die App. Nicht übermittelte Daten bleiben unbekannt. Bons, private Preisgeschichte, Gesundheitskopie und Zugangsdaten werden nicht angehängt. Selbst eingegebene Wünsche und Ausschlüsse können persönliche Angaben enthalten.
5. Die Weitergabe ausdrücklich freigeben und **Anfrage mit KI-App teilen** drücken. Android öffnet seine App-Auswahl. Die App-Auswahl allein bestätigt keine Übertragung und keine erzeugte Antwort; die Anfrage in der gewählten KI-App gegebenenfalls noch absenden.
6. Von der KI ausschließlich das angeforderte Antwort-JSON ohne Markdown liefern lassen. **Antwort-JSON der KI einfügen** und **KI-Antwort lokal prüfen** drücken, oder eine selbst gespeicherte UTF-8-JSON-Datei über **KI-Antwortdatei auswählen** öffnen. Abbruch übernimmt nichts und erlaubt einen erneuten Versuch.
7. Die eingelesene Vorschau anschließend Zutat für Zutat unabhängig prüfen: aktuelle Produktversion, Menge/Einheit/Basis, RAW-/COOKED-Zustand, Lebensmittel-Ausschlüsse, Allergene und Ernährungsformen. Modellangaben sind keine Nachweise. Der vorhandene Prüf- und Gerätebestätigungsweg bleibt erforderlich.
8. Rezept speichern, Herstellung und tatsächlicher Verzehr bleiben getrennte ausdrückliche Aktionen. Eine Anfrage oder KI-Antwort bucht nichts.

Änderungen an Wunsch, Auswahl, Vorgaben oder gebundenem Kontext widerrufen die bisherige Exportfreigabe. **Unbestätigten Rezeptvorgang verwerfen** löscht auch Anfrage und Freigabe. Nach Neustart/Prozessverlust neu beginnen; Anfrage, Auswahl, Antwortformular und Exportfreigabe werden nicht automatisch wiederhergestellt. Die bestehende optionale verschlüsselte JSON-Vormerkung ist eine andere ausdrückliche Aktion und übernimmt keine Prüfbelege oder Freigabe.

Antworten müssen zum aktuellen Anfragebezug gehören und den gepinnten `recipe.draft`-Vertrag `1.0.0-draft` erfüllen. Die App prüft Anfragenummer, Zeitbezug, Portionen und Kontextflags sowie die bestehenden Frische-, Größen- und Inhaltsgrenzen. Dateien sind auf 262144 Byte begrenzt; fehlerhaftes UTF-8 wird nicht still ersetzt. Unbekannte Nährwerte bleiben unbekannt, bestätigte Ist-Buchungen darf kein Entwurf behaupten.

Automatisierte Tests verwenden einen klar benannten synthetischen Android-Empfänger und eine synthetische Antwortdatei. Das belegt den tatsächlichen Systemaustausch und die Sicherheitsgrenzen; es ist kein Nachweis einer echten Modellantwort oder einer vollständig abgenommenen Rezept-KI.

Der Gerätefall `RecipeAiLifecycleOnDeviceTest` ist auf API 26 tatsächlich ausgeführt worden. Er prüft das Teilen über die Android-App-Auswahl, den Import einer synthetischen Antwort, die Artikel- und Mengenprüfung, die Geräte-PIN, die gespeicherte Rezeptversion, die bestätigte Herstellung und den getrennt bestätigten Verzehr. Neustart, fehlendes Standardnetz und Schutz vor doppelten Buchungen sind Teil der Prüfung. [Originaler Gerätetest](https://github.com/ecora2003-max/Ernaerungsgedaechnis/actions/runs/45865750640/artifacts/15140259893)
