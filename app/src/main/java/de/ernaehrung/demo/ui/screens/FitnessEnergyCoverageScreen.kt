package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.data.local.AppCalendar
import de.ernaehrung.demo.data.local.DayStatus
import de.ernaehrung.demo.data.local.EnergyCoveragePeriod
import de.ernaehrung.demo.data.local.FoodDatabase
import de.ernaehrung.demo.data.local.FoodStore
import de.ernaehrung.demo.data.local.Nutrient
import de.ernaehrung.demo.ui.Fold
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FitnessEnergyCoverageScreen(onOpenDiary: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { FoodStore(FoodDatabase.localSandbox(context)) }
    val today = AppCalendar.today()
    var periodName by rememberSaveable { mutableStateOf(EnergyCoveragePeriod.DAYS_28.name) }
    val period = EnergyCoveragePeriod.valueOf(periodName)
    val flow = remember(store, today, period) {
        store.watchFitnessEnergyCoverage(today, period).map { Result.success(it) }.catch { emit(Result.failure(it)) }
    }
    val state by flow.collectAsStateWithLifecycle(initialValue = null)
    Column(Modifier.fillMaxWidth().testTag("fitness_energy_coverage"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Datenlage für die Energieanalyse", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnergyCoveragePeriod.entries.forEach { choice ->
                FilterChip(period == choice, { periodName = choice.name }, label = { Text("${choice.days} Tage") },
                    modifier = Modifier.testTag("fitness_energy_period_${choice.days}"))
            }
        }
        val days = state?.getOrNull()?.takeIf { it.size == period.days && it.first().date == today }
        when {
            state?.isFailure == true -> Text("Die lokale Datenlage ist gerade nicht lesbar.")
            days == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                Text("${days.last().date} bis ${days.first().date} · Europe/Berlin")
                Text("${days.count { it.completeIntake }} von ${period.days} Tagen mit bestätigter und vollständiger Ernährungsenergie",
                    Modifier.testTag("fitness_energy_food_count"))
                Text("${days.count { it.weightMeasurements > 0 }} Tage mit bekannten, einbezogenen Gewichtsmessungen")
                Text("${days.count { it.activitySources.isNotEmpty() }} Tage mit Aktivenergie-Daten")
                Text("Gewichtsmessungen zählen am ursprünglichen Messtag. Ein Aktivenergie-Intervall kann einen Teil des Tages abdecken.")
                Fold("Tagesdetails") {
                    days.forEach { item ->
                        Text(item.date.toString(), style = MaterialTheme.typography.titleSmall)
                        val diary = when (item.diaryStatus) {
                            DayStatus.OPEN -> "Erfassung offen"
                            DayStatus.PARTIAL -> "Erfassung teilweise"
                            DayStatus.COMPLETE -> "Erfassung vollständig bestätigt"
                        }
                        Text("$diary · ${nutrientText(Nutrient.ENERGY, item.foodEnergy)}",
                            Modifier.testTag("fitness_energy_food_${item.date}"))
                        Text("Einbezogene Gewichtsmessungen: ${item.weightMeasurements}")
                        Text("Aktivenergie-Quellen: ${item.activitySources.joinToString().ifEmpty { "keine lokalen Intervalle" }}")
                        TextButton(onClick = { onOpenDiary(item.date.toString()) },
                            modifier = Modifier.testTag("fitness_energy_diary_${item.date}")) { Text("Ernährungstag öffnen") }
                    }
                }
            }
        }
    }
}
