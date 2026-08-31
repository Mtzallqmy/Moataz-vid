package com.moatazvid.storage

import com.moatazvid.core.*

enum class StorageKind { SAF_URI, APP_PRIVATE, MEDIASTORE, TEMP_CACHE }
enum class FileAvailability { AVAILABLE, MISSING, PERMISSION_LOST, CHANGED }
enum class AssetType { MUSIC, SFX, IMAGE, STICKER, FONT, LUT, TEXT_STYLE, GENERATED, OTHER }
enum class CacheKind { THUMBNAIL, WAVEFORM, PROXY, ANALYSIS, RENDER }
enum class CacheImportance { REGENERATABLE, EXPENSIVE_TO_REGENERATE }
enum class TransactionOrigin { MANUAL, AI, IMPORT, MIGRATION, SYSTEM }
enum class TransactionStatus { COMMITTED, FAILED, COMPACTED }

data class FileReference(
    val id: String,
    val storageKind: StorageKind,
    val uri: String?,
    val managedRelativePath: String?,
    val persistedPermission: Boolean,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long?,
    val sha256: String?,
    val availability: FileAvailability,
) {
    init {
        require(sizeBytes >= 0)
        when (storageKind) {
            StorageKind.SAF_URI, StorageKind.MEDIASTORE -> require(!uri.isNullOrBlank())
            StorageKind.APP_PRIVATE, StorageKind.TEMP_CACHE -> require(!managedRelativePath.isNullOrBlank())
        }
        require(managedRelativePath?.startsWith('/') != true) { "Managed paths must be relative" }
    }
}

data class MediaFingerprint(
    val algorithmVersion: Int,
    val sizeBytes: Long,
    val durationUs: Long?,
    val mimeType: String,
    val metadataDigest: String,
    val headTailSha256: String,
    val fullSha256: String?,
) {
    fun canonical(): String = listOf(
        algorithmVersion, sizeBytes, durationUs ?: -1L, mimeType,
        metadataDigest, headTailSha256, fullSha256 ?: "-"
    ).joinToString(":")
}

data class CacheEntry(
    val id: String,
    val projectId: ProjectId,
    val kind: CacheKind,
    val relativePath: String,
    val sizeBytes: Long,
    val lastAccessEpochMs: Long,
    val inputFingerprint: String,
    val importance: CacheImportance,
    val pinned: Boolean = false,
)

data class EditTransactionRecord(
    val id: TransactionId,
    val projectId: ProjectId,
    val sequenceId: SequenceId,
    val parentId: TransactionId?,
    val branchId: String,
    val baseRevision: Long,
    val resultRevision: Long,
    val origin: TransactionOrigin,
    val title: String,
    val forwardOperationsJson: String,
    val inverseOperationsJson: String,
    val beforeHash: String,
    val afterHash: String,
    val status: TransactionStatus,
    val createdAtEpochMs: Long,
)

data class ProjectSnapshot(
    val project: Project,
    val sequence: Sequence,
    val tracks: List<Track>,
    val items: List<TimelineItem>,
    val constraintsRevision: Long,
)

