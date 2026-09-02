package com.moatazvid.app

import android.content.Context
import android.util.Base64
import com.moatazvid.ai.editor.EditingModel
import com.moatazvid.ai.editor.EditingModelResolver
import com.moatazvid.ai.editor.ProviderEditPlanClient
import com.moatazvid.ai.provider.*
import com.moatazvid.ai.provider.android.AndroidKeystoreSecretStore
import com.moatazvid.ai.provider.android.EncryptedSecretBlob
import com.moatazvid.ai.provider.android.EncryptedSecretBlobStore
import com.moatazvid.storage.room.AiModelAssignmentEntity
import com.moatazvid.storage.room.AiProviderDao
import com.moatazvid.storage.room.AiProviderPreferenceEntity
import com.moatazvid.storage.room.AiProviderProfileEntity
import com.moatazvid.storage.room.MoatazVidDatabase
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Application composition for cloud AI. Profiles live in Room and API keys are encrypted by
 * Android Keystore before their ciphertext is persisted in app-private SharedPreferences.
 */
class ProductionAiProviderRuntime(
    context: Context,
    database: MoatazVidDatabase,
) {
    private val profileStore = RoomProviderProfileStore(database.aiProviderDao())
    private val secretStore = AndroidKeystoreSecretStore(SharedPreferencesSecretBlobStore(context))
    private val factory = ProviderFactory(UrlConnectionHttpTransport(), secretStore)

    val settings = ProviderSettingsService(profileStore, secretStore, factory::create)
    val modelResolver: EditingModelResolver = ProductionEditingModelResolver(profileStore, factory)
    val proposalClient = ProviderEditPlanClient()

    suspend fun profiles(): List<ProviderProfile> = profileStore.list()
    suspend fun assignments(): List<ModelAssignment> = profileStore.assignments()

    suspend fun saveProvider(
        existingId: ProviderId? = null,
        type: ProviderType,
        displayName: String,
        baseUrl: String?,
        apiKey: String?,
        modelId: String?,
    ): ProviderProfile {
        require(type != ProviderType.LOCAL) { "Local LLM runtime is not bundled yet" }
        val id = existingId ?: ProviderId("provider_${UUID.randomUUID()}")
        val previous = existingId?.let { profileStore.get(it) }
        val template = when (type) {
            ProviderType.OPENAI -> ProviderDefaults.openAi(id, displayName.ifBlank { "OpenAI" })
            ProviderType.OPENROUTER -> ProviderDefaults.openRouter(id, displayName.ifBlank { "OpenRouter" })
            ProviderType.HUGGINGFACE -> ProviderDefaults.huggingFace(id, displayName.ifBlank { "Hugging Face" })
            ProviderType.NVIDIA -> ProviderDefaults.nvidia(id, displayName.ifBlank { "NVIDIA NIM" })
            ProviderType.OPENAI_COMPATIBLE, ProviderType.CUSTOM -> ProviderProfile(
                id = id,
                displayName = displayName.ifBlank { "OpenAI compatible" },
                type = type,
                baseUrl = baseUrl?.trim().orEmpty(),
                apiKeyReference = "keystore:${id.value}",
                defaultModel = modelId?.trim()?.takeIf(String::isNotBlank),
                authMode = AuthMode.BEARER,
            )
            ProviderType.LOCAL -> error("Local LLM runtime is not bundled yet")
        }
        val profile = template.copy(
            displayName = displayName.trim().ifBlank { template.displayName },
            baseUrl = baseUrl?.trim()?.takeIf(String::isNotBlank) ?: previous?.baseUrl ?: template.baseUrl,
            apiKeyReference = previous?.apiKeyReference ?: template.apiKeyReference,
            defaultModel = modelId?.trim()?.takeIf(String::isNotBlank) ?: previous?.defaultModel ?: template.defaultModel,
            enabled = true,
            priority = previous?.priority ?: profileStore.list().size,
        )
        settings.updateProvider(profile, apiKey?.takeIf(String::isNotBlank)?.toCharArray())
        val selectedModel = profile.defaultModel
        if (selectedModel != null) {
            settings.assignModelRole(ModelAssignment(ModelRole.EDITING, profile.id, selectedModel))
            settings.assignModelRole(ModelAssignment(ModelRole.FAST, profile.id, selectedModel))
        }
        if (profileStore.defaultProviderId() == null) settings.setDefaultProvider(profile.id)
        return profile
    }

    suspend fun deleteProvider(id: ProviderId): Boolean = settings.deleteProvider(id)
    suspend fun testProvider(id: ProviderId): ConnectionTestResult? = settings.testProvider(id)
    suspend fun models(id: ProviderId): LlmResult<List<ModelDescriptor>> = settings.fetchModels(id)

    suspend fun assignEditingModel(providerId: ProviderId, modelId: String) {
        val model = modelId.trim()
        require(model.isNotBlank())
        settings.assignModelRole(ModelAssignment(ModelRole.EDITING, providerId, model))
        settings.assignModelRole(ModelAssignment(ModelRole.FAST, providerId, model))
        settings.setDefaultProvider(providerId)
        profileStore.get(providerId)?.let { settings.updateProvider(it.copy(defaultModel = model)) }
    }
}

private class ProductionEditingModelResolver(
    private val profiles: RoomProviderProfileStore,
    private val factory: ProviderFactory,
) : EditingModelResolver {
    override suspend fun resolve(requirements: TaskRequirements, role: ModelRole): LlmResult<EditingModel> {
        val allProfiles = profiles.list().filter { it.enabled }
        val assignments = profiles.assignments()
        val direct = assignments.firstOrNull { it.role == role }
            ?: if (role == ModelRole.FAST) assignments.firstOrNull { it.role == ModelRole.EDITING } else null
        val defaultProvider = profiles.defaultProviderId()
        val candidates = allProfiles
            .filter { !requirements.localOnly || it.type == ProviderType.LOCAL }
            .sortedWith(
                compareByDescending<ProviderProfile> { it.id == direct?.providerId }
                    .thenByDescending { it.id == defaultProvider }
                    .thenBy { it.priority }
            )
        var lastError: LlmError? = null
        for (profile in candidates) {
            if (profile.type == ProviderType.LOCAL) continue
            val provider = runCatching { factory.create(profile) }.getOrElse {
                lastError = LlmError.ProviderUnavailable(profile.id, null, null)
                continue
            }
            val preferredId = direct?.takeIf { it.providerId == profile.id }?.modelId ?: profile.defaultModel
            val discovered = when (val result = provider.listModels()) {
                is LlmResult.Success -> result.value
                is LlmResult.Failure -> {
                    lastError = result.error
                    emptyList()
                }
            }
            val candidatesForProvider = if (discovered.isNotEmpty()) {
                if (preferredId != null) discovered.sortedByDescending { it.id == preferredId } else discovered
            } else {
                preferredId?.let { modelId ->
                    val capabilities = provider.getCapabilities(modelId).values
                    listOf(
                        ModelDescriptor(
                            id = modelId,
                            displayName = modelId,
                            providerId = profile.id,
                            contextLength = null,
                            inputModalities = buildSet {
                                add(Modality.TEXT)
                                if (capabilities.vision == TriState.YES) add(Modality.IMAGE)
                            },
                            outputModalities = setOf(Modality.TEXT),
                            capabilities = ModelCapabilities(capabilities, "provider-capabilities"),
                        )
                    )
                }.orEmpty()
            }
            val chosen = candidatesForProvider.firstOrNull { it.satisfies(requirements) }
            if (chosen != null) return LlmResult.Success(EditingModel(provider, chosen))
        }
        return LlmResult.Failure(
            lastError ?: LlmError.UnsupportedCapability(
                direct?.providerId ?: defaultProvider ?: ProviderId("unconfigured"),
                direct?.modelId,
                "No enabled configured model satisfies this task",
            )
        )
    }

    private fun ModelDescriptor.satisfies(requirements: TaskRequirements): Boolean {
        val modelContextLength = contextLength
        val capabilities = capabilities.values
        return (modelContextLength == null || modelContextLength >= requirements.minimumContext) &&
            (!requirements.needsTools || capabilities.tools == TriState.YES) &&
            (!requirements.needsStructured || capabilities.structuredOutput == TriState.YES || capabilities.jsonMode == TriState.YES) &&
            (!requirements.needsVision || capabilities.vision == TriState.YES)
    }
}

private class RoomProviderProfileStore(
    private val dao: AiProviderDao,
) : ProviderProfileStore {
    override suspend fun list(): List<ProviderProfile> = dao.profiles().map(::toDomain)
    override suspend fun get(id: ProviderId): ProviderProfile? = dao.profile(id.value)?.let(::toDomain)

    override suspend fun save(profile: ProviderProfile) {
        dao.save(
            AiProviderProfileEntity(
                providerId = profile.id.value,
                displayName = profile.displayName,
                providerType = profile.type.name,
                baseUrl = profile.baseUrl,
                apiKeyReference = profile.apiKeyReference,
                defaultModel = profile.defaultModel,
                modelsPath = profile.modelsPath,
                chatPath = profile.chatPath,
                responsesPath = profile.responsesPath,
                authMode = profile.authMode.name,
                customAuthHeader = profile.customAuthHeader,
                customHeadersJson = JSONObject(profile.customHeaders).toString(),
                extraBodyJson = encodeJsonObject(profile.extraBody).toString(),
                timeoutMs = profile.timeoutMs,
                retries = profile.retries,
                enabled = profile.enabled,
                priorityIndex = profile.priority,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun delete(id: ProviderId): Boolean = dao.delete(id.value) > 0
    override suspend fun assignments(): List<ModelAssignment> = dao.assignments().mapNotNull { row ->
        runCatching { ModelAssignment(ModelRole.valueOf(row.role), ProviderId(row.providerId), row.modelId) }.getOrNull()
    }

    override suspend fun assign(assignment: ModelAssignment) {
        dao.assign(AiModelAssignmentEntity(assignment.role.name, assignment.providerId.value, assignment.modelId))
    }

    override suspend fun setDefault(id: ProviderId?) {
        dao.preference(AiProviderPreferenceEntity(DEFAULT_PROVIDER_KEY, id?.value))
    }

    suspend fun defaultProviderId(): ProviderId? = dao.preferenceValue(DEFAULT_PROVIDER_KEY)?.takeIf(String::isNotBlank)?.let(::ProviderId)

    private fun toDomain(row: AiProviderProfileEntity): ProviderProfile = ProviderProfile(
        id = ProviderId(row.providerId),
        displayName = row.displayName,
        type = enumOr(row.providerType, ProviderType.OPENAI_COMPATIBLE),
        baseUrl = row.baseUrl,
        apiKeyReference = row.apiKeyReference,
        defaultModel = row.defaultModel,
        modelsPath = row.modelsPath,
        chatPath = row.chatPath,
        responsesPath = row.responsesPath,
        authMode = enumOr(row.authMode, AuthMode.BEARER),
        customAuthHeader = row.customAuthHeader,
        customHeaders = decodeStringMap(row.customHeadersJson),
        extraBody = decodeJsonObject(row.extraBodyJson),
        timeoutMs = row.timeoutMs,
        retries = row.retries,
        enabled = row.enabled,
        priority = row.priorityIndex,
    )

    companion object { private const val DEFAULT_PROVIDER_KEY = "default_provider" }
}

private class SharedPreferencesSecretBlobStore(context: Context) : EncryptedSecretBlobStore {
    private val prefs = context.applicationContext.getSharedPreferences("ai_provider_secret_blobs_v1", Context.MODE_PRIVATE)

    override suspend fun put(providerId: String, iv: ByteArray, ciphertext: ByteArray) {
        prefs.edit()
            .putString("$providerId.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("$providerId.ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun get(providerId: String): EncryptedSecretBlob? {
        val iv = prefs.getString("$providerId.iv", null) ?: return null
        val ciphertext = prefs.getString("$providerId.ct", null) ?: return null
        return runCatching {
            EncryptedSecretBlob(
                Base64.decode(iv, Base64.NO_WRAP),
                Base64.decode(ciphertext, Base64.NO_WRAP),
            )
        }.getOrNull()
    }

    override suspend fun delete(providerId: String): Boolean {
        val existed = prefs.contains("$providerId.iv") || prefs.contains("$providerId.ct")
        prefs.edit().remove("$providerId.iv").remove("$providerId.ct").apply()
        return existed
    }
}

private fun encodeJsonObject(value: JsonObject): JSONObject = JSONObject().apply {
    value.forEach { (key, item) -> put(key, encodeJsonValue(item)) }
}

private fun encodeJsonValue(value: JsonValue): Any? = when (value) {
    is JsonValue.StringValue -> value.value
    is JsonValue.NumberValue -> value.value
    is JsonValue.BooleanValue -> value.value
    is JsonValue.ObjectValue -> encodeJsonObject(value.value)
    is JsonValue.ArrayValue -> JSONArray().apply { value.value.forEach { put(encodeJsonValue(it)) } }
    JsonValue.NullValue -> JSONObject.NULL
}

private fun decodeJsonObject(raw: String): JsonObject = runCatching {
    val objectValue = JSONObject(raw)
    objectValue.keys().asSequence().associateWith { key -> decodeJsonValue(objectValue.opt(key)) }
}.getOrDefault(emptyMap())

private fun decodeJsonValue(value: Any?): JsonValue = when (value) {
    null, JSONObject.NULL -> JsonValue.NullValue
    is Boolean -> JsonValue.BooleanValue(value)
    is Number -> JsonValue.NumberValue(value.toDouble())
    is JSONObject -> JsonValue.ObjectValue(value.keys().asSequence().associateWith { key -> decodeJsonValue(value.opt(key)) })
    is JSONArray -> JsonValue.ArrayValue(List(value.length()) { decodeJsonValue(value.opt(it)) })
    else -> JsonValue.StringValue(value.toString())
}

private fun decodeStringMap(raw: String): Map<String, String> = runCatching {
    val value = JSONObject(raw)
    value.keys().asSequence().associateWith { key -> value.optString(key) }
}.getOrDefault(emptyMap())

private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
