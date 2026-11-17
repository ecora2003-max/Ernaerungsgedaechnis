# Verbindliche Codex-Fortsetzungsregel bei externen Livequellen-Blockaden

> **Ergänzende jüngste Quellenentscheidung 16.11.2026:** [RETAILER_PUBLIC_SOURCE_POLICY_2026-11-16.md](RETAILER_PUBLIC_SOURCE_POLICY_2026-11-16.md) hebt die pauschale Pflicht eines ausdrücklichen Händler-`APP_DISPLAY`-Genehmigungsbelegs auf. `UNVERIFIED` allein ist kein Verbot. Für konkrete öffentlich zugängliche Daten sind stattdessen Herkunft/Endpoint, Filial-/Regionscope, tatsächliche Zugriffszulässigkeit und Nutzungsbedingungen maßgeblich; keine Drittberechtigungen erfinden. Alle entgegenstehenden Aussagen weiter unten gelten insoweit als überholt.

**Nutzerentscheidung vom 16.11.2026. Gilt ab sofort vorrangig** vor älteren P0→P1-Abfolgen in `AGENTS.md`, Ausführungsplan, Roadmap und Issue #286. Sie ändert die Arbeitspriorisierung. Die ältere pauschale Händler-Genehmigungsnachweispflicht wird durch die ergänzende Quellenregel ersetzt; Datenschutz-, Vertrags-, Sicherheits- und andere Abnahmeregeln bleiben bestehen. Knuspr und Picnic bleiben DEFERRED. Ein einzelner Codex-Integrator schreibt ausschließlich in `ecora2003-max/Ernaerungsgedaechnis`; andere Repositories nur lesen. Keine alten A/B/III/IV-Automationen oder Lock-Dateien reaktivieren.

## 1. Blockaden korrekt klassifizieren

| Klassifikation | Beispiele | Verbindliche Reaktion |
| --- | --- | --- |
| **INTERNAL / umsetzbar** | Fehlender Android-Adapter, nicht verdrahteter Artikelreview, UI/Filialwahl, Testfälle, Produkt-/SKU-/Gebindezuordnung, Fehlerbehandlung oder Geräte-Testharness | **Selbstständig implementieren, testen und integrieren.** Keine externe Quellenfreigabe als Vorwand für Stillstand. |
| **EXTERNAL / nicht eigenständig freischaltbar** | Tatsächliche quellenbezogene Zugriffs-/Nutzungsbeschränkungen, reale Händler-/Filialendpoint-Berechtigung, fehlender authentifizierter Livezugang, nicht erreichbarer Anbieter, real fehlende AICore-/Watch-Hardware oder vom Nutzer bereitzustellende Cloud-Zugangsdaten | Exakt benennen, mit reproduzierbarem Beleg und fehlender Voraussetzung **BLOCKED** dokumentieren; keine Rechte behaupten oder erfundenen Live-PASS melden. Andere Arbeiten fortsetzen. |
| **ENTSCHEIDUNG / ausdrücklich autorisierungspflichtig** | Zahlung/Kosten, personenbezogene Daten nach außen senden, unwiderrufliche Aktionen, Vertrags-/Schemaänderung, Umgehung einer echten Zugriffs- oder Anbietergrenze | Nur hierfür gezielt die nötige Entscheidung einholen; parallel unabhängige Arbeit fortsetzen. |

**Wichtig:** `UNVERIFIED` und `RESTRICTED` sind dokumentierte Statuskategorien, keine automatisch feststehende allgemeine Rechtsaussage. Dass öffentliche Daten technisch abrufbar sind, beweist umgekehrt weder Verwertungsrecht noch App-Anzeigefreigabe. Die aktualisierte quellenbezogene Host-Policy gilt; keine universelle schriftliche Händlerfreigabe als Voraussetzung. Keine behaupteten Drittberechtigungen, kein massenhaftes Harvesting, keine 403/429/Cloudflare-Umgehung und kein ungeprüftes Einschalten produktiver Quellen. Neue, belastbare konkrete Belege nach bestehendem Verfahren prüfen; Grundsatzrecherchen ohne neue Fakten nicht wiederholen.

## 2. P0 technisch fertigstellen, unabhängig von Livefreigaben

1. Aktuellen Main, offene funktionale PRs, tatsächlichen Code und `docs/STATUS.md` prüfen; getestete Arbeit wiederverwenden, keine doppelte Entwicklung. Ausgangsstand 15.11.2026: PR #302/#303 integriert, 949 JVM / 57 API35 / 33 API26 erfolgreich; produktive `profiles: []` und Artikelreview `denyAll` sind **keine** positiven Live-Nachweise.
2. **Implementierbare P0-Lücken zuerst schließen:** den echten Host-Artikelreview-/Registry-Provider an die bestehende Angebots-/Preisvergleichsstrecke anschließen, SKU→Produktversion→Gebinde/Packungszahl/Bedingungen/Filialscope robust zuordnen; Mehrdeutigkeit, unbekannte Werte, Mengen-/Pfand-/Preisarten, Zeitgültigkeit und Widerruf korrekt behandeln. Port und UI so implementieren, dass echte autorisierte Profile später ohne Architekturumbau angeschlossen werden können.
3. Sämtliche acht Händler **einzeln** weiterbearbeiten: reale anhand zulässiger öffentlicher Quellen konfigurierte Profile aktivieren und live abnehmen; bei nicht verfügbaren Nachweisen Quellen auf BLOCKED belassen. Synthetische Quellen/Antworten ausschließlich in Tests und Test-APK, niemals als echte Preise im Produktions-APK. REWE/ALDI Süd gezielte Suchpreise nicht als vollständige Angebotslisten ausgeben.
4. Für alle unabhängig implementierbaren Teile JVM-/Android-/Neustart-/Offline-/Datenschutz-/Cache-/Vertrags-Regression ausführen. Bestätigte Tests/Belege nicht grundlos wiederholen. Produktive Feed-Freigabe und echte Liveartikel-/Geräteabnahme **separat** als offen markieren.

**P0-Ergebnis ohne Livezugang:** `P0_TECHNICALLY_READY_LIVE_BLOCKED` (nur nach tatsächlich geprüften implementierbaren Komponenten) – ausdrücklich **nicht** `P0_DONE`, `LIVE_VERIFIED` oder `INTEGRATED`.

## 3. Verbindlicher Wechsel zu P1 ohne Leerlauf

Sobald alle derzeit **ohne externe Voraussetzungen** umsetzbaren P0-Aufgaben abgeschlossen oder konkret als nicht weiterbearbeitbar belegt sind und kein zulässiger Livefeed aktivierbar ist, **sofort P1 Rezept-KI aufnehmen**, auch wenn P0 formal BLOCKED bleibt. Der bisherige Satz „P1 erst nach vollständigem P0-Liveabschluss“ gilt in dieser Situation nicht mehr.

P1 umfasst die drei ausdrücklich wählbaren Modi **Gemini Nano 4 Fast, Nano 3 und Gemini Cloud**. Zuerst die vorhandene Implementation (u. a. PR #289) auf wirkliche Vollständigkeit prüfen, fehlende Geräte-/API-/Status-/Abbruch-/Revalidierungs-/UI-Funktionen umsetzen und mit zulässigen Tests nachweisen. Fehlende physische Modellverfügbarkeit oder fehlende sichere Cloud-Konfiguration pro Modus separat BLOCKED deklarieren und die anderen Modi weiterbearbeiten. Keine Schlüssel in APK/Git, kein stiller Cloudfallback und keine ungefragte Datenübertragung oder Kosten.

Wenn auch P1 nur noch externe Abnahmen offen hat, nach Roadmap die nächsten unabhängig ausführbaren, priorisierten App-Funktionen fertigstellen; keine kosmetischen Nebenaufgaben zur Beschäftigung und keine neuen Scope-Ziele erfinden.

## 4. Eigenständige Ausführung und Stopbedingungen

- Keine pauschale „Freigabe“ vom Nutzer für routinemäßige Implementierung, Tests, sichere Branches/PRs oder Dokumentation verlangen. **Weder der Codex-Auftrag noch die Abwesenheit einer Händlergenehmigung sind eine zusätzliche interne Arbeitssperre.**
- In einer verfügbaren Codex-Sitzung zielgerichtet weiterarbeiten; **nicht nach einer bloßen Statusübergabe oder einem erfolgreich gemergten Teil-PR stoppen**, solange weitere unabhängige Arbeit möglich ist. Nutzungslimits, Sitzungslaufzeit und Tool-Grenzen gelten weiterhin; keine Arbeit im Hintergrund behaupten.
- Nach jedem abgeschlossenen Funktionsblock Commits/PR, exakt geprüfte Tests, Blockerklasse und **nächste unmittelbar ausführbare Codeaufgabe** in `docs/STATUS.md` knapp sichern. Funktionalen Fortschritt gegenüber wiederholten reinen Dokumentations-PRs priorisieren.
- Beenden nur bei (a) vollständig nachgewiesenen Abnahmekriterien des jeweils möglichen Scopes, (b) **konkret** ausgeschöpften unabhängig ausführbaren Aufgaben nach P0→P1→Roadmap, (c) echter ausschließlich extern auflösbarer Blockade oder (d) tatsächlichem Ausführungs-/Kontingentende. In (b)–(d) präzisen Wiederaufnahmeauftrag dokumentieren.
- Branch-/Merge-Sicherheit, CI auf exaktem Head, FoodStore-Buchungsgrenzen, Datenminimierung, Room-Migration und andere bestehende Sicherheitsanforderungen unverändert einhalten.

**Nächste konkrete Aufgabe:** Den produktiv noch fehlenden, aber sicher hinter Quellen-/Artikelgates betreibbaren Host-Artikelreview-Provider und die Android-Verdrahtung analysieren und implementieren; nach Belegung der technischen P0-Grenze unmittelbar zur Restintegration und Abnahme der drei Rezept-KI-Modi übergehen. Nicht allein wegen `profiles: []` stoppen.
