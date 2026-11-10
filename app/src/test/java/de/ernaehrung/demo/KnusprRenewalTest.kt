package de.ernaehrung.demo

import de.ernaehrung.demo.data.offers.*
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic OAuth transcripts, never a real Knuspr credential or request. */
class KnusprRenewalTest {
    private val at = Instant.parse("2026-11-10T10:00:00Z")
    private val server = KnusprAuthorizationServer(URI("https://auth.example.test"), URI("https://auth.example.test/authorize"),
        URI("https://auth.example.test/token"), null, listOf("products:read"))
    private val previous = KnusprToken("synthetic-old-access", "synthetic old/+refresh", at.minusSeconds(1).toEpochMilli(),
        "synthetic-public-client", server)
    private fun form(request: OfferHttpRequest) = String(requireNotNull(request.body)).split('&').associate {
        val pair = it.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    @Test fun explicitRenewalUsesOnlyOriginalPublicClientResourceAndTokenEndpointAndHandlesRotationOrOmission(): Unit = runBlocking {
        val requests = mutableListOf<OfferHttpRequest>()
        val replies = mutableListOf(
            """{"token_type":"Bearer","access_token":"synthetic-new-access","refresh_token":"synthetic-rotated-refresh","expires_in":3600}""",
            """{"token_type":"Bearer","access_token":"synthetic-next-access"}""")
        val oauth = KnusprOAuth(OfferHttp { requests += it; OfferHttpReply(200, emptyMap(), replies.removeAt(0)) }) { at }
        val rotated = oauth.renew(previous)
        assertEquals("synthetic-rotated-refresh", rotated.refresh); assertEquals(at.plusSeconds(3600).toEpochMilli(), rotated.expiresAt)
        val reused = oauth.renew(rotated)
        assertEquals(rotated.refresh, reused.refresh); assertNull(reused.expiresAt)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals(server.token, request.uri); assertEquals("POST", request.method)
            assertNull(request.headers["Authorization"])
            assertEquals(setOf("grant_type", "refresh_token", "client_id", "resource"), form(request).keys)
            assertEquals("refresh_token", form(request)["grant_type"]); assertEquals(previous.clientId, form(request)["client_id"])
            assertEquals(KnusprOAuth.RESOURCE.toString(), form(request)["resource"])
            assertFalse(request.toString().contains("synthetic"))
        }
        assertEquals(previous.refresh, form(requests[0])["refresh_token"])
        assertEquals(rotated.refresh, form(requests[1])["refresh_token"])
        assertFalse(rotated.toString().contains(rotated.access))
    }

    @Test fun missingRefreshAndUntrustedIssuerResourceOrTokenEndpointCannotSendCredentials(): Unit = runBlocking {
        var calls = 0
        val oauth = KnusprOAuth(OfferHttp { calls++; error("Must stop before IO") }) { at }
        val invalid = listOf(previous.copy(refresh = null), previous.copy(server = server.copy(token = URI("https://other.example.test/token"))),
            previous.copy(server = server.copy(resource = URI("https://other.example.test/mcp"))))
        invalid.forEach { assertTrue(runCatching { oauth.renew(it) }.isFailure) }
        assertEquals(0, calls)
    }

    @Test fun deniedBlockedAndLimitedRenewalsStopAfterOneRequestWithoutRetryOrDiscovery(): Unit = runBlocking {
        for ((status, body, code) in listOf(Triple(400, """{"error":"invalid_grant"}""", OfferSourceFailureCode.LOGIN_REQUIRED),
            Triple(401, "synthetic denial", OfferSourceFailureCode.LOGIN_REQUIRED),
            Triple(403, "synthetic denial", OfferSourceFailureCode.ACCESS_BLOCKED), Triple(429, "synthetic denial", OfferSourceFailureCode.RATE_LIMITED))) {
            var calls = 0
            val oauth = KnusprOAuth(OfferHttp { calls++; OfferHttpReply(status, emptyMap(), body) }) { at }
            try { oauth.renew(previous); fail("Must reject provider failure") }
            catch (failure: OfferSourceFailure) { assertEquals(code, failure.code) }
            assertEquals(1, calls)
        }
    }

    @Test fun invalidRenewalExpiryAndRotationAreNotAcceptedAsAnIndefiniteCredential(): Unit = runBlocking {
        val bodies = listOf("""{"token_type":"Bearer","access_token":"synthetic","expires_in":0}""",
            """{"token_type":"Bearer","access_token":"synthetic","expires_in":"unknown"}""",
            """{"token_type":"Bearer","access_token":"synthetic","refresh_token":null}""")
        bodies.forEach { body ->
            var calls = 0
            val oauth = KnusprOAuth(OfferHttp { calls++; OfferHttpReply(200, emptyMap(), body) }) { at }
            assertTrue(runCatching { oauth.renew(previous) }.isFailure); assertEquals(1, calls)
        }
    }

    @Test fun expiredMcpAccessRequiresLoginBeforeAnyRpcAndUiMetadataContainsNoCredential(): Unit = runBlocking {
        var calls = 0
        val http = OfferHttp { calls++; error("Expired access must never make a request") }
        try { KnusprMcp(http, previous, { at }).discover(); fail("Must reject expired access") }
        catch (failure: OfferSourceFailure) { assertEquals(OfferSourceFailureCode.LOGIN_REQUIRED, failure.code) }
        assertEquals(0, calls)
        assertTrue(previous.connectionInfo(at).expired)
        val unknown = previous.copy(expiresAt = null).connectionInfo(at)
        assertFalse(unknown.expired); assertNull(unknown.expiresAt)
        assertTrue(unknown.renewable); assertFalse(unknown.toString().contains("synthetic"))
    }
}
