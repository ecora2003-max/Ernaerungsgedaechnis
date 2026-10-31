package de.ernaehrung.demo.data.integration

import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Read-only projection of EG-Produktdaten@1f85b17...::to_historical_price_observation.
 *
 * Inputs are independently confirmed facts, NOT OCR suggestions. Nothing here persists a
 * purchase, establishes rights for third-party prices, or makes a historical price a
 * currently valid retailer offer. Room/FoodStore adapters must enforce those boundaries.
 */
internal object HistoricalPaidPriceKernel {
    data class Observation(
        val observationId: String, val productId: String, val versionId: String,
        val retailerId: String, val storeId: String?, val purchasedOn: String,
        val priceCents: Long, val purchaseUnits: Long, val linePaidCents: Long,
        val allocatedDiscountCents: Long, val depositCents: Long?,
        val priceKind: String = "HISTORICAL_PAID", val currency: String = "EUR",
        val sourceOrigin: String = "local_user_confirmed_receipt",
        val validFrom: String? = null, val validUntil: String? = null
    )
    data class Decision(val observation: Observation?, val reason: String? = null)

    private fun deny(reason: String) = Decision(null, reason)
    private fun truth(value: Any?) = value === true
    private fun nonempty(value: Any?) = when (value) {
        null, false, 0, 0L, "" -> false
        else -> true
    }
    private fun isoDate(text: Any?): LocalDate {
        require(text is String)
        val value = LocalDate.parse(text)
        require(value.toString() == text)
        return value
    }
    private fun time(text: Any?): OffsetDateTime {
        require(text is String)
        return OffsetDateTime.parse(text)
    }
    private fun normalizedGtin(raw: Any?): String {
        require(raw is String && (raw.length == 8 || raw.length == 12 || raw.length == 13 || raw.length == 14))
        require(raw.all { it in '0'..'9' } && raw.any { it != '0' })
        val digits = raw.map { it - '0' }
        val checksum = (10 - digits.dropLast(1).asReversed().mapIndexed { i, v -> v * if (i % 2 == 0) 3 else 1 }.sum() % 10) % 10
        require(digits.last() == checksum)
        return raw.padStart(14, '0')
    }
    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun propose(receipt: Map<String, Any?>, line: Map<String, Any?>, article: Map<String, Any?>): Decision {
        if (!truth(receipt["purchaseConfirmed"]) || !truth(line["confirmed"]))
            return deny("UNCONFIRMED_PURCHASE_OR_LINE")
        if (truth(receipt["purchaseReversed"]) || truth(line["lineReversed"]))
            return deny("REVERSED_PURCHASE_OR_LINE")
        if (!truth(line["productConfirmed"])) return deny("UNCONFIRMED_PRODUCT_LINK")
        if (line["lineKind"] != "FOOD") return deny("NON_FOOD_DISCOUNT_DEPOSIT_OR_RETURN")
        for (key in listOf("purchaseId", "receiptId", "retailerId", "confirmedAt"))
            if (!nonempty(receipt[key])) return deny("MISSING_" + key.uppercase())
        if (!nonempty(receipt["purchaseAt"]) && !nonempty(receipt["purchasedOn"]))
            return deny("MISSING_PURCHASE_DATE")
        if (!nonempty(line["lineId"]) || line["receiptId"] != receipt["receiptId"])
            return deny("INVALID_LINE_IDENTITY")
        if (line["retailerId"] != receipt["retailerId"]) return deny("MERCHANT_MISMATCH")

        val purchasedAt: OffsetDateTime?
        val confirmedAt: OffsetDateTime
        val purchasedOn: LocalDate
        try {
            purchasedAt = if (nonempty(receipt["purchaseAt"])) time(receipt["purchaseAt"]) else null
            confirmedAt = time(receipt["confirmedAt"])
            purchasedOn = if (receipt["purchasedOn"] != null) isoDate(receipt["purchasedOn"])
                else requireNotNull(purchasedAt).toLocalDate()
            // These are contract shape/identity checks, not a trust/confirmation shortcut.
            require(nonempty(article["id"]) && nonempty(article["versionId"]) && nonempty(article["label"]))
            val organic = article["organic"]
            require(organic == null || organic is Boolean)
            require(article["brandKind"] == null || article["brandKind"] in listOf("MANUFACTURER", "PRIVATE_LABEL", "UNKNOWN"))
            if (article["gtin"] != null) normalizedGtin(article["gtin"])
        } catch (_: IllegalArgumentException) {
            return deny("INVALID_DATE_OR_ARTICLE")
        } catch (_: java.time.DateTimeException) {
            return deny("INVALID_DATE_OR_ARTICLE")
        }
        if (purchasedAt != null && purchasedAt.toLocalDate() != purchasedOn)
            return deny("PURCHASE_DATE_CONFLICT")
        if (purchasedAt != null && confirmedAt.toInstant() < purchasedAt.toInstant())
            return deny("CONFIRMATION_PRECEDES_PURCHASE")
        if (purchasedAt == null && confirmedAt.toLocalDate() < purchasedOn)
            return deny("CONFIRMATION_PRECEDES_PURCHASE")
        if (line["productId"] != article["id"] || line["versionId"] != article["versionId"])
            return deny("PRODUCT_VERSION_MISMATCH")
        if (line["gtin"] != null) {
            val left: String
            val right: String
            try {
                left = normalizedGtin(line["gtin"])
                right = article["gtin"]?.let(::normalizedGtin) ?: return deny("BARCODE_CONFLICT")
            } catch (_: IllegalArgumentException) {
                return deny("INVALID_BARCODE")
            }
            if (left != right) return deny("BARCODE_CONFLICT")
        }
        val amount = line["amountCents"] as? Long
        if (amount == null || amount < 0) return deny("UNKNOWN_OR_INVALID_PRICE")
        val units = line["purchaseUnits"] as? Long
        if (units == null || units < 1 || !truth(line["unitsConfirmed"]))
            return deny("UNKNOWN_PURCHASE_UNITS")
        if (!truth(line["discountReconciled"])) return deny("DISCOUNT_ACCOUNTING_UNKNOWN")
        if (truth(line["unallocatedDiscount"])) return deny("UNALLOCATED_DISCOUNT")
        val discount = if (line.containsKey("allocatedDiscountCents")) line["allocatedDiscountCents"] as? Long else 0L
        if (discount == null || discount < 0 || discount > amount) return deny("INVALID_DISCOUNT")
        if (truth(line["discountApplied"]) && !line.containsKey("allocatedDiscountCents"))
            return deny("UNALLOCATED_DISCOUNT")
        if (!truth(line["amountExcludesDeposit"])) return deny("DEPOSIT_ACCOUNTING_UNKNOWN")
        val paid = amount - discount
        if (paid % units != 0L) return deny("NONINTEGRAL_UNIT_PRICE")
        if (nonempty(line["storeId"]) && nonempty(receipt["storeId"]) && line["storeId"] != receipt["storeId"])
            return deny("STORE_MISMATCH")
        if (receipt["storeId"] != null && receipt["storeId"] !is String) return deny("INVALID_STORE")
        val deposit = line["depositPerUnitCents"] as? Long
        if (line["depositPerUnitCents"] != null && (deposit == null || deposit < 0))
            return deny("INVALID_DEPOSIT")
        val key = listOf(receipt["purchaseId"], receipt["receiptId"], line["lineId"]).joinToString("\u001f")
        return Decision(Observation(
            observationId = "receipt-" + sha256(key).take(32),
            productId = article["id"].toString(),
            versionId = article["versionId"].toString(),
            retailerId = receipt["retailerId"].toString(),
            storeId = (receipt["storeId"] as? String)?.takeIf(String::isNotEmpty),
            purchasedOn = purchasedOn.toString(),
            priceCents = paid / units, purchaseUnits = units, linePaidCents = paid,
            allocatedDiscountCents = discount, depositCents = deposit
        ))
    }
}
