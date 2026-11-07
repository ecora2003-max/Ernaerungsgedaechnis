package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ernaehrung.demo.ui.*

/** Same FoodStore and physical review as the kitchen. No plan reservation,
 * mass-to-volume conversion or predicted release of a future container.
 */
@Composable
internal fun PlanStorageCapacityPreview(model: LocalDataViewModel, onOpen: ((String?) -> Unit)?) {
    Fold("Lagerraum für die Planung") {
        val state by model.storageCapacity.collectAsStateWithLifecycle()
        Text("Physische Prüfstände als Entscheidungshilfe für Kochtermine und Meal Prep. Zutatengewicht und Portionszahl bestimmen kein Behälter-Außenvolumen. Vor der Herstellung Platz und Bedingungen erneut vor Ort prüfen.")
        Text("Diese Übersicht reserviert keinen Lagerplatz. Geplanter Verzehr oder Entsorgung belegt kein tatsächlich ausgeräumtes Gefäß.")
        if (state.readError) {
            Hint("Lagerprüfungen gerade nicht lesbar. Freier Platz bleibt unbekannt.")
            OutlinedButton(model::retryStorageCapacity) { Text("Lagerprüfungen neu lesen") }
        } else if (state.snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.snapshot?.let { snapshot ->
            if (snapshot.locations.isEmpty()) {
                Text("Noch keine Lagerorte erfasst. Kapazität und Lagerbedingungen sind unbekannt.")
                if (onOpen != null) OutlinedButton({ onOpen(null) }) { Text("Lagerorte und Kapazität prüfen") }
            }
            snapshot.locations.sortedBy { it.name }.forEach { place -> Panel {
                Section(place.name,if (place.confirmed) "Physisch vorhandener Lagerort" else "Geplanter oder ungeprüfter Lagerort")
                if (!place.confirmed) Text("Kapazität und Nutzbarkeit unbekannt.",Modifier.testTag("plan_storage_planned_${place.id}"))
                else {
                    val latest = snapshot.history.filter { it.locationId == place.id }.maxByOrNull { it.revision }
                    if (latest == null) Text("Noch keine physische Kapazitätsprüfung. Freier Platz unbekannt.",Modifier.testTag("plan_storage_unchecked_${place.id}"))
                    else CapacityFacts(latest,snapshot,"plan_storage_${place.id}_")
                }
                if (onOpen != null) OutlinedButton({ onOpen(place.id) },Modifier.testTag("plan_storage_open_${place.id}")) {
                    Text("Lagerdaten dieses Ortes prüfen")
                }
            } }
        }
    }
}
