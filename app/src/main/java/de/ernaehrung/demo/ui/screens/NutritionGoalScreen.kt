package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable
private fun rememberNutritionGoalModel(): NutritionGoalViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NutritionGoalViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    return viewModel(key = "personal-nutrition-goals", factory = factory)
}

private data class GoalForm(val previousId: String?, val key: String, val date: String,
    val reason: String = "", val minimums: Map<Nutrient, String> = emptyMap(), val maximums: Map<Nutrient, String> = emptyMap()) {
    fun input(): NutritionGoalDraftInput = NutritionGoalDraftInput(previousId, date, reason, Nutrient.entries.mapNotNull { nutrient ->
        val low = minimums[nutrient]?.takeIf(String::isNotBlank)
        val high = maximums[nutrient]?.takeIf(String::isNotBlank)
        if (low == null && high == null) null else NutritionGoalRange(nutrient, low, high)
    }).also { NutritionGoalRules.validate(it.ranges); WriteRules.date(it.effectiveFrom); require(it.reason.isNotBlank()) }
}
private data class GoalReview(val key: String, val token: String, val version: NutritionGoalVersionEntity,
    val binding: String, val ranges: List<NutritionGoalRange>)
private data class GoalEraseReview(val key: String, val token: String, val versions: Int)

@Composable
internal fun NutritionGoalScreen() {
    val model = rememberNutritionGoalModel()
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.saveState.collectAsStateWithLifecycle()
    val data = state.snapshot
    // These personal fields and both confirmations are intentionally never SavedState.
    var form by remember { mutableStateOf<GoalForm?>(null) }
    var review by remember { mutableStateOf<GoalReview?>(null) }
    var erasing by remember { mutableStateOf<GoalEraseReview?>(null) }
    LaunchedEffect(saving.outcome) { if (saving.outcome != null) { form = null; review = null; erasing = null } }
    Heading("Persönliche Ernährungsziele", "Vorschlag und bestätigte Vorgabe bleiben getrennt")
    Text("Eigene Bereiche pro Tag erfassen. Es sind keine Kalorien- oder Proteinwerte vorausgewählt. Die App berechnet hier keinen Energiebedarf und ändert persönliche Ziele niemals automatisch.")
    if (state.readError) {
        Hint("Persönliche Ziele gerade nicht lesbar. Keine unbekannten Ziele als Nullwerte ausgeben.")
        OutlinedButton(model::retry) { Text("Zielangaben neu lesen") }
    }
    if (data == null && !state.readError) LinearProgressIndicator(Modifier.fillMaxWidth())
    data?.let { snapshot ->
        val active = snapshot.active(AppCalendar.today().toString(), System.currentTimeMillis())
        Panel {
            Section("Heute gültige bestätigte Vorgabe", AppCalendar.today().toString())
            if (active == null) Text("Keine bestätigten Zielbereiche.", Modifier.testTag("nutrition_goals_active_empty"))
            else {
                Text("Fassung ${active.revision} · gültig ab ${active.effectiveFrom}", Modifier.testTag("nutrition_goals_active"))
                GoalRanges(snapshot.ranges(active.id), "nutrition_goals_active_")
            }
            Text("Unbestätigte Vorschläge aktivieren nichts. Künftige datierte Vorgaben gelten erst ab ihrem Datum; eine neue frühere Vorgabe hebt eine bereits geplante spätere Änderung nicht stillschweigend auf.")
        }
        Button(enabled = !saving.busy, modifier = Modifier.testTag("nutrition_goals_new"), onClick = {
            model.clearResult()
            val prior = snapshot.latest
            val ranges = prior?.let { snapshot.ranges(it.id) }.orEmpty()
            form = GoalForm(prior?.id, UUID.randomUUID().toString(), AppCalendar.today().toString(),
                minimums = ranges.mapNotNull { r -> r.minimumText?.let { r.nutrient to it } }.toMap(),
                maximums = ranges.mapNotNull { r -> r.maximumText?.let { r.nutrient to it } }.toMap())
        }) { Text("Neuen unbestätigten Zielvorschlag bearbeiten") }
        form?.let { current ->
            val stale = current.previousId != snapshot.latest?.id || state.readError
            if (stale) Hint("Zielstand inzwischen geändert. Eingaben bleiben erhalten; neu öffnen und den aktuellen Stand prüfen.")
            Field("Ziele gültig ab JJJJ-MM-TT", current.date, { form = current.copy(date = it) }, modifier = Modifier.testTag("nutrition_goals_date"))
            Field("Grund für diesen Zielvorschlag", current.reason, { form = current.copy(reason = it) }, modifier = Modifier.testTag("nutrition_goals_reason"))
            Text("Grenzen leer lassen bedeutet unbekannt. Eine ausdrückliche 0 bleibt Null. Alle Bereiche leer lassen und später separat bestätigen nimmt die eigenen Zielvorgaben ab diesem Datum zurück.")
            Nutrient.entries.forEach { nutrient ->
                Text("${nutrient.label} pro Tag · ${nutrient.unit}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Untergrenze (${nutrient.unit})", current.minimums[nutrient].orEmpty(),
                        { form = current.copy(minimums = current.minimums + (nutrient to it)) },
                        modifier = Modifier.weight(1f).testTag("nutrition_goals_min_${nutrient.name}"))
                    Field("Obergrenze (${nutrient.unit})", current.maximums[nutrient].orEmpty(),
                        { form = current.copy(maximums = current.maximums + (nutrient to it)) },
                        modifier = Modifier.weight(1f).testTag("nutrition_goals_max_${nutrient.name}"))
                }
            }
            val parsed = runCatching { current.input() }
            if (parsed.isFailure && current.reason.isNotBlank()) Text(parsed.exceptionOrNull()?.message ?: "Bereiche und Datum prüfen.")
            Button(enabled = !saving.busy && !stale && parsed.isSuccess,
                modifier = Modifier.testTag("nutrition_goals_save_proposal"), onClick = { model.propose(current.key, parsed.getOrThrow()) }) {
                Text("Unbestätigten Vorschlag lokal merken")
            }
            OutlinedButton({ form = null }, enabled = !saving.busy, modifier = Modifier.testTag("nutrition_goals_cancel_form")) { Text("Ungespeicherte Ziele verwerfen") }
        }
        val latest = snapshot.latest
        if (latest?.status == NutritionGoalRules.PROPOSED) {
            Panel {
                Section("Unbestätigter Vorschlag · Fassung ${latest.revision}", "Gültig ab ${latest.effectiveFrom} · noch nicht aktiv")
                GoalRanges(snapshot.ranges(latest.id), "nutrition_goals_proposed_")
                Button(enabled = !saving.busy, modifier = Modifier.testTag("nutrition_goals_review"), onClick = {
                    model.clearResult()
                    review = GoalReview(UUID.randomUUID().toString(), snapshot.token, latest,
                        snapshot.versionBinding(latest.id), snapshot.ranges(latest.id))
                }) { Text("Vorschlag separat prüfen und bestätigen") }
            }
        }
        snapshot.versions.sortedByDescending { it.revision }.forEach { row ->
            Fold("Zielhistorie · Fassung ${row.revision} · ${if (row.status == NutritionGoalRules.CONFIRMED) "bestätigt" else "Vorschlag"}") {
                Text("Gültig ab ${row.effectiveFrom} · ${row.reason}")
                Text("Selbst eingegeben · ${Instant.ofEpochMilli(row.recordedAt).atZone(ZoneId.of(row.zoneId))}")
                GoalRanges(snapshot.ranges(row.id), "nutrition_goals_history_${row.id}_")
                Text("Fassungskennung: ${row.id}")
            }
        }
        if (snapshot.versions.isNotEmpty()) OutlinedButton(enabled = !saving.busy,
            modifier = Modifier.testTag("nutrition_goals_erase"), onClick = {
                model.clearResult(); erasing = GoalEraseReview(UUID.randomUUID().toString(), snapshot.token, snapshot.versions.size)
            }) { Text("Persönliche Zielhistorie löschen") }
    }
    if (saving.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    saving.error?.let { Hint(it) }
    saving.outcome?.let { Text(if (it.erased) "Persönliche Zielwerte gelöscht. Keine Ziele reaktiviert." else
        if (saving.action == "CONFIRM") "Persönliche Zielbereiche ausdrücklich bestätigt; gültig ab dem geprüften Datum."
        else "Unbestätigten Zielvorschlag lokal gespeichert. Noch keine aktive Vorgabe.", Modifier.testTag("nutrition_goals_result")) }
    review?.let { frozen -> key(frozen.key) {
        var agreed by remember { mutableStateOf(false) }
        val stale = state.readError || data?.token != frozen.token
        LaunchedEffect(stale) { if (stale) agreed = false }
        AlertDialog(onDismissRequest = { if (!saving.busy) review = null }, modifier = Modifier.testTag("nutrition_goals_confirm_dialog"),
            title = { Text("Diese persönlichen Zielbereiche bestätigen") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Fassung ${frozen.version.revision} · ab ${frozen.version.effectiveFrom} · ${frozen.version.reason}")
                GoalRanges(frozen.ranges, "nutrition_goals_review_")
                if (stale) Text("Prüfstand geändert. Dialog schließen und neu prüfen.")
                Check("Diese Bereiche als eigene Vorgabe ausdrücklich bestätigen", agreed) { if (!stale && !saving.busy) agreed = it }
            } },
            confirmButton = { TextButton(enabled = agreed && !stale && !saving.busy,
                modifier = Modifier.testTag("nutrition_goals_confirm"), onClick = {
                    model.confirm(frozen.key, frozen.version.id, frozen.binding, agreed)
                }) { Text("Persönliche Ziele bestätigen") } },
            dismissButton = { TextButton({ review = null }, enabled = !saving.busy) { Text("Abbrechen") } })
    } }
    erasing?.let { frozen -> key(frozen.key) {
        var agreed by remember { mutableStateOf(false) }
        val stale = state.readError || data?.token != frozen.token
        LaunchedEffect(stale) { if (stale) agreed = false }
        AlertDialog(onDismissRequest = { if (!saving.busy) erasing = null }, modifier = Modifier.testTag("nutrition_goals_erase_dialog"),
            title = { Text("Zielhistorie unwiderruflich löschen") }, text = { Column {
                Text("${frozen.versions} Zielfassungen samt Werten und Gründen löschen. Ernährung, Vorrat, Körpermessungen und Training bleiben erhalten. Wertfreie Schutzmarker verhindern eine Wiederbelebung durch alte Wiederholungsversuche.")
                if (stale) Text("Löschumfang geändert. Neu prüfen.")
                Check("Diesen aktuellen Zielumfang unwiderruflich löschen", agreed) { if (!stale && !saving.busy) agreed = it }
            } }, confirmButton = { TextButton(enabled = agreed && !stale && !saving.busy,
                modifier = Modifier.testTag("nutrition_goals_erase_confirm"), onClick = { model.erase(frozen.key, frozen.token, agreed) }) { Text("Zielwerte löschen") } },
            dismissButton = { TextButton({ erasing = null }, enabled = !saving.busy) { Text("Abbrechen") } })
    } }
    Text("Eine bestätigte Vorgabe ist deine eigene Entscheidung, keine automatische Bedarfsermittlung. Quelle, Datenlücken und Schätzungen der Ernährung bleiben sichtbar. Zielwerte werden nicht automatisch an eine KI oder Gesundheitsquelle übertragen.")
}

@Composable
private fun GoalRanges(ranges: List<NutritionGoalRange>, prefix: String) {
    if (ranges.isEmpty()) Text("Keine persönlichen Zielgrenzen vorgegeben.", Modifier.testTag(prefix + "empty"))
    ranges.forEach { r -> Text("${r.nutrient.label}: ${r.minimumText ?: "offen"} bis ${r.maximumText ?: "offen"} ${r.nutrient.unit} pro Tag",
        Modifier.testTag(prefix + r.nutrient.name)) }
}

/** Reads the actual same diary day; unconfirmed intake or nutrient gaps cannot
 * become a completed shortfall/within-range assessment.
 */
@Composable
internal fun PersonalNutritionGoalSummary(day: DayNutrition) {
    val model = rememberNutritionGoalModel()
    val state by model.state.collectAsStateWithLifecycle()
    Panel {
        Section("Persönliche Zielbereiche", "Vorschläge zählen nicht als bestätigte Vorgabe")
        val snapshot = state.snapshot
        if (state.readError) {
            Text("Zielvergleich gerade nicht verfügbar.")
            OutlinedButton(model::retry) { Text("Zielvergleich neu lesen") }
        }
        else if (snapshot == null) Text("Persönliche Ziele werden lokal gelesen.")
        else {
            val active = snapshot.active(day.date, System.currentTimeMillis())
            if (active == null) Text("Für diesen Tag keine bestätigten persönlichen Ziele.", Modifier.testTag("nutrition_day_goals_empty"))
            else {
                Text("Fassung ${active.revision} · gültig ab ${active.effectiveFrom}", Modifier.testTag("nutrition_day_goals_version"))
                val ranges = snapshot.ranges(active.id)
                GoalRanges(ranges, "nutrition_day_goal_")
                ranges.forEach { range ->
                    val value = day.totals[range.nutrient]
                    val result = NutritionGoalRules.assess(range, value, day.status)
                    val label = when (result) {
                        NutritionGoalRules.Result.UNKNOWN -> "Ist-Wert unbekannt; keine Zielbewertung."
                        NutritionGoalRules.Result.INCOMPLETE -> "Erfassung oder Nährwerte unvollständig; kein abschließender Vergleich."
                        NutritionGoalRules.Result.KNOWN_PART_ABOVE -> "Schon der bekannte Teilwert liegt über der eigenen Obergrenze. Tagesgesamtwert bleibt unvollständig."
                        NutritionGoalRules.Result.BELOW -> "Rechnerisch unter der eigenen Untergrenze."
                        NutritionGoalRules.Result.WITHIN -> "Rechnerisch im eigenen bestätigten Bereich."
                        NutritionGoalRules.Result.ABOVE -> "Rechnerisch über der eigenen Obergrenze."
                    }
                    Text("${range.nutrient.label}: $label", Modifier.testTag("nutrition_day_goal_result_${range.nutrient.name}"))
                    if (value.qualities.any { it != Quality.CONFIRMED }) Text("Nährwertquellen: ${value.qualities.joinToString { it.label }}. Bestätigte Ziele machen Schätzungen nicht zu Messwerten.")
                }
            }
        }
    }
}
