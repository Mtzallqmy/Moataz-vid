package com.moatazvid.storage

import com.moatazvid.core.*
import kotlinx.coroutines.flow.Flow

sealed interface StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>
    data class Failure(val error: StorageError) : StorageResult<Nothing>
}

sealed interface StorageError {
    data class NotFound(val entity: String, val id: String) : StorageError
    data class PermissionLost(val fileRefId: String) : StorageError
    data class Conflict(val expectedRevision: Long, val actualRevision: Long) : StorageError
    data class OutOfStorage(val requiredBytes: Long, val availableBytes: Long) : StorageError
    data class CorruptState(val detail: String) : StorageError
    data class IoFailure(val operation: String, val detail: String) : StorageError
}

interface ProjectRepository {
    suspend fun create(project: Project, initialSequence: Sequence): StorageResult<ProjectSnapshot>
    suspend fun load(projectId: ProjectId): StorageResult<ProjectSnapshot>
    fun observe(projectId: ProjectId): Flow<ProjectSnapshot>
    suspend fun archive(projectId: ProjectId): StorageResult<Unit>
    suspend fun delete(projectId: ProjectId, deleteManagedFiles: Boolean): StorageResult<Unit>
}

interface TimelineRepository {
    suspend fun load(sequenceId: SequenceId): StorageResult<ProjectSnapshot>
    suspend fun applyTransaction(
        expectedRevision: Long,
        transaction: EditTransactionRecord,
        mutation: TimelineMutation,
    ): StorageResult<ProjectSnapshot>
}

interface TransactionRepository {
    suspend fun current(sequenceId: SequenceId): EditTransactionRecord?
    suspend fun undo(sequenceId: SequenceId): StorageResult<ProjectSnapshot>
    suspend fun redo(sequenceId: SequenceId): StorageResult<ProjectSnapshot>
    suspend fun list(sequenceId: SequenceId, limit: Int): List<EditTransactionRecord>
}

interface UriResolver {
    suspend fun inspect(uri: String): StorageResult<ResolvedUri>
    suspend fun takePersistableReadPermission(uri: String): Boolean
    suspend fun canRead(fileReference: FileReference): Boolean
    suspend fun copyToManagedStorage(source: FileReference, targetRelativePath: String): StorageResult<FileReference>
}

data class ResolvedUri(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtEpochMs: Long?,
    val supportsPersistablePermission: Boolean,
)

sealed interface TimelineMutation {
    data class ReplaceState(val snapshot: ProjectSnapshot) : TimelineMutation
    data class ApplyOperations(val canonicalOperationsJson: String) : TimelineMutation
}

