package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.time.LocalDate

/** Synthetic documents for failure/regression tests only. Production profile
 * metadata and the opt-in live test never read these fixtures. */
internal object PennyPublicSourceFixtures {
    const val STORE = "9999001"
    const val SOURCE = "synthetic-penny-store-top"
    fun markets(next: String = "synthetic-next") = """[{"wwIdent":"unrelated","marketName":"Other","ignored":{"nested":[1,true,null]}},{"wwIdent":"$STORE","marketName":"Synthetic PENNY","streetWithHouseNumber":"Teststraße 1","zipCode":"00000","city":"Teststadt","sellingRegion":"synthetic-current","nextWeekSellingRegion":"$next"}]"""
    fun offers() = """{"offerTiles":[{"uuid":"synthetic-a","title":"Synthetic first offer","price":"1.59","quantity":"je 250 g","basePrice":"(1 kg = 6.36)","originalPrice":"3.29","originalPriceType":"uvp","linkHref":"/angebote/top-angebote/synthetic-first"},{"uuid":"synthetic-zero","title":"Synthetic explicit zero","price":"0.00"},{"uuid":"synthetic-unknown","title":"Synthetic unknown price","price":null},{"uuid":"synthetic-web-only","title":"Synthetic online price","price":"0.99","onlyOnline":true}]}"""
    fun configuration(day: LocalDate, source: String = SOURCE,
        vararg additionalSources: Map<String, Any?>): ReviewedOfferSources {
        val row = ReviewedOfferSourceFixtures.row(LiveRetailer.PENNY, day, STORE, source).toMutableMap()
        row["format"] = PublishedOfferHttpSource.Format.PENNY_STORE_WEEK.name
        row["endpoint"] = "https://www.penny.de/.rest/offers/by-category/"
        row["availabilityEndpoint"] = "https://www.penny.de/.rest/market"
        row["regionId"] = null; row["weekStartsOn"] = null
        val approval = requireNotNull(OfferJson.objectValue(row["approval"])).toMutableMap()
        approval.remove("appDisplayEvidence"); approval["sourceEvidence"] = "https://review.example.test/synthetic-source"
        approval["allowedHosts"] = listOf("www.penny.de"); approval["cachePermitted"] = false
        row["approval"] = approval
        return ReviewedOfferSources.decode(ReviewedOfferSourceFixtures.document(row, *additionalSources))
    }
}
