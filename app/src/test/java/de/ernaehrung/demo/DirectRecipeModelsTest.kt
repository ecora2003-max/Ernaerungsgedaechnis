package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.ai.*
import de.ernaehrung.demo.data.integration.*
import de.ernaehrung.demo.data.integration.RecipeDraftHostGate as Gate
import de.ernaehrung.demo.data.offers.*
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol, not real Google inference or a Nano hardware proof. */
class DirectRecipeModelsTest {
    private val now = OffsetDateTime.parse("2026-11-08T12:00:00Z")
    private val prepared = RecipeAiExchange.prepare(RecipeAiExchange.Input("Ein neues Gericht mit Zitrone", 2,
        emptySet(), RecipeRequestPreferences(), emptyList(), Gate.Flags(false, false, false)), "synthetic-direct-request", now)
    private val id = "models/gemini-synthetic-test"
    private val key = "synthetic-not-a-real-api-key"
    private fun consent() = GeminiCloudConsent(prepared.requestId, prepared.inputBinding, "revision", id, true)
    private fun draft() = JsonOutput.encode(prepared.responseTemplate)
    private fun model() = mapOf("name" to id, "displayName" to "Synthetic Gemini", "supportedGenerationMethods" to listOf("generateContent"))
    private class Wire(val answer: String, val status: Int = 200) : OfferHttp {
        val requests = mutableListOf<OfferHttpRequest>()
        override suspend fun execute(request: OfferHttpRequest): OfferHttpReply {
            requests += request
            val body = if (request.method == "GET") JsonOutput.encode(mapOf("name" to "models/gemini-synthetic-test",
                "supportedGenerationMethods" to listOf("generateContent"))) else answer
            return OfferHttpReply(if (request.method == "GET") 200 else status, emptyMap(), body)
        }
    }
    private fun envelope(text: String = draft(), finish: String = "STOP", extraPart: Map<String, Any?>? = null) =
        JsonOutput.encode(mapOf("modelVersion" to "gemini-synthetic-test-001", "candidates" to listOf(mapOf(
            "finishReason" to finish, "content" to mapOf("parts" to (extraPart?.let { listOf(it) }.orEmpty() +
                mapOf("text" to text, "thoughtSignature" to "synthetic-opaque-state")))))))
    @Test fun actualVersionFamilyIsRequiredAndPreviewPreferenceDoesNotSilentlyTurnNano3IntoNano4() {
        assertTrue(NanoModelIdentity.matches(DirectRecipeMode.NANO3, "gemini-nano-v3"))
        assertTrue(NanoModelIdentity.matches(DirectRecipeMode.NANO4_FAST, "models/gemini-nano-v4-fast"))
        assertFalse(NanoModelIdentity.matches(DirectRecipeMode.NANO3, "nano-4-fast-3b"))
        assertFalse(NanoModelIdentity.matches(DirectRecipeMode.NANO4_FAST, "nano-3"))
        assertFalse(NanoModelIdentity.matches(DirectRecipeMode.NANO4_FAST, "nano-4-full"))
        assertFalse(NanoModelIdentity.matches(DirectRecipeMode.NANO3, "unknown-3"))
    }
    @Test fun missingOrChangedDataCostKeyModelRequestConsentPerformsNoNetworkIo(): Unit = runBlocking {
        for (approval in listOf(consent().copy(approved = false), consent().copy(configurationRevision = "old"),
            consent().copy(inputBinding = "old"), consent().copy(requestId = "old"), consent().copy(model = "models/gemini-other"))) {
            val wire = Wire(envelope()); val client = GeminiCloudClient(wire)
            assertTrue(runCatching { client.generate(key, prepared, prepared.inputBinding, "revision", id, approval) }.isFailure)
            assertEquals(0, wire.requests.size)
        }
    }
    @Test fun officialModelListRequiresRealSupportedGeminiNamesAndDoesNotSendRecipeOrPutKeyInUrl(): Unit = runBlocking {
        val requests = mutableListOf<OfferHttpRequest>()
        val client = GeminiCloudClient(OfferHttp { request ->
            requests += request
            OfferHttpReply(200, emptyMap(), JsonOutput.encode(mapOf("models" to listOf(model(),
                model() + ("name" to "models/embedding-synthetic"), model() + ("supportedGenerationMethods" to listOf("countTokens"))))))
        })
        assertEquals(listOf(id), client.listModels(key).map { it.name })
        val sent = requests.single(); assertEquals("GET", sent.method); assertNull(sent.body)
        assertEquals("generativelanguage.googleapis.com", sent.uri.host); assertFalse(sent.uri.toString().contains(key))
        assertEquals(key, sent.headers["x-goog-api-key"]); assertFalse(sent.toString().contains(key))
    }
    @Test fun directActualTextPathPreservesPinnedDraftForIndependentInspectionAndDoesNotTrustThinkingOrNutrition(): Unit = runBlocking {
        val wire = Wire(envelope(extraPart = mapOf("thought" to true, "text" to "Synthetic reasoning never enters draft")))
        val reply = GeminiCloudClient(wire).generate(key, prepared, prepared.inputBinding, "revision", id, consent())
        assertEquals(draft(), reply.text); assertTrue(reply.verifiedModel.contains("synthetic-test-001"))
        val inspected = RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, reply.text, now)
        assertEquals(prepared.requestId, inspected.draftId)
        assertEquals(2, inspected.draft.portions)
        assertEquals(listOf("GET", "POST"), wire.requests.map { it.method })
        val post = OfferJson.read(requireNotNull(wire.requests.last().body).toString(Charsets.UTF_8))
        val contents = post["contents"] as List<*>; val parts = OfferJson.objectValue(contents.single())!!["parts"] as List<*>
        assertEquals(prepared.prompt, OfferJson.objectValue(parts.single())!!["text"])
        assertNull(post["tools"]); assertFalse(reply.toString().contains(prepared.prompt))
    }
    @Test fun incompleteOrToolContentCannotBecomeARecipeAndIsNeverAutomaticallyRepaired(): Unit = runBlocking {
        val replies = listOf(envelope(finish = "MAX_TOKENS"), envelope(extraPart = mapOf("functionCall" to mapOf("name" to "buy"))), "{\"candidates\":[]}")
        for (response in replies) {
            val wire = Wire(response)
            assertTrue(runCatching { GeminiCloudClient(wire).generate(key, prepared, prepared.inputBinding, "revision", id, consent()) }.isFailure)
            assertEquals(2, wire.requests.size)
        }
    }
    @Test fun quotaAndAccessFailuresNeverRetryOrFallbackOrExposeRawProviderText(): Unit = runBlocking {
        for (status in listOf(403, 429, 500)) {
            val wire = Wire("$key private synthetic provider error", status)
            val error = runCatching { GeminiCloudClient(wire).generate(key, prepared, prepared.inputBinding, "revision", id, consent()) }.exceptionOrNull()
            assertTrue(error is DirectModelFailure); assertFalse(error.toString().contains(key)); assertEquals(2, wire.requests.size)
        }
    }
    @Test fun modelOutputNeverChangesThePinnedPublicContractAndHostRejectsWrongVersionWithoutRepair(): Unit = runBlocking {
        val wrong = draft().replace("1.0.0-draft", "1.1-draft")
        val reply = GeminiCloudClient(Wire(envelope(wrong))).generate(key, prepared, prepared.inputBinding, "revision", id, consent())
        assertEquals(wrong, reply.text)
        assertTrue(runCatching { RecipeAiExchange.inspectResponse(prepared, prepared.inputBinding, reply.text, now) }.isFailure)
    }
}
