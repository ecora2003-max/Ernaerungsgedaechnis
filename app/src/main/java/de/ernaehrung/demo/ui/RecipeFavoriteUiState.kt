package de.ernaehrung.demo.ui

import de.ernaehrung.demo.data.local.RecipeFavoriteSnapshot

internal data class RecipeFavoriteUiState(val snapshot: RecipeFavoriteSnapshot? = null,val readError: Boolean = false)
internal data class RecipeFavoriteSaveState(val busy: Boolean = false,val message: String? = null,val error: String? = null)
