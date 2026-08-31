package com.moatazvid.ai.provider

import kotlinx.coroutines.flow.Flow
import java.net.URI
import kotlin.math.min
import kotlin.random.Random

object BaseUrlNormalizer {
    fun normalize(raw: String, defaultVersion: String = "v1"): String {
        val parsed = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("Malformed provider URL") }
        require(parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrBlank()) { "Provider URL requires http(s) host" }
        require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) { "Credentials, query and fragment are not allowed in base URL" }
        val cleanSegments = parsed.path.orEmpty().split('/').filter(String::isNotBlank).toMutableList()
        while (cleanSegments.size >= 2 && cleanSegments.last() == defaultVersion && cleanSegments[cleanSegments.lastIndex - 1] == defaultVersion) cleanSegments.removeLast()
        if (cleanSegments.lastOrNull() != defaultVersion) cleanSegments += defaultVersion
        val authority = if (parsed.port == -1) parsed.host else "${parsed.host}:${parsed.port}"
        return URI(parsed.scheme.lowercase(), authority.lowercase(), "/${cleanSegments.joinToString("/")}", null, null).toString().trimEnd('/')
    }

    fun resolve(base: String, path: String): String {
        val normalized = base.trimEnd('/')
        val pathSegments = path.trim().trim('/').split('/').filter(String::isNotBlank)
        val baseSegments = URI(normalized).path.split('/').filter(String::isNotBlank)
        val overlap = (min(baseSegments.size, pathSegments.size) downTo 1).firstOrNull { n -> baseSegments.takeLast(n) == pathSegments.take(n) } ?: 0
        return "$normalized/${pathSegments.drop(overlap).joinToString("/")}".trimEnd('/')
    }
}

data class HttpRequest(
    val requestId: RequestId, val method: String, val url: String, val headers: Map<String, String>,
    val body: String?, val timeoutMs: Long, val idempotent: Boolean,
)
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)
interface HttpTransport {
    suspend fun execute(request: HttpRequest): TransportResult<HttpResponse>
    fun stream(request: HttpRequest): Flow<TransportStreamEvent>
    suspend fun cancel(requestId: RequestId): Boolean
}
sealed interface TransportResult<out T> { data class Success<T>(val value: T) : TransportResult<T>; data class Failure(val detail: String, val timeout: Boolean = false, val offline: Boolean = false) : TransportResult<Nothing> }
sealed interface TransportStreamEvent { data class Data(val line: String) : TransportStreamEvent; data class Failure(val detail: String) : TransportStreamEvent; data object Closed : TransportStreamEvent }

interface SecretStore {
    suspend fun saveSecret(providerId: ProviderId, value: CharArray): String
    suspend fun readSecret(providerId: ProviderId): SecretValue?
    suspend fun deleteSecret(providerId: ProviderId): Boolean
}
class SecretValue(private val chars: CharArray) : AutoCloseable {
    fun <T> useValue(block: (CharArray) -> T): T = block(chars)
    override fun close() { chars.fill('\u0000') }
    override fun toString() = "SecretValue(REDACTED)"
}

object AuthHeaderFactory {
    fun create(profile: ProviderProfile, secret: CharArray?): Map<String, String> = when (profile.authMode) {
        AuthMode.NONE -> emptyMap()
        AuthMode.BEARER -> mapOf("Authorization" to "Bearer ${requireNotNull(secret).concatToString()}")
        AuthMode.API_KEY_HEADER -> mapOf("X-API-Key" to requireNotNull(secret).concatToString())
        AuthMode.CUSTOM_HEADER -> mapOf(requireNotNull(profile.customAuthHeader) to requireNotNull(secret).concatToString())
    }
}

data class RetryDecision(val retry: Boolean, val delayMs: Long)
class RetryPolicy(private val maximumRetries: Int, private val jitter: (Long) -> Long = { Random.nextLong(0, it.coerceAtLeast(1)) }) {
    fun decide(attempt: Int, status: Int?, transientNetwork: Boolean, retryAfterMs: Long?, idempotent: Boolean): RetryDecision {
        if (attempt >= maximumRetries || !idempotent) return RetryDecision(false, 0)
        val retryable = transientNetwork || status == 429 || status in 500..599
        if (!retryable) return RetryDecision(false, 0)
        val base = retryAfterMs ?: (500L shl attempt).coerceAtMost(8_000)
        return RetryDecision(true, base + jitter(base / 4 + 1))
    }
}

class RedactingNetworkLogger(private val enabled: Boolean) {
    private val sensitiveHeaders = setOf("authorization", "x-api-key", "api-key")
    fun requestSummary(request: HttpRequest): String {
        if (!enabled) return "network logging disabled"
        val headers = request.headers.mapValues { (key, value) -> if (key.lowercase() in sensitiveHeaders || key.lowercase().contains("token")) "<redacted>" else value.take(128) }
        return "${request.method} ${request.url} headers=$headers body=<redacted:${request.body?.length ?: 0} chars>"
    }
}
