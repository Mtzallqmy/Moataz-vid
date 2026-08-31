package com.moatazvid.storage.room

import androidx.room3.*

@Entity(tableName = "ai_provider_profiles", indices = [Index(value = ["enabled", "priorityIndex"])])
data class AiProviderProfileEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val providerType: String,
    val baseUrl: String,
    val apiKeyReference: String?,
    val defaultModel: String?,
    val modelsPath: String,
    val chatPath: String,
    val responsesPath: String,
    val authMode: String,
    val customAuthHeader: String?,
    val customHeadersJson: String,
    val extraBodyJson: String,
    val timeoutMs: Long,
    val retries: Int,
    val enabled: Boolean,
    val priorityIndex: Int,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "ai_model_assignments",
    foreignKeys = [ForeignKey(AiProviderProfileEntity::class, ["providerId"], ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class AiModelAssignmentEntity(@PrimaryKey val role: String, val providerId: String, val modelId: String)

@Entity(tableName = "ai_provider_preferences")
data class AiProviderPreferenceEntity(@PrimaryKey val key: String, val value: String?)

@Dao
interface AiProviderDao {
    @Query("SELECT * FROM ai_provider_profiles ORDER BY priorityIndex") suspend fun profiles(): List<AiProviderProfileEntity>
    @Query("SELECT * FROM ai_provider_profiles WHERE providerId = :id") suspend fun profile(id: String): AiProviderProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(profile: AiProviderProfileEntity)
    @Query("DELETE FROM ai_provider_profiles WHERE providerId = :id") suspend fun delete(id: String): Int
    @Query("SELECT * FROM ai_model_assignments") suspend fun assignments(): List<AiModelAssignmentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun assign(assignment: AiModelAssignmentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun preference(preference: AiProviderPreferenceEntity)
}
