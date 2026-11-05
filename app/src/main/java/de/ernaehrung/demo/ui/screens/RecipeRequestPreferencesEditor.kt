package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.integration.RecipeRequestPreferences
import de.ernaehrung.demo.data.integration.RecipeOfflineDraftGenerator
import de.ernaehrung.demo.data.local.Money
import de.ernaehrung.demo.ui.Fold
import java.math.BigDecimal

/** Unsaved host form. A JSON draft/checkpoint cannot supply these request facts. */
internal data class RecipePreferenceForm(
    val mealType: String = "", val minutes: String = "", val equipmentKnown: Boolean = false,
    val equipment: String = "", val preferredFoods: String = "", val budget: String = "",
    val freeze: String = "0", val freezerCapacity: String = "",
    val minimums: List<String> = List(7) { "" }, val maximums: List<String> = List(7) { "" }
) {
    fun preferences(): RecipeRequestPreferences {
        fun integer(text: String): Int? = text.trim().takeIf(String::isNotEmpty)?.let {
            require(it.matches(Regex("[0-9]{1,4}"))) { "Ganzzahl eingeben oder Feld leer lassen." }
            it.toInt()
        }
        fun decimal(text: String): BigDecimal? = text.trim().takeIf(String::isNotEmpty)?.let {
            require(it.matches(Regex("[0-9]{1,14}([,.][0-9]{1,6})?"))) { "Nichtnegative Zielwerte mit höchstens sechs Nachkommastellen eingeben." }
            BigDecimal(it.replace(',', '.'))
        }
        fun tokens(text: String) = text.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        require(minimums.size == 7 && maximums.size == 7)
        val targets = RecipeRequestPreferences.nutrientLabels.keys.mapIndexedNotNull { index, nutrient ->
            val min = decimal(minimums[index]); val max = decimal(maximums[index])
            if (min == null && max == null) null else RecipeRequestPreferences.Target(nutrient, min, max)
        }
        val cents = budget.trim().takeIf(String::isNotEmpty)?.let(Money::parse)
        return RecipeRequestPreferences(mealType.takeIf(String::isNotEmpty), integer(minutes),
            if (equipmentKnown) tokens(equipment) else null, targets, tokens(preferredFoods), cents,
            budgetConfirmed = cents != null, freezePortions = integer(freeze) ?: 0,
            freezerCapacityPortions = integer(freezerCapacity))
    }
}

@Composable
internal fun RecipeRequestPreferencesEditor(form: RecipePreferenceForm, onChange: (RecipePreferenceForm) -> Unit) {
    Fold("Vorgaben für den Rezeptvorschlag") {
        Text("Diese eigenen Vorgaben gelten für Vorschlag und spätere Prüfung. Sie werden nicht aus JSON übernommen und bleiben nur während dieses Vorgangs erhalten. Nach Wiederherstellung erneut eingeben. Unbekannte Angaben bleiben offen.")
        val meals = listOf("Nicht festgelegt", "Hauptmahlzeit", "Frühstück")
        val current = when (form.mealType) { "MAIN" -> meals[1]; "BREAKFAST" -> meals[2]; else -> meals[0] }
        WrappingChoices(meals, current, "recipe_pref_meal_") {
            onChange(form.copy(mealType = when (it) { meals[1] -> "MAIN"; meals[2] -> "BREAKFAST"; else -> "" }))
        }
        PreferenceField("recipe_pref_minutes", "Höchstens Minuten · optional", form.minutes) { onChange(form.copy(minutes = it)) }
        Check("Verfügbare Küchengeräte ausdrücklich angeben", form.equipmentKnown) { onChange(form.copy(equipmentKnown = it)) }
        if (form.equipmentKnown) {
            val equipment = form.equipment.split(',').filter(String::isNotBlank).toSet()
            listOf("HOB" to "Kochstelle verfügbar", "POT" to "Kochtopf verfügbar").forEach { (id, label) ->
                Check(label, id in equipment) { checked ->
                    onChange(form.copy(equipment = (if (checked) equipment + id else equipment - id).sorted().joinToString(",")))
                }
            }
            Text("Keine Auswahl bedeutet ausdrücklich keine verfügbaren Geräte.")
        }
        Fold("Bevorzugte Zutaten") {
            val foods = form.preferredFoods.split(',').filter(String::isNotBlank).toSet()
            RecipeOfflineDraftGenerator.ingredientTypes.forEach { ingredient ->
                Check("${ingredient.label} bevorzugen", ingredient.foodTypeId in foods) { checked ->
                    onChange(form.copy(preferredFoods = (if (checked) foods + ingredient.foodTypeId else foods - ingredient.foodTypeId)
                        .sorted().joinToString(",")))
                }
            }
        }
        PreferenceField("recipe_pref_budget", "Rezeptbudget in EUR · optional", form.budget) { onChange(form.copy(budget = it)) }
        Text("Ohne vollständige zulässige Preisdaten wird keine Budgeteinhaltung bestätigt. 0 EUR ist eine echte Nullgrenze.")
        PreferenceField("recipe_pref_freeze", "Davon einzufrierende Portionen", form.freeze) { onChange(form.copy(freeze = it)) }
        PreferenceField("recipe_pref_capacity", "Geprüfte freie Gefrierkapazität in Portionen · optional", form.freezerCapacity) { onChange(form.copy(freezerCapacity = it)) }
        Text("Kapazität ersetzt keine Prüfung von Kühlung, Behältern und Haltbarkeit bei der tatsächlichen Herstellung.")
        Fold("Eigene Nährwertbereiche je Portion") {
            Text("Freiwillige eigene Vorgaben, keine automatische Zielberechnung. Fehlende Nährwerte erlauben keine Aussage zur Einhaltung.")
            RecipeRequestPreferences.nutrientLabels.entries.forEachIndexed { index, (id, label) ->
                PreferenceField("recipe_pref_min_$id", "$label mindestens · optional", form.minimums[index]) {
                    onChange(form.copy(minimums = form.minimums.mapIndexed { i, value -> if (i == index) it else value }))
                }
                PreferenceField("recipe_pref_max_$id", "$label höchstens · optional", form.maximums[index]) {
                    onChange(form.copy(maximums = form.maximums.mapIndexed { i, value -> if (i == index) it else value }))
                }
            }
        }
    }
}

@Composable
private fun PreferenceField(tag: String, label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 160) onChange(it) },
        Modifier.fillMaxWidth().testTag(tag), label = { Text(label) }, maxLines = 2)
}
