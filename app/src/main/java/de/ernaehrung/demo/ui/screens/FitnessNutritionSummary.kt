package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DayUiState

/** Only the shared diary's immutable read state crosses into Fitness. There is
 * no writer, new aggregate, cached nutrition truth or calorie-target operation.
 */
@Composable
internal fun FitnessNutritionSummary(requestedDay: String, daily: DayUiState, onOpen: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("fitness_nutrition_summary")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Deine erfasste Ernährung · $requestedDay", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("fitness_nutrition_day"))
            val report = daily.report?.takeIf { daily.date == requestedDay && it.date == requestedDay }
            when {
                daily.date == requestedDay && daily.error != null -> Text("Das lokale Ernährungstagebuch ist gerade nicht lesbar.")
                report == null -> Text("Ernährung für diesen Tag wird gelesen …")
                else -> {
                    Text(when (report.status) {
                        DayStatus.OPEN -> "Erfassung offen"
                        DayStatus.PARTIAL -> "Erfassung teilweise"
                        DayStatus.COMPLETE -> "Erfassung vollständig bestätigt"
                    }, Modifier.testTag("fitness_nutrition_status"))
                    Text("${report.meals.size} tatsächlich erfasste Verzehre", Modifier.testTag("fitness_nutrition_meals"))
                    val energy = report.totals[Nutrient.ENERGY]
                    Text("Erfasste Energie: ${nutrientText(Nutrient.ENERGY, energy)}", Modifier.testTag("fitness_nutrition_energy"))
                    Text("Nährwertbeiträge: ${energy.knownCount} bekannt · ${energy.missingCount} ungeklärt",
                        Modifier.testTag("fitness_nutrition_coverage"))
                    if (energy.qualities.isNotEmpty()) Text("Qualität der Energieangaben: ${energy.qualities.sortedBy { it.name }.joinToString { it.label }}")
                    if (report.meals.isEmpty()) Text("Keine Verzehre erfasst. Fehlende Angaben werden nicht zu Kalorien-Nullwerten.")
                    if (!energy.complete) Text("Die bekannte Teilsumme beschreibt keinen vollständig bekannten Tageswert.")
                }
            }
            Text("Aus deinem lokalen Ernährungstagebuch (Tagesbezug Europe/Berlin). Pläne, Rezeptentwürfe und Herstellung zählen erst nach separat bestätigtem Verzehr. Tagesbestätigung und bekannte Nährwerte sind getrennte Angaben.")
            Text("Kein Energiebedarf und keine Verrechnung mit Uhrkalorien. Ernährungsziele werden nicht geändert.")
            OutlinedButton(onOpen, Modifier.testTag("fitness_open_nutrition")) { Text("Diesen Tag im Ernährungstagebuch öffnen") }
        }
    }
}
