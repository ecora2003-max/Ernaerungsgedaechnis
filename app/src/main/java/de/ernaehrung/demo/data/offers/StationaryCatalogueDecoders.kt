package de.ernaehrung.demo.data.offers

import java.time.Instant
import java.math.BigInteger

/** Published source fields, never independent rights, article or price-condition
 * evidence. A displayed former price is not a numeric historical paid price. */
internal data class LivePriceDetails(val regularCents: Long? = null, val previousDisplay: String? = null,
    val appCents: Long? = null, val wishlistUntilLocal: String? = null, val brand: String? = null,
    val unitPriceDisplay: String? = null) {
    init {
        require(listOf(regularCents, appCents).all { it == null || it in 0L..100_000_000L })
        require(listOf(previousDisplay, wishlistUntilLocal, brand, unitPriceDisplay).all { it == null || it.length <= 1000 })
    }
}

/** EG-Preisdaten@5c52bf7 adapters/stationary_price_catalogues.py, blob ff44bc6.
 * Only supplied bounded query results; no pagination, IO, identity matching or
 * inferred validity/package/discount percentage. The host binds the request's
 * store separately and still checks TARGETED_PRODUCT_PRICE_QUERY/APP_DISPLAY. */
internal object StationaryCatalogueDecoders {
    private fun cents(value: Any?) = runCatching { OfferJson.integer(value) }.getOrNull()?.takeIf { it in 0L..100_000_000L }
    private fun id(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() && it.length <= 200 }
        is Long -> value.takeIf { it >= 0 }?.toString()
        is Int -> value.takeIf { it >= 0 }?.toString()
        is BigInteger -> value.takeIf { it.signum() >= 0 }?.toString()?.takeIf { it.length <= 200 }
        else -> null
    }
    private fun rows(raw: String, key: String): List<*> = (OfferJson.read(raw)[key] as? List<*>
        ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)).also { require(it.size <= 256) }

    fun rewe(raw: String, sourceId: String, storeId: String, at: Instant, url: String): List<LiveOffer> {
        require(storeId.isNotBlank())
        return rows(raw, "products").mapNotNull { item ->
            val p = OfferJson.objectValue(item) ?: return@mapNotNull null
            val price = OfferJson.objectValue(p["pricing"]) ?: return@mapNotNull null
            val current = cents(price["current"]) ?: return@mapNotNull null
            val regular = cents(price["regular"])
            val id = id(p["id"]) ?: return@mapNotNull null
            val title = OfferJson.text(p["title"]) ?: return@mapNotNull null
            val kind = when {
                regular == current -> LiveOfferKind.REGULAR
                regular != null && current < regular -> LiveOfferKind.PROMOTION
                else -> LiveOfferKind.UNKNOWN
            }
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.REWE, title, id, null,
                current, cents(price["refund"]), null, null, null, kind,
                null, null, null, at, storeId, null, null, LiveOfferApplicability.STORE_CONFIRMED,
                null, false, url, priceDetails = LivePriceDetails(regularCents = regular, brand = OfferJson.text(p["brand"])))
        }
    }

    fun aldiSued(raw: String, sourceId: String, storeId: String, at: Instant, url: String): List<LiveOffer> {
        require(storeId.isNotBlank())
        return rows(raw, "data").mapNotNull { item ->
            val p = OfferJson.objectValue(item) ?: return@mapNotNull null
            val price = OfferJson.objectValue(p["price"]) ?: return@mapNotNull null
            if (price["currencyCode"] != "EUR") return@mapNotNull null
            val current = cents(price["amountRelevant"]) ?: cents(price["amount"]) ?: return@mapNotNull null
            val id = (p["sku"] as? String)?.let(::id) ?: return@mapNotNull null
            val title = OfferJson.text(p["name"]) ?: return@mapNotNull null
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.ALDI_SUED, title, id, null,
                current, cents(price["bottleDeposit"]), null, null, null, LiveOfferKind.UNKNOWN,
                null, null, null, at, storeId, null, null, LiveOfferApplicability.STORE_CONFIRMED,
                null, false, url, priceDetails = LivePriceDetails(previousDisplay = (price["wasPriceDisplay"] as? String)
                    ?.takeIf { it.isNotBlank() && it.length <= 1000 },
                    brand = OfferJson.text(p["brandName"])))
        }
    }
}
