package com.moatazvid.ai.provider

sealed class LlmError(
    open val providerId: ProviderId, open val model: String?, open val httpStatus: Int? = null,
    open val retryAfterMs: Long? = null, open val userMessageKey: String, open val safeDebugDetail: String? = null,
) {
    class Authentication(providerId: ProviderId, model: String?, status: Int? = 401) : LlmError(providerId, model, status, userMessageKey = "ai.authentication")
    class Permission(providerId: ProviderId, model: String?, status: Int? = 403) : LlmError(providerId, model, status, userMessageKey = "ai.permission")
    class InvalidBaseUrl(providerId: ProviderId, detail: String) : LlmError(providerId, null, userMessageKey = "ai.invalid_base_url", safeDebugDetail = detail)
    class NetworkUnavailable(providerId: ProviderId, detail: String?) : LlmError(providerId, null, userMessageKey = "ai.network_unavailable", safeDebugDetail = detail)
    class Timeout(providerId: ProviderId, model: String?) : LlmError(providerId, model, userMessageKey = "ai.timeout")
    class RateLimited(providerId: ProviderId, model: String?, override val retryAfterMs: Long?) : LlmError(providerId, model, 429, retryAfterMs, "ai.rate_limited")
    class ProviderUnavailable(providerId: ProviderId, model: String?, status: Int?) : LlmError(providerId, model, status, userMessageKey = "ai.provider_unavailable")
    class ModelNotFound(providerId: ProviderId, model: String?) : LlmError(providerId, model, 404, userMessageKey = "ai.model_not_found")
    class ContextTooLarge(providerId: ProviderId, model: String?, detail: String? = null) : LlmError(providerId, model, 400, userMessageKey = "ai.context_too_large", safeDebugDetail = detail)
    class UnsupportedCapability(providerId: ProviderId, model: String?, capability: String) : LlmError(providerId, model, userMessageKey = "ai.unsupported_capability", safeDebugDetail = capability)
    class InvalidStructuredResponse(providerId: ProviderId, model: String?, detail: String?) : LlmError(providerId, model, userMessageKey = "ai.invalid_structured_response", safeDebugDetail = detail)
    class MalformedProviderResponse(providerId: ProviderId, model: String?, detail: String?) : LlmError(providerId, model, userMessageKey = "ai.malformed_response", safeDebugDetail = detail)
    class StreamingFailure(providerId: ProviderId, model: String?, detail: String?) : LlmError(providerId, model, userMessageKey = "ai.streaming_failure", safeDebugDetail = detail)
    class Cancelled(providerId: ProviderId, model: String?) : LlmError(providerId, model, userMessageKey = "ai.cancelled")
    class Unknown(providerId: ProviderId, model: String?, status: Int?, detail: String?) : LlmError(providerId, model, status, userMessageKey = "ai.unknown", safeDebugDetail = detail)
}

object HttpErrorMapper {
    fun map(provider: ProviderId, model: String?, status: Int, body: String?, retryAfterMs: Long?): LlmError = when (status) {
        401 -> LlmError.Authentication(provider, model)
        403 -> LlmError.Permission(provider, model)
        404 -> LlmError.ModelNotFound(provider, model)
        429 -> LlmError.RateLimited(provider, model, retryAfterMs)
        in 500..599 -> LlmError.ProviderUnavailable(provider, model, status)
        400 -> if (body?.contains("context", ignoreCase = true) == true) LlmError.ContextTooLarge(provider, model) else LlmError.Unknown(provider, model, status, "Invalid request")
        else -> LlmError.Unknown(provider, model, status, "HTTP $status")
    }
}
