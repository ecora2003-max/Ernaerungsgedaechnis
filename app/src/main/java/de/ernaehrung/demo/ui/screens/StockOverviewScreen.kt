package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.*
import java.math.BigDecimal
import java.math.BigInteger

@Composable
internal fun StockOverviewScreen(state: LocalDataState,onOpenBatchReview: ((String) -> Unit)?) {
    var query by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var positiveOnly by rememberSaveable { mutableStateOf(false) }
    var usableOnly by rememberSaveable { mutableStateOf(false) }
    val date = AppCalendar.today().toString()
    val entries = remember(state.versions,state.batches,state.locations,date) {
        StockOverviewPreviews.build(state.versions,state.batches,state.locations,date)
    }
    val groups = remember(entries,query,location,positiveOnly,usableOnly) {
        StockOverviewPreviews.select(entries,query,location.takeIf(String::isNotEmpty),positiveOnly,usableOnly)
    }
    Section("Artikelbestände und Chargen","Genaue gespeicherte Fassungen · Prüfstand $date")
    OutlinedTextField(query,{ query = it },Modifier.fillMaxWidth().testTag("stock_overview_search"),
        label = { Text("Lager nach Artikel, Barcode, Charge oder Ort durchsuchen") },singleLine = true)
    IdPicker("Lagerort der Übersicht",listOf("" to "Alle Lagerorte") + state.locations.map {
        it.id to "${it.name}${if (it.confirmed) "" else " · geplant oder unbestätigt"}"
    },location) { location = it }
    Check("Nur verbleibende Mengen",positiveOnly) { positiveOnly = it }
    Check("Nur heute nach Bestandsregeln angerechnete Chargen",usableOnly) { usableOnly = it }
    Text("${groups.size} Artikelversionen · ${groups.sumOf { it.entries.size }} angezeigte Chargen",Modifier.testTag("stock_overview_count"))
    Text("Summen gelten nur für die angezeigten Chargen. Artikelversionen und Einheiten werden getrennt gehalten. Angerechneter Bestand ist keine neue Lebensmittel-, Allergen- oder Lagerfreigabe und keine Reservierung.")
    if (groups.isEmpty()) Text("Keine Charge entspricht dieser Suche und Auswahl. Filter prüfen; daraus folgt keine vollständige Erfassung aller Vorräte.")
    groups.forEach { group -> Panel(Modifier.testTag("stock_overview_group_${group.versionId}")) {
        Section(group.name,"Artikelversion ${group.revision ?: "unbekannt"} · ${group.unit.label}")
        Text("Physische Summe der angezeigten Chargen: ${group.physicalAmount?.let(::overviewAmount) ?: "ungeklärt"} ${group.unit.label}",
            Modifier.testTag("stock_overview_physical_${group.versionId}"))
        val known = group.usableKnownAmount?.let { "${overviewAmount(it)} ${group.unit.label}" } ?: "unbekannt"
        Text(if (group.unknownUsableCount == 0) "Heute nach Bestandsregeln angerechnet: $known" else
            "Heute angerechnete bekannte Teilsumme: $known; ${group.unknownUsableCount} Chargen ungeklärt",
            Modifier.testTag("stock_overview_usable_${group.versionId}"))
        group.entries.forEach { entry ->
            val row = entry.row
            Panel(Modifier.testTag("stock_batch_${row.batch.id}")) {
                Section("Charge ${row.batch.id.take(8)}",row.locationName)
                KV("Physisch vorhanden","${Amounts.format(row.stockAmount)} ${row.unit.label}")
                KV("Verwendungsstatus",when (row.batch.release) {
                    ReleaseStatus.UNCHECKED -> "Ungeklärt · keine automatische Freigabe"
                    ReleaseStatus.BLOCKED -> "Gesperrt"
                    ReleaseStatus.RELEASED -> "Vom Nutzer für diesen Test freigegeben"
                })
                entry.reason?.let { Text("Nicht angerechnet: $it") }
                KV("MHD",row.batch.bestBefore ?: "Unbekannt"); KV("Verbrauchsdatum",row.batch.useBy ?: "Unbekannt")
                Text("Charge: ${row.batch.id}",style = MaterialTheme.typography.labelSmall)
                onOpenBatchReview?.let { open -> Action("Chargenangaben prüfen",true,"stock_review_${row.batch.id}") { open(row.batch.id) } }
                if (row.originReversed) Badge("Herkunft storniert · nur Historie",Amber)
            }
        }
    } }
}

private fun overviewAmount(value: BigInteger): String = BigDecimal(value,3).stripTrailingZeros().toPlainString().replace('.',',')
