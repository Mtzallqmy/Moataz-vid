package com.moatazvid.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room3.Room
import com.moatazvid.ai.editor.AiEditableProject
import com.moatazvid.ai.editor.ClipEditProperties
import com.moatazvid.core.*
import com.moatazvid.storage.ProjectSnapshot
import com.moatazvid.storage.room.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Production persistence facade used by Home/Import/Editor. All media IO runs off the main thread. */
class ProductionProjectRepository private constructor(
    private val context: Context,
    val database: MoatazVidDatabase,
    private val ids: IdGenerator = UlidIdGenerator(),
) {
    data class ProjectSummary(val id: ProjectId, val title: String, val updatedAtEpochMs: Long)

    fun observeProjects(): Flow<List<ProjectSummary>> = database.projectDao().observeAll().map { rows ->
        rows.map { ProjectSummary(ProjectId(it.projectId), it.title, it.updatedAtEpochMs) }
    }

    suspend fun createProject(title: String, width: Int = 1920, height: Int = 1080): ProjectId = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val projectId = ProjectId(ids.newId(IdKind.PROJECT))
        val sequenceId = SequenceId(ids.newId(IdKind.SEQUENCE))
        val tracks = listOf(
            newTrack(sequenceId, TrackType.VIDEO, 0, CollisionPolicy.NO_OVERLAP, "Video"),
            newTrack(sequenceId, TrackType.AUDIO, 0, CollisionPolicy.ALLOW_OVERLAP, "Audio"),
            newTrack(sequenceId, TrackType.MUSIC, 0, CollisionPolicy.ALLOW_OVERLAP, "Music"),
            newTrack(sequenceId, TrackType.CAPTION, 0, CollisionPolicy.ALLOW_OVERLAP, "Captions"),
            newTrack(sequenceId, TrackType.OVERLAY, 0, CollisionPolicy.STACK, "Overlays"),
        )
        database.withWriteTransaction {
            database.projectDao().insert(
                ProjectEntity(
                    projectId.value,
                    title.trim().ifBlank { "Untitled project" }.take(200),
                    sequenceId.value,
                    "1.0.0",
                    0,
                    ProjectStatus.ACTIVE.name,
                    PrivacyLevel.LOCAL_ONLY.name,
                    now,
                    now,
                    0,
                )
            )
            database.timelineDao().insertSequence(
                SequenceEntity(
                    sequenceId.value, projectId.value, "Main", width, height,
                    Rational.FPS_30.numerator, Rational.FPS_30.denominator,
                    ProjectColorMode.SDR.name, 0, now, now,
                )
            )
            database.timelineDao().insertTracks(tracks)
        }
        projectId
    }

    suspend fun renameProject(id: ProjectId, title: String) = withContext(Dispatchers.IO) {
        val clean = title.trim().take(200)
        require(clean.isNotBlank())
        check(database.projectDao().rename(id.value, clean, System.currentTimeMillis()) == 1)
    }

    suspend fun deleteProject(id: ProjectId) = withContext(Dispatchers.IO) {
        database.projectDao().delete(id.value) == 1
    }

    suspend fun importMedia(projectId: ProjectId, uri: Uri): SourceId = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val project = requireNotNull(database.projectDao().get(projectId.value)) { "Project not found" }
        val sequenceId = requireNotNull(project.activeSequenceId) { "Project has no active sequence" }
        val sequence = requireNotNull(database.timelineDao().sequence(sequenceId))
        val metadata = inspectMedia(resolver, uri)
        val sourceId = SourceId(ids.newId(IdKind.SOURCE))
        val fileRefId = "fil_${ids.newId(IdKind.ASSET).substringAfter('_')}"
        val clipId = ClipId(ids.newId(IdKind.CLIP))
        val now = System.currentTimeMillis()
        val persisted = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        val fileRef = FileReferenceEntity(
            fileRefId = fileRefId,
            storageKind = "SAF_URI",
            uriString = uri.toString(),
            managedRelativePath = null,
            persistedPermission = persisted,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            modifiedAtEpochMs = null,
            sha256 = null,
            availability = "AVAILABLE",
            lastVerifiedAtEpochMs = now,
        )
        val source = MediaSourceEntity(
            sourceId = sourceId.value,
            projectId = projectId.value,
            fileRefId = fileRefId,
            kind = metadata.kind.name,
            displayName = metadata.displayName,
            importMode = ImportMode.LINKED_SAF.name,
            mimeType = metadata.mimeType,
            durationUs = metadata.durationUs,
            codedWidth = metadata.width,
            codedHeight = metadata.height,
            rotationDegrees = metadata.rotation,
            fpsNumerator = metadata.fps?.numerator,
            fpsDenominator = metadata.fps?.denominator,
            colorSpace = if (metadata.hdr) "HDR" else "SDR",
            quickFingerprint = metadata.fingerprint,
            fullSha256 = null,
            fingerprintVersion = 1,
            availability = SourceAvailability.AVAILABLE.name,
            metadataVersion = 1,
            createdAtEpochMs = now,
        )
        val tracks = database.timelineDao().tracks(sequenceId)
        val targetType = if (metadata.kind == MediaKind.AUDIO) TrackType.AUDIO else TrackType.VIDEO
        val track = requireNotNull(tracks.firstOrNull { it.type == targetType.name }) { "Missing $targetType track" }
        val existing = database.timelineDao().clips(sequenceId).filter { it.trackId == track.trackId }
        val timelineStart = existing.maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L
        val duration = requireNotNull(metadata.durationUs) { "Media duration unavailable" }.coerceAtLeast(50_000L)
        val clip = ClipEntity(
            clipId = clipId.value,
            projectId = projectId.value,
            sequenceId = sequenceId,
            trackId = track.trackId,
            sourceId = sourceId.value,
            itemType = if (metadata.kind == MediaKind.AUDIO) TimelineItemType.AUDIO.name else TimelineItemType.VIDEO.name,
            timelineStartUs = timelineStart,
            timelineDurationUs = duration,
            sourceInUs = 0,
            sourceOutUs = duration,
            zIndex = 0,
            enabled = true,
            locked = false,
            lockReason = null,
            groupId = null,
            linkGroupId = null,
            rowRevision = 0,
        )
        val properties = ClipPropertiesEntity(
            clipId.value, null, 1f, 0f, 0f, false, true,
            0, 0, "1.0", null,
            if (metadata.kind == MediaKind.AUDIO) "DIALOGUE" else null,
            null, 1,
        )
        database.withWriteTransaction {
            database.mediaDao().insertFileRef(fileRef)
            database.mediaDao().insertSource(source)
            database.timelineDao().insertClips(listOf(clip))
            database.timelineDao().upsertClipProperties(properties)
            database.timelineDao().updateSequence(sequence.copy(revision = sequence.revision + 1, updatedAtEpochMs = now))
            database.projectDao().update(project.copy(timelineRevision = project.timelineRevision + 1, updatedAtEpochMs = now, rowRevision = project.rowRevision + 1))
        }
        sourceId
    }

    suspend fun loadEditableProject(projectId: ProjectId): AiEditableProject? = withContext(Dispatchers.IO) {
        val projectRow = database.projectDao().get(projectId.value) ?: return@withContext null
        val sequenceId = projectRow.activeSequenceId ?: return@withContext null
        val sequenceRow = database.timelineDao().sequence(sequenceId) ?: return@withContext null
        val trackRows = database.timelineDao().tracks(sequenceId)
        val clipRows = database.timelineDao().clips(sequenceId)
        val sourceRows = database.mediaDao().sources(projectId.value)
        val propertyRows = if (clipRows.isEmpty()) emptyList() else database.timelineDao().clipProperties(clipRows.map { it.clipId })
        val project = Project(
            ProjectId(projectRow.projectId), projectRow.title, projectRow.activeSequenceId?.let(::SequenceId),
            projectRow.timelineRevision, projectRow.schemaVersion, ProjectStatus.valueOf(projectRow.status),
            PrivacyLevel.valueOf(projectRow.privacyLevel), projectRow.createdAtEpochMs, projectRow.updatedAtEpochMs,
        )
        val sequence = Sequence(
            SequenceId(sequenceRow.sequenceId), ProjectId(sequenceRow.projectId), sequenceRow.name,
            sequenceRow.canvasWidth, sequenceRow.canvasHeight,
            Rational(sequenceRow.fpsNumerator, sequenceRow.fpsDenominator), ProjectColorMode.valueOf(sequenceRow.colorMode), sequenceRow.revision,
        )
        val tracks = trackRows.map { row ->
            Track(TrackId(row.trackId), SequenceId(row.sequenceId), TrackType.valueOf(row.type), row.orderIndex, CollisionPolicy.valueOf(row.collisionPolicy), row.locked, row.muted, row.hidden)
        }
        val items = clipRows.map { row ->
            TimelineItem(
                ClipId(row.clipId), ProjectId(row.projectId), SequenceId(row.sequenceId), TrackId(row.trackId), TimelineItemType.valueOf(row.itemType),
                TimeUs(row.timelineStartUs), DurationUs(row.timelineDurationUs), row.sourceId?.let(::SourceId),
                if (row.sourceInUs != null && row.sourceOutUs != null) TimeRangeUs(TimeUs(row.sourceInUs), TimeUs(row.sourceOutUs)) else null,
                row.enabled, row.locked, row.linkGroupId,
            )
        }
        val sources = sourceRows.map { row ->
            MediaSource(
                SourceId(row.sourceId), ProjectId(row.projectId), MediaKind.valueOf(row.kind), row.displayName, row.fileRefId,
                ImportMode.valueOf(row.importMode), row.mimeType, row.durationUs?.let(::DurationUs), row.codedWidth, row.codedHeight,
                row.rotationDegrees, if (row.fpsNumerator != null && row.fpsDenominator != null) Rational(row.fpsNumerator, row.fpsDenominator) else null,
                row.quickFingerprint, SourceAvailability.valueOf(row.availability),
            )
        }
        val properties = propertyRows.associate { row ->
            ClipId(row.clipId) to ClipEditProperties(
                speed = row.speedMapJson.toDoubleOrNull()?.coerceIn(0.25, 4.0) ?: 1.0,
                preservePitch = row.preservePitch,
                gainDb = row.gainDb,
            )
        }
        AiEditableProject(ProjectSnapshot(project, sequence, tracks, items, 0), sources, properties)
    }

    suspend fun sourceUri(sourceId: SourceId): Uri? = withContext(Dispatchers.IO) {
        val source = database.mediaDao().source(sourceId.value) ?: return@withContext null
        database.mediaDao().fileRef(source.fileRefId)?.uriString?.let(Uri::parse)
    }

    private fun newTrack(sequenceId: SequenceId, type: TrackType, index: Int, collision: CollisionPolicy, name: String) = TrackEntity(
        ids.newId(IdKind.TRACK), sequenceId.value, type.name, name, index, collision.name,
        muted = false, hidden = false, locked = false, volumeDb = 0f, blendMode = null,
    )

    private data class InspectedMedia(
        val kind: MediaKind,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationUs: Long?,
        val width: Int?,
        val height: Int?,
        val rotation: Int,
        val fps: Rational?,
        val hdr: Boolean,
        val fingerprint: String,
    )

    private fun inspectMedia(resolver: ContentResolver, uri: Uri): InspectedMedia {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("image/") -> MediaKind.IMAGE
            else -> error("Unsupported media type: $mime")
        }
        require(kind != MediaKind.IMAGE) { "Image import into the primary timeline is not supported yet" }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
            val fpsValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val fps = fpsValue?.takeIf { it > 0 }?.let(::toRationalFps)
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Media"
            val fingerprint = quickFingerprint(resolver, uri, mime, size, durationMs ?: 0)
            InspectedMedia(kind, displayName, mime, size, durationMs?.times(1_000), width, height, rotation, fps, false, fingerprint)
        } finally {
            retriever.release()
        }
    }

    private fun quickFingerprint(resolver: ContentResolver, uri: Uri, mime: String, size: Long, durationMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("v1|$mime|$size|$durationMs|".toByteArray())
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                val read = input.read(buffer)
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toRationalFps(value: Double): Rational {
        val known = listOf(Rational.FPS_23_976, Rational.FPS_24, Rational.FPS_25, Rational.FPS_29_97, Rational.FPS_30, Rational.FPS_50, Rational.FPS_59_94, Rational.FPS_60)
        return known.minByOrNull { kotlin.math.abs(it.asDouble() - value) }?.takeIf { kotlin.math.abs(it.asDouble() - value) < 0.05 }
            ?: Rational(kotlin.math.round(value).toInt().coerceAtLeast(1), 1)
    }

    companion object {
        fun create(context: Context): ProductionProjectRepository {
            val app = context.applicationContext
            val db = Room.databaseBuilder(app, MoatazVidDatabase::class.java, MoatazVidDatabase.FILE_NAME)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
            return ProductionProjectRepository(app, db)
        }
    }
}
