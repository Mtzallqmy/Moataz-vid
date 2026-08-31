package com.moatazvid.ai.provider

data class CapabilityEvidence(val capability: String, val value: TriState, val source: EvidenceSource, val priority: Int)
enum class EvidenceSource { STATIC_PROVIDER, MODELS_ENDPOINT, MODEL_METADATA, CHEAP_PROBE, USER_OVERRIDE }

class CapabilityDetector {
    fun merge(evidence: List<CapabilityEvidence>): CapabilitySet {
        fun value(name: String): TriState = evidence.filter { it.capability == name }.maxByOrNull { it.priority }?.value ?: TriState.UNKNOWN
        return CapabilitySet(value("chat"), value("streaming"), value("jsonMode"), value("structuredOutput"), value("tools"), value("vision"), value("responsesApi"))
    }
}

interface ModelRegistry {
    suspend fun models(providerId: ProviderId, refresh: Boolean = false): LlmResult<List<ModelDescriptor>>
    suspend fun find(providerId: ProviderId, modelId: String): ModelDescriptor?
}

class InMemoryModelRegistry(private val providers: () -> Collection<LlmProvider>) : ModelRegistry {
    private val cache = mutableMapOf<ProviderId, List<ModelDescriptor>>()
    override suspend fun models(providerId: ProviderId, refresh: Boolean): LlmResult<List<ModelDescriptor>> {
        if (!refresh) cache[providerId]?.let { return LlmResult.Success(it) }
        val provider = providers().firstOrNull { it.profile.id == providerId } ?: return LlmResult.Failure(LlmError.ProviderUnavailable(providerId, null, null))
        val result = provider.listModels(); if (result is LlmResult.Success) cache[providerId] = result.value; return result
    }
    override suspend fun find(providerId: ProviderId, modelId: String): ModelDescriptor? = when (val result = models(providerId)) {
        is LlmResult.Success -> result.value.firstOrNull { it.id == modelId }; is LlmResult.Failure -> null
    }
}
