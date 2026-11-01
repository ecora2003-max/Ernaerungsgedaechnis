package de.ernaehrung.demo.data.integration

import de.ernaehrung.demo.data.local.WriteRules
import java.time.Instant

/**
 * Pure, versioned binding for host-reviewed context content. No model-provided
 * digest, free-form toString(), or mutable collection identity is trusted.
 * A digest is an optimistic change detector, NOT user authentication or proof
 * that an external source or snapshot was independently verified.
 */
internal object RecipeContextContentBinding {
    enum class Source { STOCK, SHOPPING, OFFERS }

    data class Line(
        val identity: String,
        val versionId: String?,
        val amountMilli: Long?,
        val unit: String?,
        val storeId: String? = null
    )
    data class Snapshot(
        val source: Source,
        val scopeId: String,
        val revision: String,
        val capturedAt: Instant,
        val lines: List<Line>
    )
    data class Binding(
        val flags: RecipeDraftHostGate.Flags,
        val snapshotAt: Instant,
        val stockDigest: String?,
        val shoppingDigest: String?,
        val offersDigest: String?,
        val digest: String
    )

    private fun digest(source: Source, at: Instant, snapshot: Snapshot?): String? {
        if (snapshot == null || snapshot.source != source || snapshot.capturedAt != at ||
            snapshot.scopeId.isBlank() || snapshot.revision.isBlank() ||
            snapshot.lines.size > 10_000) return null
        val lines = snapshot.lines
        if (lines.any { it.identity.isBlank() ||
                it.versionId?.isBlank() == true || it.storeId?.isBlank() == true ||
                it.amountMilli != null && it.amountMilli < 0 ||
                it.amountMilli != null && it.unit.isNullOrBlank() }) return null
        // The identity + version + store tuple must not occur twice, even if
        // the two rows claim different quantities. This is not an aggregation.
        val keys = lines.map { Triple(it.identity, it.versionId, it.storeId) }
        if (keys.toSet().size != keys.size) return null
        val ordered = lines.sortedWith(compareBy<Line>({ it.identity },
            { it.versionId ?: "" }, { it.storeId ?: "" }))
        return WriteRules.fingerprint("RECIPE_CONTEXT_SOURCE_V1", source.name,
            snapshot.scopeId, snapshot.revision, at.toString(),
            ordered.map { listOf(it.identity, it.versionId, it.amountMilli,
                it.unit, it.storeId) })
    }

    fun bind(
        flags: RecipeDraftHostGate.Flags,
        snapshotAt: Instant,
        stock: Snapshot?,
        shopping: Snapshot?,
        offers: Snapshot?
    ): Binding? {
        val s = if (flags.considerStock) digest(Source.STOCK, snapshotAt, stock) else null
        val l = if (flags.considerShopping) digest(Source.SHOPPING, snapshotAt, shopping) else null
        val o = if (flags.considerOffers) digest(Source.OFFERS, snapshotAt, offers) else null
        if (flags.considerStock && s == null || flags.considerShopping && l == null ||
            flags.considerOffers && o == null) return null
        return Binding(flags, snapshotAt, s, l, o,
            WriteRules.fingerprint("RECIPE_CONTEXT_BINDING_V1",
                flags.considerStock, flags.considerShopping, flags.considerOffers,
                snapshotAt.toString(), s, l, o))
    }

    fun matches(
        binding: Binding,
        flags: RecipeDraftHostGate.Flags,
        stock: Snapshot?,
        shopping: Snapshot?,
        offers: Snapshot?
    ): Boolean = bind(flags, binding.snapshotAt, stock, shopping, offers) == binding
}
