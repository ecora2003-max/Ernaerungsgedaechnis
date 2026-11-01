package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.BaseUnit
import de.ernaehrung.demo.data.local.RecipeInput
import de.ernaehrung.demo.data.local.RecipeItemInput

/**
 * Read-only host boundary for untrusted EG-Rezept-KI recipe.draft suggestions.
 * This is NOT the generator, a full JSON-schema validator or a FoodStore writer.
 * Only independently confirmed host facts may turn a draft into a RecipeInput.
 */
internal object RecipeDraftHostGate {
    const val CONTRACT = "1.0.0-draft"
    private const val MAX_MILLI = 1_000_000_000_000L

    data class Flags(val considerStock: Boolean, val considerShopping: Boolean, val considerOffers: Boolean)
    data class Evidence<T>(val value: T, val approvedForRequest: Boolean)
    data class Context<S, L, O>(
        val stock: S?, val shopping: L?, val offers: O?,
        val gaps: Set<String>
    )

    /** Inactive and unapproved contexts are never forwarded, including to optional local models. */
    fun <S, L, O> context(
        flags: Flags, stock: Evidence<S>?, shopping: Evidence<L>?, offers: Evidence<O>?
    ): Context<S, L, O> {
        val gaps = linkedSetOf<String>()
        fun <T> select(enabled: Boolean, evidence: Evidence<T>?, name: String): T? {
            if (!enabled) return null
            if (evidence?.approvedForRequest != true) {
                gaps += "${name}_SNAPSHOT_UNAVAILABLE"
                return null
            }
            return evidence.value
        }
        return Context(
            select(flags.considerStock, stock, "STOCK"),
            select(flags.considerShopping, shopping, "SHOPPING"),
            select(flags.considerOffers, offers, "OFFERS"),
            gaps
        )
    }

    enum class UnitCode { G, ML, PORTION, PIECE }
    enum class Basis { NET, DRAINED, GROSS, PER_PORTION, PIECE, UNKNOWN }
    enum class State { RAW, COOKED, UNKNOWN }
    enum class Status { BLOCKED, REVIEW_REQUIRED, READY_FOR_EXPLICIT_SAVE }

    /** Values from an untrusted draft. Never treat these as confirmed product facts. */
    data class DraftLine(
        val foodTypeId: String, val label: String, val amount: Long?,
        val unit: UnitCode, val basis: Basis, val state: State
    )
    data class Draft(
        val contractVersion: String, val messageType: String,
        val title: String, val steps: String, val portions: Int,
        val flags: Flags, val lines: List<DraftLine>
    )
    /** Independently resolved host facts; a FoodType is NOT a ProductVersion. */
    data class HostLine(
        val foodTypeId: String, val productVersionId: String?,
        val identityConfirmed: Boolean, val unit: BaseUnit?,
        val basis: Basis?, val basisConfirmed: Boolean,
        val state: State?, val stateConfirmed: Boolean,
        /** Confirmed milligrams-equivalent in host milli-grams for one piece. */
        val confirmedPieceMassMilliGrams: Long? = null,
        /** true=present, false=independently confirmed absent, null=unknown. */
        val allergens: Map<String, Boolean?> = emptyMap()
    )
    data class Request(
        val flags: Flags, val portions: Int,
        val confirmedRestrictions: Set<String> = emptySet(),
        /** Must originate from a separate explicit user action, never the model. */
        val userConfirmedRecipeSave: Boolean = false
    )
    data class Review(
        val status: Status, val blockers: Set<String>, val gaps: Set<String>,
        /** Null unless independently validated AND separately confirmed. No side effects. */
        val input: RecipeInput?
    )

    fun review(draft: Draft, request: Request, facts: Map<String, HostLine>): Review {
        val blockers = linkedSetOf<String>()
        val gaps = linkedSetOf<String>()
        if (draft.contractVersion != CONTRACT || draft.messageType != "recipe.draft") {
            blockers += "UNSUPPORTED_CONTRACT"
        }
        if (draft.flags != request.flags) blockers += "CONTEXT_CONSENT_MISMATCH"
        if (draft.portions != request.portions || draft.portions !in 1..1000) blockers += "PORTIONS_MISMATCH"
        if (draft.title.isBlank() || draft.title.length > 200 ||
            draft.steps.isBlank() || draft.steps.length > 20000 ||
            draft.lines.isEmpty() || draft.lines.size > 64) blockers += "INVALID_DRAFT"
        if (draft.lines.map { it.foodTypeId }.any { it.isBlank() } ||
            draft.lines.map { it.foodTypeId }.toSet().size != draft.lines.size) {
            blockers += "AMBIGUOUS_FOOD_TYPE"
        }

        val allergies = linkedSetOf<String>()
        val bannedFoods = linkedSetOf<String>()
        for (code in request.confirmedRestrictions) {
            when {
                code.startsWith("ALLERGEN:") && code.length > 9 -> allergies += code.removePrefix("ALLERGEN:")
                code.startsWith("FOOD:") && code.length > 5 -> bannedFoods += code.removePrefix("FOOD:")
                else -> blockers += "UNSUPPORTED_RESTRICTION"
            }
        }
        val items = mutableListOf<RecipeItemInput>()
        val versionIds = mutableSetOf<String>()
        for (line in draft.lines) {
            if (line.foodTypeId in bannedFoods) blockers += "CONFIRMED_FOOD_CONFLICT"
            if (line.amount == null) gaps += "UNKNOWN_AMOUNT"
            else if (line.amount <= 0) blockers += "INVALID_AMOUNT"
            if (line.state == State.UNKNOWN) gaps += "UNKNOWN_PREPARATION_STATE"
            val host = facts[line.foodTypeId]
            if (host == null || host.foodTypeId != line.foodTypeId ||
                !host.identityConfirmed || host.productVersionId.isNullOrBlank()) {
                gaps += "UNRESOLVED_PRODUCT_VERSION"
                continue
            }
            if (!versionIds.add(host.productVersionId)) blockers += "DUPLICATE_PRODUCT_VERSION"
            if (!host.basisConfirmed || host.basis == null ||
                !host.stateConfirmed || host.state == null) {
                gaps += "UNCONFIRMED_BASIS_OR_STATE"
            } else if (host.basis != line.basis || host.state != line.state) {
                blockers += "BASIS_OR_STATE_MISMATCH"
            }
            for (allergen in allergies) {
                when (host.allergens[allergen]) {
                    true -> blockers += "CONFIRMED_ALLERGEN_CONFLICT"
                    false -> Unit
                    null -> gaps += "ALLERGEN_STATUS_UNKNOWN"
                }
            }
            val expectedUnit = when (line.unit) {
                UnitCode.G, UnitCode.PIECE -> BaseUnit.GRAM
                UnitCode.ML -> BaseUnit.MILLILITRE
                UnitCode.PORTION -> BaseUnit.PORTION
            }
            val basisAllowed = when (line.unit) {
                UnitCode.G, UnitCode.ML -> line.basis == Basis.NET || line.basis == Basis.DRAINED
                UnitCode.PORTION -> line.basis == Basis.PER_PORTION
                UnitCode.PIECE -> line.basis == Basis.PIECE
            }
            if (!basisAllowed || host.unit != expectedUnit) blockers += "INCOMPATIBLE_UNIT_BASIS"
            val amount = line.amount
            if (amount != null && amount > 0 && basisAllowed && host.unit == expectedUnit) {
                val factor = if (line.unit == UnitCode.PIECE) host.confirmedPieceMassMilliGrams else 1000L
                if (factor == null || factor <= 0) {
                    gaps += "UNCONFIRMED_PIECE_SIZE"
                } else {
                    val milli = try { Math.multiplyExact(amount, factor) }
                    catch (_: ArithmeticException) { null }
                    if (milli == null || milli !in 1..MAX_MILLI) blockers += "AMOUNT_OVERFLOW"
                    else items += RecipeItemInput(host.productVersionId, milli,
                        "${line.state.name} / ${line.basis.name}")
                }
            }
        }
        if (!request.userConfirmedRecipeSave) gaps += "EXPLICIT_RECIPE_CONFIRMATION_REQUIRED"
        val status = when {
            blockers.isNotEmpty() -> Status.BLOCKED
            gaps.isNotEmpty() || items.size != draft.lines.size -> Status.REVIEW_REQUIRED
            else -> Status.READY_FOR_EXPLICIT_SAVE
        }
        val input = if (status == Status.READY_FOR_EXPLICIT_SAVE) {
            RecipeInput(draft.title.trim(), Math.multiplyExact(draft.portions.toLong(), 1000L),
                items, draft.steps)
        } else null
        return Review(status, blockers, gaps, input)
    }
}
