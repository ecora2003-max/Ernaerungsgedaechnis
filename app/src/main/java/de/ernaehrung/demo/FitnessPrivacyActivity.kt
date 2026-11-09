package de.ernaehrung.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.ui.DemoTheme

/** Public permission rationale only. Never shows personal records or accepts an action. */
class FitnessPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoTheme { Surface {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Gesundheitsverbindung · Datenschutz", style = MaterialTheme.typography.headlineSmall)
                Text("Mit deiner ausdrücklichen Basisfreigabe liest Ernährungsgedächtnis Schritte und Trainings aus Samsung Health über Health Connect. Die Quelldaten müssen dort für Health Connect freigegeben sein.")
                Text("Unter Weitere Gesundheitsdaten kannst du zusätzlich Herzfrequenz, Distanz, Geschwindigkeit, Aktivenergie, Gesamtenergie, Körperfettanteil und Grundumsatzrate einzeln wählen. Diese getrennte Freigabe liest die gewählten Typen aus allen von Health Connect freigegebenen Quellen, zum Beispiel Telefon, Uhr oder Waage. Optional kann Health Connect einen gemeinsamen Schritt-Tageswert liefern; überlappende Rohintervalle werden nicht addiert. Datenquellen und Energiearten bleiben getrennt. Grundumsatz ist eine Quellrate und keine TDEE-Schätzung.")
                Text("Gespeichert werden Werte, Zeiten, gelieferte UTC-Offsets, Quell-IDs, Paket, Geräteangaben und Änderungen. Samsung Health als Quellpaket belegt allein kein bestimmtes Uhrenmodell. Fehlende Werte bleiben unbekannt.")
                Text("Die Kopie bleibt lokal in der App. Die App überträgt diese Gesundheitsdaten nicht an einen Server und schreibt keine Gesundheitsdaten an Samsung Health oder Health Connect zurück. Es gibt keine automatische Ernährungs-, Bestands- oder Kalorienzielbuchung.")
                Text("Die Basisübernahme aktualisiert beim Öffnen der Fitnessansicht ein begrenztes 28-Tage-Fenster. Die zusätzliche Übernahme aktualisiert nur beim Öffnen ihrer Ansicht die gespeicherte Auswahl (7, 14, 28, 42 oder 90 Kalendertage bis zum gewählten Enddatum). Für ältere Zeiträume sind eine eigene Historieneinwilligung und ein unterstütztes Health-Connect-Historienleserecht erforderlich. Ansonsten werden nur die Leserechte der ausgewählten Typen angefordert. Keine Hintergrund-, Standort- oder Schreibrechte. Synchronisation der Quelle kann verzögert sein; Originalintervalle mit Ende nach dem Lesestand bleiben zurückgestellt.")
                Text("Du kannst beide Übernahmen getrennt ausschalten und Leserechte in Health Connect entziehen. In Weitere Gesundheitsdaten löschst du nur die Zusatzkopie samt Änderungshistorie; die Basisfreigabe bleibt erhalten. In der Fitnessansicht kannst du beide Health-Connect-Kopien gemeinsam löschen und ausschalten. Deine Ernährung, Vorräte, manuellen Körpermessungen und Trainings sowie die Quelldaten bleiben erhalten. Automatische Backups und Exporte sind ausgeschaltet. Unter Daten & Sicherungen kannst du eine passwortverschlüsselte Datei ausdrücklich anlegen. Gesundheits-/Fitnesshistorien werden nur nach eigener Auswahl eingeschlossen. Die Wiederherstellung aktiviert keine Health-Connect-Übernahme oder Leserechte.")
                Text("Nach dem Löschen bleibt nur eine minimale lokale Wiederholungssperre ohne Gesundheitswerte oder Quelldatensatz-IDs erhalten. Sie verhindert, dass alte Übernahme- oder Löschaufrufe später erneut wirken.")
                Button({ finish() }) { Text("Schließen") }
            }
        } } }
    }
}
