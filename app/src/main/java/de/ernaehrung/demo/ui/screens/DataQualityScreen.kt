package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*

@Composable
private fun rememberDataQualityModel(): DataQualityViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DataQualityViewModel(FoodStore(FoodDatabase.localSandbox(context))) as T
    } }
    return viewModel(key = "local-data-quality", factory = factory)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DataQualityScreen(onOpen: (DataQualityIssue) -> Unit) {
    val model = rememberDataQualityModel()
    val state by model.state.collectAsStateWithLifecycle()
    var categoryName by rememberSaveable { mutableStateOf(DataQualityCategory.PRODUCT.name) }
    val category = DataQualityCategory.valueOf(categoryName)
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose { }
    }
    Heading("Datenqualität", "Offene Angaben in deinen tatsächlich gespeicherten Daten")
    Text("Unbekannt bleibt unbekannt. Bekannte Nullwerte sind keine Datenlücke. Anzeigen und Prüfen erzeugen keine Buchung.")
    val snapshot = state.snapshot
    when {
        state.readError -> {
            Hint("Die lokalen Prüfdaten sind gerade nicht lesbar. Daraus lässt sich keine Vollständigkeit ableiten.")
            OutlinedButton({ model.refresh() }, Modifier.testTag("quality_retry")) { Text("Lokale Daten erneut prüfen") }
        }
        snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> {
            Text("Prüftag ${snapshot.checkedOn} · Europe/Berlin", Modifier.testTag("quality_checked_on"))
            Text("${snapshot.products} aktuelle Produkte · ${snapshot.activeBatches} Chargen mit aktivem Bestand · ${snapshot.knownDays} belegte Ernährungstage",
                Modifier.testTag("quality_scope"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DataQualityCategory.entries.forEach { item ->
                    val count = snapshot.issues.count { it.category == item }
                    FilterChip(category == item, { categoryName = item.name }, label = { Text("${item.label} · $count") },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("quality_category_${item.name}"))
                }
            }
            val selected = snapshot.issues.filter { it.category == category }
            Text("${category.label}: ${selected.size} offene Prüfpunkte", Modifier.testTag("quality_count_${category.name}"))
            if (selected.isEmpty()) Text("In diesem Prüfbereich sind derzeit keine offenen Angaben erkannt. Das ist keine vollständige Daten- oder Sicherheitsfreigabe.")
            selected.forEach { issue -> key(issue.id) {
                Panel {
                    Text(issue.title, Modifier.testTag("quality_issue_${issue.id}"), style = MaterialTheme.typography.titleMedium)
                    issue.details.forEach { Text(it) }
                    Text("Bezug: ${issue.reference}", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton({ onOpen(issue) }, Modifier.testTag("quality_open_${issue.id}")) { Text(issue.target.label) }
                }
            } }
            OutlinedButton({ model.refresh() }, Modifier.testTag("quality_refresh")) { Text("Erneut prüfen") }
        }
    }
    Text("Umfang: aktuelle Produktfassungen, aktive Aliasentscheidungen, offene Bons, positiver aktiver Lagerbestand, belegte Ernährungstage und aktuelle Rezeptmengen. Historische Verzehre behalten ihre ursprüngliche Artikelversion. Nicht erfasste Tage werden nicht erfunden; eine Tagesbestätigung schließt Nährwertlücken nicht automatisch. Medizinische Bewertung, Quellenrechte und reale Watch-Abnahme sind separate Prüfungen.")
}
