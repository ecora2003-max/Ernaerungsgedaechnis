package de.ernaehrung.demo.ui

/** An interpreted item in a new, unconfirmed manual purchase; never payment facts. */
internal data class ShoppingPurchasePrefill(val requestId: String,val versionId: String,val name: String)
internal data class ShoppingPurchaseHandoffUiState(val request: ShoppingPurchasePrefill? = null,val error: String? = null)
