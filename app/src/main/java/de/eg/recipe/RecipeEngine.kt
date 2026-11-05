package de.eg.recipe

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

/** Public, pure JVM/Android Kotlin core. No Android classes, IO, network or persistence. */
object RecipeEngine {
    const val CONTRACT_VERSION = "1.0.0-draft"
    const val PRODUCER = "eg-rezept-ki-offline-1"
    enum class Basis { NET, DRAINED, GROSS, PIECE, PER_PORTION, UNKNOWN }
    enum class UnitCode(val wire: String) { G("g"), ML("ml"), PIECE("piece"), PORTION("portion") }
    enum class State { RAW, COOKED, UNKNOWN }
    enum class Completeness { COMPLETE, PARTIAL, UNKNOWN }
    enum class Evidence { COMPLETE, PARTIAL, UNKNOWN }
    enum class Severity { BLOCK, REVIEW, INFO }
    enum class Verdict { BLOCKED, REVIEW_REQUIRED, STRUCTURALLY_VALID }
    data class Flags(val considerStock: Boolean = false, val considerShopping: Boolean = false, val considerOffers: Boolean = false)
    data class Quantity(val amount: Long?, val unit: UnitCode, val basis: Basis, val confirmed: Boolean = false, val state: State = State.RAW)
    data class Line(val foodTypeId: String, val label: String, val quantity: Quantity, val allergenNotes: String? = null)
    data class InventoryEntry(val foodTypeId: String, val quantity: Quantity, val bestBefore: LocalDate? = null)
    data class Snapshot(val shared: Boolean, val coverage: Completeness, val asOf: String?, val entries: List<InventoryEntry>, val source: String? = null, val quality: String? = null)
    data class Offer(val foodTypeId: String, val priceCents: Long?, val currency: String, val validFrom: LocalDate?, val validUntil: LocalDate?, val source: String?, val quality: String?)
    data class OfferSnapshot(val shared: Boolean, val asOf: String?, val offers: List<Offer>)
    data class Restriction(val code: String, val confirmed: Boolean)
    data class Target(val nutrient: String, val min: BigDecimal?, val max: BigDecimal?, val confirmed: Boolean)
    data class Request(
        val requestId: String, val createdAt: String, val shoppingDate: LocalDate, val portions: Int,
        val flags: Flags = Flags(), val mealType: String? = null, val maxMinutes: Int? = null,
        val equipment: Set<String>? = null, val restrictions: List<Restriction> = emptyList(),
        val targets: List<Target> = emptyList(), val preferredFoodTypes: Set<String> = emptySet(),
        val budgetCents: Long? = null, val budgetConfirmed: Boolean = false,
        val freezePortions: Int = 0, val freezerCapacityPortions: Int? = null,
        val stock: Snapshot? = null, val shopping: Snapshot? = null, val offers: OfferSnapshot? = null
    )
    data class NutrientFact(
        val foodTypeId: String, val unit: UnitCode, val basis: Basis, val state: State,
        val per100: Map<String, BigDecimal?>, val allergens: Set<String>, val allergenEvidence: Evidence,
        val dietTags: Set<String>?, val source: String, val dataQuality: String
    )
    data class RecipeFacts(val byFoodType: Map<String, NutrientFact>)
    data class TemplateLine(val foodTypeId: String, val label: String, val amountPerPortion: Long?, val unit: UnitCode, val basis: Basis, val state: State = State.RAW)
    data class Template(val id: String, val title: String, val mealType: String, val minutes: Int?, val equipment: Set<String>, val ingredients: List<TemplateLine>, val steps: List<String>)
    data class Candidate(val id: String, val template: Template, val ingredients: List<Line>, val portions: Int)
    data class Finding(val severity: Severity, val code: String, val detail: String)
    data class Validation(val verdict: Verdict, val findings: List<Finding>)
    data class Nutrition(val totals: Map<String, BigDecimal?>, val perPortion: Map<String, BigDecimal?>, val partial: Map<String, BigDecimal?>, val gaps: Map<String, List<String>>)
    data class CandidateResult(val candidate: Candidate, val validation: Validation, val nutrition: Nutrition, val score: Int, val contextNotes: List<String>, val ingredientContexts: List<Map<String, Any?>>, val generatorKind: String)
    data class Result(val messages: List<Map<String, Any?>>, val rejected: List<Pair<String, Validation>>)

    private val nutrientKeys = listOf("energyKcal", "proteinG", "carbohydrateG", "fatG", "fiberG", "vitaminB12Ug", "vitaminDUg")

    /** Synthetic, non-claiming recipes: quantities per one portion, never personal recommendations. */
    val offlineTemplates = listOf(
        Template("oat-bowl", "Haferflocken-Bowl", "BREAKFAST", 10, emptySet(),
            listOf(TemplateLine("oats", "Haferflocken", 70, UnitCode.G, Basis.NET), TemplateLine("apple", "Apfel", 120, UnitCode.G, Basis.NET), TemplateLine("water", "Wasser", 180, UnitCode.ML, Basis.NET)),
            listOf("Hände und Zutaten prüfen und waschen, soweit erforderlich.", "Haferflocken mit Wasser vermischen.", "Apfel schneiden und hinzufügen.")),
        Template("lentil-pot", "Linsentopf", "MAIN", 30, setOf("HOB", "POT"),
            listOf(TemplateLine("dry-lentils", "Trockene Linsen", 75, UnitCode.G, Basis.NET), TemplateLine("carrot", "Karotte", 100, UnitCode.G, Basis.NET), TemplateLine("water", "Wasser", 250, UnitCode.ML, Basis.NET)),
            listOf("Gemüse waschen, putzen und schneiden.", "Linsen mit Wasser aufkochen und nach Packungshinweis vollständig garen.", "Karotten mitgaren; vor dem Servieren Gargrad prüfen.")),
        Template("rice-beans", "Reis-Bohnen-Pfanne", "MAIN", 35, setOf("HOB", "POT"),
            listOf(TemplateLine("dry-rice", "Reis (trocken)", 75, UnitCode.G, Basis.NET), TemplateLine("cooked-beans", "Bohnen (gegart)", 130, UnitCode.G, Basis.DRAINED, State.COOKED), TemplateLine("tomato", "Tomate", 100, UnitCode.G, Basis.NET)),
            listOf("Reis nach Packungshinweis vollständig garen.", "Bohnen auf sicheren Verzehrzustand prüfen und ausreichend erhitzen.", "Tomaten schneiden und alles vermengen."))
    )

    interface RecipeCandidateGenerator { fun generate(request: Request): List<Candidate> }
    class OfflineGenerator(private val templates: List<Template> = offlineTemplates) : RecipeCandidateGenerator {
        override fun generate(request: Request): List<Candidate> = templates
            .filter { request.mealType == null || it.mealType == request.mealType }
            .map { t -> Candidate("${request.requestId}-${t.id}", t, t.ingredients.map { i ->
                Line(i.foodTypeId, i.label, Quantity(i.amountPerPortion?.let { Math.multiplyExact(it, request.portions.toLong()) }, i.unit, i.basis, false, i.state))
            }, request.portions) }
    }
    /** Adapter for an OPTIONAL model's untrusted candidates; it cannot bypass validate(). */
    class ProvidedCandidates(private val candidates: List<Candidate>) : RecipeCandidateGenerator {
        override fun generate(request: Request) = candidates
    }

    fun validate(candidate: Candidate, request: Request, facts: RecipeFacts): Validation {
        val f = mutableListOf<Finding>()
        fun add(severity: Severity, code: String, detail: String) { f += Finding(severity, code, detail) }
        if (request.requestId.isBlank() || candidate.id.isBlank() || candidate.template.title.isBlank()) add(Severity.BLOCK, "INVALID_ID", "Missing request/candidate ID or title")
        if (candidate.portions < 1 || candidate.portions != request.portions) add(Severity.BLOCK, "PORTIONS", "Portion count inconsistent or invalid")
        if (candidate.ingredients.isEmpty() || candidate.template.steps.isEmpty() || candidate.template.steps.any { it.isBlank() }) add(Severity.BLOCK, "RECIPE_STRUCTURE", "Missing ingredients or preparation steps")
        if (candidate.template.minutes == null) add(Severity.REVIEW, "TIME_UNKNOWN", "Preparation time not evidenced")
        if (request.maxMinutes != null && candidate.template.minutes != null && candidate.template.minutes > request.maxMinutes) add(Severity.BLOCK, "TIME_EXCEEDED", "Recipe exceeds requested time")
        if (request.equipment != null && !request.equipment.containsAll(candidate.template.equipment)) add(Severity.BLOCK, "EQUIPMENT_MISSING", "Required equipment not available")
        if (request.equipment == null && candidate.template.equipment.isNotEmpty()) add(Severity.REVIEW, "EQUIPMENT_UNKNOWN", "Available equipment not supplied")
        val confirmedRestrictions = request.restrictions.filter { it.confirmed }
        confirmedRestrictions.filterNot { it.code.startsWith("ALLERGEN:") || it.code.startsWith("DIET:") || it.code.startsWith("FOOD:") }.forEach {
            add(Severity.BLOCK, "RESTRICTION_UNSUPPORTED", "Cannot verify restriction '${it.code}'")
        }
        if (confirmedRestrictions.any { it.code.substringAfter(':', "").isBlank() }) add(Severity.BLOCK, "RESTRICTION_EMPTY", "A confirmed restriction is missing a value")
        val seen = mutableSetOf<String>()
        candidate.ingredients.forEach { i ->
            if (i.foodTypeId.isBlank() || i.label.isBlank() || !seen.add(i.foodTypeId)) add(Severity.BLOCK, "INGREDIENT_ID", "Blank or duplicate ingredient")
            val q = i.quantity
            if (q.amount == null) add(Severity.REVIEW, "QUANTITY_UNKNOWN", "${i.label}: amount unknown")
            else if (q.amount <= 0) add(Severity.BLOCK, "QUANTITY_NONPOSITIVE", "${i.label}: amount must be positive")
            if ((q.unit == UnitCode.PIECE && q.basis != Basis.PIECE) || (q.unit == UnitCode.PORTION && q.basis != Basis.PER_PORTION) || (q.unit in setOf(UnitCode.G, UnitCode.ML) && q.basis in setOf(Basis.PIECE, Basis.PER_PORTION))) add(Severity.BLOCK, "UNIT_BASIS", "${i.label}: incompatible unit and basis")
            if (q.basis == Basis.UNKNOWN || q.state == State.UNKNOWN) add(Severity.REVIEW, "BASIS_UNKNOWN", "${i.label}: amount basis/preparation state unknown")
            val fact = facts.byFoodType[i.foodTypeId]
            val confirmed = confirmedRestrictions
            confirmed.filter { it.code.startsWith("FOOD:") && it.code.removePrefix("FOOD:").equals(i.foodTypeId, ignoreCase = true) }.forEach {
                add(Severity.BLOCK, "FOOD_FORBIDDEN", "${i.label}: explicitly excluded food type")
            }
            val forbiddenAllergens = confirmed.filter { it.code.startsWith("ALLERGEN:") }.map { it.code.removePrefix("ALLERGEN:").uppercase() }.toSet()
            val knownAllergens = fact?.allergens?.map { it.uppercase() }?.toSet() ?: emptySet()
            if (forbiddenAllergens.intersect(knownAllergens).isNotEmpty()) add(Severity.BLOCK, "ALLERGEN_CONFLICT", "${i.label}: confirmed allergen restriction conflicts with ingredient")
            else if (forbiddenAllergens.isNotEmpty() && (fact?.allergenEvidence != Evidence.COMPLETE || fact.source.isBlank() || fact.dataQuality !in setOf("CONFIRMED", "SOURCED"))) add(Severity.REVIEW, "ALLERGEN_UNVERIFIED", "${i.label}: allergens not comprehensively evidenced")
            if (fact?.allergenEvidence != Evidence.COMPLETE || fact.source.isBlank() || fact.dataQuality !in setOf("CONFIRMED", "SOURCED")) add(Severity.REVIEW, "ALLERGEN_DATA_GAP", "${i.label}: allergen evidence incomplete; do not claim allergen-free")
            confirmed.filter { it.code.startsWith("DIET:") }.forEach { r ->
                val diet = r.code.removePrefix("DIET:").uppercase()
                if (fact?.dietTags == null) add(Severity.REVIEW, "DIET_UNKNOWN", "${i.label}: dietary status unknown for $diet")
                else if (diet !in fact.dietTags.map { it.uppercase() }) add(Severity.BLOCK, "DIET_CONFLICT", "${i.label}: violates confirmed diet $diet")
            }
        }
        val ids = candidate.template.ingredients.map { it.foodTypeId }
        if (ids != candidate.ingredients.map { it.foodTypeId }) add(Severity.BLOCK, "INGREDIENT_MISMATCH", "Candidate ingredients do not match template")
        else candidate.template.ingredients.zip(candidate.ingredients).forEach { (spec, i) ->
            if (i.quantity.unit != spec.unit || i.quantity.basis != spec.basis || i.quantity.state != spec.state ||
                (spec.amountPerPortion != null && i.quantity.amount != safeMultiply(spec.amountPerPortion, candidate.portions.toLong())))
                add(Severity.BLOCK, "CONTRADICTORY_AMOUNT", "${i.label}: contradictory recipe quantity / unit / preparation state")
        }
        if (request.freezePortions < 0 || request.freezePortions > candidate.portions) add(Severity.BLOCK, "FREEZE_PORTIONS", "Freezing count outside planned portions")
        if (request.freezePortions > 0) {
            if (request.freezerCapacityPortions == null) add(Severity.REVIEW, "FREEZER_UNKNOWN", "Freezing capacity unverified; no storage safety guarantee")
            else if (request.freezerCapacityPortions < request.freezePortions) add(Severity.BLOCK, "FREEZER_INSUFFICIENT", "Not enough confirmed freezing capacity")
            add(Severity.REVIEW, "STORAGE_SAFETY", "Cooling, temperature and storage duration require separate verification")
        }
        if (request.budgetConfirmed) add(Severity.REVIEW, "BUDGET_UNVERIFIED", "Full recipe cost unavailable: cannot confirm budget compliance")
        return Validation(when { f.any { it.severity == Severity.BLOCK } -> Verdict.BLOCKED; f.any { it.severity == Severity.REVIEW } -> Verdict.REVIEW_REQUIRED; else -> Verdict.STRUCTURALLY_VALID }, f)
    }
    private fun safeMultiply(a: Long, b: Long): Long? = try { Math.multiplyExact(a, b) } catch (_: ArithmeticException) { null }
    private fun safeAdd(a: Long, b: Long): Long? = try { Math.addExact(a, b) } catch (_: ArithmeticException) { null }

    fun estimateNutrition(candidate: Candidate, facts: RecipeFacts): Nutrition {
        val total = linkedMapOf<String, BigDecimal?>()
        val partial = linkedMapOf<String, BigDecimal?>()
        val gaps = linkedMapOf<String, List<String>>()
        for (key in nutrientKeys) {
            var subtotal = BigDecimal.ZERO
            var knownCount = 0
            val problems = mutableListOf<String>()
            for (i in candidate.ingredients) {
                val fact = facts.byFoodType[i.foodTypeId]
                val q = i.quantity
                val per100 = fact?.per100?.get(key)
                when {
                    q.amount == null || q.amount <= 0 -> problems += "${i.foodTypeId}: quantity unavailable"
                    fact == null -> problems += "${i.foodTypeId}: nutrition facts unavailable"
                    per100 == null -> problems += "${i.foodTypeId}: $key unavailable"
                    q.unit !in setOf(UnitCode.G, UnitCode.ML) || q.unit != fact.unit || q.basis != fact.basis || q.state != fact.state -> problems += "${i.foodTypeId}: incompatible quantity basis/state for $key"
                    fact.source.isBlank() || fact.dataQuality !in setOf("CONFIRMED", "SOURCED") -> problems += "${i.foodTypeId}: no reliable nutrition provenance"
                    per100.signum() < 0 -> problems += "${i.foodTypeId}: negative $key is invalid"
                    else -> { subtotal += per100.multiply(BigDecimal.valueOf(q.amount)).divide(BigDecimal(100), 8, RoundingMode.HALF_UP); knownCount++ }
                }
            }
            partial[key] = if (knownCount == 0) null else subtotal.stripTrailingZeros()
            gaps[key] = problems
            total[key] = if (problems.isEmpty()) subtotal.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros() else null
        }
        return Nutrition(total, total.mapValues { (_, v) -> v?.divide(BigDecimal.valueOf(candidate.portions.toLong()), 4, RoundingMode.HALF_UP)?.stripTrailingZeros() }, partial, gaps)
    }

    private fun dated(at: String?): Boolean = at != null && runCatching { OffsetDateTime.parse(at) }.isSuccess
    private fun approved(s: Snapshot): Boolean = s.shared && dated(s.asOf) && !s.source.isNullOrBlank() && s.quality in setOf("CONFIRMED", "SOURCED")

    private fun context(request: Request, candidate: Candidate): Triple<Int, List<String>, List<Map<String, Any?>>> {
        var score = 0
        val notes = mutableListOf<String>()
        val details = mutableListOf<Map<String, Any?>>()
        val stock = request.stock?.takeIf { request.flags.considerStock && approved(it) }
        val shopping = request.shopping?.takeIf { request.flags.considerShopping && approved(it) }
        val offers = request.offers?.takeIf { request.flags.considerOffers && it.shared && dated(it.asOf) }
        if (request.flags.considerStock && stock == null) notes += "STOCK_SNAPSHOT_UNAVAILABLE"
        if (request.flags.considerShopping && shopping == null) notes += "SHOPPING_SNAPSHOT_UNAVAILABLE"
        if (request.flags.considerOffers && offers == null) notes += "OFFERS_SNAPSHOT_UNAVAILABLE"
        for (i in candidate.ingredients) {
            val item = linkedMapOf<String, Any?>("foodTypeId" to i.foodTypeId)
            if (stock != null) {
                val matching = stock.entries.filter { it.foodTypeId == i.foodTypeId }
                val compatible = matching.filter { it.quantity.unit == i.quantity.unit && it.quantity.basis == i.quantity.basis && it.quantity.state == i.quantity.state && it.quantity.confirmed && it.quantity.amount != null }
                val sum = compatible.map { it.quantity.amount!! }.takeIf { it.all { n -> n >= 0 } }
                    ?.fold(0L as Long?) { acc, n -> if (acc == null) null else safeAdd(acc, n) }
                val known = sum != null && matching.size == compatible.size && (matching.isNotEmpty() || stock.coverage == Completeness.COMPLETE)
                val need = i.quantity.amount
                item["stockAvailable"] = if (known) sum else null
                item["remainingNeed"] = if (known && need != null && need >= 0) (if (sum!! >= need) 0L else need - sum) else null
                if (!known) notes += "STOCK_AMOUNT_UNKNOWN:${i.foodTypeId}"
                else if (sum!! > 0L) score += 10
                if (matching.any { it.bestBefore != null && it.bestBefore < request.shoppingDate }) notes += "BEST_BEFORE_REVIEW:${i.foodTypeId}"
            }
            if (shopping != null) {
                val matching = shopping.entries.filter { it.foodTypeId == i.foodTypeId }
                val compatible = matching.filter { it.quantity.unit == i.quantity.unit && it.quantity.basis == i.quantity.basis && it.quantity.state == i.quantity.state && it.quantity.confirmed && it.quantity.amount != null }
                val plannedSum = compatible.map { it.quantity.amount!! }.takeIf { it.all { n -> n >= 0 } }
                    ?.fold(0L as Long?) { acc, n -> if (acc == null) null else safeAdd(acc, n) }
                val known = plannedSum != null && matching.isNotEmpty() && matching.size == compatible.size
                item["plannedShoppingQuantity"] = if (known) plannedSum else null
                if (matching.isNotEmpty() && !known) notes += "SHOPPING_QUANTITY_UNKNOWN:${i.foodTypeId}"
                if (known) score += 5
                // Planned purchase NEVER increases stockAvailable or decreases remainingNeed.
            }
            if (offers != null) {
                val valid = offers.offers.filter { it.foodTypeId == i.foodTypeId && it.priceCents != null && it.priceCents >= 0 && it.currency == "EUR" && it.validFrom != null && it.validUntil != null && it.validFrom <= request.shoppingDate && it.validUntil >= request.shoppingDate && it.source?.isNotBlank() == true && it.quality in setOf("SOURCED", "CONFIRMED") }
                item["validOfferCount"] = valid.size
                if (valid.isNotEmpty()) score += 3
                if (offers.offers.any { it.foodTypeId == i.foodTypeId } && valid.isEmpty()) notes += "NO_VERIFIABLE_OFFER:${i.foodTypeId}"
            }
            details += item
        }
        score += candidate.ingredients.count { it.foodTypeId in request.preferredFoodTypes } * 2
        return Triple(score, notes.distinct().sorted(), details)
    }

    /** No side effects. Explicitly rejects blocked candidates; unknowns stay review-required. */
    fun propose(request: Request, facts: RecipeFacts, generator: RecipeCandidateGenerator = OfflineGenerator()): Result {
        require(request.portions > 0) { "portions must be >= 1" }
        require(request.requestId.isNotBlank()) { "requestId required" }
        require(request.maxMinutes == null || request.maxMinutes > 0) { "maxMinutes must be positive" }
        require(request.budgetCents == null || request.budgetCents >= 0) { "negative budget" }
        OffsetDateTime.parse(request.createdAt)
        require(request.targets.filter { it.confirmed }.all { it.min == null || it.max == null || it.min <= it.max }) { "confirmed target bounds inconsistent" }
        val candidates = generator.generate(request)
        val rejected = mutableListOf<Pair<String, Validation>>()
        val ready = mutableListOf<CandidateResult>()
        for (candidate in candidates) {
            val rawValidation = validate(candidate, request, facts)
            val validation = if (generator is ProvidedCandidates && rawValidation.verdict != Verdict.BLOCKED) {
                val extra = Finding(Severity.REVIEW, "GENERATED_CANDIDATE_UNVERIFIED", "External/model candidate requires human review; no safety certification")
                Validation(Verdict.REVIEW_REQUIRED, rawValidation.findings + extra)
            } else rawValidation
            if (validation.verdict == Verdict.BLOCKED) { rejected += candidate.id to validation; continue }
            val context = context(request, candidate)
            val nutrition = estimateNutrition(candidate, facts)
            ready += CandidateResult(candidate, validation, nutrition, context.first, context.second, context.third,
                if (generator is OfflineGenerator) "OFFLINE_TEMPLATE" else "UNTRUSTED_PROVIDED")
        }
        val sorted = ready.sortedWith(compareByDescending<CandidateResult> { it.score }.thenBy { it.candidate.id })
        return Result(sorted.map { toContract(it, request) }, rejected)
    }

    fun toContract(result: CandidateResult, request: Request): Map<String, Any?> {
        val c = result.candidate
        val nutritionGaps = result.nutrition.gaps.filterValues { it.isNotEmpty() }
        val targetChecks = request.targets.filter { it.confirmed }.map { target ->
            val actual = result.nutrition.perPortion[target.nutrient]
            val status = when {
                actual == null -> "UNKNOWN"
                target.min != null && actual < target.min -> "BELOW"
                target.max != null && actual > target.max -> "ABOVE"
                else -> "IN_RANGE"
            }
            linkedMapOf<String, Any?>("nutrient" to target.nutrient, "min" to target.min,
                "max" to target.max, "perPortion" to actual, "status" to status)
        }
        val targetWarnings = targetChecks.filter { it["status"] != "IN_RANGE" }.map { "TARGET_${it["status"]}:${it["nutrient"]}" }
        val warnings = (result.validation.findings.map { it.code + ": " + it.detail } + result.contextNotes +
            nutritionGaps.map { (k, v) -> "NUTRIENT_INCOMPLETE:$k:${v.joinToString(";")}" } + targetWarnings).distinct()
        return linkedMapOf(
            "contractVersion" to CONTRACT_VERSION, "messageType" to "recipe.draft",
            "meta" to linkedMapOf("producer" to PRODUCER, "createdAt" to request.createdAt, "traceId" to request.requestId,
                "inputsSnapshotAt" to null, "coverageNote" to "Offline template / user-supplied facts; no external lookup"),
            "payload" to linkedMapOf(
                "draftId" to c.id, "title" to c.template.title, "portionCount" to c.portions,
                "ingredients" to c.ingredients.map { i -> linkedMapOf(
                    "label" to i.label, "foodTypeId" to i.foodTypeId, "productRef" to null,
                    "quantity" to linkedMapOf("amount" to i.quantity.amount, "unit" to i.quantity.unit.wire,
                        "basis" to i.quantity.basis.name, "confirmed" to false,
                        "notes" to "${i.quantity.state.name} weight/volume; proposed, not measured"),
                    "sourceStatus" to "ESTIMATED", "allergenNotes" to i.allergenNotes
                ) },
                "steps" to c.template.steps, "flags" to linkedMapOf("considerStock" to request.flags.considerStock,
                    "considerShopping" to request.flags.considerShopping, "considerOffers" to request.flags.considerOffers),
                "nutrition" to linkedMapOf("total" to result.nutrition.totals, "perPortion" to result.nutrition.perPortion,
                    "knownPartial" to result.nutrition.partial, "gaps" to nutritionGaps,
                    "method" to "per100 * compatible raw quantity / 100; null for incomplete totals"),
                "warnings" to warnings, "status" to "DRAFT",
                "xRecipeKi" to linkedMapOf("generator" to result.generatorKind, "verification" to result.validation.verdict.name,
                    "findings" to result.validation.findings.map { linkedMapOf("severity" to it.severity.name, "code" to it.code, "detail" to it.detail) },
                    "score" to result.score, "ingredientsContext" to result.ingredientContexts, "mealPrep" to linkedMapOf(
                        "plannedPortions" to c.portions, "freezeRequestedPortions" to request.freezePortions,
                        "freezerCapacityPortions" to request.freezerCapacityPortions, "rawIngredientsCharged" to false,
                        "preparedPortionsCreated" to false, "consumptionRecorded" to false),
                    "requiredEquipment" to c.template.equipment.sorted(), "minutes" to c.template.minutes,
                    "budgetCompliance" to null, "actualCostCents" to null,
                    "targetsApplied" to request.targets.filter { it.confirmed }.map { it.nutrient },
                    "targetsEvaluation" to targetChecks
                )
            )
        )
    }
}

/** Strict, dependency-free JSON encoding for interchange. Android host uses its own JSON parser. */
object JsonOutput {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.flatMap { c -> when (c) {
            '"' -> "\\\"".toList(); '\\' -> "\\\\".toList(); '\b' -> "\\b".toList(); '\u000c' -> "\\f".toList(); '\n' -> "\\n".toList(); '\r' -> "\\r".toList(); '\t' -> "\\t".toList()
            else -> if (c.code < 32) "\\u%04x".format(c.code).toList() else listOf(c)
        } }.joinToString("") + "\""
        is Boolean, is Int, is Long, is BigDecimal -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> encode(k as String) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> throw IllegalArgumentException("Unsupported JSON type: ${value::class}")
    }
}
