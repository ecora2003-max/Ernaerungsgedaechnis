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
                Text("Mit deiner ausdrücklichen Freigabe liest Ernährungsgedächtnis ausschließlich Schritte und Trainings aus Samsung Health über Health Connect. Die Quelldaten müssen in Samsung Health für Health Connect freigegeben sein.")
                Text("Gespeichert werden Werte, Zeiten, gelieferte UTC-Offsets, Quell-IDs, Paket, Geräteangaben und Änderungen. Samsung Health als Quellpaket belegt allein kein bestimmtes Uhrenmodell. Fehlende Werte bleiben unbekannt.")
                Text("Die Kopie bleibt lokal in der App. Die App überträgt diese Gesundheitsdaten nicht an einen Server und schreibt keine Gesundheitsdaten an Samsung Health oder Health Connect zurück. Es gibt keine automatische Ernährungs-, Bestands- oder Kalorienzielbuchung.")
                Text("Nach Freigabe wird beim Öffnen der Fitnessansicht ein begrenztes 28-Tage-Fenster aktualisiert. Es werden keine zusätzlichen Hintergrund-, älteren Historien-, Standort-, Herzfrequenz- oder Schreibrechte angefordert. Synchronisation der Quelle kann verzögert sein.")
                Text("Du kannst die Übernahme in der Fitnessansicht ausschalten, Leserechte in Health Connect entziehen und die lokale Gesundheitskopie einschließlich ihrer Änderungshistorie separat löschen. Deine Ernährung und Vorräte sowie die Daten in Samsung Health bleiben dabei erhalten. Automatische Backups und Exporte sind ausgeschaltet. Unter Daten & Sicherungen kannst du eine passwortverschlüsselte Datei ausdrücklich anlegen. Gesundheits-/Fitnesshistorien werden nur nach eigener Auswahl eingeschlossen. Die Wiederherstellung aktiviert keine Health-Connect-Übernahme oder Leserechte.")
                Text("Nach dem Löschen bleibt nur eine minimale lokale Wiederholungssperre ohne Gesundheitswerte oder Quelldatensatz-IDs erhalten. Sie verhindert, dass alte Übernahme- oder Löschaufrufe später erneut wirken.")
                Button({ finish() }) { Text("Schließen") }
            }
        } } }
    }
}
