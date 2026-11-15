package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.offers.*
import java.time.LocalDate

/** Synthetic, release-host profile documents. Test source sets only; no real
 * retailer approval, branch discovery or live response is represented here. */
internal object ReviewedOfferSourceFixtures {
    fun row(retailer: LiveRetailer, day: LocalDate, store: String? = "DE123",
        id: String = "synthetic-${retailer.name}-$store"): Map<String, Any?> {
        val format = when (retailer) {
            LiveRetailer.LIDL -> PublishedOfferHttpSource.Format.LIDL_STORE_V4
            LiveRetailer.EDEKA -> PublishedOfferHttpSource.Format.EDEKA_MARKET
            LiveRetailer.PENNY -> PublishedOfferHttpSource.Format.PENNY_REGION
            LiveRetailer.ALDI_NORD -> PublishedOfferHttpSource.Format.ALDI_NORD_NEXT_DATA
            LiveRetailer.KAUFLAND -> PublishedOfferHttpSource.Format.KAUFLAND_STORE
            LiveRetailer.REWE -> PublishedOfferHttpSource.Format.REWE_STATIONARY
            LiveRetailer.ALDI_SUED -> PublishedOfferHttpSource.Format.ALDI_SUED_WALK_IN
            LiveRetailer.NETTO_MD -> PublishedOfferHttpSource.Format.NETTO_BRANCH_HTML
            LiveRetailer.KNUSPR -> error("Knuspr has a separate connection contract")
        }
        val endpoint = when (retailer) {
            LiveRetailer.REWE -> "https://www.rewe.de/api/stationary-product-search/products?query=synthetic&wwIdent=$store"
            LiveRetailer.ALDI_SUED -> "https://api.aldi-sued.de/v3/product-search?q=synthetic&servicePoint=$store&serviceType=walk-in&limit=12&offset=0"
            LiveRetailer.NETTO_MD -> "https://www.netto-online.de/angebote/c-N07"
            else -> "https://offers.example.test/${retailer.name}/$store"
        }
        return linkedMapOf("id" to id, "revision" to 1, "label" to "Synthetische Filiale $store", "retailer" to retailer.name,
            "storeId" to store, "regionId" to if (retailer == LiveRetailer.PENNY) "synthetic-region" else null,
            "format" to format.name, "sourceId" to id, "endpoint" to endpoint,
            "availabilityEndpoint" to if (retailer == LiveRetailer.KAUFLAND) "https://availability.example.test/$store" else null,
            "weekStartsOn" to if (retailer == LiveRetailer.PENNY) day.with(java.time.DayOfWeek.MONDAY).toString() else null,
            "approval" to linkedMapOf("appDisplayEvidence" to "https://review.example.test/synthetic-app-display",
                "scopeEvidence" to "https://review.example.test/synthetic-scope-$store", "reviewedAt" to "${day.minusDays(1)}T00:00:00Z",
                "from" to day.toString(), "until" to day.plusDays(6).toString(),
                "allowedHosts" to listOf(java.net.URI(endpoint).host) + if (retailer == LiveRetailer.KAUFLAND) listOf("availability.example.test") else emptyList<String>(),
                "cachePermitted" to true, "minimumRefreshSeconds" to 3600))
    }
    fun document(vararg rows: Map<String, Any?>) = JsonOutput.encode(mapOf("format" to "eg-reviewed-published-sources-1", "profiles" to rows.toList()))
}
