package de.ernaehrung.demo.data.integration

import de.eg.recipe.RecipeDraftRevalidation
import de.eg.recipe.RecipeEngine as Core
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.local.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Read-only projection into the pinned upstream v1 revalidator. Only resolved
 * host facts enter this port; imported nutrition/allergen/verification claims
 * never enter its facts. This result cannot authorize any FoodStore write.
 */
internal object RecipeReferenceV1 {
    data class Report(
        val blockers: Set<String>,
        val findings: Set<String>,
        val totals: Map<String, BigDecimal?>,
        val perPortion: Map<String, BigDecimal?>,
        val partial: Map<String, BigDecimal?>,
        val nutritionGaps: Map<String, List<String>>,
        val contextNotes: List<String>
    )

    fun revalidate(
        json: String, request: Gate.Request, facts: Map<String, Gate.HostLine>,
        now: OffsetDateTime, catalog: List<ProductVersionEntity> = emptyList(),
        context: Gate.Context<*, *, *>? = null
    ): Report {
        val rows = catalog.groupBy { it.id }
        val nutrientKeys = linkedMapOf(
            "energyKcal" to Nutrient.ENERGY, "proteinG" to Nutrient.PROTEIN,
            "carbohydrateG" to Nutrient.CARBS, "fatG" to Nutrient.FAT,
            "fiberG" to Nutrient.FIBRE, "vitaminB12Ug" to Nutrient.B12,
            "vitaminDUg" to Nutrient.VITAMIN_D
        )
        val nutrientFacts = facts.mapNotNull { (id, host) ->
            val version = rows[host.productVersionId]?.singleOrNull()
            if (host.foodTypeId != id || !host.identityConfirmed || !host.basisConfirmed ||
                !host.stateConfirmed || host.basis == null || host.state == null ||
                version == null || version.baseUnit != host.unit ||
                version.recordedAt !in 0L..now.toInstant().toEpochMilli() ||
                version.source == Source.DEMO ||
                version.quality !in setOf(Quality.CONFIRMED, Quality.SOURCED)) return@mapNotNull null
            val unit = when (version.baseUnit) {
                BaseUnit.GRAM -> Core.UnitCode.G
                BaseUnit.MILLILITRE -> Core.UnitCode.ML
                BaseUnit.PORTION -> Core.UnitCode.PORTION
            }
            val per100 = nutrientKeys.mapValues { (_, nutrient) ->
                version.nutrient(nutrient)?.takeIf { it.isFinite() && it >= 0.0 && version.nutritionBasis > 0 }
                    ?.let { BigDecimal.valueOf(it).multiply(BigDecimal(100_000))
                        .divide(BigDecimal.valueOf(version.nutritionBasis), MathContext.DECIMAL128) }
            }
            id to Core.NutrientFact(id, unit, Core.Basis.valueOf(host.basis.name),
                Core.State.valueOf(host.state.name), per100,
                host.allergens.filterValues { it == true }.keys,
                // Per-restriction answers do not prove a comprehensive allergen
                // declaration. In particular, never infer COMPLETE from false.
                if (host.allergens.isEmpty()) Core.Evidence.UNKNOWN else Core.Evidence.PARTIAL,
                dietTags = null, source = "host-version:${version.id}", dataQuality = version.quality.name)
        }.toMap()
        val flags = Core.Flags(request.flags.considerStock, request.flags.considerShopping, request.flags.considerOffers)
        val projected = context?.takeIf { it.projectedFlags == request.flags }
        fun snapshot(value: Any?, source: RecipeContextContentBinding.Source): Core.Snapshot? {
            val input = value as? RecipeContextContentBinding.Snapshot ?: return null
            if (input.source != source) return null
            val entries = input.lines.flatMap { line ->
                // Do not guess a food type from a version ID or apply another
                // product's basis/state to this row. Unmapped rows add no facts.
                facts.values.filter { it.productVersionId == line.versionId &&
                    it.identityConfirmed && it.basisConfirmed && it.stateConfirmed &&
                    it.basis != null && it.state != null }.map { host ->
                    val unit = when (line.unit) {
                        "g" -> Core.UnitCode.G
                        "ml" -> Core.UnitCode.ML
                        "Portion" -> Core.UnitCode.PORTION
                        else -> null
                    }
                    val amount = line.amountMilli?.takeIf { it >= 0L && it % 1000L == 0L }?.div(1000L)
                    Core.InventoryEntry(host.foodTypeId,
                        Core.Quantity(amount, unit ?: Core.UnitCode.G,
                            if (unit == null) Core.Basis.UNKNOWN else Core.Basis.valueOf(requireNotNull(host.basis).name),
                            confirmed = unit != null && amount != null,
                            state = Core.State.valueOf(requireNotNull(host.state).name)))
                }
            }
            // The projection covers only independently mapped ingredient types;
            // an absent row is never evidence for complete zero inventory.
            return Core.Snapshot(true, Core.Completeness.PARTIAL,
                input.capturedAt.atOffset(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                entries, "host-snapshot:${input.revision}", "CONFIRMED")
        }
        val coreRequest = Core.Request("host-revalidation", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            now.toLocalDate(), request.portions, flags,
            restrictions = request.confirmedRestrictions.sorted().map { Core.Restriction(it, true) },
            stock = if (flags.considerStock) snapshot(projected?.stock, RecipeContextContentBinding.Source.STOCK) else null,
            shopping = if (flags.considerShopping) snapshot(projected?.shopping, RecipeContextContentBinding.Source.SHOPPING) else null,
            // Bound offer rows lack the upstream price/validity/provenance DTO.
            // Do not fabricate those facts from an offer ID or amount.
            offers = null)
        val states = facts.filterValues { it.stateConfirmed && it.state != null }
            .mapValues { Core.State.valueOf(requireNotNull(it.value.state).name) }
        val result = RecipeDraftRevalidation.revalidate(RecipeDraftBoundedJson.decode(json),
            coreRequest, Core.RecipeFacts(nutrientFacts), states)
        val blockers = result.rejected.flatMap { it.second.findings }
            .filter { it.severity == Core.Severity.BLOCK }.mapTo(linkedSetOf()) { it.code }
        val payload = result.messages.singleOrNull()?.get("payload") as? Map<*, *>
        val extension = payload?.get("xRecipeKi") as? Map<*, *>
        val findings = (extension?.get("findings") as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.get("code") as? String
        }?.toSet().orEmpty()
        val nutrition = payload?.get("nutrition") as? Map<*, *>
        fun amounts(key: String): Map<String, BigDecimal?> =
            (nutrition?.get(key) as? Map<*, *>)?.entries?.associate { (k, value) ->
                (k as String) to (value as BigDecimal?)
            }.orEmpty()
        val gaps = (nutrition?.get("gaps") as? Map<*, *>)?.entries?.associate { (k, value) ->
            (k as String) to (value as List<*>).map { it as String }
        }.orEmpty()
        val notes = (payload?.get("warnings") as? List<*>)?.filterIsInstance<String>()
            ?.filter { it.startsWith("STOCK_") || it.startsWith("SHOPPING_") || it.startsWith("OFFERS_") }.orEmpty()
        return Report(blockers, findings, amounts("total"), amounts("perPortion"), amounts("knownPartial"), gaps, notes)
    }
}
