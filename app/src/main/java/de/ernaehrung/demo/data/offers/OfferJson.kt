package de.ernaehrung.demo.data.offers

import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import de.ernaehrung.demo.data.local.AppCalendar
import de.ernaehrung.demo.data.local.Money
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

private val quantityRate = Regex("je\\s+(?:[0-9]+(?:[,.][0-9]+)?\\s*)?(?:kg|g|l|ml)", RegexOption.IGNORE_CASE)

/** A price for a weight or volume is not a price for a purchasable pack. */
internal fun publicQuantityRate(label: String?): Boolean = label?.let(quantityRate::matches) == true

/** Common strict JSON mechanics, not an invented remote supplier schema.
 * Concrete adapters select their own pinned source fields. Unknown stays null. */
internal object OfferJson {
    fun read(text: String): Map<String, Any?> = RecipeDraftBoundedJson.decode(text,
        RecipeDraftBoundedJson.Limits(maxChars = 1_048_576, maxDepth = 24, maxNodes = 50_000))

    /** The public Next.js page puts the entire offer document in one escaped
     * apiData string. Only the outer retailer envelope needs the larger limit. */
    fun readAldiNordEnvelope(text: String): Map<String, Any?> = RecipeDraftBoundedJson.decode(text,
        RecipeDraftBoundedJson.Limits(maxChars = 1_048_576, maxDepth = 24, maxNodes = 50_000,
            maxStringChars = 786_432))
    fun text(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 }
    fun objectValue(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *> || value.keys.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    fun integer(value: Any?): Long? = when (value) {
        is BigInteger -> value.also { require(it.bitLength() <= 63) { "Integer exceeds supported range" } }.toLong()
        is Long -> value
        is Int -> value.toLong()
        else -> null // Do not coerce strings, fractions, booleans or null into cents.
    }
    fun euroCents(value: Any?): Long? {
        val decimal = when (value) {
            is BigDecimal -> value
            is BigInteger -> value.toBigDecimal()
            is Int -> value.toBigDecimal()
            is Long -> value.toBigDecimal()
            is String -> {
                val clean = value.trim().removeSuffix("€").trim()
                if (!clean.matches(Regex("[0-9]+([,.][0-9]{1,2})?"))) return null
                clean.replace(',', '.').toBigDecimalOrNull() ?: return null
            }
            else -> return null
        }
        return runCatching { decimal.movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it in 0..1_000_000_000_000L }
    }
    fun date(value: Any?): LocalDate? = (value as? String)?.let { raw ->
        runCatching { LocalDate.parse(raw).takeIf { it.toString() == raw } }.getOrNull()
    }
    fun instant(value: Any?): Instant? = (value as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

/** Pinned EG-Preisdaten@5c52bf7 adapters/retailer_offers.py source shapes.
 * No HTTP, source permissions, package inference, host identity or booking here. */
internal object PublishedOfferDecoders {
    fun edeka(raw: String, sourceId: String, marketId: String, fetchedAt: Instant, sourceUrl: String): List<LiveOffer> {
        require(marketId.matches(Regex("[0-9]+")))
        val root = OfferJson.read(raw)
        val docs = root["docs"] as? List<*> ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        require(docs.size <= 256)
        val boundToStore = root["national"] == false
        val rootFrom = date(root["gueltig_von"]); val rootUntil = date(root["gueltig_bis"])
        return docs.mapNotNull { value ->
            val row = OfferJson.objectValue(value) ?: return@mapNotNull null
            val id = OfferJson.text(row["angebotid"]) ?: OfferJson.text(row["id"])
                ?: OfferJson.integer(row["angebotid"])?.toString() ?: return@mapNotNull null
            val title = OfferJson.text(row["titel"]) ?: return@mapNotNull null
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.EDEKA, title, id, null,
                OfferJson.euroCents(row["preis"]), null, null, null, null, LiveOfferKind.PROMOTION,
                date(row["gueltig_von"]) ?: rootFrom, date(row["gueltig_bis"]) ?: rootUntil,
                null, fetchedAt, marketId.takeIf { boundToStore }, null, null,
                if (boundToStore) LiveOfferApplicability.STORE_CONFIRMED else if (root["national"] == true) LiveOfferApplicability.CHAIN_REPORTED else LiveOfferApplicability.UNKNOWN,
                OfferJson.text(row["beschreibung"]), false, sourceUrl, OfferJson.text(row["beschreibung"]))
        }
    }

    fun lidl(raw: String, sourceId: String, storeKey: String, fetchedAt: Instant, sourceUrl: String,
        market: LiveStoreScopeEvidence? = null): List<LiveOffer> {
        require(storeKey.matches(Regex("DE[0-9]+")))
        val root = OfferJson.read(raw); val rows = root["offers"] as? List<*>
            ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        require(rows.size <= 256)
        val total = OfferJson.integer(root["totalOffers"])
        if (total != null || market != null) require(total == rows.size.toLong())
        return rows.mapNotNull { value ->
            val row = OfferJson.objectValue(value) ?: return@mapNotNull null
            if (row["offerType"] != "StoreSpecialPriceDiscount" || row["category"] != "Store" ||
                (row["redemptionChannel"] != null && row["redemptionChannel"] != "Store")) return@mapNotNull null
            val id = OfferJson.text(row["id"]) ?: return@mapNotNull null
            val title = OfferJson.text(row["title"]) ?: return@mapNotNull null
            val box = OfferJson.objectValue(row["priceBox"]) ?: return@mapNotNull null
            fun visibleDate(key: String): LocalDate? {
                val localDay = date(row[key]) ?: return null
                val utcKey = key + "UTC"
                return if (market == null || utcKey !in row) localDay
                else localDay.takeIf { OfferJson.instant(row[utcKey])?.let(AppCalendar::today) == it }
            }
            val from = visibleDate("startValidityDate") ?: return@mapNotNull null
            val until = visibleDate("endValidityDate") ?: return@mapNotNull null
            if (until < from) return@mapNotNull null
            val price = OfferJson.euroCents(box["largePartNumeric"] ?: box["largePartString"])
            val unitPrice = OfferJson.text(row["pricePerUnit"])
            val discount = OfferJson.text(box["discountMessage"])
            val appPrice = if (market != null) {
                if (row["redemptionChannel"] != "Store" || box["priceSymbol"] != "€")
                    return@mapNotNull null
                price?.takeIf { it <= 100_000_000L } ?: return@mapNotNull null
            } else null
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.LIDL, title, id, null,
                price.takeIf { market == null }, null, null, null, null,
                LiveOfferKind.PROMOTION, from, until, null, fetchedAt, storeKey, null, null,
                LiveOfferApplicability.STORE_CONFIRMED,
                if (market == null) discount else listOfNotNull(
                    "In Lidl Plus veröffentlichter Preis. Teilnahme und Einlösung in Lidl Plus prüfen.", discount
                ).joinToString(" "), false, sourceUrl, OfferJson.text(row["packaging"]),
                priceDetails = LivePriceDetails(appCents = appPrice,
                    brand = OfferJson.text(row["brand"]), unitPriceDisplay = unitPrice),
                storeEvidence = market)
        }
    }

    fun penny(raw: String, sourceId: String, region: String, weekStart: LocalDate, fetchedAt: Instant, sourceUrl: String): List<LiveOffer> {
        require(region.isNotBlank())
        val rows = OfferJson.read(raw)["offerTiles"] as? List<*> ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        require(rows.size <= 256)
        return rows.mapNotNull { value ->
            val row = OfferJson.objectValue(value) ?: return@mapNotNull null
            val id = OfferJson.text(row["uuid"]) ?: return@mapNotNull null // no positional IDs
            val title = OfferJson.text(row["title"]) ?: return@mapNotNull null
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.PENNY, title, id, null,
                OfferJson.euroCents(row["price"]), null, null, null, null, LiveOfferKind.PROMOTION,
                weekStart, null, null, fetchedAt, null, region, null, LiveOfferApplicability.REGION_CONFIRMED,
                OfferJson.text(row["benefitPrice"]), false, sourceUrl, OfferJson.text(row["quantity"]))
        }
    }

    /** Next-week response at the pinned reference, never a guessed current/store catalogue. */
    fun aldiNordNext(rawNextData: String, sourceId: String, fetchedAt: Instant, sourceUrl: String): List<LiveOffer> =
        aldiNordWeek(rawNextData, sourceId, fetchedAt, sourceUrl, "next")

    fun aldiNordCurrent(rawNextData: String, sourceId: String, fetchedAt: Instant, sourceUrl: String): List<LiveOffer> =
        aldiNordWeek(rawNextData, sourceId, fetchedAt, sourceUrl, "current")

    private fun aldiNordWeek(rawNextData: String, sourceId: String, fetchedAt: Instant, sourceUrl: String,
        week: String): List<LiveOffer> {
        val root = OfferJson.readAldiNordEnvelope(rawNextData)
        val props = requireNotNull(OfferJson.objectValue(root["props"]))
        val page = requireNotNull(OfferJson.objectValue(props["pageProps"]))
        val data = page["apiData"]
        val calls = if (data is String) OfferJson.read("{\"calls\":$data}")["calls"] as? List<*> else data as? List<*>
        requireNotNull(calls); require(calls.size <= 256)
        val responses = calls.mapNotNull { value ->
            val pair = value as? List<*> ?: return@mapNotNull null
            if (pair.size != 2 || pair[0] != "OFFER_GET") return@mapNotNull null
            val response = OfferJson.objectValue(pair[1]) ?: return@mapNotNull null
            val request = OfferJson.objectValue(response["req"]) ?: return@mapNotNull null
            if (request["week"] != week) return@mapNotNull null
            require(request.keys.none { it in setOf("storeId", "marketId", "region", "zipCode", "store") })
            response
        }
        val response = responses.single()
        val products = requireNotNull(OfferJson.objectValue(OfferJson.objectValue(response["res"])?.get("algoliaDataMap")))
        // The current and next official lists each contain hundreds of rows.
        // Android displays only 25 cards per page.
        require(products.size <= 512)
        return products.mapNotNull { (key, value) ->
            val row = OfferJson.objectValue(value) ?: return@mapNotNull null
            val id = OfferJson.text(row["objectID"]) ?: key.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = OfferJson.text(row["name"]) ?: return@mapNotNull null
            val price = OfferJson.objectValue(row["currentPrice"])
            val amount = if (price == null) OfferJson.euroCents(row["currentPrice"]) else OfferJson.euroCents(price["priceValue"] ?: price["amountRelevant"])
            fun priceDate(p: Map<String, Any?>?, key: String) = date(p?.get(key + "LocalDate")) ?: date(p?.get(key))
            var from = priceDate(price, "validFrom"); var until = priceDate(price, "validUntil")
            if (from == null || until == null) {
                val matches = (row["promotionPrices"] as? List<*>).orEmpty().mapNotNull(OfferJson::objectValue)
                    .filter { amount != null && OfferJson.euroCents(it["priceValue"]) == amount && priceDate(it, "validFrom") != null && priceDate(it, "validUntil") != null }
                    .distinctBy { priceDate(it, "validFrom") to priceDate(it, "validUntil") }
                if (matches.size == 1) { from = priceDate(matches.single(), "validFrom"); until = priceDate(matches.single(), "validUntil") }
            }
            if (from != null && until != null && until < from) return@mapNotNull null
            val deposit = OfferJson.euroCents(row["depositValue"])?.takeIf { cents ->
                when (row["isDepositProduct"]) {
                    true -> cents > 0L
                    false -> cents == 0L
                    else -> false
                }
            }
            LiveOffer("$sourceId:$id", sourceId, LiveRetailer.ALDI_NORD, title, id, null, amount, deposit,
                null, null, null, LiveOfferKind.PROMOTION, from, until, null, fetchedAt, null, null, null,
                LiveOfferApplicability.CHAIN_REPORTED, null, false, sourceUrl, OfferJson.text(row["salesUnit"]),
                priceDetails = aldiNordPriceDetails(row, price))
        }
    }

    private fun aldiNordPriceDetails(row: Map<String, Any?>, price: Map<String, Any?>?): LivePriceDetails? {
        val baseRates = (price?.get("basePrice") as? List<*>)?.mapNotNull { entry ->
            val rate = OfferJson.objectValue(entry) ?: return@mapNotNull null
            val cents = OfferJson.euroCents(rate["basePriceValue"]) ?: return@mapNotNull null
            val basis = OfferJson.text(rate["basePriceScale"]) ?: return@mapNotNull null
            "${Money.format(cents)} / $basis"
        }?.joinToString(" · ")?.let(OfferJson::text)
        val earlier = OfferJson.objectValue(price?.get("strikePrice"))?.let { reference ->
            OfferJson.euroCents(reference["strikePriceValue"])?.let { cents ->
                listOfNotNull(OfferJson.text(reference["strikePriceLabel"]), Money.format(cents)).joinToString(" ")
            }
        }
        val discount = OfferJson.text(OfferJson.objectValue(price?.get("priceTagLabels"))?.get("promoText1"))
            ?.takeIf { it.matches(Regex("-[0-9]{1,3}\\s*%")) }
        val sourceComparison = OfferJson.text(listOfNotNull(earlier, discount).joinToString(" · "))
        val brand = OfferJson.text(row["brandName"])
        if (baseRates == null && sourceComparison == null && brand == null) return null
        return LivePriceDetails(previousDisplay = sourceComparison, brand = brand, unitPriceDisplay = baseRates)
    }

    /** Store-scoped availability must be fetched/configured independently. A
     * discontinuous date list never becomes one fabricated continuous window. */
    fun kaufland(rawOfferData: String, availability: List<Map<String, Any?>>, sourceId: String, storeId: String,
        purchaseOn: LocalDate, fetchedAt: Instant, sourceUrl: String): List<LiveOffer> {
        require(storeId.matches(Regex("DE[0-9]{3,5}")))
        val root = OfferJson.read(rawOfferData)
        val data = OfferJson.objectValue(OfferJson.objectValue(root["props"])?.get("offerData")) ?: OfferJson.objectValue(root["offerData"]) ?: root
        val cycles = data["cycles"] as? List<*> ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
        require(cycles.size <= 64 && availability.size <= 1024)
        val result = linkedMapOf<String, LiveOffer>()
        cycles.asReversed().forEach { value ->
            val cycle = OfferJson.objectValue(value) ?: return@forEach
            val categories = cycle["categories"] as? List<*> ?: return@forEach
            require(categories.size <= 256)
            categories.forEach category@ { c ->
                val rows = OfferJson.objectValue(c)?.get("offers") as? List<*> ?: return@category
                require(rows.size <= 256)
                rows.forEach row@ { v ->
                    val row = OfferJson.objectValue(v) ?: return@row
                    val id = OfferJson.text(row["klNr"]) ?: return@row
                    val title = OfferJson.text(row["title"]) ?: return@row
                    val windows = availability.filter { OfferJson.text(it["klNr"]) == id }.mapNotNull {
                        val from = date(it["dateFrom"]) ?: return@mapNotNull null
                        val until = date(it["dateTo"]) ?: return@mapNotNull null
                        (from to until).takeIf { from <= purchaseOn && purchaseOn <= until }
                    }.distinct()
                    if (windows.size != 1 || id in result) return@row
                    val from = maxOf(windows.single().first, date(row["salesFrom"]) ?: windows.single().first)
                    val until = minOf(windows.single().second, date(row["salesTo"]) ?: windows.single().second)
                    if (purchaseOn < from || purchaseOn > until) return@row
                    result[id] = LiveOffer("$sourceId:$id", sourceId, LiveRetailer.KAUFLAND, title, id, null,
                        OfferJson.euroCents(row["price"]), null, null, null, null, LiveOfferKind.PROMOTION,
                        from, until, null, fetchedAt, storeId, null, null, LiveOfferApplicability.STORE_CONFIRMED,
                        null, false, sourceUrl, OfferJson.text(row["unit"]))
                    require(result.size <= 256)
                }
            }
        }
        return result.values.toList()
    }

    private fun date(value: Any?): LocalDate? {
        val millis = OfferJson.integer(value)
        return if (millis != null) runCatching { (if (millis > 100_000_000_000L) Instant.ofEpochMilli(millis) else Instant.ofEpochSecond(millis))
            .atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate() }.getOrNull()
            else (value as? String)?.let { raw -> OfferJson.date(raw.take(10)) }
    }
}
