package de.ernaehrung.demo.data.offers

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class OfferHttpRequest(val uri: URI, val method: String = "GET", val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(), val rpcId: Long? = null) {
    override fun toString() = "OfferHttpRequest(method=$method, credentials/body redacted)"
}
internal data class OfferHttpReply(val status: Int, val headers: Map<String, String>, val text: String) {
    override fun toString() = "OfferHttpReply(status=$status, headers/body redacted)"
}
internal fun interface OfferHttp { suspend fun execute(request: OfferHttpRequest): OfferHttpReply }

/** No cookies, redirects, analytics or implicit retries. Callers perform their
 * source/permission check first; OAuth discovery carries no catalogue facts. */
internal class HttpsOfferHttp : OfferHttp {
    override suspend fun execute(request: OfferHttpRequest): OfferHttpReply = withContext(Dispatchers.IO) {
        requirePublicHttps(request.uri)
        require(request.method in setOf("GET", "POST"))
        require(request.body == null || request.body.size <= 262_144)
        require(request.headers.all { (name, value) -> name.matches(Regex("[A-Za-z0-9-]+")) && value.length <= 16_384 && '\r' !in value && '\n' !in value })
        currentCoroutineContext().ensureActive()
        val connection = request.uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 12_000; connection.readTimeout = 20_000
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            request.body?.let { bytes ->
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            // No redirected token, cookies or unnecessary headers retained.
            val permittedHeaders = setOf("content-type", "www-authenticate", "mcp-session-id", "retry-after", "cache-control", "date", "age", "etag")
            val headers = connection.headerFields.filterKeys { it?.lowercase() in permittedHeaders }
                .map { (key, values) -> key.lowercase() to values.joinToString(",").take(16_384) }.toMap()
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            if (status in 200..299 && headers["content-type"]?.substringBefore(';')?.trim() == "text/event-stream" && request.rpcId != null) {
                val text = requireNotNull(input).use { McpEventStream.readResponse(it, request.rpcId) }
                currentCoroutineContext().ensureActive()
                return@withContext OfferHttpReply(status, headers, text)
            }
            val output = ByteArrayOutputStream()
            input?.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "Source response exceeds limit" }
                    output.write(buffer, 0, count)
                }
            }
            currentCoroutineContext().ensureActive()
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            OfferHttpReply(status, headers, text)
        } finally { connection.disconnect() }
    }
}

/** A streamable-HTTP response can remain open after its correlated JSON-RPC
 * reply. Stop at that actual event, not EOF; unrelated notifications are bounded. */
internal object McpEventStream {
    fun readResponse(input: java.io.InputStream, id: Long): String {
        val reader = java.io.InputStreamReader(input, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))
        val line = StringBuilder(); val data = StringBuilder(); var count = 0
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val value = reader.read()
            if (value < 0) break
            require(++count <= 1_048_576) { "MCP stream exceeds limit" }
            if (value.toChar() == '\n') {
                val complete = line.toString().removeSuffix("\r"); line.setLength(0)
                if (complete.isEmpty()) {
                    if (data.isNotEmpty()) {
                        val json = data.toString().removeSuffix("\n"); data.setLength(0)
                        val envelope = OfferJson.read(json)
                        if (envelope["jsonrpc"] == "2.0" && OfferJson.integer(envelope["id"]) == id) return json
                    }
                } else if (complete.startsWith("data:")) data.append(complete.substring(5).removePrefix(" ")).append('\n')
            } else line.append(value.toChar())
        }
        throw OfferSourceFailure(OfferSourceFailureCode.UNSUPPORTED_SOURCE_SCHEMA)
    }
}

internal fun requirePublicHttps(uri: URI) {
    val host = uri.host?.lowercase().orEmpty()
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null && uri.port in setOf(-1, 443))
    require(host.contains('.') && host.length <= 253 && !host.endsWith(".local") && !host.endsWith(".localhost") &&
        !host.endsWith(".internal") && host.matches(Regex("[a-z0-9.-]+")) && host.any { it in 'a'..'z' })
}

internal fun OfferHttpReply.requireSuccess() {
    when (status) {
        in 200..299 -> Unit
        401 -> throw OfferSourceFailure(OfferSourceFailureCode.LOGIN_REQUIRED)
        403 -> throw OfferSourceFailure(OfferSourceFailureCode.ACCESS_BLOCKED)
        429 -> throw OfferSourceFailure(OfferSourceFailureCode.RATE_LIMITED)
        else -> throw OfferSourceFailure(OfferSourceFailureCode.NETWORK_UNAVAILABLE)
    }
}
