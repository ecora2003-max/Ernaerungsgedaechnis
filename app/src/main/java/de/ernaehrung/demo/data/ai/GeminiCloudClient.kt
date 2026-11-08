package de.ernaehrung.demo.data.ai

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.offers.*
import java.net.URI
import kotlinx.coroutines.CancellationException

internal data class GeminiCloudModel(val name: String, val title: String)
internal data class GeminiCloudConsent(val requestId: String, val inputBinding: String, val configurationRevision: String,
    val model: String, val approved: Boolean)

/** Optional BYOK, explicit per-request data/cost approval, one bounded request.
 * No key in URL, model guess, tools, automatic retry, cloud fallback or booking.
 * Models come from the actual official API and are rechecked before inference. */
internal class GeminiCloudClient(private val http: OfferHttp = HttpsOfferHttp()) {
    companion object {
        private const val ROOT = "https://generativelanguage.googleapis.com/v1beta/"
        fun validModelName(name: String) = name.matches(Regex("models/gemini-[A-Za-z0-9._-]{1,150}"))
    }
    private suspend fun get(path: String, key: String): Map<String, Any?> {
        val reply = http.execute(OfferHttpRequest(URI(ROOT + path), headers = mapOf("x-goog-api-key" to key)))
        checkStatus(reply.status); return OfferJson.read(reply.text)
    }
    private fun checkStatus(status: Int) {
        if (status !in 200..299) throw DirectModelFailure(when (status) {
            401, 403 -> "CLOUD_KEY_OR_ACCESS"; 429 -> "CLOUD_QUOTA"; 400 -> "CLOUD_REQUEST_REJECTED"
            else -> "CLOUD_UNAVAILABLE"
        })
    }
    private fun model(value: Any?): GeminiCloudModel? {
        val row = OfferJson.objectValue(value) ?: return null
        val name = OfferJson.text(row["name"]) ?: return null
        if (!validModelName(name) || (row["supportedGenerationMethods"] as? List<*>)?.contains("generateContent") != true) return null
        return GeminiCloudModel(name, OfferJson.text(row["displayName"]) ?: name)
    }
    suspend fun listModels(key: String): List<GeminiCloudModel> {
        require(key.isNotBlank())
        var page: String? = null; val result = mutableListOf<GeminiCloudModel>()
        repeat(4) {
            val suffix = page?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val root = get("models?pageSize=100$suffix", key)
            val rows = root["models"] as? List<*> ?: throw DirectModelFailure("CLOUD_MODEL_SCHEMA")
            require(rows.size <= 100)
            result += rows.mapNotNull(::model)
            page = OfferJson.text(root["nextPageToken"])
            if (page == null) {
                if (result.map { it.name }.distinct().size != result.size) throw DirectModelFailure("CLOUD_MODEL_SCHEMA")
                return result
            }
        }
        throw DirectModelFailure("CLOUD_MODEL_LIST_LIMIT")
    }
    suspend fun generate(key: String, prepared: de.ernaehrung.demo.data.integration.RecipeAiExchange.Prepared,
        currentBinding: String, revision: String, modelName: String, consent: GeminiCloudConsent): DirectModelReply {
        if (!consent.approved || consent.requestId != prepared.requestId || consent.inputBinding != prepared.inputBinding ||
            currentBinding != prepared.inputBinding || consent.configurationRevision != revision || consent.model != modelName)
            throw DirectModelFailure("CLOUD_CONSENT_CHANGED") // before ANY network IO
        if (!validModelName(modelName)) throw DirectModelFailure("CLOUD_MODEL_INVALID")
        if (model(get(modelName, key))?.name != modelName) throw DirectModelFailure("CLOUD_MODEL_NOT_AVAILABLE")
        val payload = mapOf("contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prepared.prompt)))),
            "generationConfig" to mapOf("candidateCount" to 1, "maxOutputTokens" to 2048, "responseMimeType" to "application/json"))
        val reply = http.execute(OfferHttpRequest(URI(ROOT + modelName + ":generateContent"), "POST",
            JsonOutput.encode(payload).toByteArray(), mapOf("Content-Type" to "application/json", "x-goog-api-key" to key)))
        checkStatus(reply.status)
        val response = OfferJson.read(reply.text)
        val candidate = (response["candidates"] as? List<*>)?.singleOrNull()?.let(OfferJson::objectValue)
            ?: throw DirectModelFailure("CLOUD_EMPTY_OR_AMBIGUOUS")
        if (candidate["finishReason"] != "STOP") throw DirectModelFailure("INCOMPLETE_REPLY")
        val parts = OfferJson.objectValue(candidate["content"])?.get("parts") as? List<*> ?: throw DirectModelFailure("CLOUD_REPLY_SCHEMA")
        if (parts.isEmpty() || parts.size > 32) throw DirectModelFailure("CLOUD_REPLY_SCHEMA")
        val text = parts.joinToString("") { part ->
            val value = OfferJson.objectValue(part) ?: throw DirectModelFailure("CLOUD_REPLY_SCHEMA")
            if (value.keys.any { it !in setOf("text", "thought", "thoughtSignature", "partMetadata") }) throw DirectModelFailure("CLOUD_NON_TEXT_REPLY")
            if (value["thought"] != null && value["thought"] !is Boolean) throw DirectModelFailure("CLOUD_REPLY_SCHEMA")
            if (value["thought"] == true) return@joinToString "" // only the final answer enters the draft
            if (value["thoughtSignature"] != null && value["thoughtSignature"] !is String) throw DirectModelFailure("CLOUD_REPLY_SCHEMA")
            if (value["partMetadata"] != null && OfferJson.objectValue(value["partMetadata"]) == null) throw DirectModelFailure("CLOUD_REPLY_SCHEMA")
            value["text"] as? String ?: throw DirectModelFailure("CLOUD_NON_TEXT_REPLY")
        }
        if (text.isBlank() || text.length > 262144) throw DirectModelFailure("REPLY_LIMIT")
        val version = OfferJson.text(response["modelVersion"])?.takeIf { it.length <= 160 && it.matches(Regex("[A-Za-z0-9._/-]+")) }
        return DirectModelReply(text, "$modelName · API-Version ${version ?: "nicht gemeldet"}")
    }
}
