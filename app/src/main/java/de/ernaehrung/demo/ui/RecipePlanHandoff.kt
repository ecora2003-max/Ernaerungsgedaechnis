package de.ernaehrung.demo.ui

/** RAM-only selection for a new plan form. No entry or actual event is created. */
internal data class RecipePlanPrefill(val requestId: String,val recipeVersionId: String,val name: String)
internal data class RecipePlanHandoffUiState(val request: RecipePlanPrefill? = null,val error: String? = null)
