package com.moatazvid.storage.room

import androidx.room3.*

@Entity(tableName = "projects", indices = [Index("updatedAtEpochMs")])
data class ProjectEntity(
    @PrimaryKey val projectId: String,
    val title: String,
    val activeSequenceId: String?,
    val schemaVersion: String,
    val timelineRevision: Long,
    val status: String,
    val privacyLevel: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val rowRevision: Long,
)

@Entity(
    tableName = "sequences",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index(value = ["projectId", "name"], unique = true)],
)
data class SequenceEntity(
    @PrimaryKey val sequenceId: String,
    val projectId: String,
    val name: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fpsNumerator: Int,
    val fpsDenominator: Int,
    val colorMode: String,
    val revision: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "file_references",
    indices = [Index("sha256"), Index("managedRelativePath", unique = true)],
)
data class FileReferenceEntity(
    @PrimaryKey val fileRefId: String,
    val storageKind: String,
    val uriString: String?,
    val managedRelativePath: String?,
    val persistedPermission: Boolean,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long?,
    val sha256: String?,
    val availability: String,
    val lastVerifiedAtEpochMs: Long?,
)

@Entity(
    tableName = "media_sources",
    foreignKeys = [
        ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FileReferenceEntity::class, ["fileRefId"], ["fileRefId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("projectId"), Index("fileRefId"), Index(value = ["projectId", "quickFingerprint"]),
    ],
)
data class MediaSourceEntity(
    @PrimaryKey val sourceId: String,
    val projectId: String,
    val fileRefId: String,
    val kind: String,
    val displayName: String,
    val importMode: String,
    val mimeType: String,
    val durationUs: Long?,
    val codedWidth: Int?,
    val codedHeight: Int?,
    val rotationDegrees: Int,
    val fpsNumerator: Int?,
    val fpsDenominator: Int?,
    val colorSpace: String,
    val quickFingerprint: String,
    val fullSha256: String?,
    val fingerprintVersion: Int,
    val availability: String,
    val metadataVersion: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(SequenceEntity::class, ["sequenceId"], ["sequenceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sequenceId"), Index(value = ["sequenceId", "type", "orderIndex"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey val trackId: String,
    val sequenceId: String,
    val type: String,
    val name: String,
    val orderIndex: Int,
    val collisionPolicy: String,
    val muted: Boolean,
    val hidden: Boolean,
    val locked: Boolean,
    val volumeDb: Float,
    val blendMode: String?,
)

@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(SequenceEntity::class, ["sequenceId"], ["sequenceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["trackId"], ["trackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaSourceEntity::class, ["sourceId"], ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("sequenceId"), Index(value = ["trackId", "timelineStartUs"]), Index("sourceId"),
        Index("linkGroupId"),
    ],
)
data class ClipEntity(
    @PrimaryKey val clipId: String,
    val projectId: String,
    val sequenceId: String,
    val trackId: String,
    val sourceId: String?,
    val itemType: String,
    val timelineStartUs: Long,
    val timelineDurationUs: Long,
    val sourceInUs: Long?,
    val sourceOutUs: Long?,
    val zIndex: Int,
    val enabled: Boolean,
    val locked: Boolean,
    val lockReason: String?,
    val groupId: String?,
    val linkGroupId: String?,
    val rowRevision: Long,
)

@Entity(
    tableName = "clip_properties",
    foreignKeys = [ForeignKey(ClipEntity::class, ["clipId"], ["clipId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("clipId", unique = true)],
)
data class ClipPropertiesEntity(
    @PrimaryKey val clipId: String,
    val streamId: String?,
    val opacity: Float,
    val gainDb: Float,
    val pan: Float,
    val muted: Boolean,
    val preservePitch: Boolean,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val speedMapJson: String,
    val transformJson: String?,
    val audioRole: String?,
    val extraJson: String?,
    val parameterSchemaVersion: Int,
)

@Entity(
    tableName = "captions",
    foreignKeys = [ForeignKey(TrackEntity::class, ["trackId"], ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["trackId", "startUs"]), Index("transcriptId")],
)
data class CaptionEntity(
    @PrimaryKey val captionId: String,
    val sequenceId: String,
    val trackId: String,
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val styleId: String,
    val sourceType: String,
    val transcriptId: String?,
    val linkedWordIdsJson: String,
    val userEdited: Boolean,
    val alignmentStatus: String,
    val rowRevision: Long,
)

@Entity(
    tableName = "overlays",
    foreignKeys = [ForeignKey(ClipEntity::class, ["clipId"], ["clipId"], onDelete = ForeignKey.CASCADE)],
)
data class OverlayEntity(
    @PrimaryKey val clipId: String,
    val overlayType: String,
    val text: String?,
    val assetId: String?,
    val styleJson: String,
    val transformJson: String,
    val schemaVersion: Int,
)

@Entity(
    tableName = "effects",
    foreignKeys = [ForeignKey(ClipEntity::class, ["clipId"], ["clipId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["clipId", "orderIndex"], unique = true)],
)
data class EffectEntity(
    @PrimaryKey val effectId: String,
    val clipId: String,
    val effectType: String,
    val startOffsetUs: Long,
    val endOffsetUs: Long,
    val orderIndex: Int,
    val enabled: Boolean,
    val parametersJson: String,
    val parameterSchemaVersion: Int,
)

@Entity(
    tableName = "transitions",
    foreignKeys = [
        ForeignKey(TrackEntity::class, ["trackId"], ["trackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ClipEntity::class, ["outgoingClipId"], ["clipId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ClipEntity::class, ["incomingClipId"], ["clipId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId"), Index("outgoingClipId"), Index("incomingClipId"), Index(value = ["outgoingClipId", "incomingClipId"], unique = true)],
)
data class TransitionEntity(
    @PrimaryKey val transitionId: String,
    val trackId: String,
    val outgoingClipId: String,
    val incomingClipId: String,
    val type: String,
    val durationUs: Long,
    val alignment: String,
    val parametersJson: String,
)

@Entity(
    tableName = "assets",
    foreignKeys = [ForeignKey(FileReferenceEntity::class, ["fileRefId"], ["fileRefId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("projectId"), Index("fileRefId"), Index("contentHash")],
)
data class AssetEntity(
    @PrimaryKey val assetId: String,
    val projectId: String?,
    val fileRefId: String?,
    val assetType: String,
    val name: String,
    val contentHash: String?,
    val licenseType: String,
    val attribution: String?,
    val metadataJson: String,
    val userCreated: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "project_constraints",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class ProjectConstraintEntity(
    @PrimaryKey val constraintId: String,
    val projectId: String,
    val type: String,
    val priority: String,
    val payloadJson: String,
    val summary: String,
    val source: String,
    val enabled: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "protected_ranges",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index(value = ["sourceId", "startUs", "endUs"])],
)
data class ProtectedRangeEntity(
    @PrimaryKey val protectedRangeId: String,
    val projectId: String,
    val scope: String,
    val sourceId: String?,
    val sequenceId: String?,
    val startUs: Long,
    val endUs: Long,
    val protectionFlags: Long,
    val reason: String,
    val createdBy: String,
    val enabled: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "transcripts",
    foreignKeys = [ForeignKey(MediaSourceEntity::class, ["sourceId"], ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index(value = ["sourceId", "sourceFingerprint"])],
)
data class TranscriptEntity(
    @PrimaryKey val transcriptId: String,
    val sourceId: String,
    val streamId: String?,
    val languageTag: String?,
    val engine: String,
    val modelId: String,
    val modelVersion: String,
    val status: String,
    val sourceFingerprint: String,
    val relativeArtifactPath: String?,
    val revision: Long,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "analysis_records",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index(value = ["subjectId", "analysisType", "inputFingerprint"])],
)
data class AnalysisEntity(
    @PrimaryKey val analysisId: String,
    val projectId: String,
    val subjectType: String,
    val subjectId: String,
    val analysisType: String,
    val startUs: Long?,
    val endUs: Long?,
    val inputFingerprint: String,
    val payloadJson: String?,
    val relativeArtifactPath: String?,
    val analyzerVersion: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "proxies",
    foreignKeys = [
        ForeignKey(MediaSourceEntity::class, ["sourceId"], ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FileReferenceEntity::class, ["fileRefId"], ["fileRefId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["sourceId", "presetId"], unique = true), Index("fileRefId")],
)
data class ProxyEntity(
    @PrimaryKey val proxyId: String,
    val sourceId: String,
    val fileRefId: String,
    val presetId: String,
    val width: Int,
    val height: Int,
    val fpsNumerator: Int,
    val fpsDenominator: Int,
    val codecMime: String,
    val sourceFingerprint: String,
    val status: String,
    val progressPermille: Int,
    val generatedAtEpochMs: Long?,
    val errorCode: String?,
)

@Entity(
    tableName = "edit_transactions",
    foreignKeys = [
        ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SequenceEntity::class, ["sequenceId"], ["sequenceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("projectId"), Index(value = ["sequenceId", "resultRevision"], unique = true), Index("parentTransactionId")],
)
data class EditTransactionEntity(
    @PrimaryKey val transactionId: String,
    val projectId: String,
    val sequenceId: String,
    val parentTransactionId: String?,
    val branchId: String,
    val baseRevision: Long,
    val resultRevision: Long,
    val origin: String,
    val title: String,
    val editPlanId: String?,
    val forwardOperationsJson: String,
    val inverseOperationsJson: String,
    val beforeTimelineHash: String,
    val afterTimelineHash: String,
    val status: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "history_cursors")
data class HistoryCursorEntity(
    @PrimaryKey val sequenceId: String,
    val currentTransactionId: String?,
    val activeBranchId: String,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "export_records",
    foreignKeys = [
        ForeignKey(ProjectEntity::class, ["projectId"], ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SequenceEntity::class, ["sequenceId"], ["sequenceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("projectId"), Index("sequenceId"), Index("createdAtEpochMs")],
)
data class ExportRecordEntity(
    @PrimaryKey val exportId: String,
    val projectId: String,
    val sequenceId: String,
    val timelineRevision: Long,
    val outputUri: String?,
    val status: String,
    val progressPermille: Int,
    val settingsJson: String,
    val backend: String?,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long?,
    val completedAtEpochMs: Long?,
    val errorCode: String?,
)
