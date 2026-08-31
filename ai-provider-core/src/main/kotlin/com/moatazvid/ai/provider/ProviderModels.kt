package com.moatazvid.ai.provider

@JvmInline value class ProviderId(val value: String)
@JvmInline value class RequestId(val value: String)

enum class ProviderType { OPENAI, OPENROUTER, HUGGINGFACE, NVIDIA, OPENAI_COMPATIBLE, CUSTOM, LOCAL }
enum class AuthMode { BEARER, API_KEY_HEADER, CUSTOM_HEADER, NONE }
enum class TriState { YES, NO, UNKNOWN }
enum class Modality { TEXT, IMAGE, AUDIO }

data class ProviderProfile(
    val id: ProviderId,
    val displayName: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKeyReference: String?,
    val defaultModel: String?,
    val modelsPath: String = "models",
    val chatPath: String = "chat/completions",
    val responsesPath: String = "responses",
    val authMode: AuthMode = AuthMode.BEARER,
    val customAuthHeader: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val extraBody: JsonObject = emptyMap(),
    val timeoutMs: Long = 60_000,
    val retries: Int = 2,
    val enabled: Boolean = true,
    val priority: Int = 0,
) {
    init {
        require(displayName.isNotBlank()); require(timeoutMs in 1_000..600_000); require(retries in 0..5)
        if (authMode == AuthMode.CUSTOM_HEADER) require(!customAuthHeader.isNullOrBlank())
        HeaderPolicy.validate(customHeaders, advanced = false)
    }
}

data class CapabilitySet(
    val chat: TriState = TriState.UNKNOWN,
    val streaming: TriState = TriState.UNKNOWN,
    val jsonMode: TriState = TriState.UNKNOWN,
    val structuredOutput: TriState = TriState.UNKNOWN,
    val tools: TriState = TriState.UNKNOWN,
    val vision: TriState = TriState.UNKNOWN,
    val responsesApi: TriState = TriState.UNKNOWN,
)

data class ProviderCapabilities(val values: CapabilitySet, val source: String)
data class ModelCapabilities(val values: CapabilitySet, val source: String, val sourceVersion: String? = null)

data class ModelPricing(val inputPerMillion: Double?, val outputPerMillion: Double?)
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val providerId: ProviderId,
    val contextLength: Long?,
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val capabilities: ModelCapabilities,
    val pricing: ModelPricing? = null,
)

enum class EditingCapabilityLevel { LEVEL_A, LEVEL_B, LEVEL_C, LEVEL_D }
fun CapabilitySet.editingLevel(): EditingCapabilityLevel = when {
    structuredOutput == TriState.YES && tools == TriState.YES -> EditingCapabilityLevel.LEVEL_A
    structuredOutput == TriState.YES -> EditingCapabilityLevel.LEVEL_B
    jsonMode == TriState.YES -> EditingCapabilityLevel.LEVEL_C
    else -> EditingCapabilityLevel.LEVEL_D
}

enum class ModelRole { EDITING, FAST, VISION, LOCAL, FUTURE_AUDIO }
data class ModelAssignment(val role: ModelRole, val providerId: ProviderId, val modelId: String)

typealias JsonObject = Map<String, JsonValue>
sealed interface JsonValue {
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: Double) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data class ObjectValue(val value: JsonObject) : JsonValue
    data class ArrayValue(val value: List<JsonValue>) : JsonValue
    data object NullValue : JsonValue
}

object HeaderPolicy {
    private val protected = setOf("authorization", "content-length", "host", "connection", "transfer-encoding")
    fun validate(headers: Map<String, String>, advanced: Boolean) {
        require(headers.keys.all { it.matches(Regex("[A-Za-z0-9-]{1,80}")) })
        if (!advanced) require(headers.keys.none { it.lowercase() in protected }) { "Protected HTTP header override" }
        require(headers.values.all { '\n' !in it && '\r' !in it })
    }
}
