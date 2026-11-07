package de.ernaehrung.demo.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import de.ernaehrung.demo.data.local.RecipeVersionEntity
import de.ernaehrung.demo.ui.Fold

/** Versioned recipe guidance, independently of today's physical release. */
@Composable
internal fun RecipePreparationNotes(recipe: RecipeVersionEntity,scope: String) {
    Fold(if (scope == "production") "Gespeicherte Rezeptangaben vor der Herstellung prüfen" else "Zeiten und Aufbewahrung · ${recipe.name}") {
        Text("Aktive Zeit: ${recipe.activeMinutes?.let { "$it min" } ?: "unbekannt"} · Gesamtzeit: ${recipe.totalMinutes?.let { "$it min" } ?: "unbekannt"}",
            Modifier.testTag("recipe_preparation_${scope}_${recipe.id}"))
        Text("Aufbewahrungshinweise: ${recipe.storageInstructions?.takeIf(String::isNotBlank) ?: "unbekannt"}",
            Modifier.testTag("recipe_storage_${scope}_${recipe.id}"))
        Text("Angegebene Quelle: ${recipe.safetySource?.takeIf(String::isNotBlank) ?: "unbekannt"}",
            Modifier.testTag("recipe_safety_source_${scope}_${recipe.id}"))
        Text("Angaben dieser gespeicherten Rezeptfassung. Vor der tatsächlichen Herstellung passende Lagerbedingungen, Lebensmittel und Platz erneut prüfen.")
    }
}

internal fun optionalRecipeMinutes(text: String): Int? = if (text.isBlank()) null else
    text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Zubereitungszeit als ganze nichtnegative Minuten eingeben oder leer lassen.")
