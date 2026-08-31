package com.moatazvid.ai.provider

object ProviderDefaults {
    fun openAi(id: ProviderId, name: String = "OpenAI") = ProviderProfile(id, name, ProviderType.OPENAI, "https://api.openai.com/v1", "keystore:${id.value}", null)
    fun openRouter(id: ProviderId, name: String = "OpenRouter", attribution: Map<String, String> = emptyMap()) = ProviderProfile(
        id, name, ProviderType.OPENROUTER, "https://openrouter.ai/api/v1", "keystore:${id.value}", null, customHeaders = attribution)
    fun huggingFace(id: ProviderId, name: String = "Hugging Face") = ProviderProfile(
        id, name, ProviderType.HUGGINGFACE, "https://router.huggingface.co/v1", "keystore:${id.value}", null)
    fun nvidia(id: ProviderId, name: String = "NVIDIA NIM") = ProviderProfile(
        id, name, ProviderType.NVIDIA, "https://integrate.api.nvidia.com/v1", "keystore:${id.value}", null)
}

class ProviderFactory(private val transport: HttpTransport, private val secrets: SecretStore) {
    fun create(profile: ProviderProfile): LlmProvider = when (profile.type) {
        ProviderType.OPENAI -> OpenAiProvider(profile, transport, secrets)
        ProviderType.OPENROUTER -> OpenRouterProvider(profile, transport, secrets)
        ProviderType.HUGGINGFACE -> HuggingFaceProvider(profile, transport, secrets)
        ProviderType.NVIDIA -> NvidiaProvider(profile, transport, secrets)
        ProviderType.OPENAI_COMPATIBLE, ProviderType.CUSTOM -> CustomOpenAiCompatibleProvider(profile, transport, secrets)
        ProviderType.LOCAL -> error("A LocalLlmProvider runtime must be installed explicitly")
    }
}
