package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol transcripts only. Not a real Knuspr login or live data. */
class KnusprOAuthTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private class Http(vararg responses: OfferHttpReply) : OfferHttp {
        val queue = responses.toMutableList(); val calls = mutableListOf<OfferHttpRequest>()
        override suspend fun execute(request: OfferHttpRequest): OfferHttpReply { calls += request; return queue.removeAt(0) }
    }
    private fun reply(body: String, status: Int = 200) = OfferHttpReply(status, emptyMap(), body)
    private val registration get() = reply("""{"client_id":"synthetic-public-client","token_endpoint_auth_method":"none"}""")
    private val server get() = KnusprAuthorizationServer(URI("https://auth.example.test"), URI("https://auth.example.test/authorize"),
        URI("https://auth.example.test/token"), URI("https://auth.example.test/register"), listOf("products:read"))
    private fun params(uri: URI) = uri.rawQuery.split('&').associate { part ->
        val pair = part.split('=', limit = 2); pair[0] to URLDecoder.decode(pair[1], "UTF-8")
    }
    @Test fun real403ProtectionFailureStopsAfterExactlyOneRequestWithoutAlternativeEndpoint() = runBlocking {
        val http = Http(reply("synthetic protection page", 403))
        try { KnusprOAuth(http) { now }.discover(); fail("Must stop") }
        catch (failure: OfferSourceFailure) { assertEquals(OfferSourceFailureCode.ACCESS_BLOCKED, failure.code) }
        assertEquals(1, http.calls.size); assertEquals(KnusprOAuth.RESOURCE, http.calls.single().uri)
    }
    @Test fun publicPkceUsesFreshStateS256ResourceAndOneTimeCodeWithNoEmbeddedSecret() = runBlocking {
        val http = Http(registration, reply("""{"token_type":"Bearer","access_token":"synthetic-only-access","expires_in":3600}"""))
        val oauth = KnusprOAuth(http) { now }; val (pending, authorization) = oauth.begin("de.ernaehrung.demo", server)
        val query = params(authorization)
        assertEquals("S256", query["code_challenge_method"]); assertEquals(pending.state, query["state"])
        assertEquals(pending.redirect, query["redirect_uri"]); assertFalse(authorization.toString().contains(pending.verifier))
        assertFalse(pending.toString().contains(pending.verifier))
        val token = oauth.complete(pending, "${pending.redirect}?state=${pending.state}&code=synthetic-code")
        assertEquals(now.plusSeconds(3600).toEpochMilli(), token.expiresAt)
        val body = String(http.calls.last().body!!)
        assertTrue(body.contains("code_verifier=${pending.verifier}")); assertFalse(body.contains("client_secret"))
        assertFalse(token.toString().contains(token.access)); assertFalse(http.calls.last().toString().contains(pending.verifier))
    }
    @Test fun wrongStateIssuerDuplicateFieldsExpiryAndWrongRedirectNeverReachTokenEndpoint() = runBlocking {
        val http = Http(registration); val oauth = KnusprOAuth(http) { now }; val (pending, _) = oauth.begin("de.ernaehrung.demo.watchspike", server)
        val callbacks = listOf("${pending.redirect}?state=wrong&code=a", "${pending.redirect}?state=${pending.state}&state=${pending.state}&code=a",
            "${pending.redirect}?state=${pending.state}&code=a&iss=https%3A%2F%2Fother.example.test",
            "de.ernaehrung.demo://knuspr-oauth?state=${pending.state}&code=a", "${pending.redirect}?state=${pending.state}&error=access_denied")
        for (callback in callbacks) assertTrue(runCatching { oauth.complete(pending, callback) }.isFailure)
        assertTrue(runCatching { KnusprOAuth(http) { now.plusSeconds(601) }.complete(pending, "${pending.redirect}?state=${pending.state}&code=a") }.isFailure)
        assertEquals(1, http.calls.size)
    }
    @Test fun discoveryRequiresExactOfficialResourceVerifiedIssuerAndS256() = runBlocking {
        val http = Http(OfferHttpReply(401, mapOf("www-authenticate" to "Bearer resource_metadata=\"https://mcp.knuspr.de/.well-known/oauth-protected-resource/mcp\""), ""),
            reply("""{"resource":"https://mcp.knuspr.de/mcp","authorization_servers":["https://auth.example.test"]}"""),
            reply("""{"issuer":"https://auth.example.test","authorization_endpoint":"https://auth.example.test/authorize","token_endpoint":"https://auth.example.test/token","registration_endpoint":"https://auth.example.test/register","code_challenge_methods_supported":["S256"]}"""))
        assertEquals(server.issuer, KnusprOAuth(http) { now }.discover().issuer)
        assertEquals(3, http.calls.size)
        assertTrue(http.calls.all { it.uri.scheme == "https" && it.headers["Authorization"] == null })
    }
    @Test fun sseStopsAtActualCorrelatedResponseBeforeKeepaliveEofAndIgnoresUnrelatedNotifications() {
        val raw = ": ping\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"actual\":true}}\n\n"
        val stream = object : ByteArrayInputStream(raw.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (available() == 0) error("Must stop at selected event, never await keepalive EOF")
                return super.read(b, off, len)
            }
        }
        val message = OfferJson.read(McpEventStream.readResponse(stream, 1))
        assertEquals(true, OfferJson.objectValue(message["result"])!!["actual"])
    }
}
