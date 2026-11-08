package de.ernaehrung.demo.data.offers

import de.eg.recipe.JsonOutput
import java.time.Instant

internal data class KnusprReadTool(val name: String, val title: String, val queryProperty: String,
    val inputSchema: Map<String, Any?>)
internal data class KnusprToolDiscovery(val protocol: String, val tools: List<KnusprReadTool>, val unsupportedCount: Int,
    val metadata: List<Map<String, Any?>> = emptyList()) {
    /** Exact whitelisted discovery metadata only, no session/auth headers,
     * tool responses, shopping query, account or health/food/profile data. */
    fun schemaDocument(): String = JsonOutput.encode(mapOf("format" to "eg-knuspr-tool-discovery-1",
        "resource" to KnusprOAuth.RESOURCE.toString(), "protocolVersion" to protocol, "tools" to metadata))
        .also { require(it.toByteArray(Charsets.UTF_8).size <= 262_144) { "Tool schema document exceeds clipboard limit" } }
}

/** Only actual discovered read-only search tools can be invoked. No invented
 * names, cart mutations, checkout, reflection or evaluation of supplier code. */
internal class KnusprMcp(private val http: OfferHttp, private val token: KnusprToken,
    private val now: () -> Instant = Instant::now, private val canReadProductData: () -> Boolean = { false }) {
    private var nextId = 1L
    private var session: String? = null
    private var protocol = "2025-06-18"
    private var discovered = emptyList<KnusprReadTool>()
    private suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        require(method in setOf("initialize", "tools/list", "tools/call"))
        require(token.expiresAt == null || token.expiresAt > now().toEpochMilli()) { "OAuth session expired" }
        val id = nextId++
        val headers = mutableMapOf("Authorization" to "Bearer ${token.access}", "Content-Type" to "application/json", "MCP-Protocol-Version" to protocol)
        session?.let { headers["Mcp-Session-Id"] = it }
        val payload = JsonOutput.encode(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        val reply = http.execute(OfferHttpRequest(KnusprOAuth.RESOURCE, "POST", payload.toByteArray(), headers, id))
        reply.requireSuccess()
        reply.headers["mcp-session-id"]?.let { value -> require(value.length in 1..512 && value.all { it.code in 33..126 }); session = value }
        val envelope = OfferJson.read(reply.text)
        require(envelope["jsonrpc"] == "2.0" && OfferJson.integer(envelope["id"]) == id && envelope["error"] == null)
        return requireNotNull(OfferJson.objectValue(envelope["result"]))
    }
    suspend fun discover(): KnusprToolDiscovery {
        val initialized = call("initialize", mapOf("protocolVersion" to protocol, "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "Ernaehrungsgedaechnis", "version" to "1")))
        protocol = requireNotNull(OfferJson.text(initialized["protocolVersion"]))
        require(protocol in setOf("2025-03-26", "2025-06-18")) { "MCP protocol not supported" }
        val headers = mutableMapOf("Authorization" to "Bearer ${token.access}", "Content-Type" to "application/json", "MCP-Protocol-Version" to protocol)
        session?.let { headers["Mcp-Session-Id"] = it }
        http.execute(OfferHttpRequest(KnusprOAuth.RESOURCE, "POST", JsonOutput.encode(mapOf("jsonrpc" to "2.0",
            "method" to "notifications/initialized")).toByteArray(), headers)).requireSuccess()
        val listed = call("tools/list"); val raw = listed["tools"] as? List<*> ?: error("MCP tools missing")
        require(raw.size <= 128 && listed["nextCursor"] == null) { "Tool discovery incomplete" }
        val names = raw.mapNotNull { OfferJson.objectValue(it)?.get("name") as? String }
        require(names.size == raw.size && names.distinct().size == names.size)
        discovered = raw.mapNotNull { item ->
            val tool = OfferJson.objectValue(item) ?: return@mapNotNull null
            val name = OfferJson.text(tool["name"]) ?: return@mapNotNull null
            val annotations = OfferJson.objectValue(tool["annotations"]) ?: return@mapNotNull null
            if (annotations["readOnlyHint"] != true || annotations["destructiveHint"] == true) return@mapNotNull null
            val normalized = name.lowercase()
            if (!normalized.contains("search") || !listOf("product", "offer").any(normalized::contains) ||
                listOf("cart", "basket", "checkout", "order", "delete", "update", "create", "purchase", "payment", "add", "remove").any(normalized::contains)) return@mapNotNull null
            val schema = OfferJson.objectValue(tool["inputSchema"]) ?: return@mapNotNull null
            if (schema["type"] != "object" || schema.keys.any { it !in setOf("type", "properties", "required", "description", "title", "additionalProperties", "\$schema") }) return@mapNotNull null
            val properties = OfferJson.objectValue(schema["properties"]) ?: return@mapNotNull null
            val query = properties.keys.filter { it in setOf("query", "search", "searchTerm", "search_term", "q") }.singleOrNull() ?: return@mapNotNull null
            val querySchema = OfferJson.objectValue(properties[query]) ?: return@mapNotNull null
            if (querySchema["type"] != "string" || querySchema.keys.any { it !in setOf("type", "description", "title", "minLength", "maxLength") }) return@mapNotNull null
            if (schema["required"] != null && schema["required"] !is List<*>) return@mapNotNull null
            val min = OfferJson.integer(querySchema["minLength"])
            val max = OfferJson.integer(querySchema["maxLength"])
            if (("minLength" in querySchema && min == null) || ("maxLength" in querySchema && max == null) ||
                min?.let { it < 0 } == true || max?.let { it <= 0 } == true || (min != null && max != null && min > max)) return@mapNotNull null
            val required = schema["required"] as? List<*> ?: emptyList<Any>()
            if (required.any { it != query }) return@mapNotNull null
            KnusprReadTool(name, OfferJson.text(tool["title"]) ?: name, query, schema)
        }
        val metadata = raw.map { value -> requireNotNull(OfferJson.objectValue(value))
            .filterKeys { it in setOf("name", "title", "description", "inputSchema", "outputSchema", "annotations") } }
        return KnusprToolDiscovery(protocol, discovered, raw.size - discovered.size, metadata)
    }
    suspend fun search(tool: KnusprReadTool, query: String): Map<String, Any?> {
        require(canReadProductData()) { "Independent source/display authorization required before product data request" }
        require(discovered.any { it == tool }) { "Tool not independently discovered in this session" }
        require(query.isNotBlank() && query.length <= 256)
        val properties = requireNotNull(OfferJson.objectValue(tool.inputSchema["properties"]))
        val schema = requireNotNull(OfferJson.objectValue(properties[tool.queryProperty]))
        OfferJson.integer(schema["minLength"])?.let { require(query.length >= it) }
        OfferJson.integer(schema["maxLength"])?.let { require(query.length <= it) }
        val result = call("tools/call", mapOf("name" to tool.name, "arguments" to mapOf(tool.queryProperty to query)))
        require(result["isError"] != true) { "MCP tool failed" }
        // Preserve actual structured results for a separately verified product
        // profile. A prose reply never manufactures prices or product identity.
        return OfferJson.objectValue(result["structuredContent"]) ?: throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    }
}
