package de.ernaehrung.demo

import de.eg.recipe.JsonOutput
import de.ernaehrung.demo.data.offers.*
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire protocol only, never a Knuspr-login/price/live proof. */
class KnusprMcpTest {
    private val server = KnusprAuthorizationServer(URI("https://login.example.test"), URI("https://login.example.test/authorize"),
        URI("https://login.example.test/token"), null, emptyList())
    private val token = KnusprToken("synthetic-access", null, null, "synthetic-client", server)
    private fun tool(name: String = "synthetic_product_search", readOnly: Boolean = true, required: Any = listOf("query")) = mapOf(
        "name" to name, "annotations" to mapOf("readOnlyHint" to readOnly),
        "inputSchema" to mapOf("type" to "object", "required" to required,
            "properties" to mapOf("query" to mapOf("type" to "string", "minLength" to 2, "maxLength" to 10))))
    private class Wire(val tools: List<Map<String, Any?>>, val proseOnly: Boolean = false) : OfferHttp {
        var toolCalls = 0
        override suspend fun execute(request: OfferHttpRequest): OfferHttpReply {
            assertEquals(KnusprOAuth.RESOURCE, request.uri)
            val envelope = OfferJson.read(requireNotNull(request.body).toString(Charsets.UTF_8))
            if (envelope["method"] == "notifications/initialized") return OfferHttpReply(202, emptyMap(), "")
            val result: Map<String, Any?> = when (envelope["method"]) {
                "initialize" -> mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>())
                "tools/list" -> mapOf("tools" to tools)
                "tools/call" -> {
                    toolCalls++
                    if (proseOnly) mapOf("content" to listOf(mapOf("type" to "text", "text" to "Synthetic prose is not a price contract")))
                    else mapOf("structuredContent" to mapOf("synthetic_integer_cents" to 0, "unknown" to null))
                }
                else -> error("Unexpected MCP method")
            }
            return OfferHttpReply(200, mapOf("mcp-session-id" to "synthetic-session"),
                JsonOutput.encode(mapOf("jsonrpc" to "2.0", "id" to envelope["id"], "result" to result)))
        }
    }
    @Test fun onlyActuallyDiscoveredReadOnlyNonMutatingCompatibleToolsCanBeSelected() = runBlocking {
        val wire = Wire(listOf(tool(), tool("synthetic_search_product_add_to_cart"), tool("synthetic_product_search_unsafe", false),
            tool("synthetic_product_search_other_required", required = listOf("query", "deliveryArea")), tool("synthetic_product_search_bad_schema", required = "query")))
        val discovery = KnusprMcp(wire, token).discover()
        assertEquals(listOf("synthetic_product_search"), discovery.tools.map { it.name })
        assertEquals(4, discovery.unsupportedCount); assertEquals(0, wire.toolCalls)
        assertEquals(5, discovery.metadata.size)
        val document = discovery.schemaDocument()
        assertTrue(document.contains("synthetic_search_product_add_to_cart")) // metadata only, never callable
        assertTrue(document.contains("inputSchema")); assertFalse(document.contains(token.access))
    }
    @Test fun loginAndDiscoveryAloneCannotAuthorizeProductDataRequestAndProseDoesNotBecomePrices() = runBlocking {
        val wire = Wire(listOf(tool())); val blocked = KnusprMcp(wire, token)
        val selected = blocked.discover().tools.single()
        assertTrue(runCatching { blocked.search(selected, "Milch") }.isFailure); assertEquals(0, wire.toolCalls)
        val prose = Wire(listOf(tool()), proseOnly = true); val approvedFixture = KnusprMcp(prose, token, canReadProductData = { true })
        val actual = approvedFixture.discover().tools.single()
        assertTrue(runCatching { approvedFixture.search(actual, "Milch") }.isFailure); assertEquals(1, prose.toolCalls)
    }
    @Test fun validatedQueryInvokesExactDiscoveredToolAndPreservesStructuredZeroAndUnknownWithoutPriceInference() = runBlocking {
        val wire = Wire(listOf(tool())); val client = KnusprMcp(wire, token, canReadProductData = { true })
        val selected = client.discover().tools.single()
        assertTrue(runCatching { client.search(selected, "x") }.isFailure)
        assertTrue(runCatching { client.search(selected.copy(name = "injected_product_search"), "Milch") }.isFailure)
        assertEquals(0, wire.toolCalls)
        val result = client.search(selected, "Milch")
        assertEquals(0L, OfferJson.integer(result["synthetic_integer_cents"])); assertNull(result["unknown"])
        assertEquals(1, wire.toolCalls)
    }
}
