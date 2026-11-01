package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.HostCatalogArticleRegistry
import de.ernaehrung.demo.data.integration.ShoppingComparabilityKernel
import de.ernaehrung.demo.data.integration.ShoppingUnitRateKernel
import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.ProductVersionEntity
import de.ernaehrung.demo.data.local.Quality
import de.ernaehrung.demo.data.local.Source
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class HostCatalogArticleRegistryTest {
    private val asOf = OffsetDateTime.parse("2026-10-20T12:00:00Z")
    private val earlier = OffsetDateTime.parse("2026-10-19T12:00:00Z")
    private val later = OffsetDateTime.parse("2026-10-22T12:00:00Z")
    private val recordedAt = OffsetDateTime.parse("2026-10-18T12:00:00Z").toInstant().toEpochMilli()
    private val confirmedAt = OffsetDateTime.parse("2026-10-19T10:00:00Z").toInstant().toEpochMilli()

    private fun version(
        id: String = "v1", revision: Int = 1, recorded: Long = recordedAt,
        quality: Quality = Quality.CONFIRMED, source: Source = Source.USER
    ) = ProductVersionEntity(
        id = id, productId = "p1", revision = revision,
        name = "Reis", brand = "Brand", category = "Lebensmittel",
        barcode = "12345678", baseUnit = BaseUnit.GRAM,
        packageAmount = 500L, nutritionBasis = 1000L,
        energyKcal = null, proteinGrams = null, fatGrams = null, carbsGrams = null,
        source = source, sourceDetail = null, quality = quality,
        recordedAt = recorded, changeReason = "synthetic test"
    )

    private val article = ShoppingComparabilityKernel.Article(
        foodTypeId = "rice",
        attributes = mapOf("state" to "RAW"),
        quantity = ShoppingUnitRateKernel.Quantity(500, "g", "NET", 1, true),
        gtin = "12345678", brandKind = "BRANDED", brandName = "Brand"
    )

    private fun confirmed(
        id: String = "v1", revision: Int = 1,
        versionAt: Long = recordedAt, at: Long = confirmedAt,
        item: ShoppingComparabilityKernel.Article = article, organic: Boolean? = false
    ) = HostCatalogArticleRegistry.ConfirmedArticle(
        id, revision, versionAt, at, item, organic
    )

    private fun registry(
        versions: List<ProductVersionEntity> = listOf(version()),
        evidence: HostCatalogArticleRegistry.ConfirmedArticle = confirmed()
    ) = HostCatalogArticleRegistry(versions, mapOf(evidence.productVersionId to evidence))

    @Test fun independentlyReviewedVersionAndQuantityAreAcceptedOnlyAtValidCutoff() {
        val gate = registry()
        assertTrue(gate.isConfirmed("v1", article, false, asOf))
        assertTrue(gate.isConfirmed("v1", article, false, earlier))
        assertFalse(gate.isConfirmed("v1", article, false,
            OffsetDateTime.parse("2026-10-18T13:00:00Z")))
        assertFalse(gate.isConfirmed("unknown", article, false, asOf))
    }

    @Test fun feedClaimsAndUnknownQuantitiesCannotBecomeHostIdentityEvidence() {
        val gate = registry()
        assertFalse(gate.isConfirmed("v1", article.copy(foodTypeId = "beans"), false, asOf))
        assertFalse(gate.isConfirmed("v1", article.copy(attributes = emptyMap()), false, asOf))
        assertFalse(gate.isConfirmed("v1", article.copy(gtin = "forged"), false, asOf))
        assertFalse(gate.isConfirmed("v1", article.copy(brandName = "Other"), false, asOf))
        assertFalse(gate.isConfirmed("v1", article, null, asOf))
        assertFalse(gate.isConfirmed("v1", article, true, asOf))
        assertFalse(gate.isConfirmed("v1", article.copy(quantity =
            ShoppingUnitRateKernel.Quantity(null, "g", "NET", 1, true)), false, asOf))
        assertFalse(gate.isConfirmed("v1", article.copy(quantity =
            ShoppingUnitRateKernel.Quantity(0, "g", "NET", 1, true)), false, asOf))
        assertFalse(gate.isConfirmed("v1", article.copy(quantity =
            ShoppingUnitRateKernel.Quantity(500, "ml", "NET", 1, true)), false, asOf))
    }

    @Test fun knownCatalogPackageSizeMustMatchReviewedPerPackAmount() {
        // Catalog version v1 describes 500 g per pack. A separately reviewed
        // 1000 g claim for the same version must not silently change its size.
        assertTrue(registry().isConfirmed("v1", article, false, asOf))
        assertFalse(registry(versions = listOf(version().copy(packageAmount = 1000L)))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(versions = listOf(version().copy(packageAmount = 0L)))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(versions = listOf(version().copy(packageAmount = -500L)))
            .isConfirmed("v1", article, false, asOf))

        // An unknown catalog package size is not fabricated as zero; an
        // independent positive reviewed amount can still be considered.
        assertTrue(registry(versions = listOf(version().copy(packageAmount = null)))
            .isConfirmed("v1", article, false, asOf))

        // packageCount multiplies the reviewed 500 g pack, not the catalog
        // per-pack size: two 500 g packs are not a new 1000 g SKU.
        val twoPacks = article.copy(quantity =
            ShoppingUnitRateKernel.Quantity(500, "g", "NET", 2, true))
        assertTrue(registry(evidence = confirmed(item = twoPacks))
            .isConfirmed("v1", twoPacks, false, asOf))
        assertFalse(registry(
            versions = listOf(version().copy(packageAmount = 1000L)),
            evidence = confirmed(item = twoPacks)
        ).isConfirmed("v1", twoPacks, false, asOf))
    }

    @Test fun latestKnownRevisionIsHistoricalAndAmbiguityFailsClosed() {
        val newAt = OffsetDateTime.parse("2026-10-21T12:00:00Z").toInstant().toEpochMilli()
        val old = version()
        val newer = version(id = "v2", revision = 2, recorded = newAt)
        val gate = registry(listOf(old, newer))
        assertTrue(gate.isConfirmed("v1", article, false, asOf))
        assertFalse(gate.isConfirmed("v1", article, false, later))
        assertFalse(registry(listOf(old, old)).isConfirmed("v1", article, false, asOf))
        assertFalse(registry(listOf(old, version(id = "v3"))).isConfirmed(
            "v1", article, false, asOf))
    }

    @Test fun forgedOrLateConfirmationAndUntrustedCatalogRowsAreRejected() {
        assertFalse(registry(evidence = confirmed(revision = 2))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(evidence = confirmed(at = later.toInstant().toEpochMilli()))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(evidence = confirmed(at = recordedAt - 1))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(versions = listOf(version(source = Source.DEMO)))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(versions = listOf(version().copy(barcode = null)))
            .isConfirmed("v1", article, false, asOf))
        assertFalse(registry(versions = listOf(version().copy(brand = null)))
            .isConfirmed("v1", article, false, asOf))
    }
}
