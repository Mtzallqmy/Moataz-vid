package com.moatazvid.app

import com.moatazvid.ai.editor.PendingEditStrategy
import com.moatazvid.ai.editor.PendingEditTransaction
import com.moatazvid.core.ProjectId
import com.moatazvid.storage.room.VideoUseSessionEntity
import com.moatazvid.videouse.VideoUseSelfEvaluationReport
import com.moatazvid.videouse.VideoUseSessionPhase

/** Room-backed equivalent of video-use's project/session memory. */
class ProductionVideoUseSessionMemory(
    private val repository: ProductionProjectRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun strategyReady(strategy: PendingEditStrategy) {
        val now = clock()
        repository.database.videoUseSessionDao().upsert(
            VideoUseSessionEntity(
                sessionId = strategy.id,
                projectId = strategy.projectId.value,
                projectRevision = strategy.baseRevision,
                phase = VideoUseSessionPhase.STRATEGY_READY.name,
                userInstruction = strategy.userInstruction,
                strategyText = strategy.summary,
                strategyStatus = "PENDING_APPROVAL",
                editPlanId = null,
                editSummary = null,
                selfEvaluationJson = null,
                userFeedback = null,
                createdAtEpochMs = strategy.createdAtEpochMs,
                updatedAtEpochMs = now,
            )
        )
    }

    suspend fun strategyConfirmed(sessionId: String) {
        update(sessionId) { current, now ->
            current.copy(
                phase = VideoUseSessionPhase.STRATEGY_CONFIRMED.name,
                strategyStatus = "CONFIRMED",
                updatedAtEpochMs = now,
            )
        }
    }

    suspend fun strategyRejected(sessionId: String) {
        update(sessionId) { current, now ->
            current.copy(
                phase = VideoUseSessionPhase.CONVERSATION.name,
                strategyStatus = "REJECTED",
                updatedAtEpochMs = now,
            )
        }
    }

    suspend fun planReady(sessionId: String, pending: PendingEditTransaction) {
        update(sessionId) { current, now ->
            current.copy(
                phase = VideoUseSessionPhase.PLAN_READY.name,
                projectRevision = pending.baseRevision,
                strategyStatus = "CONFIRMED",
                editPlanId = pending.editPlan.id.value,
                editSummary = pending.editPlan.summary.ifBlank { pending.editPlan.title },
                updatedAtEpochMs = now,
            )
        }
    }

    suspend fun applied(sessionId: String, projectRevision: Long) {
        update(sessionId) { current, now ->
            current.copy(
                phase = VideoUseSessionPhase.PREVIEW.name,
                projectRevision = projectRevision,
                updatedAtEpochMs = now,
            )
        }
    }

    suspend fun recordSelfEvaluation(projectId: ProjectId, report: VideoUseSelfEvaluationReport) {
        val session = repository.database.videoUseSessionDao().recent(projectId.value, 1).firstOrNull() ?: return
        val now = clock()
        repository.database.videoUseSessionDao().upsert(
            session.copy(
                phase = VideoUseSessionPhase.FINAL.name,
                selfEvaluationJson = report.toCompactJson(),
                updatedAtEpochMs = now,
            )
        )
    }

    suspend fun recentMemory(projectId: ProjectId, limit: Int = 8): List<VideoUseSessionEntity> =
        repository.database.videoUseSessionDao().recent(projectId.value, limit.coerceIn(1, 20))

    private suspend fun update(
        sessionId: String,
        transform: (VideoUseSessionEntity, Long) -> VideoUseSessionEntity,
    ) {
        val current = repository.database.videoUseSessionDao().session(sessionId) ?: return
        repository.database.videoUseSessionDao().upsert(transform(current, clock()))
    }

    private fun VideoUseSelfEvaluationReport.toCompactJson(): String {
        val issuesJson = issues.joinToString(prefix = "[", postfix = "]") { issue ->
            "{\"code\":\"${issue.code.escapeJson()}\",\"severity\":\"${issue.severity.name}\",\"timeUs\":${issue.timelineTime?.value ?: -1},\"message\":\"${issue.message.escapeJson()}\"}"
        }
        return "{\"pass\":$pass,\"passed\":$passed,\"representativeFramesChecked\":$representativeFramesChecked,\"cutBoundariesChecked\":$cutBoundariesChecked,\"issues\":$issuesJson}"
    }

    private fun String.escapeJson(): String = buildString(length) {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
