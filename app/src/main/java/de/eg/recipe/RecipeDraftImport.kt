package de.eg.recipe

import java.time.OffsetDateTime

/**
 * Read-only import of parsed recipe.draft maps. Android host owns JSON decoding.
 * Supplied nutrition, provenance and verification claims remain untrusted.
 */
object RecipeDraftImport {
    data class Ingredient(
        val label: String, val foodTypeId: String?, val productRef: String?,
        val amount: Long?, val unit: RecipeEngine.UnitCode, val basis: RecipeEngine.Basis,
        val confirmed: Boolean, val sourceStatus: String, val allergenNotes: String?
    )
    data class Draft(
        val draftId: String, val title: String, val portions: Int,
        val ingredients: List<Ingredient>, val steps: List<String>,
        val flags: RecipeEngine.Flags, val warnings: List<String>,
        val producer: String, val traceId: String, val createdAt: String,
        val suppliedNutritionClaim: Boolean, val suppliedVerificationClaim: String?
    ) {
        /** Not constructor state: clients cannot copy() an imported draft into trusted state. */
        val requiresIndependentValidation: Boolean get() = true
    }

    private fun obj(v: Any?, field: String): Map<*, *> =
        v as? Map<*, *> ?: throw IllegalArgumentException("$field must be an object")
    private fun arr(v: Any?, field: String): List<*> =
        v as? List<*> ?: throw IllegalArgumentException("$field must be an array")
    private fun str(v: Any?, field: String): String =
        (v as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$field must be nonempty string")
    private fun optionalStr(v: Any?, field: String): String? =
        if (v == null) null else str(v, field)
    private fun bool(v: Any?, field: String): Boolean =
        v as? Boolean ?: throw IllegalArgumentException("$field must be boolean")
    private fun amount(v: Any?): Long? = when (v) {
        null -> null
        is Int -> v.toLong().takeIf { it >= 0 } ?: throw IllegalArgumentException("negative amount")
        is Long -> v.takeIf { it >= 0 } ?: throw IllegalArgumentException("negative amount")
        is java.math.BigInteger -> {
            require(v.signum() >= 0 && v.bitLength() <= 63) { "amount outside supported 64-bit integer range" }
            v.longValueExact()
        }
        else -> throw IllegalArgumentException("amount must be a JSON integer or null")
    }
    private fun unit(v: Any?): RecipeEngine.UnitCode =
        RecipeEngine.UnitCode.values().firstOrNull { it.wire == v }
            ?: throw IllegalArgumentException("unsupported quantity unit")
    private fun basis(v: Any?): RecipeEngine.Basis =
        RecipeEngine.Basis.values().firstOrNull { it.name == v }
            ?: throw IllegalArgumentException("unsupported quantity basis")

    fun read(message: Map<String, Any?>): Draft {
        require(message["contractVersion"] == RecipeEngine.CONTRACT_VERSION) { "unsupported interchange version" }
        require(message["messageType"] == "recipe.draft") { "not a recipe.draft" }
        val meta = obj(message["meta"], "meta")
        val allowedMeta = setOf("producer", "createdAt", "traceId", "inputsSnapshotAt", "coverageNote")
        require(meta.keys.all { it in allowedMeta }) { "unknown unnamespaced meta member" }
        val producer = str(meta["producer"], "meta.producer")
        val traceId = str(meta["traceId"], "meta.traceId")
        val createdAt = str(meta["createdAt"], "meta.createdAt")
        require(runCatching { OffsetDateTime.parse(createdAt) }.isSuccess) { "invalid meta.createdAt" }
        if (meta.containsKey("inputsSnapshotAt") && meta["inputsSnapshotAt"] != null)
            require(runCatching { OffsetDateTime.parse(str(meta["inputsSnapshotAt"], "meta.inputsSnapshotAt")) }.isSuccess) {
                "invalid meta.inputsSnapshotAt"
            }
        if (meta.containsKey("coverageNote") && meta["coverageNote"] != null)
            str(meta["coverageNote"], "meta.coverageNote")

        val p = obj(message["payload"], "payload")
        require(p["status"] == "DRAFT") { "only unconfirmed drafts may be imported" }
        val draftId = str(p["draftId"], "payload.draftId")
        val title = str(p["title"], "payload.title")
        val portions = p["portionCount"]
        val count = when (portions) {
            is Int -> portions
            is Long -> portions.toInt().takeIf { it.toLong() == portions }
            else -> null
        }
        require(count != null && count > 0) { "portionCount must be a supported positive integer" }
        val f = obj(p["flags"], "payload.flags")
        require(f.keys == setOf("considerStock", "considerShopping", "considerOffers")) {
            "flags must have exactly the three independent options"
        }
        val flags = RecipeEngine.Flags(bool(f["considerStock"], "considerStock"),
            bool(f["considerShopping"], "considerShopping"), bool(f["considerOffers"], "considerOffers"))

        val lines = arr(p["ingredients"], "ingredients").mapIndexed { i, v ->
            val line = obj(v, "ingredient[$i]")
            val q = obj(line["quantity"], "ingredient[$i].quantity")
            val u = unit(q["unit"])
            val b = basis(q["basis"])
            require((u == RecipeEngine.UnitCode.PIECE && b == RecipeEngine.Basis.PIECE) ||
                (u == RecipeEngine.UnitCode.PORTION && b == RecipeEngine.Basis.PER_PORTION) ||
                (u in setOf(RecipeEngine.UnitCode.G, RecipeEngine.UnitCode.ML) &&
                    b !in setOf(RecipeEngine.Basis.PIECE, RecipeEngine.Basis.PER_PORTION))) {
                "incompatible quantity unit/basis"
            }
            val source = str(line["sourceStatus"], "ingredient[$i].sourceStatus")
            require(source in setOf("CONFIRMED", "SOURCED", "ESTIMATED", "UNKNOWN")) {
                "unsupported ingredient source status"
            }
            Ingredient(str(line["label"], "ingredient[$i].label"),
                optionalStr(line["foodTypeId"], "ingredient[$i].foodTypeId"),
                optionalStr(line["productRef"], "ingredient[$i].productRef"),
                amount(q["amount"]), u, b, bool(q["confirmed"], "ingredient[$i].confirmed"),
                source, optionalStr(line["allergenNotes"], "ingredient[$i].allergenNotes"))
        }
        require(lines.isNotEmpty()) { "empty ingredient list" }
        val steps = arr(p["steps"], "steps").mapIndexed { i, v -> str(v, "steps[$i]") }
        require(steps.isNotEmpty()) { "recipe requires at least one step" }
        val warnings = arr(p["warnings"], "warnings").mapIndexed { i, v ->
            v as? String ?: throw IllegalArgumentException("warnings[$i] must be a string")
        }
        val extension = p["xRecipeKi"]?.let { obj(it, "xRecipeKi") }
        val prep = extension?.get("mealPrep")?.let { obj(it, "xRecipeKi.mealPrep") }
        for (key in listOf("rawIngredientsCharged", "preparedPortionsCreated", "consumptionRecorded")) {
            if (prep?.containsKey(key) == true)
                require(prep[key] == false) { "draft cannot claim a completed booking: $key" }
        }
        val claim = extension?.get("verification")?.let { str(it, "xRecipeKi.verification") }
        return Draft(draftId, title, count, lines, steps, flags, warnings,
            producer, traceId, createdAt, p["nutrition"] != null, claim)
    }
}
