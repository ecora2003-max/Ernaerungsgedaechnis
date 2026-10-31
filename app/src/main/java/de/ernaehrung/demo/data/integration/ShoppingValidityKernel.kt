package de.ernaehrung.demo.data.integration

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Pure port of EG-Einkaufsintelligenz@a3e13b1 engine._valid/_scope_ok.
 * This is only a time/channel/store eligibility filter, NOT a source-rights grant,
 * current-price proof, product match, price comparison or booking authorization.
 * Callers must separately enforce the host retailer-capability and APP_DISPLAY gates.
 */
internal object ShoppingValidityKernel {
    data class Source(
        val dataQuality: String?, val rightsStatus: String?, val observedAt: String?
    )
    data class Scope(
        val retailerId: String?, val scopeType: String?, val storeId: String?
    )
    data class Observation(
        val priceKind: String?, val priceCents: Long?,
        val availability: String?, val conditional: Boolean,
        val source: Source?, val scope: Scope?,
        val validFrom: String?, val validUntil: String?,
        val shippingCents: Long?
    )
    data class Verdict(val eligible: Boolean, val notes: List<String>)

    private val priceKinds = setOf(
        "SALE", "CURRENT_DISPLAYED", "LISTED_REGULAR", "REFERENCE_OLD",
        "HISTORICAL_PAID", "ONLINE_LISTED", "UNKNOWN"
    )
    private val scopeTypes = setOf(
        "STORE_CONFIRMED", "REGION_CONFIRMED", "CHAIN_WIDE_REPORTED",
        "ONLINE", "UNKNOWN"
    )
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun day(value: String): LocalDate {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) {
            "date: YYYY-MM-DD required"
        }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("date: YYYY-MM-DD required", e)
        }
    }

    private fun instant(value: String): OffsetDateTime = try {
        OffsetDateTime.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("observedAt/purchaseAt: ISO-8601 offset required", e)
    }

    fun check(
        observation: Observation, shoppingDate: String, asOf: String,
        historical: Boolean = false, allowOnline: Boolean = false,
        allowedStores: Map<String, Set<String>>? = null
    ): Verdict {
        val requestedDay = day(shoppingDate)
        val cutoff = instant(asOf)
        val kind = observation.priceKind
        fun reject(reason: String) = Verdict(false, listOf(reason))
        if (kind !in priceKinds) return reject("unrecognized price kind")
        if (kind in setOf("REFERENCE_OLD", "HISTORICAL_PAID", "UNKNOWN"))
            return reject("reference/historical/unknown price cannot be a purchasable offer")
        if (observation.priceCents == null) return reject("price is unknown")
        if (observation.availability == "OUT_OF_STOCK_REPORTED") return reject("out of stock")
        if (observation.conditional)
            return reject("coupon/membership/multi-buy conditions unverified")
        val source = observation.source
        if (source?.dataQuality !in setOf("CONFIRMED", "SOURCED"))
            return reject("source quality unverified")
        if (source?.rightsStatus == "RESTRICTED") return reject("source rights restricted")
        val observedAt = source?.observedAt ?: return reject("observation timestamp unknown")
        val observed = instant(observedAt)
        if (observed.toInstant().isAfter(cutoff.toInstant()))
            return reject("observed after decision time")
        if (kind == "ONLINE_LISTED" && !allowOnline) return reject("online channel excluded")
        if (kind == "ONLINE_LISTED" && observation.shippingCents == null)
            return reject("online shipping costs unknown")
        if (kind == "SALE" && (observation.validFrom == null || observation.validUntil == null))
            return reject("sale dates incomplete")
        val start = observation.validFrom?.let(::day)
        val end = observation.validUntil?.let(::day)
        require(start == null || end == null || !start.isAfter(end)) {
            "validFrom after validUntil"
        }
        if ((start != null && requestedDay.isBefore(start)) ||
            (end != null && requestedDay.isAfter(end)))
            return reject("not valid on shopping date")
        if (kind != "SALE" && (start == null || end == null) &&
            observed.atZoneSameInstant(berlin).toLocalDate() != requestedDay)
            return reject("no dated validity proof for requested day")
        if (historical && kind == "ONLINE_LISTED")
            return reject("online checkout not proven at past store")

        val scope = observation.scope
        val retailer = scope?.retailerId
        val type = scope?.scopeType
        val store = scope?.storeId
        if (retailer.isNullOrEmpty() || type !in scopeTypes)
            return reject("unknown store/region scope")
        if (type == "STORE_CONFIRMED" && store.isNullOrEmpty())
            return reject("missing confirmed store ID")
        if (type == "UNKNOWN") return reject("unknown local store scope")
        if (allowedStores != null) {
            if (!allowedStores.containsKey(retailer))
                return reject("retailer $retailer not in accessible stores")
            if (type == "STORE_CONFIRMED" && store !in allowedStores.getValue(retailer))
                return reject("store $store not accessible")
        }
        if (historical && type != "STORE_CONFIRMED")
            return reject("historical alternative needs confirmed store")
        return Verdict(true, if (type == "STORE_CONFIRMED") emptyList()
            else listOf("chain/region scope is not a confirmed local shelf price"))
    }
}
