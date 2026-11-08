package de.ernaehrung.demo.data.offers

import de.eg.recipe.JsonOutput
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal data class KnusprAuthorizationServer(val issuer: URI, val authorization: URI, val token: URI,
    val registration: URI?, val scopes: List<String>, val resource: URI = URI("https://mcp.knuspr.de/mcp"))
internal data class KnusprPendingAuthorization(val server: KnusprAuthorizationServer, val clientId: String,
    val state: String, val verifier: String, val redirect: String, val createdAt: Long) {
    override fun toString() = "KnusprPendingAuthorization(redacted)"
}
internal data class KnusprToken(val access: String, val refresh: String?, val expiresAt: Long?, val clientId: String,
    val server: KnusprAuthorizationServer) {
    override fun toString() = "KnusprToken(redacted)"
}

/** Actual official OAuth/PKCE discovery. No fixed tool names, credentials,
 * catalogue request, cookie/login scraping or alternative Knuspr endpoint. */
internal class KnusprOAuth(private val http: OfferHttp, private val now: () -> Instant = Instant::now) {
    companion object {
        val RESOURCE = URI("https://mcp.knuspr.de/mcp")
        private const val VERSION = "2025-06-18"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private fun random(): String = encoder.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        private fun form(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        private fun endpoint(value: Any?, issuer: URI): URI {
            val uri = URI(requireNotNull(OfferJson.text(value)))
            requirePublicHttps(uri)
            require(uri.host.equals(issuer.host, ignoreCase = true) && uri.port == issuer.port && uri.rawQuery == null) {
                "OAuth endpoint must belong to the discovered issuer"
            }
            return uri
        }
    }

    suspend fun discover(): KnusprAuthorizationServer {
        val initialize = JsonOutput.encode(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to mapOf(
            "protocolVersion" to VERSION, "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "Ernaehrungsgedaechnis", "version" to "1"))))
        val challenge = http.execute(OfferHttpRequest(RESOURCE, "POST", initialize.toByteArray(), mapOf("Content-Type" to "application/json")))
        if (challenge.status == 403 || challenge.status == 429) challenge.requireSuccess() // Never retry a protection block.
        require(challenge.status == 401) { "Official OAuth challenge missing" }
        val advertised = Regex("resource_metadata=\"([^\"]+)\"").find(challenge.headers["www-authenticate"].orEmpty())?.groupValues?.get(1)
        val metadataUri = advertised?.let(::URI) ?: URI("https://mcp.knuspr.de/.well-known/oauth-protected-resource/mcp")
        requirePublicHttps(metadataUri)
        require(metadataUri.host == RESOURCE.host) { "Resource metadata must belong to official Knuspr host" }
        val resourceReply = http.execute(OfferHttpRequest(metadataUri)); resourceReply.requireSuccess()
        val resource = OfferJson.read(resourceReply.text)
        val resourceId = URI(requireNotNull(OfferJson.text(resource["resource"])))
        requirePublicHttps(resourceId)
        require(resourceId.host == RESOURCE.host && resourceId.rawQuery == null &&
            resourceId.path in setOf("", "/", "/mcp")) { "OAuth resource mismatch" }
        val issuers = resource["authorization_servers"] as? List<*> ?: error("OAuth issuer missing")
        require(issuers.size == 1) { "Ambiguous OAuth issuer" }
        val issuer = URI(requireNotNull(OfferJson.text(issuers.single())))
        requirePublicHttps(issuer); require(issuer.rawQuery == null)
        val metadata = URI(issuer.scheme, issuer.authority,
            "/.well-known/oauth-authorization-server" + issuer.path.trimEnd('/'), null, null)
        val serverReply = http.execute(OfferHttpRequest(metadata)); serverReply.requireSuccess()
        val server = OfferJson.read(serverReply.text)
        require(server["issuer"] == issuer.toString()) { "OAuth issuer metadata mismatch" }
        require((server["code_challenge_methods_supported"] as? List<*>)?.contains("S256") == true) { "Verified S256 PKCE support required" }
        val scopes = (resource["scopes_supported"] as? List<*>)?.mapNotNull { OfferJson.text(it) }.orEmpty()
            .filter { it in setOf("products:read", "catalog:read", "offers:read", "mcp:read", "read") }
        return KnusprAuthorizationServer(issuer, endpoint(server["authorization_endpoint"], issuer),
            endpoint(server["token_endpoint"], issuer), server["registration_endpoint"]?.let { endpoint(it, issuer) }, scopes, resourceId)
    }

    suspend fun begin(applicationId: String, server: KnusprAuthorizationServer): Pair<KnusprPendingAuthorization, URI> {
        require(applicationId.matches(Regex("de\\.ernaehrung\\.demo(\\.watchspike)?")))
        val redirect = "$applicationId://knuspr-oauth"
        val registration = requireNotNull(server.registration) { "Public OAuth client registration not advertised" }
        val reply = http.execute(OfferHttpRequest(registration, "POST", JsonOutput.encode(mapOf(
            "client_name" to "Ernährungsgedächtnis", "redirect_uris" to listOf(redirect),
            "grant_types" to listOf("authorization_code", "refresh_token"), "response_types" to listOf("code"),
            "token_endpoint_auth_method" to "none")).toByteArray(), mapOf("Content-Type" to "application/json")))
        reply.requireSuccess(); val client = OfferJson.read(reply.text)
        require(client["client_secret"] == null && client["token_endpoint_auth_method"] == "none") { "Public PKCE client required; no embedded client secret" }
        val id = requireNotNull(OfferJson.text(client["client_id"]))
        val pending = KnusprPendingAuthorization(server, id, random(), random(), redirect, now().toEpochMilli())
        val challenge = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray(Charsets.US_ASCII)))
        val values = linkedMapOf("client_id" to id, "redirect_uri" to redirect, "response_type" to "code",
            "state" to pending.state, "code_challenge" to challenge, "code_challenge_method" to "S256", "resource" to server.resource.toString())
        if (server.scopes.isNotEmpty()) values["scope"] = server.scopes.joinToString(" ")
        return pending to URI(server.authorization.toString() + "?" + String(form(values), Charsets.UTF_8))
    }

    fun authorizationCode(pending: KnusprPendingAuthorization, redirectUri: String): String {
        require(redirectUri.length <= 16384)
        val callback = URI(redirectUri); val expected = URI(pending.redirect)
        require(callback.scheme == expected.scheme && callback.host == expected.host && callback.path.orEmpty().isEmpty() && callback.fragment == null && callback.userInfo == null && callback.port == -1)
        val values = callback.rawQuery.orEmpty().split('&').associate { part ->
            val pair = part.split('=', limit = 2); require(pair.size == 2)
            java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }
        require(values.keys.size == callback.rawQuery.orEmpty().split('&').size) { "Duplicate OAuth callback fields" }
        require(MessageDigest.isEqual(values["state"].orEmpty().toByteArray(), pending.state.toByteArray())) { "OAuth state mismatch" }
        require(now().toEpochMilli() - pending.createdAt in 0L..600_000L) { "OAuth attempt expired" }
        require(values["error"] == null) { "OAuth authorization not granted" }
        values["iss"]?.let { require(it == pending.server.issuer.toString()) { "OAuth callback issuer mismatch" } }
        val code = requireNotNull(values["code"]) { "OAuth code missing" }; require(code.length in 1..8192)
        return code
    }
    suspend fun complete(pending: KnusprPendingAuthorization, redirectUri: String): KnusprToken {
        val code = authorizationCode(pending, redirectUri)
        val reply = http.execute(OfferHttpRequest(pending.server.token, "POST", form(mapOf(
            "grant_type" to "authorization_code", "code" to code, "client_id" to pending.clientId,
            "redirect_uri" to pending.redirect, "code_verifier" to pending.verifier, "resource" to pending.server.resource.toString())),
            mapOf("Content-Type" to "application/x-www-form-urlencoded")))
        reply.requireSuccess(); val token = OfferJson.read(reply.text)
        require((token["token_type"] as? String)?.equals("Bearer", ignoreCase = true) == true)
        val access = requireNotNull(token["access_token"] as? String); require(access.length in 1..16384 && '\r' !in access && '\n' !in access)
        val expiry = OfferJson.integer(token["expires_in"])?.let { seconds ->
            require(seconds > 0); Math.addExact(now().toEpochMilli(), Math.multiplyExact(seconds, 1000L))
        }
        val refresh = (token["refresh_token"] as? String)?.also { require(it.length in 1..16384) }
        return KnusprToken(access, refresh, expiry, pending.clientId, pending.server)
    }
}
