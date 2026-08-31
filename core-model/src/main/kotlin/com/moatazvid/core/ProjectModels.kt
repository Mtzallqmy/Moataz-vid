package com.moatazvid.core

enum class PrivacyLevel { LOCAL_ONLY, TEXT_AI, VISION_AI, CLOUD_MEDIA }
enum class ProjectStatus { ACTIVE, ARCHIVED, RECOVERY_REQUIRED }
enum class MediaKind { VIDEO, AUDIO, IMAGE, GENERATED }
enum class SourceAvailability { AVAILABLE, MISSING, PERMISSION_LOST, CHANGED }
enum class ImportMode { LINKED_SAF, MANAGED_COPY, GENERATED }
enum class TrackType { VIDEO, AUDIO, MUSIC, CAPTION, OVERLAY }
enum class CollisionPolicy { NO_OVERLAP, ALLOW_OVERLAP, STACK }
enum class TimelineItemType { VIDEO, AUDIO, MUSIC, TEXT, IMAGE }
enum class ProjectColorMode { SDR, HDR_KEEP, HDR_TO_SDR }

data class Project(
    val id: ProjectId,
    val title: String,
    val activeSequenceId: SequenceId?,
    val timelineRevision: Long,
    val schemaVersion: String = "1.0.0",
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val privacyLevel: PrivacyLevel = PrivacyLevel.LOCAL_ONLY,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(title.isNotBlank() && title.length <= 200)
        require(timelineRevision >= 0)
    }
}

data class MediaSource(
    val id: SourceId,
    val projectId: ProjectId,
    val kind: MediaKind,
    val displayName: String,
    val fileRefId: String,
    val importMode: ImportMode,
    val mimeType: String,
    val duration: DurationUs?,
    val codedWidth: Int?,
    val codedHeight: Int?,
    val rotationDegrees: Int,
    val frameRate: Rational?,
    val fingerprint: String,
    val availability: SourceAvailability,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) requireNotNull(duration)
    }
}

data class Sequence(
    val id: SequenceId,
    val projectId: ProjectId,
    val name: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val frameRate: Rational,
    val colorMode: ProjectColorMode,
    val revision: Long,
) {
    init {
        require(canvasWidth > 0 && canvasHeight > 0)
        require(revision >= 0)
    }
}

data class Track(
    val id: TrackId,
    val sequenceId: SequenceId,
    val type: TrackType,
    val orderIndex: Int,
    val collisionPolicy: CollisionPolicy,
    val locked: Boolean = false,
    val muted: Boolean = false,
    val hidden: Boolean = false,
)

data class TimelineItem(
    val id: ClipId,
    val projectId: ProjectId,
    val sequenceId: SequenceId,
    val trackId: TrackId,
    val type: TimelineItemType,
    val timelineStart: TimeUs,
    val timelineDuration: DurationUs,
    val sourceId: SourceId?,
    val sourceRange: TimeRangeUs?,
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val linkGroupId: String? = null,
) {
    init {
        require(timelineDuration.value >= 50_000) { "Timeline items must be at least 50ms" }
        if (type == TimelineItemType.VIDEO || type == TimelineItemType.AUDIO) {
            requireNotNull(sourceId)
            requireNotNull(sourceRange)
        }
    }
}

