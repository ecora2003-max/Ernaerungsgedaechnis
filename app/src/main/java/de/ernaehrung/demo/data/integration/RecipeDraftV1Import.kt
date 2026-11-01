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
    private fun optionalStr(value: Any?, name: String): String? =
        if (value == null) null else str(value, name)
    private fun bool(value: Any?, name: String): Boolean =
        value as? Boolean ?: throw IllegalArgumentException(name + " must be boolean")
    private fun number(value: Any?, name: String, nullable: Boolean = false): Long? {
        if (nullable && value == null) return null
        val n = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is BigInteger -> try { value.longValueExact() }
                catch (_: ArithmeticException) { throw IllegalArgumentException(name + " out of range") }
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
        require(p["status"] == "DRAFT") { "only unconfirmed DRAFT status allowed" }
        val draftId = str(p["draftId"], "draftId")
        val title = str(p["title"], "title")
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
            str(it, "step")
        }
        require(steps.isNotEmpty()) { "empty recipe steps" }
        list(p["warnings"], "warnings").forEach {
            require(it is String) { "warnings must contain strings" }
        }
        if (p.containsKey("nutrition") && p["nutrition"] != null)
            obj(p["nutrition"], "nutrition") // Claims are deliberately discarded.

        val extension = p["xRecipeKi"]?.let { obj(it, "xRecipeKi") }
        val prep = extension?.get("mealPrep")?.let { obj(it, "mealPrep") }
        for (key in listOf("rawIngredientsCharged", "preparedPortionsCreated", "consumptionRecorded")) {
            if (prep?.containsKey(key) == true)
                require(prep[key] == false) { "draft claims a completed booking" }
        }

        val lines = list(p["ingredients"], "ingredients").mapIndexed { index, value ->
            val line = obj(value, "ingredient " + index)
            val id = str(line["foodTypeId"], "ingredient.foodTypeId")
            val label = str(line["label"], "ingredient.label")
            optionalStr(line["productRef"], "ingredient.productRef")
            optionalStr(line["allergenNotes"], "ingredient.allergenNotes")
            require(str(line["sourceStatus"], "sourceStatus") in
                setOf("CONFIRMED", "SOURCED", "ESTIMATED", "UNKNOWN")) {
                "invalid sourceStatus"
            }
            val q = obj(line["quantity"], "quantity")
            require(q.containsKey("amount")) { "quantity.amount missing" }
            val amount = number(q["amount"], "quantity.amount", nullable = true)
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
        return Imported(draftId, created, snapshot, Gate.Draft(
            Gate.CONTRACT, "recipe.draft", title, steps.joinToString("\n"),
            portions.toInt(), flags, lines
        ))
    }

    /** No caller can get a writable RecipeInput from malformed or stale JSON. */
    fun review(
        json: String, request: Gate.Request, facts: Map<String, Gate.HostLine>,
        reviewedStates: Map<String, Gate.State>, now: OffsetDateTime,
        context: Gate.Context<*, *, *>? = null
    ): Gate.Review = try {
        Gate.review(read(json, reviewedStates, now).draft, request, facts, context)
    } catch (_: IllegalArgumentException) {
        Gate.Review(Gate.Status.BLOCKED, setOf("INVALID_RECIPE_DRAFT_V1"), emptySet(), null)
    }
}
