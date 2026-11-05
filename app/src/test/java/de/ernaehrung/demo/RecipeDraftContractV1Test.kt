package de.ernaehrung.demo

import de.ernaehrung.demo.data.integration.RecipeDraftBoundedJson
import de.ernaehrung.demo.data.integration.RecipeDraftContractV1
import de.ernaehrung.demo.data.integration.RecipeDraftV1Import
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class RecipeDraftContractV1Test {
    @Test fun allSharedDraft202012ReferenceVectorsMatchTheRuntimeGate() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "integration/recipe_schema_v1_cases.jsonl"))
        var accepted = 0
        var rejected = 0
        stream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val case = RecipeDraftBoundedJson.decode(line)
            @Suppress("UNCHECKED_CAST")
            val message = case["message"] as Map<String, Any?>
            val valid = runCatching { RecipeDraftContractV1.requireValid(message) }.isSuccess
            assertEquals(case["name"].toString(), case["valid"], valid)
            if (valid) accepted++ else rejected++
        } }
        assertTrue(accepted >= 8 && rejected >= 14)
    }

    @Test fun schemaValidZeroAndUnresolvedIdentityStillCannotPassSemanticImport() {
        val base = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "integration/recipe_draft_v1.json")).bufferedReader().use { it.readText() }
        val decoded = RecipeDraftBoundedJson.decode(base)
        RecipeDraftContractV1.requireValid(decoded)
        val now = OffsetDateTime.parse("2026-11-01T04:30:00+01:00")
        assertEquals(200L, RecipeDraftV1Import.read(base, emptyMap(), now).draft.lines.single().amount)
        @Suppress("UNCHECKED_CAST")
        val payload = decoded["payload"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val ingredient = (payload["ingredients"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val quantity = ingredient["quantity"] as Map<String, Any?>
        assertNotNull(quantity["amount"])
        // Formal v1 allows known zero; the host must independently reject it.
        val zero = base.replace(Regex("\"amount\"\\s*:\\s*[0-9]+"), "\"amount\":0")
        RecipeDraftContractV1.requireValid(RecipeDraftBoundedJson.decode(zero))
        assertTrue(runCatching {
            RecipeDraftV1Import.read(zero, emptyMap(), now)
        }.isFailure)
        val unresolved = base.replace(Regex("\"foodTypeId\"\\s*:\\s*\"[^\"]+\""), "\"foodTypeId\":null")
        RecipeDraftContractV1.requireValid(RecipeDraftBoundedJson.decode(unresolved))
        assertTrue(runCatching {
            RecipeDraftV1Import.read(unresolved, emptyMap(), now)
        }.isFailure)
    }
}
