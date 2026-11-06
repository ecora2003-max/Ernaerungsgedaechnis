package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*

@Composable
internal fun PlanNutritionPreview(startsOn: String, endsOn: String) {
    Fold("Plan-Nährwerte aus gespeicherten Verzehrpositionen") {
        val context = LocalContext.current.applicationContext
        val factory = remember(context, startsOn, endsOn) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlanNutritionViewModel(FoodStore(FoodDatabase.localSandbox(context)), startsOn, endsOn) as T
        } }
        val model: PlanNutritionViewModel = viewModel(key = "plan-nutrition-$startsOn-$endsOn", factory = factory)
        val state by model.state.collectAsStateWithLifecycle()
        Text("Rechnerische Sollwerte der aktuellen bestätigten Planfassungen. Kochpositionen werden nicht als zusätzlicher Verzehr gezählt. Entwürfe und verworfene Fassungen gehören nicht zu dieser Vorschau.")
        Text("Die vollen geplanten Verzehrmengen bleiben auch nach einer Ist-Zuordnung als ursprüngliche Planung sichtbar. Sie werden weder zum tatsächlichen Ernährungstag addiert noch als verbleibender Einkaufsbedarf ausgegeben. Rezeptwerte beziehen sich auf Zutaten und Sollausbeute der gespeicherten Fassung; tatsächliche Herstellungsabweichungen separat prüfen.")
        if (state.readError) {
            Hint("Plan-Nährwerte gerade nicht lesbar. Fehlende Daten bleiben unbekannt.")
            OutlinedButton(model::retry) { Text("Plan-Nährwerte neu lesen") }
        } else if (state.report == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.report?.let { report ->
            Text("${report.startsOn} bis ${report.endsOn}", Modifier.testTag("plan_nutrition_scope"))
            if (report.days.isEmpty()) Text("Keine Verzehrpositionen aus aktuellen bestätigten Plänen in diesem Zeitraum. Das ist keine Aussage über die tatsächliche Ernährung.")
            if (report.preparationsExcluded > 0) Text("${report.preparationsExcluded} Kochpositionen nicht zusätzlich als Verzehr gezählt.",
                Modifier.testTag("plan_nutrition_preparations_excluded"))
            val weeks = report.weeks()
            weeks.forEach { week -> key(week.startsOn, week.endsOn) { Panel {
                Section("Planwoche ${week.startsOn} bis ${week.endsOn}",
                    "${week.days.size} Tage mit Verzehrpositionen im gewählten Ausschnitt")
                Text("Summe dieser Planpositionen. Fehlende Plantage bleiben offen; die Karte belegt keine vollständige Woche.")
                NutrientLines(week.nutrition, "plan_nutrition_week_${week.startsOn}")
                QualityNotes(week.nutrition)
                var showDays by remember { mutableStateOf(weeks.size == 1) }
                OutlinedButton({ showDays = !showDays }, Modifier.testTag("plan_nutrition_week_open_${week.startsOn}")) {
                    Text(if (showDays) "Plantage zuklappen" else "Plantage anzeigen")
                }
                if (showDays) week.days.forEach { day -> Panel {
                Section(day.date, "${day.lines.size} geplante Verzehrpositionen · keine Ist-Buchung")
                NutrientLines(day.nutrition, "plan_nutrition_day_${day.date}")
                QualityNotes(day.nutrition)
                if (day.goalVersion == null) Text("Für diesen Tag keine bestätigten persönlichen Zielbereiche.")
                else {
                    Text("Eigene Tagesvorgabe · Fassung ${day.goalVersion.revision} · gültig ab ${day.goalVersion.effectiveFrom}")
                    if (day.goals.isEmpty()) Text("Keine persönlichen Zielgrenzen vorgegeben.")
                    day.goals.forEach { range -> Text("${range.nutrient.label}: ${range.minimumText ?: "offen"} bis ${range.maximumText ?: "offen"} ${range.nutrient.unit}",
                        Modifier.testTag("plan_nutrition_goal_${day.date}_${range.nutrient.name}")) }
                    Text("Zum Einordnen der Planung: Gespeicherte Planpositionen belegen weder sämtliche Mahlzeiten noch die tatsächliche Aufnahme oder Zielerfüllung.")
                }
                Fold("Planpositionen und weitere Nährwerte · ${day.date}") {
                    NutrientLines(day.nutrition, "plan_nutrition_day_extra_${day.date}", extra = true)
                    day.lines.forEach { line ->
                        Section(line.name, "Planfassung ${line.planRevision} · Artikelfassung/Rezeptfassung ${line.referenceRevision ?: "unbekannt"}")
                        Text("Geplant: ${line.entry.amount?.let(Amounts::format) ?: "unbekannt"} ${line.entry.unit.label}")
                        NutrientLines(line.nutrition, "plan_nutrition_entry_${line.entry.id}")
                    }
                }
                } }
            } } }
        }
    }
}
