package com.moatazvid.storage.room

import androidx.room3.*

/** Durable per-project editing memory for the Android-native video-use workflow. */
@Entity(
    tableName = "video_use_sessions",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index(value = ["projectId", "updatedAtEpochMs"])],
)
data class VideoUseSessionEntity(
    @PrimaryKey val sessionId: String,
    val projectId: String,
    val projectRevision: Long,
    val phase: String,
    val userInstruction: String,
    val strategyText: String?,
    val strategyStatus: String?,
    val editPlanId: String?,
    val editSummary: String?,
    val selfEvaluationJson: String?,
    val userFeedback: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Dao
interface VideoUseSessionDao {
    @Query("SELECT * FROM video_use_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun session(sessionId: String): VideoUseSessionEntity?

    @Query("SELECT * FROM video_use_sessions WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(projectId: String, limit: Int = 20): List<VideoUseSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: VideoUseSessionEntity)

    @Query("DELETE FROM video_use_sessions WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String): Int
}
