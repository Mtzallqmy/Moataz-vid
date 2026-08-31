package com.moatazvid.ai.provider

interface ProviderProfileStore {
    suspend fun list(): List<ProviderProfile>
    suspend fun get(id: ProviderId): ProviderProfile?
    suspend fun save(profile: ProviderProfile)
    suspend fun delete(id: ProviderId): Boolean
    suspend fun assignments(): List<ModelAssignment>
    suspend fun assign(assignment: ModelAssignment)
    suspend fun setDefault(id: ProviderId?)
}

class ProviderSettingsService(
    private val profiles: ProviderProfileStore,
    private val secrets: SecretStore,
    private val providers: (ProviderProfile) -> LlmProvider,
) {
    suspend fun addProvider(profile: ProviderProfile, secret: CharArray?) {
        profiles.save(profile)
        if (secret != null) secrets.saveSecret(profile.id, secret)
    }
    suspend fun updateProvider(profile: ProviderProfile, newSecret: CharArray? = null) = addProvider(profile, newSecret)
    suspend fun deleteProvider(id: ProviderId): Boolean { secrets.deleteSecret(id); return profiles.delete(id) }
    suspend fun testProvider(id: ProviderId): ConnectionTestResult? = profiles.get(id)?.let { providers(it).testConnection() }
    suspend fun fetchModels(id: ProviderId): LlmResult<List<ModelDescriptor>> = profiles.get(id)?.let { providers(it).listModels() }
        ?: LlmResult.Failure(LlmError.ProviderUnavailable(id, null, null))
    suspend fun assignModelRole(assignment: ModelAssignment) = profiles.assign(assignment)
    suspend fun setDefaultProvider(id: ProviderId?) = profiles.setDefault(id)
    suspend fun readCapabilities(id: ProviderId, model: String?) = profiles.get(id)?.let { providers(it).getCapabilities(model) }
}
