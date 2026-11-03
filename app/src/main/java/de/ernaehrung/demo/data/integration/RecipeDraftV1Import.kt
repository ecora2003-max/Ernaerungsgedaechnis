package de.ernaehrung.demo.data.integration

import java.math.BigInteger
import java.time.OffsetDateTime
import java.time.Instant
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate

/**
 * Narrow, fail-closed v1 recipe.draft JSON boundary. Never trusts claimed confirmation,
 * nutrition, allergens, raw/cooked state or booking status from a generator.
 *
 * This checks the consumed v1 fields and additional host rules, but is NOT a general
 * JSON-Schema 2020-12 validator. Full pinned-schema validation remains a release gate.
 */
internal object RecipeDraftV1Import {
    data class Imported(
        val draftId: String,
        val createdAt: OffsetDateTime,
        val snapshotAt: OffsetDateTime?,
        val draft: Gate.Draft
    )

    private fun obj(value: Any?, name: String): Map<*, *> =
        value as? Map<*, *> ?: throw IllegalArgumentException(name + " must be an object")
    private fun list(value: Any?, name: String): List<*> =
        value as? List<*> ?: throw IllegalArgumentException(name + " must be an array")
    private fun str(value: Any?, name: String): String =
        (value as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(name + " must be nonempty string")
    /** Reject untrusted display controls and bidi overrides before manual review. */
    private fun previewText(value: Any?, name: String, multiline: Boolean = false): String {
        val text = str(value, name)
        // Inspect Unicode code points, not UTF-16 chars: supplementary
        // format controls (including invisible tag characters) must not pass.
        // U+034F is a default-ignorable combining grapheme joiner (Mn).
        // U+2028/U+2029 are visual line breaks even though they are not ISO controls;
        // accepting them in IDs, titles or steps can spoof the review display.
        var offset = 0
        while (offset < text.length) {
            val cp = Character.codePointAt(text, offset)
            require(
                !(Character.isISOControl(cp) && !(multiline && cp == '\n'.code)) &&
                    Character.getType(cp) != Character.FORMAT.toInt() &&
                    Character.getType(cp) != Character.LINE_SEPARATOR.toInt() &&
                    Character.getType(cp) != Character.PARAGRAPH_SEPARATOR.toInt() &&
                    cp != 0x034F
            ) { name + " contains unsafe display controls" }
            offset += Character.charCount(cp)
        }
        return text
    }
    private fun optionalStr(value: Any?, name: String): String? =
        if (value == null) null else str(value, name)
    private fun bool(value: Any?, name: String): Boolean =
        value as? Boolean ?: throw IllegalArgumentException(name + " must be boolean")
    private fun number(value: Any?, name: String, nullable: Boolean = false): Long? {
        if (nullable && value == null) return null
        val n = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is BigInteger -> if (value.bitLength() <= 63) value.toLong()
                else throw IllegalArgumentException(name + " out of range")
            else -> throw IllegalArgumentException(name + " must be integer")
        }
        require(n >= 0) { name + " must be nonnegative" }
        return n
    }
    private fun date(value: Any?, name: String): OffsetDateTime {
        val s = str(value, name)
        return try { OffsetDateTime.parse(s) }
            catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException(name + " must be an offset date-time")
            }
    }
    private fun withinWindow(value: OffsetDateTime, now: Instant, name: String) {
        require(!value.toInstant().isAfter(now.plusSeconds(300))) {
            name + " is in the future"
        }
        require(!value.toInstant().isBefore(now.minusSeconds(86_400))) {
            name + " is stale"
        }
    }
    /**
     * v1 permits extension properties in recipe payloads. None may assert that a
     * stock, preparation or consumption booking has already occurred. Inspect the
     * entire bounded tree, not just the known xRecipeKi.mealPrep extension path.
     */
    private fun rejectUntrustedBookingClaims(value: Any?) {
        val bookingFields = setOf(
            "rawIngredientsCharged", "preparedPortionsCreated", "consumptionRecorded"
        )
        when (value) {
            is Map<*, *> -> value.forEach { (key, nested) ->
                if (key is String && key in bookingFields) {
                    require(nested == false) { "draft claims a completed booking" }
                }
                rejectUntrustedBookingClaims(nested)
            }
            is List<*> -> value.forEach(::rejectUntrustedBookingClaims)
        }
    }

    private fun enumUnit(value: Any?): Gate.UnitCode = when (value) {
        "g" -> Gate.UnitCode.G
        "ml" -> Gate.UnitCode.ML
        "portion" -> Gate.UnitCode.PORTION
        "piece" -> Gate.UnitCode.PIECE
        else -> throw IllegalArgumentException("unsupported quantity unit")
    }
    private fun enumBasis(value: Any?): Gate.Basis = when (value) {
        "NET" -> Gate.Basis.NET
        "DRAINED" -> Gate.Basis.DRAINED
        "GROSS" -> Gate.Basis.GROSS
        "PIECE" -> Gate.Basis.PIECE
        "PER_PORTION" -> Gate.Basis.PER_PORTION
        "UNKNOWN" -> Gate.Basis.UNKNOWN
        else -> throw IllegalArgumentException("unsupported quantity basis")
    }

    /**
     * reviewedStates must come from a separate, explicit host/user review of each
     * intended RAW/COOKED ingredient. The JSON quantity notes are not evidence.
     */
    fun read(json: String, reviewedStates: Map<String, Gate.State>,
             now: OffsetDateTime): Imported {
        val message = RecipeDraftBoundedJson.decode(json)
        require(message.keys == setOf("contractVersion", "messageType", "meta", "payload")) {
            "unexpected v1 envelope fields"
        }
        require(message["contractVersion"] == Gate.CONTRACT &&
            message["messageType"] == "recipe.draft") { "unsupported recipe contract" }
        val meta = obj(message["meta"], "meta")
        require(meta.keys.all {
            it in setOf("producer", "createdAt", "traceId", "inputsSnapshotAt", "coverageNote")
        }) { "unknown meta member" }
        str(meta["producer"], "meta.producer")
        str(meta["traceId"], "meta.traceId")
        val created = date(meta["createdAt"], "meta.createdAt")
        val snapshot = if (meta["inputsSnapshotAt"] == null) null
            else date(meta["inputsSnapshotAt"], "meta.inputsSnapshotAt")
        if (meta.containsKey("coverageNote"))
            optionalStr(meta["coverageNote"], "meta.coverageNote")
        withinWindow(created, now.toInstant(), "meta.createdAt")
        if (snapshot != null) {
            withinWindow(snapshot, now.toInstant(), "meta.inputsSnapshotAt")
            require(!snapshot.toInstant().isAfter(created.toInstant())) {
                "snapshot is newer than draft"
            }
        }

        val p = obj(message["payload"], "payload")
        rejectUntrustedBookingClaims(p)
        require(p["status"] == "DRAFT") { "only unconfirmed DRAFT status allowed" }
        // The draft ID is displayed beside the recipe title during manual review.
        val draftId = previewText(p["draftId"], "draftId")
        require(draftId.length <= 200) { "draft identifier exceeds host preview limit" }
        val title = previewText(p["title"], "title")
        // Reject content exceeding the host preview limits before it reaches UI or
        // manual confirmation. The later review gate is not the import boundary.
        require(title.length <= 200) { "recipe title exceeds host preview limit" }
        val portions = number(p["portionCount"], "portionCount")!!
        require(portions in 1..1000) { "portion count outside host bounds" }
        val f = obj(p["flags"], "flags")
        require(f.keys == setOf("considerStock", "considerShopping", "considerOffers")) {
            "flags must contain exactly the three consent booleans"
        }
        val flags = Gate.Flags(
            bool(f["considerStock"], "considerStock"),
            bool(f["considerShopping"], "considerShopping"),
            bool(f["considerOffers"], "considerOffers")
        )
        if (flags.considerStock || flags.considerShopping || flags.considerOffers) {
            require(snapshot != null) { "requested context needs dated snapshot" }
        }
        val steps = list(p["steps"], "steps").map {
            previewText(it, "step", multiline = true)
        }
        require(steps.isNotEmpty()) { "empty recipe steps" }
        val stepText = steps.joinToString("\n")
        require(stepText.length <= 20_000) { "recipe steps exceed host preview limit" }
        list(p["warnings"], "warnings").forEach {
            // Warnings are untrusted text too: do not allow hidden bidi or
            // line-break controls to masquerade as a different review item.
            previewText(it, "warning")
        }
        if (p.containsKey("nutrition") && p["nutrition"] != null)
            obj(p["nutrition"], "nutrition") // Claims are deliberately discarded.

        // Known extension shapes are checked even though all side-effect claims
        // have already been rejected recursively above.
        val extension = p["xRecipeKi"]?.let { obj(it, "xRecipeKi") }
        extension?.get("mealPrep")?.let { obj(it, "mealPrep") }

        val ingredients = list(p["ingredients"], "ingredients")
        require(ingredients.size in 1..64) { "recipe ingredient count exceeds host preview limit" }
        val lines = ingredients.mapIndexed { index, value ->
            val line = obj(value, "ingredient " + index)
            // The canonical ingredient ID is displayed and used for independent review.
            val id = previewText(line["foodTypeId"], "ingredient.foodTypeId")
            require(id.length <= 200) { "ingredient identity exceeds host preview limit" }
            val label = previewText(line["label"], "ingredient.label")
            require(label.length <= 200) { "ingredient label exceeds host preview limit" }
            optionalStr(line["productRef"], "ingredient.productRef")
            optionalStr(line["allergenNotes"], "ingredient.allergenNotes")
            require(str(line["sourceStatus"], "sourceStatus") in
                setOf("CONFIRMED", "SOURCED", "ESTIMATED", "UNKNOWN")) {
                "invalid sourceStatus"
            }
            val q = obj(line["quantity"], "quantity")
            require(q.containsKey("amount")) { "quantity.amount missing" }
            val amount = number(q["amount"], "quantity.amount", nullable = true)
            // Zero is a known non-positive quantity, not an unknown amount.
            // Reject it before exposing the untrusted draft to the preview UI.
            require(amount == null || amount > 0L) { "quantity.amount must be positive or null" }
            val unit = enumUnit(q["unit"])
            val basis = enumBasis(q["basis"])
            bool(q["confirmed"], "quantity.confirmed") // Untrusted, never forwarded.
            if (q.containsKey("packageCount") && q["packageCount"] != null)
                require(number(q["packageCount"], "packageCount")!! >= 1) {
                    "packageCount must be positive"
                }
            if (q.containsKey("notes"))
                optionalStr(q["notes"], "quantity.notes") // Not evidence of preparation state.
            require(
                (unit == Gate.UnitCode.PIECE && basis == Gate.Basis.PIECE) ||
                (unit == Gate.UnitCode.PORTION && basis == Gate.Basis.PER_PORTION) ||
                (unit in setOf(Gate.UnitCode.G, Gate.UnitCode.ML) &&
                    basis !in setOf(Gate.Basis.PIECE, Gate.Basis.PER_PORTION))
            ) { "incompatible unit and basis" }
            Gate.DraftLine(id, label, amount, unit, basis,
                reviewedStates[id] ?: Gate.State.UNKNOWN)
        }
        require(lines.isNotEmpty()) { "no ingredients" }
        // The import boundary must not hand ambiguous ingredient identities to
        // preview/consent UI, even if a later host review would reject them.
        val ingredientIds = lines.map { it.foodTypeId }.toSet()
        require(ingredientIds.size == lines.size) { "duplicate ingredient food type" }
        // Unmatched review states alone are never forwarded (the ingredient
        // remains UNKNOWN). But a partly matching map must not mix current
        // selections with stale entries from a different draft.
        require(reviewedStates.keys.none { it in ingredientIds } ||
            reviewedStates.keys.all { it in ingredientIds }) {
            "mixed review states from different ingredient scopes"
        }
        return Imported(draftId, created, snapshot, Gate.Draft(
            Gate.CONTRACT, "recipe.draft", title, stepText,
            portions.toInt(), flags, lines
        ))
    }

    /** No caller can get a writable RecipeInput from malformed or stale JSON. */
    fun review(
        json: String, request: Gate.Request, facts: Map<String, Gate.HostLine>,
        reviewedStates: Map<String, Gate.State>, now: OffsetDateTime,
        context: Gate.Context<*, *, *>? = null,
        /** Independently timestamped host snapshot; never copied from model JSON. */
        trustedContextSnapshotAt: OffsetDateTime? = null
    ): Gate.Review = try {
        val imported = read(json, reviewedStates, now)
        val base = Gate.review(imported.draft, request, facts, context)
        val needsContext = request.flags.considerStock ||
            request.flags.considerShopping || request.flags.considerOffers
        when {
            !needsContext || base.status == Gate.Status.BLOCKED -> base
            trustedContextSnapshotAt == null -> base.copy(
                status = Gate.Status.REVIEW_REQUIRED,
                gaps = base.gaps + "TRUSTED_CONTEXT_SNAPSHOT_REQUIRED", input = null
            )
            trustedContextSnapshotAt.toInstant().isAfter(now.toInstant()) ->
                base.copy(
                    status = Gate.Status.BLOCKED,
                    blockers = base.blockers + "TRUSTED_CONTEXT_SNAPSHOT_IN_FUTURE",
                    input = null
                )
            // The independently approved typed content must refer to the same
            // snapshot instant as both the host timestamp and the draft. A
            // valid digest for another instant cannot attest this preview.
            context?.contentBinding?.let {
                it.snapshotAt != trustedContextSnapshotAt.toInstant()
            } == true -> base.copy(
                status = Gate.Status.BLOCKED,
                blockers = base.blockers + "CONTEXT_CONTENT_SNAPSHOT_TIME_MISMATCH",
                input = null
            )
            imported.snapshotAt?.toInstant() != trustedContextSnapshotAt.toInstant() ->
                base.copy(
                    status = Gate.Status.BLOCKED,
                    blockers = base.blockers + "CONTEXT_SNAPSHOT_TIME_MISMATCH",
                    input = null
                )
            // A timestamp alone does not attest the actual stock, shopping or
            // offer rows. Generic host projections may be useful for review,
            // but must not advertise a save-ready recipe without a typed,
            // independently approved content binding.
            context?.contentBindingRequired != true || context?.contentBinding == null ->
                base.copy(
                    status = Gate.Status.REVIEW_REQUIRED,
                    gaps = base.gaps + "TYPED_CONTEXT_CONTENT_REQUIRED",
                    input = null
                )
            else -> base
        }
    } catch (_: IllegalArgumentException) {
        Gate.Review(Gate.Status.BLOCKED, setOf("INVALID_RECIPE_DRAFT_V1"), emptySet(), null)
    }
}
