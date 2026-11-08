package de.ernaehrung.demo.data.ai

import de.ernaehrung.nano.NanoRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal enum class DirectRecipeMode(val title: String) {
    NANO4_FAST("Gemini Nano 4 Fast · auf diesem Gerät"), NANO3("Gemini Nano 3 · auf diesem Gerät"), CLOUD("Gemini Cloud API · optional")
}
internal enum class DirectModelStatus { NOT_CHECKED, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, READY, VERSION_MISMATCH, ERROR }
internal data class DirectModelInfo(val status: DirectModelStatus, val modelName: String? = null, val code: String? = null)
internal data class DirectModelReply(val text: String, val verifiedModel: String) {
    override fun toString() = "DirectModelReply(content redacted)"
}
internal class DirectModelFailure(val code: String) : Exception(code)

internal object NanoModelIdentity {
    /** Configuration alone never pins a version. Only explicit version-family
     * names are accepted; an unknown future identifier remains unverified. */
    fun matches(mode: DirectRecipeMode, raw: String): Boolean {
        if (mode == DirectRecipeMode.CLOUD || raw.length !in 1..160 || raw.any(Char::isISOControl)) return false
        val version = if (mode == DirectRecipeMode.NANO4_FAST) 4 else 3
        return Regex("(?i)^(?:models/)?(?:gemini[- _])?nano[- _]?(?:v(?:ersion)?[- _]?)?$version(?:$|[-._ /].*)").matches(raw) &&
            (mode != DirectRecipeMode.NANO4_FAST || !raw.contains("full", ignoreCase = true))
    }
}
internal suspend fun <T> CompletableFuture<T>.awaitDirect(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, failure ->
        if (!continuation.isActive) return@whenComplete
        if (failure == null) continuation.resume(value)
        else {
            var cause = failure
            while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
            continuation.resumeWithException(if (cause is NanoRuntime.Failure) DirectModelFailure(cause.code)
                else DirectModelFailure("MODEL_REQUEST_FAILED"))
        }
    }
    continuation.invokeOnCancellation { cancel(true) }
}

/** Real SDK calls only after an explicit user action. No SDK classes in Kotlin
 * public ABI, implicit download/warmup, background inference or cloud fallback. */
internal class NanoRecipeEngine(private val factory: (Boolean) -> NanoRuntime = ::NanoRuntime) {
    private suspend fun <T> use(mode: DirectRecipeMode, action: suspend (NanoRuntime) -> T): T {
        require(mode != DirectRecipeMode.CLOUD)
        val runtime = try { factory(mode == DirectRecipeMode.NANO4_FAST) }
        catch (failure: NanoRuntime.Failure) { throw DirectModelFailure(failure.code) }
        return try { action(runtime) } finally { runCatching { runtime.close() } }
    }
    suspend fun check(mode: DirectRecipeMode): DirectModelInfo = use(mode) { client ->
        when (client.checkStatus().awaitDirect()) {
            NanoRuntime.UNAVAILABLE -> DirectModelInfo(DirectModelStatus.UNAVAILABLE)
            NanoRuntime.DOWNLOADABLE -> DirectModelInfo(DirectModelStatus.DOWNLOADABLE)
            NanoRuntime.DOWNLOADING -> DirectModelInfo(DirectModelStatus.DOWNLOADING)
            NanoRuntime.AVAILABLE -> {
                val name = client.modelName().awaitDirect()
                DirectModelInfo(if (NanoModelIdentity.matches(mode, name)) DirectModelStatus.READY else DirectModelStatus.VERSION_MISMATCH,
                    name.takeIf { it.length <= 160 && it.none(Char::isISOControl) })
            }
            else -> DirectModelInfo(DirectModelStatus.ERROR, code = "UNKNOWN_SDK_STATUS")
        }
    }
    suspend fun download(mode: DirectRecipeMode, progress: (Long) -> Unit) = use(mode) { client ->
        if (client.checkStatus().awaitDirect() != NanoRuntime.DOWNLOADABLE) throw DirectModelFailure("DOWNLOAD_NOT_AVAILABLE")
        client.download { bytes -> progress(bytes) }.awaitDirect()
        Unit
    }
    suspend fun generate(mode: DirectRecipeMode, prompt: String): DirectModelReply = withTimeout(120_000) {
        use(mode) { client ->
            if (client.checkStatus().awaitDirect() != NanoRuntime.AVAILABLE) throw DirectModelFailure("MODEL_NOT_READY")
            val name = client.modelName().awaitDirect()
            if (!NanoModelIdentity.matches(mode, name)) throw DirectModelFailure("MODEL_VERSION_UNVERIFIED")
            val limit = client.tokenLimit().awaitDirect()
            if (limit !in 512..1_000_000) throw DirectModelFailure("UNKNOWN_TOKEN_LIMIT")
            val output = minOf(2048, limit / 2)
            val input = client.countTokens(prompt, output).awaitDirect()
            if (input < 0 || input.toLong() + output > limit) throw DirectModelFailure("REQUEST_TOO_LARGE")
            val reply = client.generate(prompt, output).awaitDirect()
            if (!reply.complete) throw DirectModelFailure("INCOMPLETE_REPLY")
            if (client.modelName().awaitDirect() != name) throw DirectModelFailure("MODEL_CHANGED")
            DirectModelReply(reply.text, name)
        }
    }
}
