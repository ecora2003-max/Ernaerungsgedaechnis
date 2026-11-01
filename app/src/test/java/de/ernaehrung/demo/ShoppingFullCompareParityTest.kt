package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.ExternalPriceBridge as Price
import de.ernaehrung.demo.data.integration.RetailerPolicy as Policy
import de.ernaehrung.demo.data.integration.ShoppingComparabilityKernel as Comparable
import de.ernaehrung.demo.data.integration.ShoppingCoverageSignalKernel as Coverage
import de.ernaehrung.demo.data.integration.ShoppingOfferGate as OfferGate
import de.ernaehrung.demo.data.integration.ShoppingPriceComparisonGate as Gate
import de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel as UnitRate
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Shared full-call vectors also checked by Python's pinned compare_prices,
 * not merely six independent kernel reference checks.
 * Host-only rights/identity gates remain intentionally stricter for other cases.
 */
class ShoppingFullCompareParityTest {
    private val day = LocalDate.parse("2026-11-02")
    private val asOf = OffsetDateTime.parse("2026-10-31T12:00:00+01:00")
    private val observed = OffsetDateTime.parse("2026-10-31T10:00:00+01:00")
    private val rights = Price.DisplayPermissionRegistry { source, ref, capability, on ->
        source == "synthetic-fixture" && ref == "synthetic-rights" && on == day &&
            capability in setOf(Policy.Capability.PUBLISHED_OFFER_COLLECTION,
                Policy.Capability.TARGETED_PRODUCT_PRICE_QUERY)
    }
    private val stores = OfferGate.ConfirmedStoreRegistry { retailer, store, region, _ ->
        retailer == Policy.Retailer.LIDL && store == "canonical-42" && region == "berlin"
    }
    private val articles = Gate.ConfirmedArticleRegistry { version, article, _, _ ->
        version.startsWith("v-") && article.foodTypeId == "milk"
    }

    private fun organic(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        "null" -> null
        else -> error("invalid organic fixture value: $value")
    }

    private fun input(
        id: String, mode: Policy.Capability, cents: Long, amount: Long,
        bio: Boolean?, food: String, deposit: Long?
    ): Gate.Input {
        val version = "v-$id"
        val request = Price.Request(Policy.Retailer.LIDL, mode, version, day, asOf,
            "canonical-42", "berlin")
        val candidate = Price.Candidate(
            id, version, true, Policy.Retailer.LIDL, mode, "synthetic-fixture",
            Price.Origin.EXTERNAL, Price.RightsStatus.PERMITTED, "synthetic-rights",
            observed, day, day.plusDays(2), "canonical-42", "berlin",
            cents, deposit, Math.multiplyExact(amount, 1000L), Price.Unit.GRAM,
            conditionsVerified = true
        )
        val article = Comparable.Article(
            foodTypeId = food, attributes = mapOf("state" to "liquid"),
            quantity = UnitRate.Quantity(amount, "g", "NET", 1, true)
        )
        return Gate.Input(request, candidate,
            OfferGate.HostFacts("SALE", "CONFIRMED", "AVAILABLE", false,
                "STORE_CONFIRMED", true), article, bio)
    }

    private fun values(text: String): List<String> =
        if (text == "-") emptyList() else text.split(",")

    @Test fun pinnedPythonFullCompareVectorsMatchKotlinHostComposition() {
        val stream = requireNotNull(javaClass.classLoader!!
            .getResourceAsStream("integration/shopping_full_compare_v1.tsv"))
        val lines = stream.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(10, lines.size) // header + nine independent full-call cases
        assertEquals("case", lines.first().split('\t').first())
        for (line in lines.drop(1)) {
            val row = line.split('\t')
            assertEquals("fixture columns: $line", 10, row.size)
            val caseId = row[0]
            val mode = Policy.Capability.valueOf(row[1])
            val target = input("target", mode, row[2].toLong(), row[3].toLong(),
                organic(row[4]), "milk", 25)
            val candidates = row[5].split(";").map { raw ->
                val fields = raw.split(":")
                assertEquals("candidate fields: $caseId", 6, fields.size)
                input(fields[0], mode, fields[1].toLong(), fields[2].toLong(),
                    organic(fields[3]), fields[4],
                    fields[5].takeUnless { it == "null" }?.toLong())
            }
            val result = Gate.compare(target, candidates,
                coverage = Gate.HostCoverage(Coverage.Coverage(true), true),
                permissions = rights, stores = stores, articles = articles)
            assertTrue("target rejected: $caseId", result.targetEligible)
            assertEquals("alternatives: $caseId", values(row[6]),
                result.cheaper.map { it.observationId })
            assertEquals("signals: $caseId", values(row[7]), result.signals.signals)
            assertEquals("incomplete: $caseId",
                result.signals.signals.contains("GREY_MAY_BE_CHEAPER"),
                result.signals.incomplete)
            assertEquals("deltas: $caseId", values(row[8]),
                result.cheaper.map { it.targetEquivalentDifferenceCents?.toString() ?: "null" })
            assertEquals("exact rates: $caseId", values(row[9]),
                result.cheaper.map {
                    "${it.candidateRate.numerator}/${it.candidateRate.denominator}"
                })
            assertTrue("unexpected rejection: $caseId", result.excluded.isEmpty())
            assertTrue("other-day sale in current case: $caseId", result.otherDayOffers.isEmpty())
            assertFalse(result.marketWideBestPriceProven)
        }
    }
}
